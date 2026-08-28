"""Add observability_events table for Android event storage.

Stores structured ObservabilityEvent records uploaded from the Android app
every 15 minutes via WorkManager. These events are the data source for
Phase 10 AI Error Analysis.

Revision ID: 0010_add_observability_events
Revises: 0009_add_citation_fields
Create Date: 2026-08-26 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0010_add_observability_events"
down_revision: Union[str, None] = "0009_add_citation_fields"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "observability_events",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column(
            "timestamp_ms",
            sa.BigInteger(),
            nullable=False,
            comment="Epoch milliseconds (UTC) when the event was captured on-device",
        ),
        sa.Column(
            "level",
            sa.String(length=16),
            nullable=False,
            comment="Event severity: DEBUG | INFO | WARN | ERROR | CRITICAL",
        ),
        sa.Column(
            "event_type",
            sa.String(length=128),
            nullable=False,
            comment="Machine-readable event category",
        ),
        sa.Column(
            "message",
            sa.Text(),
            nullable=False,
            comment="Human-readable, PII-stripped event description",
        ),
        sa.Column(
            "session_id",
            sa.String(length=128),
            nullable=False,
            comment="Groups all events within one app session",
        ),
        sa.Column(
            "request_id",
            sa.String(length=128),
            nullable=True,
            comment="Unique per HTTP call — correlates Android log with backend log",
        ),
        sa.Column(
            "trace_id",
            sa.String(length=128),
            nullable=True,
            comment="Groups related events across a single user action flow",
        ),
        sa.Column(
            "screen",
            sa.String(length=256),
            nullable=True,
            comment="Active Compose screen/route at time of event",
        ),
        sa.Column(
            "metadata_json",
            sa.Text(),
            nullable=False,
            server_default="{}",
            comment="JSON-encoded Map<String,String> of event metadata",
        ),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
            comment="UTC timestamp when this event was received by the backend",
        ),
        sa.PrimaryKeyConstraint("id"),
    )

    # Indexes for common query patterns
    op.create_index(
        "ix_obs_evt_level_received",
        "observability_events",
        ["level", "received_at"],
    )
    op.create_index(
        "ix_obs_evt_session_received",
        "observability_events",
        ["session_id", "received_at"],
    )
    op.create_index(
        "ix_obs_evt_request_id",
        "observability_events",
        ["request_id"],
    )
    op.create_index(
        "ix_observability_events_level",
        "observability_events",
        ["level"],
    )
    op.create_index(
        "ix_observability_events_event_type",
        "observability_events",
        ["event_type"],
    )
    op.create_index(
        "ix_observability_events_received_at",
        "observability_events",
        ["received_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_observability_events_received_at", table_name="observability_events")
    op.drop_index("ix_observability_events_event_type", table_name="observability_events")
    op.drop_index("ix_observability_events_level", table_name="observability_events")
    op.drop_index("ix_obs_evt_request_id", table_name="observability_events")
    op.drop_index("ix_obs_evt_session_received", table_name="observability_events")
    op.drop_index("ix_obs_evt_level_received", table_name="observability_events")
    op.drop_table("observability_events")
