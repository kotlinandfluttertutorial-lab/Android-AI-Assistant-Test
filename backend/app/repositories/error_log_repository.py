"""Repository for ``error_logs`` table — Phase 12 RCA evidence collection.

Adds time-window queries so the RCA pipeline can pull backend server errors
that coincided with an incident's detection window.

The ``error_logs`` table records Python exceptions raised inside the FastAPI
app (captured by RequestLoggingMiddleware). Unlike ``observability_events``
(which come from the Android app), these are server-side errors with full
stack traces and endpoint paths.

Phase 12 — Root Cause Analysis
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.error_log import ErrorLog

logger = logging.getLogger(__name__)


class ErrorLogRepository:
    """Data-access layer for the error_logs table."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def get_errors_around_time(
        self,
        centre: datetime,
        window_minutes: int = 30,
        limit: int = 50,
    ) -> list[ErrorLog]:
        """Return server-side error log entries in a time window around a centre point.

        Used by the RCA pipeline to find backend exceptions that occurred
        around the time an anomaly was detected.

        Args:
            centre:         The focal timestamp (typically ``Incident.detected_at``).
            window_minutes: Half-width of the window in minutes.
                            Queries ``centre ± window_minutes``, so the total
                            range is ``2 × window_minutes``.
            limit:          Maximum rows to return.

        Returns:
            ``ErrorLog`` rows ordered oldest-first, ready for timeline assembly.
        """
        # Make centre timezone-aware if it isn't
        if centre.tzinfo is None:
            centre = centre.replace(tzinfo=UTC)

        start = centre - timedelta(minutes=window_minutes)
        end   = centre + timedelta(minutes=window_minutes)

        result = await self._db.execute(
            select(ErrorLog)
            .where(
                and_(
                    ErrorLog.created_at >= start,
                    ErrorLog.created_at <= end,
                )
            )
            .order_by(ErrorLog.created_at.asc())
            .limit(limit)
        )
        return list(result.scalars().all())

    async def get_recent(
        self,
        minutes: int = 30,
        limit: int = 50,
    ) -> list[ErrorLog]:
        """Return the most recent server error log entries.

        Convenience method when no specific centre timestamp is available.
        """
        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)
        result = await self._db.execute(
            select(ErrorLog)
            .where(ErrorLog.created_at >= cutoff)
            .order_by(ErrorLog.created_at.asc())
            .limit(limit)
        )
        return list(result.scalars().all())
