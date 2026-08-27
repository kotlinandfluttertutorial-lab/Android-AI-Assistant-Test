"""Anomaly Detection Service — Phase 11.

Implements three detection stages:

  Stage 1 — Rule-Based Detection
    Simple threshold comparisons run every 60 seconds via Celery beat.
    Fast, predictable, zero false negatives for extreme conditions.

    Rules:
      error_rate   > 5%  over 5 min  → incident (HIGH)
      error_count  > 50  over 5 min  → incident (HIGH)
      error_spike  statistical outlier → incident (MEDIUM) [see Stage 2]

  Stage 2 — Statistical Detection
    Rolling mean + standard deviation over a configurable window.
    Alert when current count > mean + N * std_dev.
    Adapts to traffic patterns — fewer false positives than static rules.

  Stage 3 — ML-Based Detection (placeholder for later phases)
    Time-series models (Prophet, Isolation Forest).
    Learns seasonal patterns automatically.
    Not implemented — documented as upgrade path.

When a Stage 1 or Stage 2 anomaly is detected:
  1. Create an Incident record (status: OPEN)
  2. Trigger Phase 10 ErrorAnalysisService to provide root cause analysis
  3. Attach analysis results to the incident

Design:
  - Idempotent: ``recent_trigger_exists()`` prevents duplicate incidents for
    the same rule within a configurable window (default 5 minutes).
  - Non-blocking: the analysis call is awaited but errors are caught silently
    so a failed LLM call never prevents the incident from being created.
  - AI safety: incident creation is independent of AI analysis. If the LLM
    is unavailable, the incident still exists and the human can investigate.

Phase 11 — Anomaly Detection
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.repositories.incident_repository import IncidentRepository
from app.repositories.observability_event_repository import ObservabilityEventRepository

logger = logging.getLogger(__name__)

# ── Stage 1 thresholds (match Prometheus alerting rules in alerting.rules.yml) ──
# These are intentionally consistent with the infra-level Prometheus rules
# so both systems fire at the same conditions.

ERROR_RATE_THRESHOLD   = 0.05   # 5% of events in window are ERROR/CRITICAL
ERROR_COUNT_THRESHOLD  = 50     # absolute count — fires even at low traffic
DETECTION_WINDOW_MIN   = 5      # minutes for Stage 1 checks
DEDUP_WINDOW_MIN       = 5      # minutes before creating a duplicate incident

# Stage 2 constants
STAT_WINDOW_MIN        = 60     # historical window for computing baseline
STAT_BUCKET_MIN        = 5      # bucket size for rolling stats
STAT_STD_MULTIPLIER    = 2.0    # alert at mean + N * std_dev


@dataclass
class DetectionResult:
    """Result of a single anomaly detection check."""

    rule_name: str
    triggered: bool
    severity: str = "LOW"
    metric_value: float = 0.0
    threshold_value: float = 0.0
    title: str = ""
    event_count: int = 0
    detail: str = ""


@dataclass
class DetectionSummary:
    """Summary of one full detection cycle."""

    triggered_count: int = 0
    incident_ids: list[str] = field(default_factory=list)
    skipped_dedup: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


class AnomalyDetectionService:
    """Orchestrates all anomaly detection stages for one evaluation cycle.

    Usage (called by the Celery beat worker every 60 seconds)::

        service = AnomalyDetectionService(db)
        summary = await service.run_detection_cycle()
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._obs_repo = ObservabilityEventRepository(db)
        self._inc_repo = IncidentRepository(db)

    # ── Public entry point ────────────────────────────────────────────────────

    async def run_detection_cycle(self) -> DetectionSummary:
        """Run Stage 1 + Stage 2 detection and create incidents for any anomaly found.

        Returns a summary of what fired, what was skipped (dedup), and any errors.
        """
        summary = DetectionSummary()

        # ── Stage 1: Rule-based checks ────────────────────────────────────────
        stage1_results = await self._run_stage1()

        # ── Stage 2: Statistical checks ───────────────────────────────────────
        stage2_results = await self._run_stage2()

        # ── Process all results ───────────────────────────────────────────────
        for result in stage1_results + stage2_results:
            if not result.triggered:
                continue

            # Dedup: skip if a recent open incident for this rule already exists
            if await self._inc_repo.recent_trigger_exists(
                triggered_by=result.rule_name,
                within_minutes=DEDUP_WINDOW_MIN,
            ):
                logger.debug(
                    "anomaly_detection: skipping duplicate for rule=%s", result.rule_name
                )
                summary.skipped_dedup.append(result.rule_name)
                continue

            # Create the incident
            try:
                incident_id = await self._create_incident_with_analysis(result)
                summary.incident_ids.append(str(incident_id))
                summary.triggered_count += 1
                logger.info(
                    "anomaly_detection: incident created id=%s rule=%s severity=%s value=%.3f",
                    incident_id,
                    result.rule_name,
                    result.severity,
                    result.metric_value,
                )
            except Exception as exc:
                logger.error(
                    "anomaly_detection: failed to create incident for rule=%s: %s",
                    result.rule_name,
                    exc,
                )
                summary.errors.append(f"{result.rule_name}: {exc}")

        return summary

    # ── Stage 1 ───────────────────────────────────────────────────────────────

    async def _run_stage1(self) -> list[DetectionResult]:
        """Rule-based threshold checks."""
        results: list[DetectionResult] = []

        # ── Rule 1: Error rate ────────────────────────────────────────────────
        # Mirrors: HighHTTP5xxErrorRate in alerting.rules.yml (5% threshold)
        try:
            error_count = await self._obs_repo.count_errors_in_window(
                minutes=DETECTION_WINDOW_MIN
            )
            total_count = await self._obs_repo.count_all_in_window(
                minutes=DETECTION_WINDOW_MIN
            )
            if total_count > 0:
                error_rate = error_count / total_count
                triggered = error_rate > ERROR_RATE_THRESHOLD
                results.append(
                    DetectionResult(
                        rule_name="error_rate",
                        triggered=triggered,
                        severity="HIGH" if triggered else "LOW",
                        metric_value=round(error_rate, 4),
                        threshold_value=ERROR_RATE_THRESHOLD,
                        title=(
                            f"High error rate detected "
                            f"({error_rate * 100:.1f}% in last {DETECTION_WINDOW_MIN} min)"
                        ),
                        event_count=error_count,
                        detail=f"{error_count}/{total_count} events are ERROR/CRITICAL",
                    )
                )
        except Exception as exc:
            logger.warning("anomaly_detection: stage1 error_rate check failed: %s", exc)

        # ── Rule 2: Absolute error count ──────────────────────────────────────
        # Fires even at low traffic where the rate might look normal but count is high
        try:
            error_count = await self._obs_repo.count_errors_in_window(
                minutes=DETECTION_WINDOW_MIN
            )
            triggered = error_count > ERROR_COUNT_THRESHOLD
            results.append(
                DetectionResult(
                    rule_name="error_count",
                    triggered=triggered,
                    severity="HIGH" if triggered else "LOW",
                    metric_value=float(error_count),
                    threshold_value=float(ERROR_COUNT_THRESHOLD),
                    title=(
                        f"High absolute error count "
                        f"({error_count} errors in last {DETECTION_WINDOW_MIN} min)"
                    ),
                    event_count=error_count,
                    detail=f"{error_count} ERROR/CRITICAL events (threshold: {ERROR_COUNT_THRESHOLD})",
                )
            )
        except Exception as exc:
            logger.warning("anomaly_detection: stage1 error_count check failed: %s", exc)

        return results

    # ── Stage 2 ───────────────────────────────────────────────────────────────

    async def _run_stage2(self) -> list[DetectionResult]:
        """Statistical detection: current > mean + N * std_dev."""
        results: list[DetectionResult] = []

        try:
            stats = await self._obs_repo.compute_event_rate_stats(
                level="ERROR",
                window_minutes=STAT_WINDOW_MIN,
                bucket_minutes=STAT_BUCKET_MIN,
            )
            if stats["is_anomaly"]:
                threshold = stats["mean"] + STAT_STD_MULTIPLIER * stats["std_dev"]
                results.append(
                    DetectionResult(
                        rule_name="error_spike_statistical",
                        triggered=True,
                        severity="MEDIUM",
                        metric_value=float(stats["current"]),
                        threshold_value=round(threshold, 2),
                        title=(
                            f"Statistical error spike detected "
                            f"({stats['current']} errors vs baseline mean "
                            f"{stats['mean']:.1f} ± {stats['std_dev']:.1f})"
                        ),
                        event_count=stats["current"],
                        detail=(
                            f"Current bucket ({stats['current']}) exceeds "
                            f"mean + {STAT_STD_MULTIPLIER}σ "
                            f"({stats['mean']:.1f} + "
                            f"{STAT_STD_MULTIPLIER}×{stats['std_dev']:.1f} = "
                            f"{threshold:.1f})"
                        ),
                    )
                )
        except Exception as exc:
            logger.warning("anomaly_detection: stage2 error_spike check failed: %s", exc)

        return results

    # ── Incident creation + Phase 10 analysis ────────────────────────────────

    async def _create_incident_with_analysis(
        self, result: DetectionResult
    ) -> "uuid.UUID":
        """Create the Incident row, trigger Phase 10 analysis, attach results."""
        import uuid

        incident = await self._inc_repo.create(
            title=result.title,
            severity=result.severity,
            detection_method=(
                "statistical" if "statistical" in result.rule_name else "rule_based"
            ),
            triggered_by=result.rule_name,
            event_count=result.event_count,
            window_minutes=DETECTION_WINDOW_MIN,
            metric_value=result.metric_value,
            threshold_value=result.threshold_value,
        )

        # Flush so the incident row has an ID before we try to attach analysis
        await self._db.flush()

        # Trigger Phase 10 error analysis (non-blocking — failure does not cancel incident)
        try:
            from app.schemas.error_analysis import AnalyseErrorRequest
            from app.services.error_analysis_service import ErrorAnalysisService

            analysis_request = AnalyseErrorRequest(
                lookback_minutes=DETECTION_WINDOW_MIN,
            )
            analysis_service = ErrorAnalysisService(self._db)
            analysis = await analysis_service.analyse(analysis_request)

            # Attach analysis results to the incident
            await self._inc_repo.attach_analysis(
                incident_id=incident.id,
                analysis_id=analysis.analysis_id,
                ai_summary=analysis.summary,
                ai_confidence=analysis.confidence,
                ai_recommended_fix=analysis.recommended_fix,
            )
        except Exception as exc:
            logger.warning(
                "anomaly_detection: Phase 10 analysis failed for incident %s "
                "(incident still created): %s",
                incident.id,
                exc,
            )

        await self._db.commit()

        # Phase 15 — AIOps: notify all admin users about the new incident.
        # Uses the existing send_push_notification Celery task (non-blocking).
        # Failure to notify never rolls back the incident.
        try:
            await self._notify_admins(incident.id, result.title, result.severity)
        except Exception as exc:
            logger.warning(
                "anomaly_detection: admin push notification failed for incident %s "
                "(incident still created): %s",
                incident.id,
                exc,
            )

        return incident.id

    async def _notify_admins(
        self,
        incident_id: "uuid.UUID",
        title: str,
        severity: str,
    ) -> None:
        """Send FCM push notifications to all admin users with a registered device token.

        This implements the Phase 15 AIOps loop step:
          "Remediation Recommendation → 📱 Push Notification → Developer"

        Admin users are defined by ``UserRole.admin`` on the User model.
        Only users with a non-null ``fcm_token`` receive the notification.

        The push notification deep links the developer directly to the incident
        detail on the Android Dashboard so they can review the AI analysis and
        approve or reject the recommended remediation.

        Phase 15 — AIOps
        """
        from sqlalchemy import select

        from app.models.user import User, UserRole
        from app.workers.notification_worker import send_push_notification

        try:
            # Fetch all admin users who have a registered FCM token
            result = await self._db.execute(
                select(User.id, User.fcm_token).where(
                    User.role == UserRole.admin,
                    User.fcm_token.isnot(None),
                    User.is_active.is_(True),
                )
            )
            admin_users = result.all()

            if not admin_users:
                logger.debug(
                    "anomaly_detection: no admin users with FCM tokens — skipping notification"
                )
                return

            severity_emoji = {
                "CRITICAL": "🔴",
                "HIGH":     "🟠",
                "MEDIUM":   "🟡",
                "LOW":      "🔵",
            }.get(severity.upper(), "⚪")

            for user_id, _fcm_token in admin_users:
                send_push_notification.delay(
                    user_id=str(user_id),
                    title=f"{severity_emoji} {severity} Incident Detected",
                    body=title,
                    data={
                        "type":        "incident_created",
                        "incident_id": str(incident_id),
                        "severity":    severity,
                        "screen":      f"devops/incident/{incident_id}",
                    },
                )

            logger.info(
                "anomaly_detection: push notifications queued for %d admin user(s) "
                "— incident=%s severity=%s",
                len(admin_users),
                incident_id,
                severity,
            )
        except Exception as exc:
            # Non-fatal — log and continue
            raise exc
