"""Add Phase 12 RCA fields to incidents table.

Adds five columns that store the result of a Root Cause Analysis run
linked to a specific incident: rca_analysis_id, rca_summary,
rca_confidence, rca_candidates_json, rca_investigation_steps_json.

Revision ID: 0012_add_rca_fields_to_incidents
Revises: 0011_add_incidents_table
Create Date: 2026-08-26 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0012_add_rca_fields_to_incidents"
down_revision: Union[str, None] = "0011_add_incidents_table"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "incidents",
        sa.Column(
            "rca_analysis_id",
            sa.String(length=128),
            nullable=True,
            comment="UUID of the Phase 12 RCA run for this incident",
        ),
    )
    op.add_column(
        "incidents",
        sa.Column(
            "rca_summary",
            sa.Text(),
            nullable=True,
            comment="One-line RCA summary from chain-of-thought reasoning",
        ),
    )
    op.add_column(
        "incidents",
        sa.Column(
            "rca_confidence",
            sa.Float(),
            nullable=True,
            comment="Overall RCA confidence score 0.0–1.0",
        ),
    )
    op.add_column(
        "incidents",
        sa.Column(
            "rca_candidates_json",
            sa.Text(),
            nullable=True,
            comment="JSON list of ranked root cause candidates with per-candidate confidence",
        ),
    )
    op.add_column(
        "incidents",
        sa.Column(
            "rca_investigation_steps_json",
            sa.Text(),
            nullable=True,
            comment="JSON list of recommended investigation steps from RCA",
        ),
    )


def downgrade() -> None:
    op.drop_column("incidents", "rca_investigation_steps_json")
    op.drop_column("incidents", "rca_candidates_json")
    op.drop_column("incidents", "rca_confidence")
    op.drop_column("incidents", "rca_summary")
    op.drop_column("incidents", "rca_analysis_id")
