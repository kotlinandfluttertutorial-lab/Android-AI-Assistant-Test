"""Add refresh_tokens table.

Creates the ``refresh_tokens`` table required for JWT token rotation and
replay detection (Requirement 1.3, 1.4).

Revision ID: 0002_add_refresh_tokens
Revises: 0001_initial_schema
Create Date: 2024-01-02 00:00:00.000000

Requirements: 1.3, 1.4
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0002_add_refresh_tokens"
down_revision: str | None = "0001_initial_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# ---------------------------------------------------------------------------
# Upgrade
# ---------------------------------------------------------------------------


def upgrade() -> None:
    """Create the refresh_tokens table with all columns and constraints."""

    op.create_table(
        "refresh_tokens",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        # ------------------------------------------------------------------
        # Token identity
        # ------------------------------------------------------------------
        sa.Column(
            "token_hash",
            sa.String(64),
            nullable=False,
            unique=True,
            comment="SHA-256 hex-digest of the raw refresh token.  Raw token is never stored.",
        ),
        # ------------------------------------------------------------------
        # Ownership
        # ------------------------------------------------------------------
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        # ------------------------------------------------------------------
        # Rotation chain
        # ------------------------------------------------------------------
        sa.Column(
            "family_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
            comment="Shared UUID for all tokens in the same rotation chain; used for family revocation.",
        ),
        sa.Column(
            "parent_token_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("refresh_tokens.id", ondelete="SET NULL"),
            nullable=True,
            comment="FK to the previous token this one replaced; NULL for the first token in a family.",
        ),
        # ------------------------------------------------------------------
        # Expiry
        # ------------------------------------------------------------------
        sa.Column(
            "expires_at",
            sa.DateTime(timezone=True),
            nullable=False,
            comment="UTC timestamp after which this token is no longer valid.",
        ),
        # ------------------------------------------------------------------
        # State flags
        # ------------------------------------------------------------------
        sa.Column(
            "used",
            sa.Boolean,
            nullable=False,
            server_default=sa.false(),
            comment="True once the token has been exchanged for a new token pair.",
        ),
        sa.Column(
            "revoked",
            sa.Boolean,
            nullable=False,
            server_default=sa.false(),
            comment="True when the token has been permanently invalidated.",
        ),
        # ------------------------------------------------------------------
        # Timestamps
        # ------------------------------------------------------------------
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )

    # ------------------------------------------------------------------
    # Indexes
    # ------------------------------------------------------------------
    # Primary lookup: find a token by its hash (used on every refresh request)
    op.create_index(
        "ix_refresh_tokens_token_hash",
        "refresh_tokens",
        ["token_hash"],
        unique=True,
    )

    # Secondary lookups: active tokens by user, and family revocation
    op.create_index(
        "ix_refresh_tokens_user_id",
        "refresh_tokens",
        ["user_id"],
    )
    op.create_index(
        "ix_refresh_tokens_user_revoked",
        "refresh_tokens",
        ["user_id", "revoked"],
    )
    op.create_index(
        "ix_refresh_tokens_family_id",
        "refresh_tokens",
        ["family_id"],
    )
    op.create_index(
        "ix_refresh_tokens_expires_at",
        "refresh_tokens",
        ["expires_at"],
    )


# ---------------------------------------------------------------------------
# Downgrade
# ---------------------------------------------------------------------------


def downgrade() -> None:
    """Drop the refresh_tokens table and all associated indexes."""

    op.drop_index("ix_refresh_tokens_expires_at", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_family_id", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_user_revoked", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_user_id", table_name="refresh_tokens")
    op.drop_index("ix_refresh_tokens_token_hash", table_name="refresh_tokens")
    op.drop_table("refresh_tokens")
