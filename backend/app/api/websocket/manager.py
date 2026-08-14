# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/websocket
# File    : manager.py
# Purpose : manager — api/websocket module
#
# Architecture Layer : API Router
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""WebSocket connection manager — auth, heartbeat, and token buffering.

This module handles all low-level WebSocket lifecycle concerns so that the
router stays thin:

- JWT authentication via query-parameter ``?token=<jwt>`` (browser WebSocket
  APIs do not support custom headers).
- Application-level heartbeat: ping every 30 s, expect pong within 10 s.
- Token buffering in Redis on mid-stream disconnect: up to 1,000 tokens for
  60 seconds, keyed by ``ws_buffer:{user_id}:{conversation_id}``.
- Delivering buffered tokens on reconnect before new LLM output.

Requirements: 26.1, 26.2, 26.3
"""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING

from redis.asyncio import Redis

from app.database.redis import get_redis_client
from app.security.exceptions import InvalidTokenError
from app.security.jwt_handler import TokenPayload, verify_access_token

if TYPE_CHECKING:
    from fastapi import WebSocket

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_REVOKED_JTI_PREFIX = "revoked_jti:"
_BUFFER_KEY_PREFIX = "ws_buffer"
_BUFFER_MAX_LEN = 1_000
_BUFFER_TTL_SECONDS = 60

_HEARTBEAT_INTERVAL_SECONDS = 30
_HEARTBEAT_TIMEOUT_SECONDS = 10

# WebSocket close code for authentication failures (RFC 6455 4000-4999: app
# defined codes).
WS_CLOSE_AUTH_FAILURE = 4001


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------


async def authenticate_websocket(token: str | None) -> TokenPayload:
    """Validate the JWT passed as ``?token=<jwt>`` on the WebSocket upgrade.

    Steps:
    1. Reject if the token is absent.
    2. Validate signature + expiry via :func:`verify_access_token`.
    3. Check JTI revocation in Redis.

    Args:
        token: The raw JWT string from the query parameter, or ``None`` if the
            parameter was not supplied.

    Returns:
        The validated :class:`~app.security.jwt_handler.TokenPayload`.

    Raises:
        :class:`~app.security.exceptions.InvalidTokenError`: On any auth
            failure (missing, malformed, expired, or revoked token).

    Requirements: 26.1
    """
    if not token:
        raise InvalidTokenError("Missing token query parameter")

    # Validates signature, expiry, and required claims.
    payload = verify_access_token(token)

    # JTI revocation check (same pattern as HTTP dependency).
    redis_client = get_redis_client()
    key = f"{_REVOKED_JTI_PREFIX}{payload.jti}"
    try:
        revoked = await redis_client.exists(key)
        if revoked:
            raise InvalidTokenError(f"Token has been revoked (jti={payload.jti})")
    except InvalidTokenError:
        raise
    except Exception as exc:  # noqa: BLE001
        # Gracefully degrade when Redis is unavailable — log and continue.
        logger.warning(
            "Redis JTI revocation check unavailable (jti=%s): %s — skipping",
            payload.jti,
            exc,
        )

    return payload


# ---------------------------------------------------------------------------
# Heartbeat
# ---------------------------------------------------------------------------


class HeartbeatMonitor:
    """Application-level ping/pong heartbeat over a WebSocket.

    Sends ``{"type": "ping"}`` every :attr:`interval` seconds and expects the
    client to respond with ``{"type": "pong"}`` within :attr:`timeout` seconds.
    If no pong arrives in time the connection is closed.

    Usage::

        monitor = HeartbeatMonitor(websocket)
        task = asyncio.create_task(monitor.run())
        # ... handle messages, call monitor.pong_received() on {"type":"pong"}
        task.cancel()

    Requirements: 26.2
    """

    def __init__(
        self,
        websocket: WebSocket,
        interval: float = _HEARTBEAT_INTERVAL_SECONDS,
        timeout: float = _HEARTBEAT_TIMEOUT_SECONDS,
    ) -> None:
        self._ws = websocket
        self.interval = interval
        self.timeout = timeout
        self._pong_event = asyncio.Event()

    def pong_received(self) -> None:
        """Signal that the client responded with a pong.

        Call this from the message receive loop when a ``{"type": "pong"}``
        message arrives.
        """
        self._pong_event.set()

    async def run(self) -> None:
        """Run the heartbeat loop until the task is cancelled or the connection dies.

        Requirements: 26.2
        """
        try:
            while True:
                await asyncio.sleep(self.interval)
                # Send ping
                try:
                    await self._ws.send_json({"type": "ping"})
                except Exception:  # noqa: BLE001
                    # Connection already closed; stop heartbeat silently.
                    return

                # Wait for pong
                self._pong_event.clear()
                try:
                    await asyncio.wait_for(
                        self._pong_event.wait(),
                        timeout=self.timeout,
                    )
                except asyncio.TimeoutError:
                    logger.info("WebSocket heartbeat timeout — closing connection")
                    try:
                        await self._ws.close(code=1001)  # Going Away
                    except Exception:  # noqa: BLE001
                        pass
                    return
        except asyncio.CancelledError:
            # Normal cancellation; exit cleanly.
            pass


# ---------------------------------------------------------------------------
# Token buffer (Redis)
# ---------------------------------------------------------------------------


def _buffer_key(user_id: str, conversation_id: str) -> str:
    return f"{_BUFFER_KEY_PREFIX}:{user_id}:{conversation_id}"


async def flush_token_buffer(
    user_id: str,
    conversation_id: str,
    websocket: WebSocket,
) -> None:
    """Deliver any buffered tokens to the reconnected client and clear the buffer.

    Called immediately after a successful reconnection (auth + accept) so
    buffered tokens arrive before new streaming output begins.

    Args:
        user_id: Authenticated user UUID string.
        conversation_id: Target conversation UUID string.
        websocket: The newly accepted WebSocket connection.

    Requirements: 26.3
    """
    redis_client: Redis = get_redis_client()
    key = _buffer_key(user_id, conversation_id)
    try:
        tokens: list[str] = await redis_client.lrange(key, 0, -1)
        if tokens:
            logger.info(
                "Delivering %d buffered tokens to user=%s conversation=%s",
                len(tokens),
                user_id,
                conversation_id,
            )
            for token in tokens:
                await websocket.send_json({"type": "token", "data": token})
            await redis_client.delete(key)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to flush token buffer (key=%s): %s", key, exc)


async def buffer_token(
    user_id: str,
    conversation_id: str,
    token: str,
    redis_client: Redis | None = None,
) -> None:
    """Append a single streaming token to the Redis buffer list.

    Limits the list to :data:`_BUFFER_MAX_LEN` entries and refreshes the TTL
    to :data:`_BUFFER_TTL_SECONDS` on every write.

    Args:
        user_id: Authenticated user UUID string.
        conversation_id: Target conversation UUID string.
        token: The streaming token string to buffer.
        redis_client: Optional Redis client; falls back to the singleton.

    Requirements: 26.3
    """
    client = redis_client or get_redis_client()
    key = _buffer_key(user_id, conversation_id)
    try:
        pipe = client.pipeline()
        pipe.rpush(key, token)
        pipe.ltrim(key, -_BUFFER_MAX_LEN, -1)
        pipe.expire(key, _BUFFER_TTL_SECONDS)
        await pipe.execute()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to buffer token (key=%s): %s", key, exc)
