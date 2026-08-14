# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : middleware
# File    : request_size.py
# Purpose : request_size — middleware module
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

"""Request body size limiting middleware.

Enforces a maximum request body size at the HTTP boundary — before the body
reaches any Pydantic schema or route handler.  This prevents:

- Memory exhaustion from unexpectedly large uploads sent to JSON endpoints
- Application-layer resource exhaustion attacks

The limit is configured via ``Settings.MAX_REQUEST_BODY_SIZE`` (default 1 MB
for standard JSON endpoints).  Multipart file uploads are handled separately
by FastAPI's ``UploadFile`` mechanism and are subject to the file-size check
in the RAG router (50 MB limit per Requirement 4.1).

Implementation notes
--------------------
This middleware is implemented as a pure ASGI middleware rather than using
Starlette's ``BaseHTTPMiddleware``. This avoids a known issue where
``BaseHTTPMiddleware`` consumes the request body stream but fails to correctly
propagate the re-buffered body to downstream FastAPI handlers, causing
false-positive 422 Unprocessable Entity errors.

The middleware reads the body entirely up to the limit before passing it to
the application. This is safe for standard JSON endpoints (limited to 1 MB).

Usage (registered in ``main.py``)::

    from app.middleware.request_size import RequestBodySizeLimitMiddleware
    app.add_middleware(RequestBodySizeLimitMiddleware)

Requirements: 9.7
"""

from __future__ import annotations

import logging

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

logger = logging.getLogger(__name__)

# Paths whose uploads are managed separately and must bypass this limit.
# RAG document uploads go through FastAPI's UploadFile and have their own
# 50 MB size check in the route handler (Requirement 4.1).
_BYPASS_PREFIXES: tuple[str, ...] = ("/documents/upload",)

# Default limit: 1 MB expressed in bytes.  Overridden by
# ``settings.MAX_REQUEST_BODY_SIZE`` when present.
_DEFAULT_LIMIT_BYTES: int = 1 * 1024 * 1024  # 1 MiB


class RequestBodySizeLimitMiddleware:
    """ASGI middleware that enforces a maximum JSON request body size.

    Rejects requests with oversized bodies before they reach any schema
    validation or route handler, returning HTTP 413 Payload Too Large.

    Requirements: 9.7
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self._settings = None  # lazy

    def _get_limit(self) -> int:
        """Return the configured max body size, falling back to the default."""
        if self._settings is None:
            try:
                from app.config.settings import get_settings

                self._settings = get_settings()
            except Exception:  # noqa: BLE001
                return _DEFAULT_LIMIT_BYTES
        return getattr(self._settings, "MAX_REQUEST_BODY_SIZE", _DEFAULT_LIMIT_BYTES)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        """Process the ASGI request."""
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        method = scope.get("method")
        path = scope.get("path", "")

        # Skip multipart file-upload endpoints and non-body methods.
        is_bypass = False
        for prefix in _BYPASS_PREFIXES:
            if path.startswith(prefix):
                is_bypass = True
                break

        if is_bypass or method not in {"POST", "PUT", "PATCH"}:
            await self.app(scope, receive, send)
            return

        limit = self._get_limit()

        # Fast path via Content-Length header.
        headers = dict(scope.get("headers", []))
        content_length_header = headers.get(b"content-length")
        if content_length_header is not None:
            try:
                content_length = int(content_length_header)
                if content_length > limit:
                    logger.warning(
                        "Request body too large (Content-Length: %d > limit: %d), path=%s",
                        content_length,
                        limit,
                        path,
                    )
                    await self._send_413(scope, receive, send, limit)
                    return
            except (ValueError, TypeError):
                pass

        # Buffering path: read the body stream and count bytes.
        messages = []
        body_len = 0
        more_body = True
        while more_body:
            message = await receive()
            if message["type"] == "http.request":
                chunk = message.get("body", b"")
                body_len += len(chunk)
                if body_len > limit:
                    logger.warning(
                        "Request body too large (buffered %d bytes > limit: %d), path=%s",
                        body_len,
                        limit,
                        path,
                    )
                    await self._send_413(scope, receive, send, limit)
                    return
                messages.append(message)
                more_body = message.get("more_body", False)
            elif message["type"] == "http.disconnect":
                return

        # Patch the receive callable so the app can read the buffered messages.
        async def buffered_receive() -> dict:
            if messages:
                return messages.pop(0)
            return await receive()

        await self.app(scope, buffered_receive, send)

    async def _send_413(
        self, scope: Scope, receive: Receive, send: Send, limit: int
    ) -> None:
        """Helper to send a 413 response over raw ASGI."""
        response = JSONResponse(
            status_code=413,
            content={
                "detail": (
                    f"Request body too large. "
                    f"Maximum allowed size is {limit // 1024} KiB."
                )
            },
        )
        await response(scope, receive, send)
