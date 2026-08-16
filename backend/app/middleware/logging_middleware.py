# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : middleware
# File    : logging_middleware.py
# Purpose : logging_middleware — middleware module
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

"""Structured JSON request logging middleware.

Emits a JSON log entry for every API request containing:
- correlation_id: UUID generated per request
- user_id: JWT ``sub`` claim if authenticated, else null
- path: request URL path
- method: HTTP method
- status_code: HTTP response status code
- response_time_ms: elapsed time in milliseconds

Implementation notes
--------------------
This middleware is implemented as a pure ASGI middleware for maximum performance
and reliability. It avoids Starlette's ``BaseHTTPMiddleware`` to ensure
compatibility with body-consuming middlewares in the same stack.

Requirements: 18.1, 18.5
"""

from __future__ import annotations

import base64
import json
import logging
import time
import uuid
from typing import TYPE_CHECKING, Any

from prometheus_client import Counter
from starlette.types import ASGIApp, Receive, Scope, Send

if TYPE_CHECKING:
    from app.config.settings import Settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Prometheus counter
# ---------------------------------------------------------------------------

http_unhandled_exceptions_total: Counter = Counter(
    "http_unhandled_exceptions_total",
    "Total number of unhandled exceptions in HTTP request handlers.",
    labelnames=["path"],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _extract_user_id_from_scope(scope: Scope) -> str | None:
    """Extract the ``sub`` claim from a JWT Bearer token in the scope headers."""
    headers = dict(scope.get("headers", []))
    authorization = headers.get(b"authorization")
    if not authorization:
        return None

    try:
        auth_str = authorization.decode("utf-8")
        if not auth_str.startswith("Bearer "):
            return None

        token = auth_str.removeprefix("Bearer ").strip()
        parts = token.split(".")
        if len(parts) != 3:
            return None

        payload_b64 = parts[1]
        padding = 4 - len(payload_b64) % 4
        if padding != 4:
            payload_b64 += "=" * padding

        payload = json.loads(base64.urlsafe_b64decode(payload_b64))
        return str(payload["sub"]) if payload.get("sub") else None
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Middleware
# ---------------------------------------------------------------------------


class RequestLoggingMiddleware:
    """ASGI middleware that emits structured JSON request logs.

    Generates a UUID correlation ID per request, adds an ``X-Correlation-ID``
    response header, and emits a structured log entry.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self._settings: Settings | None = None  # lazy

    def _get_settings(self) -> Settings:
        if self._settings is None:
            from app.config.settings import get_settings

            self._settings = get_settings()
        return self._settings

    def _ensure_loki_handler(self) -> None:
        settings = self._get_settings()
        if not settings.LOKI_URL:
            return

        if any(type(h).__name__ == "LokiHandler" for h in logger.handlers):
            return

        try:
            import logging_loki

            loki_handler = logging_loki.LokiHandler(
                url=settings.LOKI_URL,
                tags={
                    "application": "android-ai-assistant",
                    "environment": settings.ENVIRONMENT,
                },
                version="1",
            )
            logger.addHandler(loki_handler)
        except Exception as exc:
            logger.warning("Failed to attach Loki handler: %s", exc)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        self._ensure_loki_handler()

        correlation_id = str(uuid.uuid4())
        # Attach correlation ID to scope so downstream can use it
        scope["correlation_id"] = correlation_id

        user_id = _extract_user_id_from_scope(scope)
        start_time = time.perf_counter()
        status_code = [500]  # Default to 500 if not caught

        async def wrapped_send(message: dict[str, Any]) -> None:
            if message["type"] == "http.response.start":
                status_code[0] = message["status"]

                # Add correlation ID header to the response
                headers = list(message.get("headers", []))
                headers.append((b"x-correlation-id", correlation_id.encode("utf-8")))
                message["headers"] = headers

            await send(message)

        unhandled_exception = False
        try:
            await self.app(scope, receive, wrapped_send)  # type: ignore[arg-type]
        except Exception:
            unhandled_exception = True
            elapsed_ms = (time.perf_counter() - start_time) * 1000.0
            logger.exception(
                "Unhandled exception during request",
                extra={"correlation_id": correlation_id},
            )
            try:
                http_unhandled_exceptions_total.labels(path=scope.get("path", "")).inc()
            except Exception:
                pass
            raise
        finally:
            elapsed_ms = (time.perf_counter() - start_time) * 1000.0
            if not unhandled_exception:
                logger.info(
                    "request",
                    extra={
                        "correlation_id": correlation_id,
                        "user_id": user_id,
                        "path": scope.get("path"),
                        "method": scope.get("method"),
                        "status_code": status_code[0],
                        "response_time_ms": round(elapsed_ms, 3),
                    },
                )
