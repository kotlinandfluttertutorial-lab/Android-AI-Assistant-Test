"""Add privacy_mode column to users table.

The ``privacy_mode`` boolean flag controls whether memory capture is active
for a user.  When ``True``, new memories are not stored.  Existing memories
are never deleted by toggling this flag.

Revision ID: 0006_add_privacy_mode_to_users
Revises: 0005_prompt_templates_versioning
Create Date: 2024-01-06 00:00:00.000000

Requirements: 7.6
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0006_add_privacy_mode_to_users"
down_revision: str | None = "0005_prompt_templates_versioning"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Add privacy_mode column to users table."""
    op.add_column(
        "users",
        sa.Column(
            "privacy_mode",
            sa.Boolean,
            nullable=False,
            server_default=sa.false(),
            comment=(
                "When True, memory capture is disabled. "
                "Existing memories are NOT deleted. Requirement 7.6"
            ),
        ),
    )


def downgrade() -> None:
    """Remove privacy_mode column from users table."""
    op.drop_column("users", "privacy_mode")
