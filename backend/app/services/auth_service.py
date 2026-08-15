# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : auth_service.py
# Purpose : Business logic for the auth domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Authentication and token management service.

This module orchestrates JWT and refresh token issuance, verification,
rotation, replay detection, and account lockout.  It is the **business
logic layer** for all authentication flows:

- Login → check lockout → verify credentials → issue JWT + refresh token
- Refresh → rotate tokens with replay detection
- Logout → revoke all active tokens for the user

The service does NOT interact with HTTP request/response objects; route
handlers call these functions with validated request data and translate the
results into HTTP responses.

Requirements: 1.2, 1.3, 1.4, 1.5, 1.10
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.repositories.refresh_token_repository import RefreshTokenRepository
from app.security.exceptions import (
    InvalidTokenError,
    TokenFamilyRevokedError,
)
from app.security.jwt_handler import (
    create_access_token,
    create_refresh_token,
    hash_token,
)


async def issue_tokens_for_user(
    db: AsyncSession,
    user_id: uuid.UUID,
    role: str,
) -> tuple[str, datetime, str, datetime]:
    """Issue a new JWT + refresh token pair for a newly authenticated user.

    This is called immediately after successful login (password or OAuth2).

    Args:
        db:      SQLAlchemy async session (transaction managed by the caller).
        user_id: UUID of the authenticated user.
        role:    User role string (``"user"``, ``"premium"``, ``"admin"``).

    Returns:
        A 4-tuple of ``(access_token, access_exp, refresh_token, refresh_exp)``.
        The access token and refresh token are strings; the exp values are datetimes.

    Requirements: 1.2
    """
    repo = RefreshTokenRepository(db)

    # Issue the JWT
    access_token, access_exp = create_access_token(user_id=user_id, role=role)

    # Generate a new refresh token (new family)
    refresh_data = create_refresh_token(family_id=None)

    # Persist the refresh token record
    await repo.create(
        user_id=user_id,
        token_hash=refresh_data.token_hash,
        expires_at=refresh_data.expires_at,
        family_id=refresh_data.family_id,
        parent_token_id=None,  # first token in the chain
    )

    return access_token, access_exp, refresh_data.raw_token, refresh_data.expires_at


async def refresh_tokens(
    db: AsyncSession,
    raw_refresh_token: str,
) -> tuple[str, datetime, str, datetime, str, uuid.UUID]:
    """Rotate a refresh token and issue a new JWT + refresh token pair.

    Token rotation ensures that every refresh token is single-use.  If a token
    that has already been ``used`` is submitted again (replay), the entire
    token family is revoked.

    Workflow:
    1. Hash the raw token and look it up in the database.
    2. If not found → raise ``InvalidTokenError``.
    3. If found but ``revoked=True`` → raise ``InvalidTokenError``.
    4. If found but ``used=True`` → **replay detected**:
       - Revoke all tokens with the same ``family_id``
       - Raise ``SecurityViolationError``
    5. If found and valid (not revoked, not used, not expired):
       - Mark the current token as ``used``
       - Issue new JWT
       - Issue new refresh token (same ``family_id``, ``parent_id`` = current token)
       - Persist the new refresh token record
       - Return ``(new_access_token, access_exp, new_refresh_token, refresh_exp, role, user_id)``

    Args:
        db:                 SQLAlchemy async session.
        raw_refresh_token:  The refresh token string submitted by the client.

    Returns:
        A 6-tuple of
        ``(new_access_token, access_exp, new_refresh_token, refresh_exp, role, user_id)``.

    Raises:
        :class:`~app.security.exceptions.InvalidTokenError`: Token not found,
            expired, or already revoked.
        :class:`~app.security.exceptions.SecurityViolationError`: Replay
            detected — all tokens in the family have been revoked.

    Requirements: 1.3, 1.4
    """
    repo = RefreshTokenRepository(db)
    token_hash = hash_token(raw_refresh_token)

    # Look up the token
    record = await repo.get_by_hash(token_hash)
    if record is None:
        raise InvalidTokenError("refresh token not found")

    # Check if revoked
    if record.revoked:
        raise InvalidTokenError("refresh token has been revoked")

    # Check expiry (Python-level check; the database clock is authoritative)
    now = datetime.now(tz=UTC)
    if record.expires_at <= now:
        raise InvalidTokenError("refresh token has expired")

    # **Replay detection**: if the token has already been used, revoke the
    # entire family and raise an error.
    if record.used:
        count = await repo.revoke_family(record.family_id)
        raise TokenFamilyRevokedError(
            f"replay detected — revoked {count} tokens in family {record.family_id}"
        )

    # Mark the current token as used (single-use property)
    await repo.mark_used(record.id)

    # Load the user to retrieve the role for the new JWT
    # (The RefreshToken model has a relationship to User; we can access it directly)
    user = record.user
    if not user.is_active:
        raise InvalidTokenError("user account is not active")

    # Issue new JWT
    new_access_token, access_exp = create_access_token(
        user_id=user.id,
        role=user.role.value,  # user.role is a UserRole enum
    )

    # Generate new refresh token (inherit family_id, set parent_id = current token)
    new_refresh_data = create_refresh_token(family_id=record.family_id)

    await repo.create(
        user_id=user.id,
        token_hash=new_refresh_data.token_hash,
        expires_at=new_refresh_data.expires_at,
        family_id=new_refresh_data.family_id,
        parent_token_id=record.id,
    )

    return (
        new_access_token,
        access_exp,
        new_refresh_data.raw_token,
        new_refresh_data.expires_at,
        user.role.value,
        user.id,
    )


async def logout_user(
    db: AsyncSession,
    user_id: uuid.UUID,
) -> int:
    """Revoke all active refresh tokens for a user (explicit logout).

    Args:
        db:      SQLAlchemy async session.
        user_id: UUID of the user logging out.

    Returns:
        The number of refresh tokens revoked.

    Requirements: 1.10
    """
    repo = RefreshTokenRepository(db)
    count = await repo.revoke_all_for_user(user_id)
    return count
