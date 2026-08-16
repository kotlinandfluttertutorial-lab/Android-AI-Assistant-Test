# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : middleware
# File    : data_residency.py
# Purpose : data_residency — middleware module
#
# Architecture Layer : Middleware
# Pattern Used       : ASGI Middleware
#
# Key Concepts:
#   - FastAPI async request handling
#   - Geographic data residency constraint enforcement
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Data residency enforcement middleware (Req 9.7).

Rejects write operations (POST, PUT, PATCH, DELETE) when the
``X-Client-Region`` request header is present and does not match the
configured ``DATA_RESIDENCY_REGION`` setting.

Enforcement logic
-----------------
- If ``DATA_RESIDENCY_REGION`` is empty (default), all requests are allowed.
- GET, HEAD, OPTIONS are **never** checked — only write methods are.
- If the ``X-Client-Region`` header is **absent**, the request is allowed
  (absence is treated as unknown / unverifiable region).
- If the header is present and **matches** the configured region (case-insensitive
  comparison), the request is allowed.
- If the header is present and **does not match**, HTTP 403 is returned with
  ``{"detail": "Data residency constraint violation"}``.

Requirements: 9.7
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

if TYPE_CHECKING:
    from app.config.settings import Settings

logger = logging.getLogger(__name__)

# HTTP methods that mutate data — subject to residency enforcement
_WRITE_METHODS = frozenset({"POST", "PUT", "PATCH", "DELETE"})


class DataResidencyMiddleware:
    """ASGI middleware that enforces geographic data residency constraints.

    When ``DATA_RESIDENCY_REGION`` is configured, write operations are only
    allowed from clients that declare a matching ``X-Client-Region`` header.
    Requests without the header are not blocked (absence = unknown region).

    Requirements: 9.7
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app
        self._settings: Settings | None = None  # lazy

    def _get_settings(self) -> Settings:
        if self._settings is None:
            from app.config.settings import get_settings

            self._settings = get_settings()
        return self._settings

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        settings = self._get_settings()
        required_region: str = settings.DATA_RESIDENCY_REGION

        # Fast path: residency not configured — pass through immediately
        if not required_region:
            await self.app(scope, receive, send)
            return

        method = scope.get("method", "GET").upper()

        # Only enforce on write methods
        if method not in _WRITE_METHODS:
            await self.app(scope, receive, send)
            return

        # Extract X-Client-Region header
        headers = dict(scope.get("headers", []))
        client_region_bytes = headers.get(b"x-client-region")

        if client_region_bytes is None:
            # Header absent — allow (unknown region is not a violation)
            await self.app(scope, receive, send)
            return

        client_region = client_region_bytes.decode("utf-8", errors="replace").strip()

        if client_region.lower() != required_region.lower():
            logger.warning(
                "Data residency violation: client_region=%r, required=%r, method=%s",
                client_region,
                required_region,
                method,
            )
            response = JSONResponse(
                status_code=403,
                content={"detail": "Data residency constraint violation"},
            )
            await response(scope, receive, send)
            return

        await self.app(scope, receive, send)
