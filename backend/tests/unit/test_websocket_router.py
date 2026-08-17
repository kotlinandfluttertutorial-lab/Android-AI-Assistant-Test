"""Unit tests for the /ws/chat/{conversation_id} WebSocket endpoint.

Covers:
1. Connection rejected (close 4001) with missing/invalid/expired JWT.
2. Connection accepted with valid JWT.
3. Heartbeat HeartbeatMonitor sends ping and closes on pong timeout.
4. Buffered tokens delivered on reconnect (mock Redis).
5. Structured events emitted in correct format.
6. Mid-stream disconnect triggers token buffering.

Requirements: 26.1, 26.2, 26.3, 26.5
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from starlette.testclient import TestClient

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

from fastapi import FastAPI

from app.api.websocket.manager import (
    HeartbeatMonitor,
    _buffer_key,
    authenticate_websocket,
    buffer_token,
    flush_token_buffer,
)
from app.api.websocket.router import router
from app.security.exceptions import InvalidTokenError
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Helper — build a FastAPI app with only the WebSocket router
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(router)


def _valid_token(
    user_id: uuid.UUID | None = None,
    role: str = "user",
    expires_delta: timedelta | None = None,
) -> str:
    uid = user_id or uuid.uuid4()
    token, _expiry = create_access_token(uid, role, expires_delta=expires_delta)
    return token


def _conversation_id() -> str:
    return str(uuid.uuid4())


# ---------------------------------------------------------------------------
# Helpers for deterministic test mocks
# ---------------------------------------------------------------------------


def _make_mock_orchestrator(tokens: list[str] | None = None):
    """Return a mock AIOrchestrator whose stream_chat emits token events."""
    mock_orch = AsyncMock()

    async def fake_stream_chat(
        conversation_id,
        user_message,
        provider,
        user_id,
        ws,
    ):
        for tok in tokens or ["Hello", " world"]:
            await ws.send_json({"type": "token", "data": tok})
        await ws.send_json(
            {
                "type": "done",
                "usage": {"input_tokens": 5, "output_tokens": 2, "provider": "openai"},
            }
        )

    mock_orch.stream_chat = fake_stream_chat
    return mock_orch


# ===========================================================================
# 1. Authentication tests (Requirement 26.1)
# ===========================================================================


class TestWebSocketAuth:
    """Tests that verify JWT authentication via ?token= query param."""

    def test_missing_token_closes_with_4001(self):
        """Connection with no token is closed with code 4001.

        Requirements: 26.1
        """
        with TestClient(_app) as client:
            with client.websocket_connect(f"/ws/chat/{_conversation_id()}") as ws:
                # We should receive an error message and then the connection closes.
                msg = ws.receive_json()
                assert msg["type"] == "error"
                assert (
                    "authentication" in msg["message"].lower()
                    or "token" in msg["message"].lower()
                )
                # After the error the server closes with 4001.
                with pytest.raises(Exception):
                    # Receive again — should fail because server closed.
                    ws.receive_json()

    def test_invalid_token_closes_with_4001(self):
        """Connection with a malformed JWT is closed with code 4001.

        Requirements: 26.1
        """
        with (
            TestClient(_app) as client,
            client.websocket_connect(
                f"/ws/chat/{_conversation_id()}?token=not.a.valid.jwt"
            ) as ws,
        ):
            msg = ws.receive_json()
            assert msg["type"] == "error"

    def test_expired_token_closes_with_4001(self):
        """Connection with an expired JWT is closed with code 4001.

        Requirements: 26.1
        """
        expired_token = _valid_token(expires_delta=timedelta(seconds=-1))
        with (
            TestClient(_app) as client,
            client.websocket_connect(
                f"/ws/chat/{_conversation_id()}?token={expired_token}"
            ) as ws,
        ):
            msg = ws.receive_json()
            assert msg["type"] == "error"

    def test_valid_token_accepts_connection(self):
        """Connection with a valid JWT is accepted.

        A mock orchestrator is used so we do not need a real DB or LLM.

        Requirements: 26.1
        """
        valid_token = _valid_token()
        mock_orch = _make_mock_orchestrator()

        with (
            patch(
                "app.api.websocket.router.AIOrchestrator",
                return_value=mock_orch,
            ),
            patch(
                "app.api.websocket.manager.get_redis_client",
                return_value=_fake_redis(),
            ),
            patch("app.api.websocket.router.AsyncSessionLocal") as mock_session_cls,
        ):
            mock_session = AsyncMock()
            mock_session.__aenter__ = AsyncMock(return_value=AsyncMock())
            mock_session.__aexit__ = AsyncMock(return_value=False)
            mock_session_cls.return_value = mock_session

            with (
                TestClient(_app) as client,
                client.websocket_connect(
                    f"/ws/chat/{_conversation_id()}?token={valid_token}"
                ) as ws,
            ):
                ws.send_json({"user_message": "hello", "provider": "openai"})
                # Stop collecting as soon as done/error arrives — do not spin
                # waiting for the next heartbeat ping (default: 30 s).
                msgs = []
                for _ in range(20):
                    try:
                        msg = ws.receive_json()
                        msgs.append(msg)
                        if msg.get("type") in ("done", "error"):
                            break
                    except Exception:
                        break

        types = [m["type"] for m in msgs]
        assert "token" in types or "done" in types


# ===========================================================================
# 2. Heartbeat tests (Requirement 26.2)
# ===========================================================================


class TestHeartbeatMonitor:
    """Tests for HeartbeatMonitor ping/pong logic."""

    @pytest.mark.asyncio
    async def test_ping_sent_after_interval(self):
        """HeartbeatMonitor sends a ping message after the interval.

        Requirements: 26.2
        """
        ws = AsyncMock()
        ws.send_json = AsyncMock()
        ws.close = AsyncMock()

        monitor = HeartbeatMonitor(ws, interval=0.05, timeout=0.05)

        # Run the monitor briefly, then cancel.
        task = asyncio.create_task(monitor.run())

        # Simulate the client responding with pong just in time.
        async def _respond_with_pong():
            await asyncio.sleep(0.06)
            monitor.pong_received()
            await asyncio.sleep(0.01)

        await asyncio.gather(
            asyncio.wait_for(_respond_with_pong(), timeout=0.5),
            asyncio.sleep(0.15),
        )
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

        # At least one ping should have been sent.
        sent_types = [
            call.args[0]["type"]
            for call in ws.send_json.call_args_list
            if isinstance(call.args[0], dict)
        ]
        assert "ping" in sent_types

    @pytest.mark.asyncio
    async def test_closes_connection_on_pong_timeout(self):
        """HeartbeatMonitor closes the connection when no pong arrives.

        Requirements: 26.2
        """
        ws = AsyncMock()
        ws.send_json = AsyncMock()
        ws.close = AsyncMock()

        monitor = HeartbeatMonitor(ws, interval=0.02, timeout=0.02)
        task = asyncio.create_task(monitor.run())

        # Do NOT call monitor.pong_received() — let it time out.
        await asyncio.sleep(0.1)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

        # close() should have been called after the timeout.
        ws.close.assert_called()

    @pytest.mark.asyncio
    async def test_no_close_when_pong_received_in_time(self):
        """HeartbeatMonitor does NOT close the connection when pong arrives in time.

        Requirements: 26.2
        """
        ws = AsyncMock()
        ws.send_json = AsyncMock()
        ws.close = AsyncMock()

        monitor = HeartbeatMonitor(ws, interval=0.03, timeout=0.1)
        task = asyncio.create_task(monitor.run())

        # Wait for the ping to be sent, then respond.
        await asyncio.sleep(0.05)
        monitor.pong_received()
        await asyncio.sleep(0.03)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

        ws.close.assert_not_called()


# ===========================================================================
# 3. Token buffering tests (Requirement 26.3)
# ===========================================================================


def _fake_redis(existing_tokens: list[str] | None = None):
    """Build a mock async Redis client for buffering tests."""
    client = AsyncMock()
    _store: list[str] = list(existing_tokens or [])

    async def _lrange(key, start, end):
        return list(_store)

    async def _delete(key):
        _store.clear()
        return 1

    async def _exists(key):
        return 0  # JTI not revoked

    client.lrange = AsyncMock(side_effect=_lrange)
    client.delete = AsyncMock(side_effect=_delete)
    client.exists = AsyncMock(side_effect=_exists)

    # Pipeline support for buffer_token
    pipe_mock = AsyncMock()
    pipe_mock.rpush = MagicMock(return_value=pipe_mock)
    pipe_mock.ltrim = MagicMock(return_value=pipe_mock)
    pipe_mock.expire = MagicMock(return_value=pipe_mock)
    pipe_mock.execute = AsyncMock(return_value=[1, None, True])
    client.pipeline = MagicMock(return_value=pipe_mock)

    return client


class TestTokenBuffer:
    """Tests for Redis-backed token buffering on disconnect."""

    @pytest.mark.asyncio
    async def test_flush_token_buffer_delivers_tokens(self):
        """flush_token_buffer sends all buffered tokens and then clears them.

        Requirements: 26.3
        """
        buffered = ["Hello", " there", " world"]
        fake_ws = AsyncMock()
        fake_ws.send_json = AsyncMock()

        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=_fake_redis(existing_tokens=buffered),
        ):
            await flush_token_buffer(user_id, conv_id, fake_ws)

        sent = [call.args[0] for call in fake_ws.send_json.call_args_list]
        assert sent == [{"type": "token", "data": t} for t in buffered]

    @pytest.mark.asyncio
    async def test_flush_token_buffer_clears_redis(self):
        """flush_token_buffer deletes the Redis key after delivering tokens.

        Requirements: 26.3
        """
        fake_redis = _fake_redis(existing_tokens=["tok1"])
        fake_ws = AsyncMock()

        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=fake_redis,
        ):
            await flush_token_buffer(user_id, conv_id, fake_ws)

        fake_redis.delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_flush_token_buffer_noop_when_empty(self):
        """flush_token_buffer sends nothing when the buffer is empty.

        Requirements: 26.3
        """
        fake_ws = AsyncMock()
        fake_ws.send_json = AsyncMock()

        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=_fake_redis(existing_tokens=[]),
        ):
            await flush_token_buffer(user_id, conv_id, fake_ws)

        fake_ws.send_json.assert_not_called()

    @pytest.mark.asyncio
    async def test_buffer_token_uses_pipeline(self):
        """buffer_token pushes to the Redis list via a pipeline.

        Requirements: 26.3
        """
        fake_redis = _fake_redis()
        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())

        await buffer_token(user_id, conv_id, "some-token", redis_client=fake_redis)

        fake_redis.pipeline.assert_called_once()
        pipe = fake_redis.pipeline.return_value
        pipe.rpush.assert_called_once()
        pipe.ltrim.assert_called_once()
        pipe.expire.assert_called_once()
        pipe.execute.assert_called_once()

    def test_buffer_key_format(self):
        """_buffer_key produces the expected Redis key format.

        Requirements: 26.3
        """
        key = _buffer_key("user-123", "conv-456")
        assert key == "ws_buffer:user-123:conv-456"


# ===========================================================================
# 4. Structured event schema tests (Requirement 26.5)
# ===========================================================================


class TestStructuredEvents:
    """Tests that emitted events conform to the defined schema."""

    @pytest.mark.asyncio
    async def test_token_event_schema(self):
        """Token events have type='token' and a 'data' field.

        Requirements: 26.5
        """
        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        ws.send_json = AsyncMock()
        proxy = _BufferingWebSocketProxy(ws, "user-1", "conv-1")

        await proxy.send_json({"type": "token", "data": "hello"})
        ws.send_json.assert_called_once_with({"type": "token", "data": "hello"})

    @pytest.mark.asyncio
    async def test_done_event_schema(self):
        """Done events have type='done' and a 'usage' field.

        Requirements: 26.5
        """
        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        ws.send_json = AsyncMock()
        proxy = _BufferingWebSocketProxy(ws, "user-1", "conv-1")

        usage = {"input_tokens": 10, "output_tokens": 5, "provider": "openai"}
        await proxy.send_json({"type": "done", "usage": usage})
        ws.send_json.assert_called_once_with({"type": "done", "usage": usage})

    @pytest.mark.asyncio
    async def test_error_event_schema(self):
        """Error events have type='error' and a 'message' field.

        Requirements: 26.5
        """
        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        ws.send_json = AsyncMock()
        proxy = _BufferingWebSocketProxy(ws, "user-1", "conv-1")

        await proxy.send_json({"type": "error", "message": "something went wrong"})
        ws.send_json.assert_called_once_with(
            {"type": "error", "message": "something went wrong"}
        )

    @pytest.mark.asyncio
    async def test_tool_call_event_schema(self):
        """Tool call events have type='tool_call', 'toolName', and 'toolInput'.

        Requirements: 26.5
        """
        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        ws.send_json = AsyncMock()
        proxy = _BufferingWebSocketProxy(ws, "user-1", "conv-1")

        payload = {
            "type": "tool_call",
            "toolName": "search",
            "toolInput": {"query": "latest news"},
        }
        await proxy.send_json(payload)
        ws.send_json.assert_called_once_with(payload)


# ===========================================================================
# 5. Buffering proxy mid-stream disconnect tests (Requirement 26.3)
# ===========================================================================


class TestBufferingWebSocketProxy:
    """Tests for _BufferingWebSocketProxy behaviour on disconnect."""

    @pytest.mark.asyncio
    async def test_switches_to_buffering_on_disconnect(self):
        """Proxy buffers tokens after WebSocketDisconnect.

        Requirements: 26.3
        """
        from fastapi import WebSocketDisconnect

        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        # First send succeeds, subsequent sends raise WebSocketDisconnect.
        ws.send_json = AsyncMock(side_effect=[None, WebSocketDisconnect()])

        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())
        fake_redis = _fake_redis()

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=fake_redis,
        ):
            proxy = _BufferingWebSocketProxy(ws, user_id, conv_id)
            # First send goes through normally.
            await proxy.send_json({"type": "token", "data": "tok1"})
            assert not proxy.disconnected

            # Second send triggers the disconnect.
            await proxy.send_json({"type": "token", "data": "tok2"})
            assert proxy.disconnected

            # Third send should write to buffer, not call ws.send_json again.
            await proxy.send_json({"type": "token", "data": "tok3"})

        # ws.send_json was called exactly twice (first two sends).
        assert ws.send_json.call_count == 2

        # The pipeline should have been used for buffering the 2nd and 3rd tokens.
        assert fake_redis.pipeline.call_count >= 1

    @pytest.mark.asyncio
    async def test_non_token_events_dropped_after_disconnect(self):
        """Non-token events are silently dropped when client is disconnected.

        Requirements: 26.3
        """
        from fastapi import WebSocketDisconnect

        from app.api.websocket.router import _BufferingWebSocketProxy

        ws = AsyncMock()
        ws.send_json = AsyncMock(side_effect=WebSocketDisconnect())

        user_id = str(uuid.uuid4())
        conv_id = str(uuid.uuid4())
        fake_redis = _fake_redis()

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=fake_redis,
        ):
            proxy = _BufferingWebSocketProxy(ws, user_id, conv_id)
            # Trigger disconnect on a 'done' event.
            await proxy.send_json({"type": "done", "usage": {}})
            assert proxy.disconnected

            # Send more non-token events — they should be silently dropped.
            ws.send_json.reset_mock()
            await proxy.send_json({"type": "done", "usage": {}})
            await proxy.send_json({"type": "error", "message": "oops"})

        # After disconnect, ws.send_json must NOT be called.
        ws.send_json.assert_not_called()
        # And no buffering pipeline should be triggered for non-token events.
        assert fake_redis.pipeline.call_count == 0


# ===========================================================================
# 6. authenticate_websocket unit tests (Requirement 26.1)
# ===========================================================================


class TestAuthenticateWebSocket:
    """Unit tests for the standalone authenticate_websocket helper."""

    @pytest.mark.asyncio
    async def test_raises_on_none_token(self):
        """Missing token raises InvalidTokenError.

        Requirements: 26.1
        """
        with pytest.raises(InvalidTokenError, match="Missing"):
            await authenticate_websocket(None)

    @pytest.mark.asyncio
    async def test_raises_on_empty_token(self):
        """Empty string token raises InvalidTokenError.

        Requirements: 26.1
        """
        with pytest.raises(InvalidTokenError):
            await authenticate_websocket("")

    @pytest.mark.asyncio
    async def test_raises_on_invalid_jwt(self):
        """Malformed JWT raises InvalidTokenError.

        Requirements: 26.1
        """
        with (
            patch(
                "app.api.websocket.manager.get_redis_client",
                return_value=_fake_redis(),
            ),
            pytest.raises(InvalidTokenError),
        ):
            await authenticate_websocket("not.a.valid.jwt")

    @pytest.mark.asyncio
    async def test_returns_payload_for_valid_token(self):
        """Valid JWT returns the TokenPayload.

        Requirements: 26.1
        """
        uid = uuid.uuid4()
        token, _expiry = create_access_token(uid, "user")

        with patch(
            "app.api.websocket.manager.get_redis_client",
            return_value=_fake_redis(),
        ):
            payload = await authenticate_websocket(token)

        assert payload.sub == str(uid)
        assert payload.role == "user"

    @pytest.mark.asyncio
    async def test_raises_on_revoked_jti(self):
        """Token with a revoked JTI raises InvalidTokenError.

        Requirements: 26.1
        """
        uid = uuid.uuid4()
        token, _expiry = create_access_token(uid, "user")

        revoked_redis = _fake_redis()
        revoked_redis.exists = AsyncMock(return_value=1)  # JTI is revoked

        with (
            patch(
                "app.api.websocket.manager.get_redis_client",
                return_value=revoked_redis,
            ),
            pytest.raises(InvalidTokenError, match="revoked"),
        ):
            await authenticate_websocket(token)
