"""Add productivity tables — todo_items, calendar_events, reminders, habit_definitions, habit_entries.

Creates all tables required for the productivity suite covering Todos,
Calendar Events, Reminders, and Habits sub-domains.

Revision ID: 0003_productivity_tables
Revises: 0002_add_refresh_tokens
Create Date: 2024-01-03 00:00:00.000000

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0003_productivity_tables"
down_revision: str | None = "0002_add_refresh_tokens"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# ---------------------------------------------------------------------------
# Upgrade: create all productivity tables
# ---------------------------------------------------------------------------


def upgrade() -> None:
    """Create todo_items, calendar_events, reminders, habit_definitions, habit_entries."""

    # -----------------------------------------------------------------------
    # Table: todo_items
    # -----------------------------------------------------------------------
    op.create_table(
        "todo_items",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.String(512), nullable=False),
        sa.Column("description", sa.Text, nullable=False, server_default=""),
        sa.Column(
            "is_completed", sa.Boolean, nullable=False, server_default=sa.false()
        ),
        sa.Column("due_date", sa.DateTime(timezone=True), nullable=True),
        sa.Column("priority", sa.String(16), nullable=False, server_default="medium"),
        sa.Column(
            "tags",
            postgresql.ARRAY(sa.Text),
            nullable=False,
            server_default=sa.text("ARRAY[]::text[]"),
        ),
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
    op.create_index("ix_todo_items_user_id", "todo_items", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: calendar_events
    # -----------------------------------------------------------------------
    op.create_table(
        "calendar_events",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.String(512), nullable=False),
        sa.Column("description", sa.Text, nullable=False, server_default=""),
        sa.Column("start_time", sa.DateTime(timezone=True), nullable=False),
        sa.Column("end_time", sa.DateTime(timezone=True), nullable=False),
        sa.Column("location", sa.String(1024), nullable=True),
        sa.Column("is_all_day", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("source", sa.String(64), nullable=False, server_default="local"),
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
    op.create_index("ix_calendar_events_user_id", "calendar_events", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: reminders
    # Note: FK to todo_items is created after todo_items is created above.
    # -----------------------------------------------------------------------
    op.create_table(
        "reminders",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.String(512), nullable=False),
        sa.Column("trigger_time", sa.DateTime(timezone=True), nullable=False),
        sa.Column("recurrence_rule", sa.String(512), nullable=True),
        sa.Column(
            "linked_todo_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("todo_items.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column(
            "is_completed", sa.Boolean, nullable=False, server_default=sa.false()
        ),
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
    op.create_index("ix_reminders_user_id", "reminders", ["user_id"])
    op.create_index("ix_reminders_trigger_time", "reminders", ["trigger_time"])

    # -----------------------------------------------------------------------
    # Table: habit_definitions
    # -----------------------------------------------------------------------
    op.create_table(
        "habit_definitions",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(512), nullable=False),
        sa.Column("description", sa.Text, nullable=False, server_default=""),
        sa.Column("recurrence", sa.String(16), nullable=False, server_default="daily"),
        sa.Column("target_frequency", sa.Integer, nullable=False, server_default="1"),
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
    op.create_index("ix_habit_definitions_user_id", "habit_definitions", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: habit_entries
    # -----------------------------------------------------------------------
    op.create_table(
        "habit_entries",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "habit_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("habit_definitions.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("note", sa.Text, nullable=True),
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
    op.create_index("ix_habit_entries_habit_id", "habit_entries", ["habit_id"])
    op.create_index("ix_habit_entries_user_id", "habit_entries", ["user_id"])


# ---------------------------------------------------------------------------
# Downgrade: drop all productivity tables in reverse dependency order
# ---------------------------------------------------------------------------


def downgrade() -> None:
    """Drop all productivity tables created by this migration."""

    # Drop tables in reverse FK dependency order
    op.drop_index("ix_habit_entries_user_id", table_name="habit_entries")
    op.drop_index("ix_habit_entries_habit_id", table_name="habit_entries")
    op.drop_table("habit_entries")

    op.drop_index("ix_habit_definitions_user_id", table_name="habit_definitions")
    op.drop_table("habit_definitions")

    op.drop_index("ix_reminders_trigger_time", table_name="reminders")
    op.drop_index("ix_reminders_user_id", table_name="reminders")
    op.drop_table("reminders")

    op.drop_index("ix_calendar_events_user_id", table_name="calendar_events")
    op.drop_table("calendar_events")

    op.drop_index("ix_todo_items_user_id", table_name="todo_items")
    op.drop_table("todo_items")
