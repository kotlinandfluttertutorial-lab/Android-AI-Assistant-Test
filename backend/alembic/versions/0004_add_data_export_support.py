"""Document data-export job_type support.

No schema changes are required: the ``job_type`` column on the ``jobs`` table
is a free-form ``VARCHAR(64)`` column, so the new ``"data_export"`` job type
value is supported without any DDL.

This migration exists to keep the revision chain intact and to document the
intent of the change.

Revision ID: 0004_add_data_export_support
Revises: 0003_productivity_tables
Create Date: 2024-01-04 00:00:00.000000

Requirements: 28.1, 28.2
"""

from __future__ import annotations

from collections.abc import Sequence

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0004_add_data_export_support"
down_revision: str | None = "0003_productivity_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """No DDL changes needed.

    The ``job_type`` column is a free-form string — the ``"data_export"``
    value is accepted by the existing schema without modification.
    """


def downgrade() -> None:
    """Nothing to revert."""
