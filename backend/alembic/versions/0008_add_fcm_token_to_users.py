"""Add fcm_token column to users table.

The ``fcm_token`` column stores the Firebase Cloud Messaging device token
for push notification delivery. It is nullable since not all users have
notifications enabled.

Revision ID: 0008_add_fcm_token_to_users
Revises: 0007_spending_alerts
Create Date: 2026-08-14 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------
revision: str = "0008_add_fcm_token_to_users"
down_revision: Union[str, None] = "0007_spending_alerts"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "users",
        sa.Column(
            "fcm_token",
            sa.String(length=512),
            nullable=True,
            comment="Firebase Cloud Messaging device token; rotated by the mobile client",
        ),
    )


def downgrade() -> None:
    op.drop_column("users", "fcm_token")
