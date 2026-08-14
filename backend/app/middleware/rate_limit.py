# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : middleware
# File    : rate_limit.py
# Purpose : rate_limit — middleware module
#
# Architecture Layer : Middleware
# Pattern Used       : ASGI Middleware
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""API rate limiting middleware — two-tier per-user and per-IP rate limits.

Tier 1 (authenticated): 60 requests/minute per authenticated user (configurable
  via RATE_LIMIT_REQUESTS_PER_MINUTE).
Tier 2 (unauthenticated): 20 requests/minute per source IP for public endpoints
  (configurable via RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE).

Uses a Redis sliding-window counter to enforce per-user and per-IP limits.

Implementation notes
--------------------
This middleware exposes both a low-level ASGI ``__call__`` interface and a
higher-level ``dispatch(request, call_next)`` interface (compatible with
Starlette's ``BaseHTTPMiddleware`` call signature) so that unit tests can
drive it with ``Request`` / ``call_next`` stubs.

Requirements: 9.9, 9.11
"""

from __future__ import annotations

import base64
import json
import logging
import math
import time

from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.types import ASGIApp, Receive, Scope, Send

logger = logging.getLogger(__name__)

# Redis key prefix for per-user rate limit counters
_RATE_KEY_PREFIX = "rate:"
# Redis key prefix for per-IP (unauthenticated) rate limit counters
_RATE_IP_KEY_PREFIX = "rate:ip:"
# TTL applied to each rate key (two full windows to handle clock edge cases)
_RATE_KEY_TTL_SECONDS = 120


# ---------------------------------------------------------------------------
# Header helpers
# ---------------------------------------------------------------------------


def _extract_user_id_from_header(authorization: str | None) -> str | None:
    """Extract the ``sub`` claim from a JWT Bearer token in an Authorization header value.

    This function accepts the *value* of the Authorization header (i.e. the
    string ``"Bearer <token>"``), **not** a ``Request`` or scope object.

    Args:
        authorization: The raw ``Authorization`` header string, or ``None``.

    Returns:
        The ``sub`` claim string if a valid Bearer JWT is provided, otherwise
        ``None``.

    Requirements: 9.9
    """
    if not authorization:
        return None

    try:
        if not authorization.startswith("Bearer "):
            return None

        token = authorization.removeprefix("Bearer ").strip()
        if not token:
            return None
        parts = token.split(".")
        if len(parts) != 3:
            return None

        payload_b64 = parts[1]
        padding = 4 - len(payload_b64) % 4
        if padding != 4:
            payload_b64 += "=" * padding

        payload = json.loads(base64.urlsafe_b64decode(payload_b64))
        return str(payload["sub"]) if payload.get("sub") else None
    except Exception:  # noqa: BLE001
        return None


def _extract_user_id_from_scope(scope: Scope) -> str | None:
    """Extract the ``sub`` claim from a JWT Bearer token in ASGI scope headers."""
    headers = dict(scope.get("headers", []))
    authorization = headers.get(b"authorization")
    if not authorization:
        return None

    try:
        return _extract_user_id_from_header(authorization.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None


def _extract_client_ip(scope: Scope) -> str:
    """Extract the client IP address from an ASGI scope.

    Checks the ``X-Forwarded-For`` header first (uses the first entry, which
    is the original client IP when a trusted reverse proxy is in use).  Falls
    back to the ``client`` tuple in the ASGI scope.

    Args:
        scope: The ASGI connection scope dict.

    Returns:
        The client IP address string, or ``"unknown"`` if it cannot be determined.

    Requirements: 9.11
    """
    headers = dict(scope.get("headers", []))
    xff = headers.get(b"x-forwarded-for")
    if xff:
        try:
            # X-Forwarded-For: client, proxy1, proxy2 — take leftmost entry
            first_ip = xff.decode("utf-8").split(",")[0].strip()
            if first_ip:
                return first_ip
        except Exception:  # noqa: BLE001
            pass

    # Fall back to the ASGI client tuple (host, port)
    client = scope.get("client")
    if client and isinstance(client, (tuple, list)) and len(client) >= 1:
        return str(client[0])

    return "unknown"


# ---------------------------------------------------------------------------
# Middleware
# ---------------------------------------------------------------------------


class RateLimitMiddleware:
    """ASGI middleware that enforces per-user and per-IP API rate limits via Redis.

    Two-tier rate limiting:
    - Authenticated requests: limited by user ID (60 req/min default).
    - Unauthenticated requests: limited by source IP (20 req/min default).

    Both tiers use Redis sliding-window counters with TTL=120 s.

    Requirements: 9.9, 9.11
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self._settings = None  # lazy

    def _get_settings(self):
        if self._settings is None:
            from app.config.settings import get_settings

            self._settings = get_settings()
        return self._settings

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # Build a minimal Request to reuse dispatch() logic.
        request = Request(scope, receive, send)
        auth_header = request.headers.get("authorization")
        user_id = _extract_user_id_from_header(auth_header)

        async def call_next(req: Request) -> Response:
            response_started = False
            response_body = []

            async def send_wrapper(message) -> None:
                nonlocal response_started
                if message["type"] == "http.response.start":
                    response_started = True
                    scope["_response_started"] = message
                elif message["type"] == "http.response.body":
                    response_body.append(message.get("body", b""))
                await send(message)

            await self.app(scope, receive, send_wrapper)
            # Return a synthetic Response so dispatch() can return it
            status_code = scope.get("_response_started", {}).get("status", 200)
            return Response(
                content=b"".join(response_body),
                status_code=status_code,
            )

        settings = self._get_settings()
        redis_client = await self._get_redis()
        now = time.time()
        window = int(now // 60)

        if user_id:
            # Tier 1 — authenticated user rate limit
            limit = settings.RATE_LIMIT_REQUESTS_PER_MINUTE
            key = f"{_RATE_KEY_PREFIX}{user_id}:{window}"
            try:
                current_count: int = await redis_client.incr(key)
                if current_count == 1:
                    await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)
                if current_count > limit:
                    retry_after = math.ceil(60 - (now % 60))
                    logger.warning(
                        "Rate limit exceeded (user)",
                        extra={
                            "user_id": user_id,
                            "count": current_count,
                            "limit": limit,
                        },
                    )
                    response = JSONResponse(
                        status_code=429,
                        content={
                            "detail": f"Rate limit exceeded. Maximum {limit} requests per minute."
                        },
                        headers={"Retry-After": str(retry_after)},
                    )
                    await response(scope, receive, send)
                    return
            except Exception as exc:  # noqa: BLE001
                logger.warning("Rate limit Redis check failed (fail-open): %s", exc)
        else:
            # Tier 2 — unauthenticated IP rate limit
            ip_addr = _extract_client_ip(scope)
            limit = settings.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE
            key = f"{_RATE_IP_KEY_PREFIX}{ip_addr}:{window}"
            try:
                current_count = await redis_client.incr(key)
                if current_count == 1:
                    await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)
                if current_count > limit:
                    retry_after = math.ceil(60 - (now % 60))
                    logger.warning(
                        "Rate limit exceeded (IP)",
                        extra={"ip": ip_addr, "count": current_count, "limit": limit},
                    )
                    response = JSONResponse(
                        status_code=429,
                        content={
                            "detail": f"Rate limit exceeded. Maximum {limit} requests per minute."
                        },
                        headers={"Retry-After": str(retry_after)},
                    )
                    await response(scope, receive, send)
                    return
            except Exception as exc:  # noqa: BLE001
                logger.warning("IP rate limit Redis check failed (fail-open): %s", exc)

        await self.app(scope, receive, send)

    async def dispatch(self, request: Request, call_next) -> Response:
        """Handle a single HTTP request with rate limiting.

        This method follows the Starlette BaseHTTPMiddleware ``dispatch``
        signature so that unit tests can drive it directly with a stub
        ``Request`` object and a ``call_next`` coroutine.

        Args:
            request: The incoming HTTP request.
            call_next: Async callable that forwards the request down the stack.

        Returns:
            A ``Response`` — either a 429 if the rate limit is exceeded, or
            whatever ``call_next`` returns.

        Requirements: 9.9, 9.11
        """
        settings = self._get_settings()
        now = time.time()
        window = int(now // 60)

        auth_header = request.headers.get("authorization")
        user_id = _extract_user_id_from_header(auth_header)

        if user_id:
            # Tier 1 — authenticated user rate limit
            limit: int = settings.RATE_LIMIT_REQUESTS_PER_MINUTE
            key = f"{_RATE_KEY_PREFIX}{user_id}:{window}"
            try:
                redis_client = await self._get_redis()
                current_count: int = await redis_client.incr(key)
                if current_count == 1:
                    await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)
                if current_count > limit:
                    retry_after = math.ceil(60 - (now % 60))
                    logger.warning(
                        "Rate limit exceeded",
                        extra={
                            "user_id": user_id,
                            "count": current_count,
                            "limit": limit,
                        },
                    )
                    return JSONResponse(
                        status_code=429,
                        content={
                            "detail": f"Rate limit exceeded. Maximum {limit} requests per minute."
                        },
                        headers={"Retry-After": str(retry_after)},
                    )
            except Exception as exc:  # noqa: BLE001
                logger.warning("Rate limit Redis check failed (fail-open): %s", exc)
        else:
            # Tier 2 — unauthenticated IP rate limit
            # Extract IP from scope if available, fall back to request.client
            scope = getattr(request, "scope", {})
            ip_addr = _extract_client_ip(scope) if scope else "unknown"
            # Also check request.headers for X-Forwarded-For
            xff = request.headers.get("x-forwarded-for")
            if xff:
                ip_addr = xff.split(",")[0].strip()
            elif ip_addr == "unknown" and request.client:
                ip_addr = str(request.client.host)

            limit = settings.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE
            key = f"{_RATE_IP_KEY_PREFIX}{ip_addr}:{window}"
            try:
                redis_client = await self._get_redis()
                current_count = await redis_client.incr(key)
                if current_count == 1:
                    await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)
                if current_count > limit:
                    retry_after = math.ceil(60 - (now % 60))
                    logger.warning(
                        "IP rate limit exceeded",
                        extra={"ip": ip_addr, "count": current_count, "limit": limit},
                    )
                    return JSONResponse(
                        status_code=429,
                        content={
                            "detail": f"Rate limit exceeded. Maximum {limit} requests per minute."
                        },
                        headers={"Retry-After": str(retry_after)},
                    )
            except Exception as exc:  # noqa: BLE001
                logger.warning("IP rate limit Redis check failed (fail-open): %s", exc)

        return await call_next(request)

    async def _get_redis(self):
        """Return the shared async Redis client singleton."""
        from app.database.redis import get_redis_client

        return get_redis_client()
