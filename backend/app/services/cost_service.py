# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : cost_service.py
# Purpose : AI Cost Dashboard — aggregation, alert CRUD, and alert monitor
#
# Architecture Layer : Service
# Pattern Used       : Async Service with background Celery monitor
#
# Key Concepts:
#   - Aggregates TokenUsage records by feature, provider, and calendar day
#   - Enforces per-user spending alert limits (max 3 per user)
#   - Alert monitor runs every 60 s via Celery beat; sends in-app notification
#
# Dependencies:
#   - app.models.token_usage, app.models.spending_alert
#   - app.repositories.token_usage_repository
#   - SQLAlchemy 2.x async ORM
#
# Requirements: 34.1, 34.2, 34.4, 34.7, 34.8
# ============================================================

"""AI Cost Dashboard service.

Provides:
- ``get_user_cost_summary``  — aggregated 90-day usage for the Cost Dashboard
- ``create_spending_alert``  — POST /usage/alerts (max 3 per user, $0.01–$999.99)
- ``delete_spending_alert``  — DELETE /usage/alerts/{id}
- ``check_spending_alerts``  — alert monitor invoked by Celery beat every 60 s

Requirements: 34.1, 34.2, 34.4, 34.7, 34.8
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.spending_alert import SpendingAlert
from app.models.token_usage import TokenUsage, UsageFeature

logger = logging.getLogger(__name__)

# Maximum spending alerts per user (Requirement 34.4)
_MAX_ALERTS_PER_USER = 3

# Retention window for cost aggregation: 90 days (Requirement 34.1)
_COST_WINDOW_DAYS = 90

# Minimum and maximum threshold amounts (Requirement 34.4)
_THRESHOLD_MIN = Decimal("0.01")
_THRESHOLD_MAX = Decimal("999.99")


# ---------------------------------------------------------------------------
# Data-transfer objects (plain dataclasses — no SQLAlchemy dependency in API)
# ---------------------------------------------------------------------------


class DailyCostRow:
    """Aggregated cost for one (feature, provider, day) combination."""

    __slots__ = (
        "cost_usd",
        "day",
        "feature",
        "input_tokens",
        "output_tokens",
        "provider",
    )

    def __init__(
        self,
        feature: str,
        provider: str,
        day: str,
        input_tokens: int,
        output_tokens: int,
        cost_usd: float,
    ) -> None:
        self.feature = feature
        self.provider = provider
        self.day = day  # ISO-8601 date string e.g. "2025-01-15"
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens
        self.cost_usd = cost_usd


class CostSummary:
    """Top-level cost summary for the Cost Dashboard endpoint."""

    __slots__ = ("rows", "total_cost_usd", "total_input_tokens", "total_output_tokens")

    def __init__(
        self,
        total_input_tokens: int,
        total_output_tokens: int,
        total_cost_usd: float,
        rows: list[DailyCostRow],
    ) -> None:
        self.total_input_tokens = total_input_tokens
        self.total_output_tokens = total_output_tokens
        self.total_cost_usd = total_cost_usd
        self.rows = rows


class SpendingAlertCreate:
    """Input DTO for creating a spending alert."""

    __slots__ = ("threshold_usd",)

    def __init__(self, threshold_usd: Decimal) -> None:
        self.threshold_usd = threshold_usd


class SpendingAlertDto:
    """Output DTO for a spending alert."""

    __slots__ = (
        "created_at",
        "dismissed_at",
        "id",
        "is_triggered",
        "threshold_usd",
        "triggered_at",
        "user_id",
    )

    def __init__(self, alert: SpendingAlert) -> None:
        self.id = alert.id
        self.user_id = alert.user_id
        self.threshold_usd = float(alert.threshold_usd)
        self.is_triggered = alert.is_triggered
        self.triggered_at = alert.triggered_at
        self.dismissed_at = alert.dismissed_at
        self.created_at = alert.created_at


# ---------------------------------------------------------------------------
# Cost aggregation
# ---------------------------------------------------------------------------


async def get_user_cost_summary(
    db: AsyncSession,
    user_id: uuid.UUID,
    days: int = _COST_WINDOW_DAYS,
) -> CostSummary:
    """Return aggregated token usage and estimated cost for *user_id*.

    Aggregation is broken down by:
    - ``feature``  (chat / rag / code / voice / comparison / suggestions)
    - ``provider`` (openai / anthropic / gemini / …)
    - Calendar day (UTC, ISO-8601 date string)

    Only records from the last *days* days are included (default 90).

    This query is intentionally scoped to ``user_id`` so it can NEVER return
    another user's data (Requirement 34.7).

    Args:
        db:      Async SQLAlchemy session.
        user_id: UUID of the authenticated user whose data is requested.
        days:    Lookback window in days; defaults to 90.

    Returns:
        :class:`CostSummary` with totals and per-(feature, provider, day) rows.

    Requirements: 34.1, 34.2, 34.7
    """
    # Compute the cutoff timestamp in UTC
    from datetime import timedelta

    from sqlalchemy import Date, cast

    cutoff = datetime.now(tz=datetime.UTC) - timedelta(days=days)

    # Aggregate by feature, provider, and calendar day
    stmt = (
        select(
            TokenUsage.feature,
            TokenUsage.provider,
            cast(TokenUsage.created_at, Date).label("day"),
            func.sum(TokenUsage.input_tokens).label("sum_input"),
            func.sum(TokenUsage.output_tokens).label("sum_output"),
            func.sum(TokenUsage.cost_usd).label("sum_cost"),
        )
        .where(
            and_(
                TokenUsage.user_id == user_id,  # strict per-user scoping
                TokenUsage.created_at >= cutoff,
            )
        )
        .group_by(
            TokenUsage.feature, TokenUsage.provider, cast(TokenUsage.created_at, Date)
        )
        .order_by(cast(TokenUsage.created_at, Date).desc())
    )

    result = await db.execute(stmt)
    rows_raw = result.all()

    rows: list[DailyCostRow] = []
    total_input = 0
    total_output = 0
    total_cost = 0.0

    for row in rows_raw:
        feature_val = (
            row.feature.value
            if isinstance(row.feature, UsageFeature)
            else str(row.feature)
        )
        input_t = int(row.sum_input or 0)
        output_t = int(row.sum_output or 0)
        cost_v = float(row.sum_cost or 0)
        day_str = row.day.isoformat() if hasattr(row.day, "isoformat") else str(row.day)
        rows.append(
            DailyCostRow(
                feature=feature_val,
                provider=str(row.provider),
                day=day_str,
                input_tokens=input_t,
                output_tokens=output_t,
                cost_usd=cost_v,
            )
        )
        total_input += input_t
        total_output += output_t
        total_cost += cost_v

    return CostSummary(
        total_input_tokens=total_input,
        total_output_tokens=total_output,
        total_cost_usd=round(total_cost, 6),
        rows=rows,
    )


# ---------------------------------------------------------------------------
# Spending alert CRUD
# ---------------------------------------------------------------------------


async def list_spending_alerts(
    db: AsyncSession,
    user_id: uuid.UUID,
) -> list[SpendingAlertDto]:
    """Return all spending alerts owned by *user_id*.

    Requirements: 34.4
    """
    result = await db.execute(
        select(SpendingAlert)
        .where(SpendingAlert.user_id == user_id)
        .order_by(SpendingAlert.created_at.asc())
    )
    return [SpendingAlertDto(a) for a in result.scalars().all()]


async def create_spending_alert(
    db: AsyncSession,
    user_id: uuid.UUID,
    threshold_usd: Decimal,
) -> SpendingAlertDto:
    """Create a new spending alert for *user_id*.

    Validates:
    - ``threshold_usd`` is in [$0.01, $999.99].
    - User does not already have 3 alerts (HTTP 422 on 4th attempt).

    Args:
        db:            Async SQLAlchemy session.
        user_id:       UUID of the authenticated user.
        threshold_usd: Threshold amount in USD.

    Returns:
        :class:`SpendingAlertDto` for the newly created alert.

    Raises:
        :exc:`ValueError` with a structured message on validation failure.

    Requirements: 34.4
    """
    # Validate threshold range
    if threshold_usd < _THRESHOLD_MIN or threshold_usd > _THRESHOLD_MAX:
        raise ValueError(
            f"threshold_usd must be between {_THRESHOLD_MIN} and {_THRESHOLD_MAX}; "
            f"got {threshold_usd}"
        )

    # Enforce per-user limit
    count_result = await db.execute(
        select(func.count(SpendingAlert.id)).where(SpendingAlert.user_id == user_id)
    )
    current_count = count_result.scalar_one()
    if current_count >= _MAX_ALERTS_PER_USER:
        raise ValueError(
            f"Maximum of {_MAX_ALERTS_PER_USER} spending alerts allowed per user; "
            f"user {user_id} already has {current_count}. Delete an existing alert first."
        )

    alert = SpendingAlert(
        user_id=user_id,
        threshold_usd=threshold_usd,
        is_triggered=False,
    )
    db.add(alert)
    await db.flush()
    return SpendingAlertDto(alert)


async def delete_spending_alert(
    db: AsyncSession,
    user_id: uuid.UUID,
    alert_id: uuid.UUID,
) -> bool:
    """Delete the spending alert identified by *alert_id*.

    Returns ``True`` if deleted, ``False`` if not found.

    The caller must ensure *user_id* matches the alert's owner to prevent
    cross-user deletion.

    Requirements: 34.4
    """
    result = await db.execute(
        select(SpendingAlert).where(
            and_(
                SpendingAlert.id == alert_id,
                SpendingAlert.user_id == user_id,  # enforce ownership
            )
        )
    )
    alert = result.scalar_one_or_none()
    if alert is None:
        return False
    await db.delete(alert)
    await db.flush()
    return True


# ---------------------------------------------------------------------------
# Alert monitor (called by Celery beat task every 60 s)
# ---------------------------------------------------------------------------


async def check_spending_alerts(db: AsyncSession) -> None:
    """Check all un-triggered spending alerts and fire notifications if thresholds crossed.

    This function is intended to be invoked from the Celery beat task
    ``check_spending_alerts_task`` every 60 seconds.

    Algorithm:
    1. Load all non-triggered, non-dismissed alerts.
    2. For each alert, sum today's cost_usd for that user.
    3. If accumulated daily cost >= threshold, mark as triggered and enqueue notification.

    Requirements: 34.8
    """
    from datetime import date

    today_start = datetime.combine(date.today(), datetime.min.time()).replace(
        tzinfo=datetime.UTC
    )

    # Load all un-triggered alerts (dismissed_at is NULL or not set)
    alerts_result = await db.execute(
        select(SpendingAlert).where(
            and_(
                SpendingAlert.is_triggered.is_(False),
                SpendingAlert.dismissed_at.is_(None),
            )
        )
    )
    alerts = list(alerts_result.scalars().all())

    if not alerts:
        return

    # Batch-fetch today's cost per user to avoid N+1 queries
    user_ids = list({alert.user_id for alert in alerts})
    cost_result = await db.execute(
        select(
            TokenUsage.user_id,
            func.sum(TokenUsage.cost_usd).label("daily_cost"),
        )
        .where(
            and_(
                TokenUsage.user_id.in_(user_ids),
                TokenUsage.created_at >= today_start,
            )
        )
        .group_by(TokenUsage.user_id)
    )
    daily_cost_by_user: dict[uuid.UUID, float] = {
        row.user_id: float(row.daily_cost or 0) for row in cost_result.all()
    }

    now = datetime.now(tz=datetime.UTC)
    triggered_user_ids: list[
        tuple[uuid.UUID, float, float]
    ] = []  # (user_id, threshold, cost)

    for alert in alerts:
        daily_cost = daily_cost_by_user.get(alert.user_id, 0.0)
        if daily_cost >= float(alert.threshold_usd):
            alert.is_triggered = True
            alert.triggered_at = now
            triggered_user_ids.append(
                (alert.user_id, float(alert.threshold_usd), daily_cost)
            )
            logger.info(
                "SpendingAlert triggered: user=%s threshold=%.2f daily_cost=%.6f",
                alert.user_id,
                float(alert.threshold_usd),
                daily_cost,
            )

    if triggered_user_ids:
        await db.flush()
        # Enqueue push notifications asynchronously (best-effort)
        _enqueue_alert_notifications(triggered_user_ids)


def _enqueue_alert_notifications(
    triggered: list[tuple[uuid.UUID, float, float]],
) -> None:
    """Enqueue FCM push notifications for each triggered spending alert.

    Best-effort: failures are logged but never propagated.

    Args:
        triggered: List of (user_id, threshold_usd, current_cost_usd) tuples.
    """
    try:
        from app.workers.notification_worker import (
            send_push_notification,
        )

        for user_id, threshold, cost in triggered:
            send_push_notification.delay(
                user_id=str(user_id),
                title="Spending Alert",
                body=(
                    f"Your daily AI cost (${cost:.2f}) has reached "
                    f"your ${threshold:.2f} threshold."
                ),
                data={
                    "event": "spending_alert_triggered",
                    "threshold_usd": f"{threshold:.2f}",
                    "current_cost_usd": f"{cost:.6f}",
                },
            )
    except Exception as exc:
        logger.warning("_enqueue_alert_notifications: failed to enqueue: %s", exc)
