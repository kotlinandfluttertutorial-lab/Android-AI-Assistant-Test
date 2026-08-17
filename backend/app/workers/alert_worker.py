# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : workers
# File    : alert_worker.py
# Purpose : Celery beat task — spending alert monitor (every 60 s)
#
# Architecture Layer : Celery Worker
# Pattern Used       : Celery periodic task (beat schedule)
#
# Key Concepts:
#   - Runs every 60 seconds via Celery beat (Requirement 34.8)
#   - Calls cost_service.check_spending_alerts to detect crossed thresholds
#   - Sends in-app notification via notification_worker when threshold crossed
#   - Failures are caught and logged; the beat schedule continues regardless
#
# Dependencies:
#   - app.workers.celery_app (celery_app)
#   - app.services.cost_service (check_spending_alerts)
#   - app.database (AsyncSession factory)
# ============================================================

"""Celery beat task for the spending alert monitor.

The ``check_spending_alerts_task`` task runs every 60 seconds.  It loads all
un-triggered spending alerts and fires an in-app notification for each user
whose accumulated daily cost has reached or exceeded their alert threshold.

Registration
------------
This module is included in ``celery_app.conf.include`` so tasks are
auto-discovered.  The beat schedule entry below is added to
``celery_app.conf.beat_schedule`` when this module is imported.

Requirements: 34.8
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.workers.celery_app import celery_app

logger = logging.getLogger(__name__)


@celery_app.task(  # type: ignore[misc]
    name="app.workers.alert_worker.check_spending_alerts_task",
    bind=True,
    max_retries=0,
    ignore_result=True,
)
def check_spending_alerts_task(self: Any) -> None:
    """Celery beat task that checks all spending alerts every 60 seconds.

    Algorithm (delegated to :func:`app.services.cost_service.check_spending_alerts`):
    1. Load all non-triggered, non-dismissed spending alerts.
    2. For each alert, sum today's accumulated cost for the owning user.
    3. If accumulated cost >= threshold, mark alert as triggered and enqueue
       an in-app push notification.

    This task uses ``asyncio.run()`` to execute the async service function
    inside a synchronous Celery task.  A new event loop is created for each
    invocation so there is no shared state between executions.

    Requirements: 34.8
    """
    try:
        asyncio.run(_run_alert_check())
    except Exception as exc:
        logger.error(
            "check_spending_alerts_task: unhandled error: %s",
            exc,
            exc_info=True,
        )


async def _run_alert_check() -> None:
    """Async inner function that obtains a DB session and invokes the alert checker."""
    from app.database import AsyncSessionLocal
    from app.services.cost_service import check_spending_alerts

    async with AsyncSessionLocal() as db:
        try:
            await check_spending_alerts(db=db)
            await db.commit()
            logger.debug("check_spending_alerts_task: completed successfully")
        except Exception as exc:
            await db.rollback()
            logger.error(
                "check_spending_alerts_task: DB error during alert check: %s",
                exc,
                exc_info=True,
            )


# ---------------------------------------------------------------------------
# Register in Celery beat schedule — runs every 60 seconds
# ---------------------------------------------------------------------------

celery_app.conf.beat_schedule = {
    **getattr(celery_app.conf, "beat_schedule", {}),
    "check-spending-alerts-every-60s": {
        "task": "app.workers.alert_worker.check_spending_alerts_task",
        "schedule": 60.0,  # seconds
        "options": {"queue": "alerts"},
    },
}

# Ensure the "alerts" queue is routed
_existing_routes = dict(getattr(celery_app.conf, "task_routes", {}))
_existing_routes["app.workers.alert_worker.*"] = {"queue": "alerts"}
celery_app.conf.task_routes = _existing_routes

# Auto-discovery: add this module to Celery's include list
_existing_include = list(getattr(celery_app.conf, "include", []))
if "app.workers.alert_worker" not in _existing_include:
    _existing_include.append("app.workers.alert_worker")
    celery_app.conf.include = _existing_include
