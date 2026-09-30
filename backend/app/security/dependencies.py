# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : dependencies.py
# Purpose : dependencies — security module
#
# Architecture Layer : Security
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""FastAPI security dependencies — JWT authentication guard.

This module provides the ``get_current_user`` dependency that protects all
non-public API routes.  It is the single integration point between FastAPI's
dependency injection system and the JWT / Redis revocation infrastructure.

Usage (per-router)::

    from fastapi import APIRouter, Depends
    from app.security.dependencies import get_current_user

    router = APIRouter(dependencies=[Depends(get_current_user)])

Or per-endpoint::

    @router.get("/me")
    async def me(current_user: TokenPayload = Depends(get_current_user)):
        return {"sub": current_user.sub, "role": current_user.role}

Requirements: 9.1
"""

from __future__ import annotations

import logging

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.security.exceptions import InvalidTokenError
from app.security.jwt_handler import TokenPayload, verify_access_token

logger = logging.getLogger(__name__)

# HTTPBearer raises HTTP 403 (not 401) by default when the header is absent.
# Setting auto_error=False lets us control the response ourselves so we can
# always return 401 with the correct WWW-Authenticate header.
_http_bearer = HTTPBearer(auto_error=False)

# ---------------------------------------------------------------------------
# Redis revocation helper
# ---------------------------------------------------------------------------

_REVOKED_JTI_KEY_PREFIX = "revoked_jti:"


async def _is_jti_revoked(jti: str) -> bool:
    """Check whether a JWT ID has been revoked via Redis.

    This performs a non-blocking async lookup.  If Redis is unavailable or not
    configured, the check is skipped and the function returns ``False`` so that
    JWT validation degrades gracefully rather than denying all traffic.

    Args:
        jti: The ``jti`` claim from the decoded JWT.

    Returns:
        ``True`` if the JTI is present in the Redis revocation set, ``False``
        otherwise (including when Redis is unreachable).

    Requirements: 9.1
    """
    try:
        from app.database.redis import get_redis_client

        redis_client = get_redis_client()
        key = f"{_REVOKED_JTI_KEY_PREFIX}{jti}"
        result = await redis_client.exists(key)
        return bool(result)
    except Exception as exc:
        # Gracefully degrade: log a warning and allow the request to proceed.
        logger.warning("Redis revocation check unavailable (jti=%s): %s — skipping", jti, exc)
        return False


# ---------------------------------------------------------------------------
# Core dependency
# ---------------------------------------------------------------------------


async def get_current_user(
    credentials: HTTPAuthorizationCredentials | None = Depends(_http_bearer),
) -> TokenPayload:
    """Extract, validate, and return the JWT payload for the current request.

    This is the primary FastAPI dependency for protecting endpoints.  Apply it
    at router level via ``dependencies=[Depends(get_current_user)]`` or
    per-endpoint via a function parameter typed as ``TokenPayload``.

    Validation steps:
    1. Presence of ``Authorization: Bearer <token>`` header.
    2. JWT signature and expiry via :func:`~app.security.jwt_handler.verify_access_token`.
    3. JTI revocation check against Redis.

    Args:
        credentials: Injected by FastAPI from the ``Authorization`` header.
            ``None`` when the header is absent (``auto_error=False`` on the
            bearer scheme).

    Returns:
        The validated :class:`~app.security.jwt_handler.TokenPayload` on success.

    Raises:
        :class:`fastapi.HTTPException`: HTTP 401 with
            ``WWW-Authenticate: Bearer`` on any validation failure.

    Requirements: 9.1
    """
    _unauthorized = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Not authenticated",
        headers={"WWW-Authenticate": "Bearer"},
    )

    # Step 1 — header presence
    if credentials is None:
        raise _unauthorized

    token = credentials.credentials

    # Step 2 — signature + expiry
    try:
        payload = verify_access_token(token)
    except InvalidTokenError as exc:
        logger.debug("JWT validation failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=str(exc),
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    # Step 3 — JTI revocation (Redis)
    if await _is_jti_revoked(payload.jti):
        logger.info("Revoked JTI presented: %s (sub=%s)", payload.jti, payload.sub)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token has been revoked",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return payload
