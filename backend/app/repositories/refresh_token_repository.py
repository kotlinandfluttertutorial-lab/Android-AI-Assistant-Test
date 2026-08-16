# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : refresh_token_repository.py
# Purpose : Database access layer for refresh_token entities
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Database access layer for refresh tokens.

All queries in this module operate on the ``refresh_tokens`` table via the
SQLAlchemy async session.  The repository layer is intentionally thin —
it translates between the service layer and the ORM without embedding any
business logic.

Token hashing is performed **before** calling any repository method.  This
module always receives hashed values and never sees raw token strings.

Requirements: 1.3, 1.4
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.refresh_token import RefreshToken


class RefreshTokenRepository:
    """CRUD and query operations for the ``refresh_tokens`` table.

    All methods are ``async`` and accept an ``AsyncSession`` injected by the
    caller (typically a FastAPI dependency or service function).  This keeps
    transaction boundaries under the caller's control.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ------------------------------------------------------------------
    # Create
    # ------------------------------------------------------------------

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        token_hash: str,
        expires_at: datetime,
        family_id: uuid.UUID,
        parent_token_id: uuid.UUID | None = None,
    ) -> RefreshToken:
        """Persist a new refresh token record and return it.

        Args:
            user_id:         UUID of the token owner.
            token_hash:      SHA-256 hex-digest of the raw token string.
            expires_at:      UTC datetime when the token expires.
            family_id:       UUID shared by the entire rotation chain.
            parent_token_id: ``id`` of the token this one replaces, or
                             ``None`` for the first token in a new chain.

        Returns:
            The newly created :class:`~app.models.refresh_token.RefreshToken`
            instance (flushed but not necessarily committed — the caller
            controls the transaction).
        """
        record = RefreshToken(
            user_id=user_id,
            token_hash=token_hash,
            expires_at=expires_at,
            family_id=family_id,
            parent_token_id=parent_token_id,
            used=False,
            revoked=False,
        )
        self._db.add(record)
        await self._db.flush()  # assigns ``id`` without committing the transaction
        return record

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def get_by_hash(self, token_hash: str) -> RefreshToken | None:
        """Return the token record matching ``token_hash``, or ``None``.

        Args:
            token_hash: SHA-256 hex-digest of the raw token string to look up.

        Returns:
            The matching :class:`~app.models.refresh_token.RefreshToken` row,
            or ``None`` if not found.
        """
        result = await self._db.execute(
            select(RefreshToken)
            .where(RefreshToken.token_hash == token_hash)
            .options(selectinload(RefreshToken.user))
        )
        return result.scalar_one_or_none()

    # ------------------------------------------------------------------
    # Revocation
    # ------------------------------------------------------------------

    async def mark_used(self, token_id: uuid.UUID) -> None:
        """Mark a single token as *used* (not yet revoked).

        Setting ``used = True`` means the token has been exchanged for a new
        token pair.  Submitting it again will trigger replay detection.

        Args:
            token_id: Primary key of the token to mark as used.
        """
        await self._db.execute(
            update(RefreshToken).where(RefreshToken.id == token_id).values(used=True)
        )

    async def revoke(self, token_id: uuid.UUID) -> None:
        """Permanently revoke a single token.

        Args:
            token_id: Primary key of the token to revoke.
        """
        await self._db.execute(
            update(RefreshToken).where(RefreshToken.id == token_id).values(revoked=True)
        )

    async def revoke_family(self, family_id: uuid.UUID) -> int:
        """Revoke **all** tokens in a rotation chain.

        Used when a replay attack is detected — all tokens that share the same
        ``family_id`` are immediately invalidated, regardless of their current
        ``used`` / ``revoked`` state.

        Args:
            family_id: The shared rotation-chain UUID.

        Returns:
            The number of rows updated (useful for audit logging).
        """
        result = await self._db.execute(
            update(RefreshToken)
            .where(RefreshToken.family_id == family_id)
            .values(revoked=True)
        )
        return result.rowcount  # type: ignore[no-any-return]

    async def revoke_all_for_user(self, user_id: uuid.UUID) -> int:
        """Revoke all active refresh tokens for a specific user.

        Called on explicit logout (Requirement 1.10) or admin-initiated session
        termination.

        Args:
            user_id: UUID of the user whose tokens should all be revoked.

        Returns:
            The number of rows updated.
        """
        result = await self._db.execute(
            update(RefreshToken)
            .where(
                RefreshToken.user_id == user_id,
                ~RefreshToken.revoked,
            )
            .values(revoked=True)
        )
        return result.rowcount  # type: ignore[return-value]
