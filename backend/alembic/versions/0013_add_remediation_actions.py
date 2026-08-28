"""Add remediation_actions table for Phase 15 AIOps.

Stores AI-generated remediation recommendations and human approval decisions.
No action is executed automatically — human approval is required.

Revision ID: 0013_add_remediation_actions
Revises: 0012_add_rca_fields_to_incidents
Create Date: 2026-08-26 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0013_add_remediation_actions"
down_revision: Union[str, None] = "0012_add_rca_fields_to_incidents"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "remediation_actions",
        sa.Column("id", sa.UUID(), nullable=False),
        sa.Column("incident_id", sa.String(128), nullable=False,
                  comment="UUID of the parent Incident row"),
        sa.Column("title", sa.String(512), nullable=False,
                  comment="Short description of the recommended action"),
        sa.Column("action_type", sa.String(128), nullable=False,
                  comment="Machine-readable action identifier"),
        sa.Column("risk_tier", sa.String(16), nullable=False,
                  comment="LOW | MEDIUM | HIGH"),
        sa.Column("reasoning", sa.Text(), nullable=False, server_default="",
                  comment="Why the AI recommends this action"),
        sa.Column("confidence", sa.Float(), nullable=True,
                  comment="AI confidence 0.0–1.0"),
        sa.Column("rank", sa.Integer(), nullable=False, server_default="1",
                  comment="Priority rank — 1 is highest"),
        sa.Column("params_json", sa.Text(), nullable=False, server_default="{}",
                  comment="JSON-encoded action parameters"),
        sa.Column("status", sa.String(32), nullable=False, server_default="RECOMMENDED",
                  comment="RECOMMENDED | APPROVED | REJECTED | EXECUTING | COMPLETED | FAILED"),
        sa.Column("reviewed_by", sa.String(128), nullable=True),
        sa.Column("rejection_reason", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True),
                  server_default=sa.text("now()"), nullable=False),
        sa.Column("reviewed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("executed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_remediation_incident_id",
                    "remediation_actions", ["incident_id"])
    op.create_index("ix_remediation_action_type",
                    "remediation_actions", ["action_type"])
    op.create_index("ix_remediation_status",
                    "remediation_actions", ["status"])
    op.create_index("ix_remediation_created_at",
                    "remediation_actions", ["created_at"])


def downgrade() -> None:
    op.drop_index("ix_remediation_created_at",  table_name="remediation_actions")
    op.drop_index("ix_remediation_status",       table_name="remediation_actions")
    op.drop_index("ix_remediation_action_type",  table_name="remediation_actions")
    op.drop_index("ix_remediation_incident_id",  table_name="remediation_actions")
    op.drop_table("remediation_actions")
