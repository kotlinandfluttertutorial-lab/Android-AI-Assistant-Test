# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : spending_alert.py
# Purpose : SpendingAlert ORM model — per-user cost threshold alerts
#
# Architecture Layer : ORM Model
# Pattern Used       : SQLAlchemy 2.x Declarative ORM
#
# Key Concepts:
#   - Each user may have at most 3 active SpendingAlert rows (enforced in service layer)
#   - threshold_usd uses NUMERIC(8,2) matching the $0.01–$999.99 range requirement
#   - is_triggered tracks whether an in-app notification has been sent for this alert
#   - triggered_at records when the threshold was first crossed (for banner display)
#   - dismissed_at records when the user explicitly dismissed the persistent banner
#
# Dependencies:
#   - SQLAlchemy 2.x, app.models.base, app.models.user
# ============================================================

"""ORM model for the ``spending_alerts`` table.

Stores per-user spending threshold alerts.  When the alert monitor detects
that a user's accumulated daily cost has crossed an alert's ``threshold_usd``,
it marks ``is_triggered = True``, records ``triggered_at``, and enqueues an
in-app notification.  The alert persists (and the banner remains) until the
user explicitly calls DELETE or dismisses it.

Requirements: 34.4, 34.6, 34.8
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import Boolean, DateTime, ForeignKey, Numeric
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk


class SpendingAlert(Base, TimestampMixin):
    """SQLAlchemy ORM model for a user's spending alert threshold."""

    __tablename__ = "spending_alerts"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    threshold_usd: Mapped[Decimal] = mapped_column(
        Numeric(8, 2),
        nullable=False,
        comment="Spending threshold in USD; valid range $0.01–$999.99",
    )
    is_triggered: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="True after the alert monitor detects the threshold has been crossed",
    )
    triggered_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when the threshold was first crossed",
    )
    dismissed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when the user explicitly dismissed the banner",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship(
        "User", back_populates="spending_alerts"
    )  # noqa: F821

    def __repr__(self) -> str:
        return (
            f"<SpendingAlert id={self.id!s} user={self.user_id!s} "
            f"threshold={self.threshold_usd} triggered={self.is_triggered}>"
        )
