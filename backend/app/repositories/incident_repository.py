"""Repository for ``incidents`` table operations.

Phase 11 — Anomaly Detection
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import and_, desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.incident import Incident


class IncidentRepository:
    """Data-access layer for the incidents table."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ── Write ─────────────────────────────────────────────────────────────────

    async def create(
        self,
        title: str,
        severity: str,
        detection_method: str,
        triggered_by: str,
        event_count: int,
        window_minutes: int,
        metric_value: float | None = None,
        threshold_value: float | None = None,
    ) -> Incident:
        """Persist a new OPEN incident and return the saved ORM instance."""
        incident = Incident(
            id=uuid.uuid4(),
            title=title,
            severity=severity.upper(),
            status="OPEN",
            detection_method=detection_method,
            triggered_by=triggered_by,
            metric_value=metric_value,
            threshold_value=threshold_value,
            event_count=event_count,
            window_minutes=window_minutes,
        )
        self._db.add(incident)
        await self._db.flush()  # get the ID without committing
        return incident

    async def attach_analysis(
        self,
        incident_id: uuid.UUID,
        analysis_id: str,
        ai_summary: str,
        ai_confidence: float,
        ai_recommended_fix: str,
    ) -> None:
        """Link a Phase 10 ErrorAnalysisResponse to an incident."""
        incident = await self.get_by_id(incident_id)
        if incident is None:
            return
        incident.analysis_id       = analysis_id
        incident.ai_summary        = ai_summary
        incident.ai_confidence     = ai_confidence
        incident.ai_recommended_fix = ai_recommended_fix
        await self._db.flush()

    async def update_status(
        self,
        incident_id: uuid.UUID,
        status: str,
    ) -> Incident | None:
        """Transition an incident to a new status."""
        incident = await self.get_by_id(incident_id)
        if incident is None:
            return None
        incident.status = status.upper()
        if status.upper() in ("RESOLVED", "DISMISSED"):
            incident.resolved_at = datetime.now(tz=UTC)
        await self._db.flush()
        return incident

    # ── Read ──────────────────────────────────────────────────────────────────

    async def get_by_id(self, incident_id: uuid.UUID) -> Incident | None:
        result = await self._db.execute(
            select(Incident).where(Incident.id == incident_id)
        )
        return result.scalar_one_or_none()

    async def list_recent(
        self,
        limit: int = 50,
        status: str | None = None,
        severity: str | None = None,
    ) -> list[Incident]:
        """Return recent incidents, optionally filtered by status or severity."""
        stmt = select(Incident).order_by(desc(Incident.detected_at)).limit(limit)
        if status:
            stmt = stmt.where(Incident.status == status.upper())
        if severity:
            stmt = stmt.where(Incident.severity == severity.upper())
        result = await self._db.execute(stmt)
        return list(result.scalars().all())

    async def get_open_count(self) -> int:
        """Return how many incidents are currently OPEN or INVESTIGATING."""
        from sqlalchemy import func as _func
        result = await self._db.execute(
            select(_func.count(Incident.id)).where(
                Incident.status.in_(["OPEN", "INVESTIGATING"])
            )
        )
        return result.scalar_one() or 0

    async def recent_trigger_exists(
        self,
        triggered_by: str,
        within_minutes: int = 5,
    ) -> bool:
        """Return True if an OPEN incident for the same trigger exists within the window.

        Used to prevent duplicate incidents from being created on every detection
        cycle (every 60s). If an incident for 'error_rate' was created 2 minutes
        ago and the error rate is still high, we do NOT create another one.
        """
        from datetime import timedelta
        cutoff = datetime.now(tz=UTC) - timedelta(minutes=within_minutes)
        result = await self._db.execute(
            select(Incident).where(
                and_(
                    Incident.triggered_by == triggered_by,
                    Incident.status.in_(["OPEN", "INVESTIGATING"]),
                    Incident.detected_at >= cutoff,
                )
            ).limit(1)
        )
        return result.scalar_one_or_none() is not None
