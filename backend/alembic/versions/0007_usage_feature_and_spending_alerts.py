"""Add usage_feature enum + feature column to token_usage; create spending_alerts table.

This migration:
1. Creates the ``usage_feature`` PostgreSQL enum type
   (chat | rag | code | voice | comparison | suggestions).
2. Adds the ``feature`` column to the ``token_usage`` table with a server
   default of ``'chat'`` so existing rows are back-filled without a table
   rewrite.
3. Creates the ``spending_alerts`` table for per-user cost threshold alerts
   (max 3 per user, enforced at the service layer).

The ``token_usage.feature`` column enables the Cost Dashboard to break down
accumulated cost by AI feature in addition to LLM provider and calendar day
(Requirement 34.1).

The ``spending_alerts`` table supports ``POST /usage/alerts`` and
``DELETE /usage/alerts/{id}`` endpoints and the background alert monitor
Celery beat task (Requirements 34.4, 34.8).

Retention: token_usage rows must be retained for ≥ 90 days (Requirement 34.1).
The ``created_at`` index on ``token_usage`` was already created in migration
0001 to support the 90-day window query efficiently.

Revision ID: 0007_usage_feature_alerts
Revises: 0006_add_privacy_mode_to_users
Create Date: 2024-01-07 00:00:00.000000

Requirements: 34.1, 34.2, 34.4, 34.7, 34.8
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0007_usage_feature_alerts"
down_revision: str | None = "0006_add_privacy_mode_to_users"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Apply schema changes for usage feature tracking and spending alerts."""

    # -----------------------------------------------------------------------
    # 1. Create the usage_feature enum type
    # -----------------------------------------------------------------------
    usage_feature_enum = postgresql.ENUM(
        "chat",
        "rag",
        "code",
        "voice",
        "comparison",
        "suggestions",
        name="usage_feature",
        create_type=False,
    )
    usage_feature_enum.create(op.get_bind(), checkfirst=True)

    # -----------------------------------------------------------------------
    # 2. Add the feature column to token_usage
    #    server_default='chat' back-fills all existing rows without a
    #    full-table rewrite (PostgreSQL fills missing values lazily).
    # -----------------------------------------------------------------------
    op.add_column(
        "token_usage",
        sa.Column(
            "feature",
            postgresql.ENUM(
                "chat",
                "rag",
                "code",
                "voice",
                "comparison",
                "suggestions",
                name="usage_feature",
                create_type=False,  # already created above
            ),
            nullable=False,
            server_default="chat",
            comment=(
                "AI feature that generated this usage record: "
                "chat | rag | code | voice | comparison | suggestions. "
                "Defaults to 'chat' for rows created before this migration. "
                "Requirements: 34.1"
            ),
        ),
    )

    # Add an index on feature to support GROUP BY queries in cost aggregation
    op.create_index("ix_token_usage_feature", "token_usage", ["feature"])

    # -----------------------------------------------------------------------
    # 3. Create the spending_alerts table
    #
    #    Constraints enforced at the service layer (not DB-level):
    #    - At most 3 active alerts per user (HTTP 422 on 4th).
    #    - threshold_usd in [$0.01, $999.99].
    # -----------------------------------------------------------------------
    op.create_table(
        "spending_alerts",
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
        sa.Column(
            "threshold_usd",
            sa.Numeric(8, 2),
            nullable=False,
            comment="Spending threshold in USD; valid range $0.01–$999.99",
        ),
        sa.Column(
            "is_triggered",
            sa.Boolean,
            nullable=False,
            server_default=sa.false(),
            comment="True after the alert monitor detects the threshold has been crossed",
        ),
        sa.Column(
            "triggered_at",
            sa.DateTime(timezone=True),
            nullable=True,
            comment="UTC timestamp when the threshold was first crossed",
        ),
        sa.Column(
            "dismissed_at",
            sa.DateTime(timezone=True),
            nullable=True,
            comment="UTC timestamp when the user explicitly dismissed the banner",
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
    op.create_index("ix_spending_alerts_user_id", "spending_alerts", ["user_id"])
    # Partial index: quickly find non-triggered, non-dismissed alerts for the
    # Celery beat alert monitor (Requirements 34.8).
    op.create_index(
        "ix_spending_alerts_active",
        "spending_alerts",
        ["user_id"],
        postgresql_where=sa.text("is_triggered = false AND dismissed_at IS NULL"),
    )


def downgrade() -> None:
    """Revert schema changes for usage feature tracking and spending alerts."""

    # Drop spending_alerts indexes and table
    op.drop_index("ix_spending_alerts_active", table_name="spending_alerts")
    op.drop_index("ix_spending_alerts_user_id", table_name="spending_alerts")
    op.drop_table("spending_alerts")

    # Drop token_usage.feature column and its index
    op.drop_index("ix_token_usage_feature", table_name="token_usage")
    op.drop_column("token_usage", "feature")

    # Drop the enum type last (after all columns referencing it are gone)
    op.execute("DROP TYPE IF EXISTS usage_feature")
