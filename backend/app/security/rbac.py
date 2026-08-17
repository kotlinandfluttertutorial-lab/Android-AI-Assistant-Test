# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : rbac.py
# Purpose : rbac — security module
#
# Architecture Layer : Security
# Pattern Used       : Role-Based Access Control
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Role-Based Access Control (RBAC) dependency factory.

Provides FastAPI dependencies that enforce role requirements on protected
endpoints.  All dependencies build on top of ``get_current_user`` so the
request must already carry a valid JWT before role checks are evaluated.

Usage
-----
Protect a single endpoint::

    from app.security.rbac import require_admin

    @router.delete("/users/{user_id}")
    async def delete_user(
        _: TokenPayload = Depends(require_admin),
    ): ...

Protect an entire router::

    from app.security.rbac import require_premium_or_admin

    router = APIRouter(dependencies=[Depends(require_premium_or_admin)])

Custom role set::

    from app.security.rbac import require_roles
    from app.models.user import UserRole

    @router.post("/feature")
    async def premium_feature(
        _: TokenPayload = Depends(require_roles(UserRole.premium, UserRole.admin)),
    ): ...

Requirements: 9.2, 1.8
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from fastapi import Depends, HTTPException, status

from app.models.user import UserRole
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)


def require_roles(*roles: UserRole | str) -> Callable[..., Any]:
    """Return a FastAPI dependency that rejects users whose role is not in *roles*.

    The dependency resolves ``get_current_user`` first, so a missing or invalid
    JWT results in HTTP 401 before any role check occurs.  An authenticated user
    whose role is not in the allowed set receives HTTP 403.

    Args:
        *roles: One or more :class:`~app.models.user.UserRole` values (or their
            string equivalents, e.g. ``"admin"``) that are permitted to access
            the endpoint.

    Returns:
        A FastAPI-compatible callable dependency that returns the
        :class:`~app.security.jwt_handler.TokenPayload` on success or raises
        :class:`fastapi.HTTPException` on failure.

    Raises:
        :class:`fastapi.HTTPException`: HTTP 403 when the authenticated user's
            role is not in the allowed set.

    Requirements: 9.2, 1.8
    """
    # Normalise everything to lowercase strings for comparison so that callers
    # can pass either UserRole enum values or plain strings.
    allowed: frozenset[str] = frozenset(
        r.value if isinstance(r, UserRole) else str(r).lower() for r in roles
    )

    async def _dependency(
        current_user: TokenPayload = Depends(get_current_user),
    ) -> TokenPayload:
        if current_user.role not in allowed:
            logger.info(
                "RBAC: access denied (role=%r, required_one_of=%s, sub=%s)",
                current_user.role,
                sorted(allowed),
                current_user.sub,
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Insufficient permissions",
            )
        return current_user

    # Give the inner dependency a descriptive name so it shows up clearly in
    # FastAPI's OpenAPI schema and error traces.
    _dependency.__name__ = f"require_roles({'|'.join(sorted(allowed))})"
    return _dependency


# ---------------------------------------------------------------------------
# Convenience dependency instances
# ---------------------------------------------------------------------------

#: Dependency that allows only users with the ``admin`` role.
require_admin: Callable[..., Any] = require_roles(UserRole.admin)

#: Dependency that allows ``premium`` **and** ``admin`` users.
require_premium_or_admin: Callable[..., Any] = require_roles(
    UserRole.premium, UserRole.admin
)
