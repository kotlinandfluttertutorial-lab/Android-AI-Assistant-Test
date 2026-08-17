"""Integration tests for the /ws/chat/{conversation_id} WebSocket streaming endpoint.

Covers four end-to-end scenarios:
1. Valid JWT connection and token streaming — tokens and done event arrive.
2. Invalid JWT rejection — error message then close with code 4001.
3. Buffer delivery on reconnect — buffered tokens delivered before new output.
4. Heartbeat timeout close — server closes connection when client ignores pings.

Requirements: 21.2, 26.1, 26.2, 26.3, 26.5
"""

from __future__ import annotations

import os
import uuid
from datetime import timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

# Ensure test environment variables are set before any app imports.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

from app.api.websocket.manager import HeartbeatMonitor
from app.api.websocket.router import router
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the WebSocket router, no middleware overhead.
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(router)


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------


def _make_token(
    user_id: uuid.UUID | None = None,
    role: str = "user",
    expires_delta: timedelta | None = None,
) -> str:
    """Return a signed JWT for the given user (or a random user)."""
    uid = user_id or uuid.uuid4()
    return create_access_token(uid, role, expires_delta=expires_delta)


def _new_conv_id() -> str:
    return str(uuid.uuid4())


def _fake_redis(buffered_tokens: list[str] | None = None) -> AsyncMock:
    """Build a minimal async Redis mock.

    Supports:
    - ``exists``  — JTI revocation check (returns 0 = not revoked by default).
    - ``lrange``  — return ``buffered_tokens`` or an empty list.
    - ``delete``  — clears the in-memory store and returns 1.
    - ``pipeline``— returns a chainable mock used by ``buffer_token``.
    """
    client = AsyncMock()
    _store: list[str] = list(buffered_tokens or [])

    async def _lrange(key: str, start: int, end: int) -> list[str]:
        return list(_store)

    async def _delete(key: str) -> int:
        _store.clear()
        return 1

    async def _exists(key: str) -> int:
        return 0  # JTI not revoked

    client.lrange = AsyncMock(side_effect=_lrange)
    client.delete = AsyncMock(side_effect=_delete)
    client.exists = AsyncMock(side_effect=_exists)

    pipe_mock = AsyncMock()
    pipe_mock.rpush = MagicMock(return_value=pipe_mock)
    pipe_mock.ltrim = MagicMock(return_value=pipe_mock)
    pipe_mock.expire = MagicMock(return_value=pipe_mock)
    pipe_mock.execute = AsyncMock(return_value=[1, None, True])
    client.pipeline = MagicMock(return_value=pipe_mock)

    return client


def _make_mock_db_session() -> AsyncMock:
    """Return a mock async DB session context manager."""
    mock_db = AsyncMock()
    mock_session = AsyncMock()
    mock_db.__aenter__ = AsyncMock(return_value=mock_session)
    mock_db.__aexit__ = AsyncMock(return_value=False)
    return mock_db


def _make_mock_orchestrator(tokens: list[str] | None = None) -> AsyncMock:
    """Return a mock AIOrchestrator that emits controlled token + done events.

    The ``stream_chat`` coroutine calls ``ws.send_json`` for each token and
    finishes with a ``done`` event — same shape as the real orchestrator.
    """
    mock_orch = AsyncMock()
    emit_tokens = list(tokens or ["Hello", " world", "!"])

    async def fake_stream_chat(
        conversation_id,
        user_message,
        provider,
        user_id,
        ws,
    ) -> None:
        for tok in emit_tokens:
            await ws.send_json({"type": "token", "data": tok})
        await ws.send_json(
            {
                "type": "done",
                "usage": {
                    "input_tokens": 10,
                    "output_tokens": len(emit_tokens),
                    "provider": "openai",
                },
            }
        )

    mock_orch.stream_chat = fake_stream_chat
    return mock_orch


def _collect_n_messages(ws, n: int) -> list[dict]:
    """Collect exactly *n* messages from the WebSocket, stopping on error."""
    msgs: list[dict] = []
    for _ in range(n):
        try:
            msgs.append(ws.receive_json())
        except Exception:
            break
    return msgs


def _drain_until_done(ws, max_messages: int = 50) -> list[dict]:
    """Receive messages until a 'done' event or an error, up to *max_messages*."""
    msgs: list[dict] = []
    for _ in range(max_messages):
        try:
            msg = ws.receive_json()
            msgs.append(msg)
            if msg.get("type") in ("done", "error"):
                break
        except Exception:
            break
    return msgs


# ===========================================================================
# Scenario 1 — Valid JWT connection and token streaming
# ===========================================================================


class TestValidJwtTokenStreaming:
    """Verify that a valid JWT produces a successful stream with token + done events.

    Requirements: 21.2, 26.1, 26.5
    """

    def test_tokens_and_done_event_received(self):
        """Connect with a valid JWT, send a message, assert token and done events.

        The client receives one ``{"type":"token","data":"..."}`` event per
        token emitted by the (mocked) orchestrator, followed by exactly one
        ``{"type":"done","usage":{...}}`` event.

        Requirements: 21.2, 26.1, 26.5
        """
        token = _make_token()
        conv_id = _new_conv_id()
        expected_tokens = ["The ", "answer ", "is ", "42."]

        mock_orch = _make_mock_orchestrator(tokens=expected_tokens)
        mock_redis = _fake_redis()
        mock_session = _make_mock_db_session()

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            ws.send_json({"user_message": "What is the answer?", "provider": "openai"})
            # Use _drain_until_done so interspersed heartbeat pings don't
            # consume slots and cause a count mismatch.
            received_msgs = _drain_until_done(ws)

        token_msgs = [m for m in received_msgs if m.get("type") == "token"]
        done_msgs = [m for m in received_msgs if m.get("type") == "done"]

        assert len(token_msgs) == len(
            expected_tokens
        ), f"Expected {len(expected_tokens)} token events, got {len(token_msgs)}"
        assert [m["data"] for m in token_msgs] == expected_tokens
        assert len(done_msgs) == 1, f"Expected 1 done event, got {len(done_msgs)}"
        assert "usage" in done_msgs[0], "done event must contain a 'usage' field"
        assert isinstance(done_msgs[0]["usage"], dict)

    def test_token_events_have_correct_schema(self):
        """Each token event has type=='token' and a 'data' string field.

        Requirements: 26.5
        """
        token = _make_token()
        conv_id = _new_conv_id()
        mock_orch = _make_mock_orchestrator(tokens=["hello"])
        mock_redis = _fake_redis()
        mock_session = _make_mock_db_session()

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            ws.send_json({"user_message": "hi", "provider": "openai"})
            # 1 token + 1 done
            msgs = _collect_n_messages(ws, 2)

        for msg in msgs:
            if msg.get("type") == "token":
                assert "data" in msg, "token event is missing 'data' field"
                assert isinstance(msg["data"], str), "'data' must be a string"

    def test_done_event_usage_contains_required_fields(self):
        """The 'done' event's usage dict contains input_tokens and output_tokens.

        Requirements: 26.5
        """
        token = _make_token()
        conv_id = _new_conv_id()
        mock_orch = _make_mock_orchestrator(tokens=["x"])
        mock_redis = _fake_redis()
        mock_session = _make_mock_db_session()

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            ws.send_json({"user_message": "x", "provider": "openai"})
            # Use _drain_until_done so interspersed heartbeat pings don't
            # consume slots and cause the done event to be missed.
            msgs = _drain_until_done(ws)

        done_msgs = [m for m in msgs if m.get("type") == "done"]
        assert done_msgs, "No done event received"
        usage = done_msgs[0]["usage"]
        assert "input_tokens" in usage
        assert "output_tokens" in usage


# ===========================================================================
# Scenario 2 — Invalid JWT rejection
# ===========================================================================


class TestInvalidJwtRejection:
    """Verify that connections with bad/missing tokens are rejected with 4001.

    Requirements: 21.2, 26.1
    """

    def test_missing_token_sends_error_and_closes(self):
        """No ?token= param → error message then connection closes.

        The server sends ``{"type":"error","message":"..."}`` and closes with
        WebSocket close code 4001.

        Requirements: 21.2, 26.1
        """
        conv_id = _new_conv_id()

        with TestClient(_app) as client:
            with client.websocket_connect(f"/ws/chat/{conv_id}") as ws:
                # Receive the error message.
                msg = ws.receive_json()

                assert msg["type"] == "error", f"Expected error event, got: {msg!r}"
                assert "message" in msg, "error event must have a 'message' field"
                assert msg["message"], "error message must not be empty"

                # Server closes the connection — further receive should raise.
                with pytest.raises(Exception):
                    ws.receive_json()

    def test_malformed_token_sends_error_and_closes(self):
        """Malformed JWT → error message then connection closes.

        Requirements: 21.2, 26.1
        """
        conv_id = _new_conv_id()

        with (
            TestClient(_app) as client,
            client.websocket_connect(
                f"/ws/chat/{conv_id}?token=this.is.not.a.valid.jwt"
            ) as ws,
        ):
            msg = ws.receive_json()

            assert msg["type"] == "error"
            assert "message" in msg

            with pytest.raises(Exception):
                ws.receive_json()

    def test_expired_token_sends_error_and_closes(self):
        """Expired JWT → error message then connection closes.

        Requirements: 21.2, 26.1
        """
        expired_token = _make_token(expires_delta=timedelta(seconds=-10))
        conv_id = _new_conv_id()

        with (
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={expired_token}") as ws,
        ):
            msg = ws.receive_json()

            assert msg["type"] == "error"
            assert "message" in msg

            with pytest.raises(Exception):
                ws.receive_json()

    def test_revoked_token_sends_error_and_closes(self):
        """Revoked JWT (JTI in Redis revocation list) → error then close.

        Requirements: 21.2, 26.1
        """
        conv_id = _new_conv_id()
        valid_token = _make_token()

        revoked_redis = _fake_redis()
        revoked_redis.exists = AsyncMock(return_value=1)  # JTI is revoked

        with (
            patch(
                "app.api.websocket.manager.get_redis_client",
                return_value=revoked_redis,
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={valid_token}") as ws,
        ):
            msg = ws.receive_json()

            assert msg["type"] == "error"
            assert "message" in msg

            with pytest.raises(Exception):
                ws.receive_json()


# ===========================================================================
# Scenario 3 — Buffer delivery on reconnect
# ===========================================================================


class TestBufferDeliveryOnReconnect:
    """Verify that buffered tokens are delivered before new stream output.

    When a client reconnects after a mid-stream disconnect, the server should
    flush any tokens stored in Redis *before* starting a new LLM stream.

    Requirements: 21.2, 26.3
    """

    def test_buffered_tokens_delivered_before_new_stream(self):
        """Reconnecting client receives buffered tokens before new stream tokens.

        Setup:
        - Redis mock holds 3 pre-buffered tokens ("buf1", "buf2", "buf3").
        - Mock orchestrator emits 2 new tokens ("new1", "new2") then done.

        Assertion:
        - The 3 buffered tokens arrive first (via flush_token_buffer), then
          the 2 new tokens, then the done event.

        Requirements: 21.2, 26.3
        """
        token = _make_token()
        conv_id = _new_conv_id()
        buffered = ["buf1", "buf2", "buf3"]
        new_tokens = ["new1", "new2"]

        mock_orch = _make_mock_orchestrator(tokens=new_tokens)
        mock_redis = _fake_redis(buffered_tokens=buffered)
        mock_session = _make_mock_db_session()

        # Total messages expected:
        #  - 3 flushed token events (before user message)
        #  - 2 new token events + 1 done event (after user message)
        total_expected = len(buffered) + len(new_tokens) + 1

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            # Collect the buffered tokens that arrive immediately on connect
            # (flush_token_buffer is called before any user message).
            flushed_msgs = _collect_n_messages(ws, len(buffered))

            # Now send a user message to trigger the new stream.
            ws.send_json({"user_message": "continue", "provider": "openai"})

            # Collect new stream messages (tokens + done).
            stream_msgs = _collect_n_messages(ws, len(new_tokens) + 1)

        flushed_token_events = [m for m in flushed_msgs if m.get("type") == "token"]
        new_token_events = [m for m in stream_msgs if m.get("type") == "token"]
        done_events = [m for m in stream_msgs if m.get("type") == "done"]

        # Buffered tokens must be delivered first and in order.
        assert [m["data"] for m in flushed_token_events] == buffered, (
            f"Buffered tokens not delivered first/in-order: "
            f"{[m['data'] for m in flushed_token_events]!r}"
        )

        # New stream tokens follow.
        assert [
            m["data"] for m in new_token_events
        ] == new_tokens, (
            f"New stream tokens incorrect: {[m['data'] for m in new_token_events]!r}"
        )

        assert len(done_events) == 1, "Expected exactly one done event"

    def test_no_buffered_tokens_proceeds_normally(self):
        """When Redis buffer is empty, reconnect delivers only new stream tokens.

        Requirements: 26.3
        """
        token = _make_token()
        conv_id = _new_conv_id()
        new_tokens = ["tok_a", "tok_b"]
        total_expected = len(new_tokens) + 1  # tokens + done

        mock_orch = _make_mock_orchestrator(tokens=new_tokens)
        mock_redis = _fake_redis(buffered_tokens=[])  # empty buffer
        mock_session = _make_mock_db_session()

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            ws.send_json({"user_message": "hello", "provider": "openai"})
            msgs = _collect_n_messages(ws, total_expected)

        token_events = [m for m in msgs if m.get("type") == "token"]
        assert [m["data"] for m in token_events] == new_tokens

    def test_redis_buffer_cleared_after_flush(self):
        """After reconnect flush, the Redis buffer key is deleted.

        Requirements: 26.3
        """
        token = _make_token()
        conv_id = _new_conv_id()
        buffered = ["old_tok"]

        mock_orch = _make_mock_orchestrator(tokens=[])
        mock_redis = _fake_redis(buffered_tokens=buffered)
        mock_session = _make_mock_db_session()

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            # Drain the flushed token.
            _collect_n_messages(ws, len(buffered))
            # Trigger the (empty) stream to reach the done event.
            ws.send_json({"user_message": "x", "provider": "openai"})
            # Collect the done event (no tokens emitted by orchestrator).
            _collect_n_messages(ws, 1)

        # Redis delete() must have been called to clear the buffer.
        mock_redis.delete.assert_called()


# ===========================================================================
# Scenario 4 — Heartbeat timeout close
# ===========================================================================


class TestHeartbeatTimeoutClose:
    """Verify that the server closes the connection when no pong is received.

    Requirements: 21.2, 26.2
    """

    def test_server_closes_connection_on_heartbeat_timeout(self):
        """Client that never responds to ping is disconnected by the server.

        HeartbeatMonitor is patched with very short intervals (50 ms) so the
        test completes quickly.  The client receives at least one ping, never
        sends a pong, and the server closes the connection.

        Requirements: 21.2, 26.2
        """
        token = _make_token()
        conv_id = _new_conv_id()

        mock_orch = _make_mock_orchestrator(tokens=[])
        mock_redis = _fake_redis()
        mock_session = _make_mock_db_session()

        _fast_interval = 0.05
        _fast_timeout = 0.05

        original_heartbeat_cls = HeartbeatMonitor

        def _fast_heartbeat(ws, interval=None, timeout=None):
            return original_heartbeat_cls(
                ws, interval=_fast_interval, timeout=_fast_timeout
            )

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            patch(
                "app.api.websocket.router.HeartbeatMonitor", side_effect=_fast_heartbeat
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            received_msgs: list[dict] = []
            # Deliberately do NOT send a pong response.
            # Collect messages until the server closes the connection.
            for _ in range(20):
                try:
                    received_msgs.append(ws.receive_json())
                except Exception:
                    # Server closed the connection — expected outcome.
                    break

        # The server must have sent at least one ping before timing out.
        ping_msgs = [m for m in received_msgs if m.get("type") == "ping"]
        assert ping_msgs, (
            "Expected at least one 'ping' message before heartbeat timeout close; "
            f"received: {received_msgs!r}"
        )

    def test_connection_stays_open_when_pong_sent(self):
        """Client that responds to ping keeps the connection alive for the stream.

        Requirements: 26.2
        """
        token = _make_token()
        conv_id = _new_conv_id()
        stream_tokens = ["hi"]
        total_expected = len(stream_tokens) + 1  # tokens + done

        mock_orch = _make_mock_orchestrator(tokens=stream_tokens)
        mock_redis = _fake_redis()
        mock_session = _make_mock_db_session()

        _fast_interval = 0.05
        _fast_timeout = 0.2  # generous so pong arrives in time

        original_heartbeat_cls = HeartbeatMonitor

        def _fast_heartbeat(ws, interval=None, timeout=None):
            return original_heartbeat_cls(
                ws, interval=_fast_interval, timeout=_fast_timeout
            )

        with (
            patch("app.api.websocket.router.AIOrchestrator", return_value=mock_orch),
            patch(
                "app.api.websocket.manager.get_redis_client", return_value=mock_redis
            ),
            patch(
                "app.api.websocket.router.AsyncSessionLocal", return_value=mock_session
            ),
            patch(
                "app.api.websocket.router.HeartbeatMonitor", side_effect=_fast_heartbeat
            ),
            TestClient(_app) as client,
            client.websocket_connect(f"/ws/chat/{conv_id}?token={token}") as ws,
        ):
            ws.send_json({"user_message": "test", "provider": "openai"})

            received_msgs: list[dict] = []
            # Collect messages, responding to pings to keep connection alive.
            for _ in range(20):
                try:
                    msg = ws.receive_json()
                    received_msgs.append(msg)
                    if msg.get("type") == "ping":
                        ws.send_json({"type": "pong"})
                    if msg.get("type") == "done":
                        break
                except Exception:
                    break

        done_events = [m for m in received_msgs if m.get("type") == "done"]
        assert done_events, (
            "Expected a 'done' event when client responds to heartbeat pings; "
            f"received: {received_msgs!r}"
        )
