"""Celery beat task for periodic anomaly detection — Phase 11.

Runs every 60 seconds via Celery beat. On each execution:
  1. Opens a database session
  2. Runs AnomalyDetectionService.run_detection_cycle()
  3. Stage 1 checks error_rate and error_count thresholds
  4. Stage 2 checks for statistical spikes (mean + 2σ)
  5. Creates Incident rows and triggers Phase 10 analysis for any anomaly found
  6. Logs the summary and closes the session

Pattern mirrors alert_worker.py (spending alerts) — same Celery beat approach.

Phase 11 — Anomaly Detection
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.workers.celery_app import celery_app

logger = logging.getLogger(__name__)


@celery_app.task(  # type: ignore[misc]
    name="app.workers.anomaly_worker.run_anomaly_detection_task",
    bind=True,
    max_retries=0,
    ignore_result=True,
)
def run_anomaly_detection_task(self: Any) -> None:
    """Celery beat task — runs Stage 1 + Stage 2 anomaly detection every 60 seconds.

    Uses ``asyncio.run()`` to execute the async detection service inside this
    synchronous Celery task.  A new event loop is created per invocation to
    avoid shared-state bugs across executions.

    Phase 11 — Anomaly Detection
    """
    try:
        asyncio.run(_run_detection())
    except Exception as exc:
        logger.error(
            "run_anomaly_detection_task: unhandled error: %s",
            exc,
            exc_info=True,
        )


async def _run_detection() -> None:
    """Async inner function — opens a DB session and runs the detection cycle."""
    from app.database import AsyncSessionLocal
    from app.services.anomaly_detection_service import AnomalyDetectionService

    async with AsyncSessionLocal() as db:
        try:
            service = AnomalyDetectionService(db)
            summary = await service.run_detection_cycle()

            if summary.triggered_count > 0:
                logger.info(
                    "anomaly_detection: cycle complete — incidents_created=%d "
                    "dedup_skipped=%d errors=%d",
                    summary.triggered_count,
                    len(summary.skipped_dedup),
                    len(summary.errors),
                )
            else:
                logger.debug(
                    "anomaly_detection: cycle complete — no anomalies detected "
                    "(dedup_skipped=%d)",
                    len(summary.skipped_dedup),
                )

            if summary.errors:
                for err in summary.errors:
                    logger.warning("anomaly_detection: error during cycle — %s", err)

        except Exception as exc:
            await db.rollback()
            logger.error(
                "run_anomaly_detection_task: DB error during detection: %s",
                exc,
                exc_info=True,
            )


# ---------------------------------------------------------------------------
# Register in Celery beat schedule — runs every 60 seconds
# ---------------------------------------------------------------------------

celery_app.conf.beat_schedule = {
    **getattr(celery_app.conf, "beat_schedule", {}),
    "run-anomaly-detection-every-60s": {
        "task": "app.workers.anomaly_worker.run_anomaly_detection_task",
        "schedule": 60.0,  # seconds
        "options": {"queue": "anomaly"},
    },
}

# Route all anomaly worker tasks to the "anomaly" queue
_existing_routes = dict(getattr(celery_app.conf, "task_routes", {}))
_existing_routes["app.workers.anomaly_worker.*"] = {"queue": "anomaly"}
celery_app.conf.task_routes = _existing_routes

# Add this module to Celery's auto-discovery include list
_existing_include = list(getattr(celery_app.conf, "include", []))
if "app.workers.anomaly_worker" not in _existing_include:
    _existing_include.append("app.workers.anomaly_worker")
    celery_app.conf.include = _existing_include
