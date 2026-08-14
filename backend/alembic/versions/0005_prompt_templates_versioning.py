"""Fix prompt_templates unique constraint for multi-version support.

The initial schema (0001) created a ``uq_prompt_templates_name`` unique
constraint on the ``name`` column alone, which prevents storing multiple
version rows for the same logical template name.

This migration:
1. Drops the single-column unique constraint and index on ``name``.
2. Creates a composite unique constraint on ``(name, version)`` so that each
   ``(name, version)`` pair is unique while allowing multiple rows per name.
3. Recreates the index on ``name`` as a non-unique index (for fast look-ups).

Revision ID: 0005_prompt_templates_versioning
Revises: 0004_add_data_export_support
Create Date: 2024-01-05 00:00:00.000000

Requirements: 25.1, 25.2
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0005_prompt_templates_versioning"
down_revision: str | None = "0004_add_data_export_support"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Replace single-column unique constraint with (name, version) composite."""

    # 1. Drop the old unique index on name alone.
    #    The initial migration created both a UniqueConstraint named
    #    ``uq_prompt_templates_name`` and an index ``ix_prompt_templates_name``
    #    with unique=True.  Drop both.
    op.drop_constraint(
        "uq_prompt_templates_name",
        "prompt_templates",
        type_="unique",
    )
    op.drop_index("ix_prompt_templates_name", table_name="prompt_templates")

    # 2. Create a composite unique constraint on (name, version).
    op.create_unique_constraint(
        "uq_prompt_templates_name_version",
        "prompt_templates",
        ["name", "version"],
    )

    # 3. Recreate a non-unique index on name for efficient active-version look-ups.
    op.create_index(
        "ix_prompt_templates_name",
        "prompt_templates",
        ["name"],
        unique=False,
    )


def downgrade() -> None:
    """Restore the original single-column unique constraint on name."""

    # Remove the composite constraint and non-unique index.
    op.drop_constraint(
        "uq_prompt_templates_name_version",
        "prompt_templates",
        type_="unique",
    )
    op.drop_index("ix_prompt_templates_name", table_name="prompt_templates")

    # Recreate the original unique index and constraint on name alone.
    op.create_index(
        "ix_prompt_templates_name",
        "prompt_templates",
        ["name"],
        unique=True,
    )
    op.create_unique_constraint(
        "uq_prompt_templates_name",
        "prompt_templates",
        ["name"],
    )
