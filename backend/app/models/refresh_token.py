# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : refresh_token.py
# Purpose : refresh_token — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""ORM model for the ``refresh_tokens`` table.

Refresh tokens are opaque, single-use credentials that allow a User to obtain a
new JWT access token without re-entering their password.

Token rotation
--------------
Every successful refresh produces a **new** refresh token and immediately
**revokes** the one that was submitted (token rotation).  This limits the
window of exposure if a token is ever intercepted.

Replay attack detection
-----------------------
If a refresh token that has already been ``used`` is submitted again, the
backend detects the replay and **revokes ALL active refresh tokens** for the
owner (family revocation / rollback on replay).  This ensures that a compromised
token cannot be silently reused after the legitimate client has already rotated
it.

Each token belongs to a **family** (``family_id``) — a UUID assigned at login
and inherited by every successor token issued through rotation.  Family
revocation flags all tokens sharing the same ``family_id``, which covers the
entire rotation chain.

Lifecycle states
----------------
A ``RefreshToken`` row can be in one of three states:

- ``active`` (``used=False``, ``revoked=False``) — valid, has not been
  presented to the refresh endpoint yet.
- ``used`` (``used=True``, ``revoked=False``) — has been exchanged for a new
  token pair; presenting it again triggers family revocation.
- ``revoked`` (``revoked=True``) — permanently invalidated; any attempt to use
  it returns HTTP 401.

Requirements: 1.3, 1.4
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class RefreshToken(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a single refresh token record.

    Columns
    -------
    token_hash   SHA-256 hex-digest of the raw token string.  The raw value is
                 never stored; only the hash, so a database breach does not
                 expose usable tokens.
    user_id      FK to the owning user; cascades to DELETE so all tokens are
                 removed when the user account is deleted.
    family_id    Shared UUID for all tokens in the same rotation chain.  Used
                 for family revocation on replay detection.
    expires_at   Absolute UTC expiry timestamp (30 days by default, controlled
                 by ``settings.REFRESH_TOKEN_EXPIRE_DAYS``).
    used         True once the token has been exchanged for a new token pair.
    revoked      True when the token has been explicitly invalidated (logout,
                 replay detection, admin deactivation).
    """

    __tablename__ = "refresh_tokens"

    id: Mapped[uuid.UUID] = uuid_pk()

    token_hash: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        unique=True,
        index=True,
        comment="SHA-256 hex-digest of the raw token.  The raw token is never stored.",
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )

    family_id: Mapped[uuid.UUID] = mapped_column(
        nullable=False,
        index=True,
        comment=(
            "Shared UUID for all tokens in the same rotation chain; used for family revocation."
        ),
    )

    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        comment="UTC timestamp after which this token is no longer valid.",
    )

    parent_token_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("refresh_tokens.id", ondelete="SET NULL"),
        nullable=True,
        default=None,
        comment="FK to the previous token this one replaced; NULL for the first token in a family.",
    )

    used: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        comment="True once the token has been exchanged for a new token pair.",
    )

    revoked: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        comment="True when the token has been permanently invalidated.",
    )

    # ------------------------------------------------------------------
    # Composite index for the common query: look up active tokens by user
    # ------------------------------------------------------------------
    __table_args__ = (
        Index("ix_refresh_tokens_user_revoked", "user_id", "revoked"),
        Index("ix_refresh_tokens_family_id", "family_id"),
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User")

    # ------------------------------------------------------------------
    # Convenience helpers
    # ------------------------------------------------------------------
    @property
    def is_valid(self) -> bool:
        """Return True when the token is active and has not expired.

        This is a Python-level check; callers must still consult the database
        clock for authoritative expiry validation.
        """
        from datetime import UTC  # local import to avoid polluting module scope

        return (
            not self.used
            and not self.revoked
            and self.expires_at > datetime.now(tz=UTC)
        )

    def __repr__(self) -> str:
        return (
            f"<RefreshToken id={self.id!s} user_id={self.user_id!s} "
            f"used={self.used} revoked={self.revoked}>"
        )
