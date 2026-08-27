"""Add incidents table for Phase 11 Anomaly Detection.

Incidents are created automatically by the anomaly detection pipeline
when error rate, latency, or event-count thresholds are breached.

Revision ID: 0011_add_incidents_table
Revises: 0010_add_observability_events
Create Date: 2026-08-26 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0011_add_incidents_table"
down_revision: Union[str, None] = "0010_add_observability_events"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "incidents",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("title", sa.String(length=512), nullable=False,
                  comment="Short human-readable description of the anomaly"),
        sa.Column("severity", sa.String(length=16), nullable=False,
                  comment="CRITICAL | HIGH | MEDIUM | LOW"),
        sa.Column("status", sa.String(length=32), nullable=False,
                  server_default="OPEN",
                  comment="OPEN | INVESTIGATING | RESOLVED | DISMISSED"),
        sa.Column("detection_method", sa.String(length=32), nullable=False,
                  server_default="rule_based",
                  comment="rule_based | statistical | ml | manual"),
        sa.Column("triggered_by", sa.String(length=128), nullable=False,
                  server_default="",
                  comment="Name of the rule or check that triggered this incident"),
        sa.Column("metric_value", sa.Float(), nullable=True,
                  comment="Observed metric value at detection time"),
        sa.Column("threshold_value", sa.Float(), nullable=True,
                  comment="Threshold value that was exceeded"),
        sa.Column("analysis_id", sa.String(length=128), nullable=True,
                  comment="UUID of the Phase 10 ErrorAnalysisResponse"),
        sa.Column("ai_summary", sa.Text(), nullable=True,
                  comment="AI-generated summary from error analysis"),
        sa.Column("ai_confidence", sa.Float(), nullable=True,
                  comment="AI confidence score 0.0–1.0"),
        sa.Column("ai_recommended_fix", sa.Text(), nullable=True,
                  comment="AI-generated recommended fix"),
        sa.Column("event_count", sa.Integer(), nullable=False,
                  server_default="0",
                  comment="Number of error events that contributed"),
        sa.Column("window_minutes", sa.Integer(), nullable=False,
                  server_default="5",
                  comment="Time window in minutes used for detection"),
        sa.Column("detected_at", sa.DateTime(timezone=True),
                  server_default=sa.text("now()"), nullable=False,
                  comment="UTC timestamp when the anomaly was detected"),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True,
                  comment="UTC timestamp when the incident was resolved"),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_index("ix_incidents_severity",    "incidents", ["severity"])
    op.create_index("ix_incidents_status",      "incidents", ["status"])
    op.create_index("ix_incidents_detected_at", "incidents", ["detected_at"])


def downgrade() -> None:
    op.drop_index("ix_incidents_detected_at", table_name="incidents")
    op.drop_index("ix_incidents_status",      table_name="incidents")
    op.drop_index("ix_incidents_severity",    table_name="incidents")
    op.drop_table("incidents")
