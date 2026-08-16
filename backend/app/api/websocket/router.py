# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/websocket
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the websocket domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""WebSocket router — /ws/chat/{conversation_id} endpoint.

Implements:
- JWT authentication via ``?token=<jwt>`` query parameter (Req 26.1).
- Application-level heartbeat ping/pong every 30 s (Req 26.2).
- Token buffering in Redis on mid-stream disconnect (Req 26.3).
- Structured event schema: token / done / error / tool_call (Req 26.5).
- Integration with AIOrchestrator.stream_chat() (Req 26.4).

Requirements: 26.1, 26.2, 26.3, 26.4, 26.5
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.api.websocket.manager import (
    WS_CLOSE_AUTH_FAILURE,
    HeartbeatMonitor,
    authenticate_websocket,
    buffer_token,
    flush_token_buffer,
)
from app.database import AsyncSessionLocal
from app.security.exceptions import InvalidTokenError
from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

logger = logging.getLogger(__name__)

# NOTE: The router has NO router-level dependency on get_current_user because
# WebSocket endpoints authenticate via query parameter, not the Authorization
# header.  Authentication is performed explicitly inside the endpoint.
router = APIRouter(prefix="/ws", tags=["websocket"])


# ---------------------------------------------------------------------------
# WebSocket endpoint
# ---------------------------------------------------------------------------


@router.websocket("/chat/{conversation_id}")
async def websocket_chat(
    websocket: WebSocket,
    conversation_id: str,
    token: str | None = None,
) -> None:
    """Stream AI chat responses over a WebSocket connection.

    Query parameters
    ----------------
    token : str
        A valid JWT access token.  Required — connection is closed with code
        4001 if absent, invalid, or expired.

    Expected client messages (JSON)
    --------------------------------
    .. code-block:: json

        {"user_message": "Hello!", "provider": "openai"}

    ``provider`` defaults to ``"openai"`` when omitted.

    Emitted server messages (JSON)
    --------------------------------
    ``{"type": "token",     "data": "..."}``        — streaming token
    ``{"type": "done",      "usage": {...}}``        — stream complete
    ``{"type": "error",     "message": "..."}``     — error
    ``{"type": "tool_call", "toolName": "...", "toolInput": {...}}``  — tool
    ``{"type": "ping"}``                            — heartbeat ping
    ``{"type": "pong"}``                            — (client → server only)

    Requirements: 26.1, 26.2, 26.3, 26.4, 26.5
    """
    # ------------------------------------------------------------------
    # Step 1 — Authenticate before (or immediately after) accept
    #
    # Starlette requires accept() before close(), so we accept first, then
    # close with 4001 on auth failure.  The connection upgrade is completed
    # but no application data is delivered before auth is confirmed.
    # ------------------------------------------------------------------
    await websocket.accept()

    try:
        payload = await authenticate_websocket(token)
    except InvalidTokenError as exc:
        logger.info(
            "WebSocket auth rejected (conversation=%s): %s", conversation_id, exc
        )
        await websocket.send_json(
            {
                "type": "error",
                "message": "Authentication failed: invalid or missing token.",
            }
        )
        await websocket.close(code=WS_CLOSE_AUTH_FAILURE)
        return

    user_id = payload.sub
    logger.info("WebSocket accepted: user=%s conversation=%s", user_id, conversation_id)

    # ------------------------------------------------------------------
    # Step 2 — Deliver any buffered tokens from a previous disconnection
    # ------------------------------------------------------------------
    await flush_token_buffer(user_id, conversation_id, websocket)

    # ------------------------------------------------------------------
    # Step 3 — Start heartbeat monitor
    # ------------------------------------------------------------------
    heartbeat = HeartbeatMonitor(websocket)
    heartbeat_task = asyncio.create_task(heartbeat.run())

    try:
        await _handle_messages(
            websocket=websocket,
            conversation_id=conversation_id,
            user_id=user_id,
            heartbeat=heartbeat,
        )
    finally:
        heartbeat_task.cancel()
        try:
            await heartbeat_task
        except (asyncio.CancelledError, Exception):
            pass


# ---------------------------------------------------------------------------
# Message handling loop
# ---------------------------------------------------------------------------


async def _handle_messages(
    websocket: WebSocket,
    conversation_id: str,
    user_id: str,
    heartbeat: HeartbeatMonitor,
) -> None:
    """Receive user messages and stream AI responses until the connection closes.

    Each JSON message from the client is expected to have the shape::

        {"user_message": "...", "provider": "openai"}

    The loop also handles heartbeat pong responses in-band.

    Args:
        websocket: The accepted WebSocket connection.
        conversation_id: UUID string of the conversation.
        user_id: Authenticated user UUID string.
        heartbeat: The :class:`~app.api.websocket.manager.HeartbeatMonitor`
            instance so pong signals can be forwarded to it.

    Requirements: 26.4, 26.5
    """
    try:
        while True:
            try:
                data: Any = await websocket.receive_json()
            except WebSocketDisconnect:
                logger.info(
                    "WebSocket disconnected: user=%s conversation=%s",
                    user_id,
                    conversation_id,
                )
                return
            except Exception as exc:
                logger.warning("WebSocket receive error: %s", exc)
                return

            if not isinstance(data, dict):
                await websocket.send_json(
                    {
                        "type": "error",
                        "message": "Invalid message format; expected a JSON object.",
                    }
                )
                continue

            # Heartbeat pong — client responding to our ping.
            if data.get("type") == "pong":
                heartbeat.pong_received()
                continue

            user_message: str = data.get("user_message", "").strip()
            if not user_message:
                await websocket.send_json(
                    {
                        "type": "error",
                        "message": "Field 'user_message' is required and must be non-empty.",
                    }
                )
                continue

            provider_str: str = data.get("provider", LLMProvider.openai.value)
            try:
                provider = LLMProvider(provider_str)
            except ValueError:
                await websocket.send_json(
                    {
                        "type": "error",
                        "message": (
                            f"Unknown provider '{provider_str}'. "
                            f"Valid values: {[p.value for p in LLMProvider]}"
                        ),
                    }
                )
                continue

            # Stream the AI response.
            await _stream_response(
                websocket=websocket,
                conversation_id=conversation_id,
                user_message=user_message,
                provider=provider,
                user_id=user_id,
            )

    except WebSocketDisconnect:
        logger.info(
            "WebSocket disconnected (outer): user=%s conversation=%s",
            user_id,
            conversation_id,
        )


# ---------------------------------------------------------------------------
# Streaming response with mid-stream disconnect buffering
# ---------------------------------------------------------------------------


class _BufferingWebSocketProxy:
    """A thin proxy around a WebSocket that buffers tokens on disconnect.

    When the underlying WebSocket raises :class:`WebSocketDisconnect` during
    a ``send_json`` call for a ``token`` event, subsequent tokens are written
    to Redis instead of being sent over the wire.

    This proxy is passed to :meth:`AIOrchestrator.stream_chat` so that the
    orchestrator itself does not need to know about buffering.

    Requirements: 26.3
    """

    def __init__(
        self,
        websocket: WebSocket,
        user_id: str,
        conversation_id: str,
    ) -> None:
        self._ws = websocket
        self._user_id = user_id
        self._conversation_id = conversation_id
        self._disconnected = False

    async def send_json(self, data: dict) -> None:  # type: ignore[override]
        """Forward JSON to the client, falling back to Redis on disconnect.

        Only ``{"type": "token", ...}`` payloads are buffered.  Other event
        types (``done``, ``error``, etc.) are silently dropped if the client
        is already disconnected.
        """
        if self._disconnected:
            # Already switched to buffering mode.
            if data.get("type") == "token":
                await buffer_token(
                    self._user_id,
                    self._conversation_id,
                    data.get("data", ""),
                )
            return

        try:
            await self._ws.send_json(data)
        except (WebSocketDisconnect, RuntimeError, OSError):
            self._disconnected = True
            logger.info(
                "Client disconnected mid-stream; buffering tokens (user=%s conversation=%s)",
                self._user_id,
                self._conversation_id,
            )
            # If this was a token event, buffer the first one too.
            if data.get("type") == "token":
                await buffer_token(
                    self._user_id,
                    self._conversation_id,
                    data.get("data", ""),
                )

    @property
    def disconnected(self) -> bool:
        """Return ``True`` if the underlying WebSocket has disconnected."""
        return self._disconnected


async def _stream_response(
    websocket: WebSocket,
    conversation_id: str,
    user_message: str,
    provider: LLMProvider,
    user_id: str,
) -> None:
    """Invoke the AIOrchestrator and stream the response.

    Creates a :class:`_BufferingWebSocketProxy` so tokens are buffered in
    Redis if the client disconnects mid-stream.

    Args:
        websocket: The active WebSocket connection.
        conversation_id: UUID string of the conversation.
        user_message: The user's input text.
        provider: The LLM provider to use.
        user_id: Authenticated user UUID string.

    Requirements: 26.4, 26.5
    """
    proxy = _BufferingWebSocketProxy(
        websocket=websocket,
        user_id=user_id,
        conversation_id=conversation_id,
    )

    try:
        async with AsyncSessionLocal() as db:
            orchestrator = AIOrchestrator(db=db)
            await orchestrator.stream_chat(
                conversation_id=conversation_id,
                user_message=user_message,
                provider=provider,
                user_id=user_id,
                ws=proxy,  # type: ignore[arg-type]
            )
    except WebSocketDisconnect:
        # Disconnected while orchestrator was running; buffering already handled
        # by the proxy.
        logger.info(
            "WebSocketDisconnect during stream_chat (user=%s conversation=%s)",
            user_id,
            conversation_id,
        )
    except ValueError as exc:
        # Prompt injection or invalid input — send error if still connected.
        if not proxy.disconnected:
            try:
                await websocket.send_json({"type": "error", "message": str(exc)})
            except Exception:
                pass
    except Exception as exc:
        logger.exception(
            "Unexpected error in stream_chat (user=%s conversation=%s): %s",
            user_id,
            conversation_id,
            exc,
        )
        if not proxy.disconnected:
            try:
                await websocket.send_json(
                    {
                        "type": "error",
                        "message": "An internal error occurred while processing your request.",
                    }
                )
            except Exception:
                pass
