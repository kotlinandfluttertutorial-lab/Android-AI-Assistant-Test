"""Repository for ``observability_events`` table operations.

Provides insert (batch ingest) and query (time-window fetch for error analysis).

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.observability_event import ObservabilityEvent

logger = logging.getLogger(__name__)

# Maximum events persisted per ingest batch (safety cap)
_MAX_BATCH_SIZE = 500


class ObservabilityEventRepository:
    """Data-access layer for observability_events."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ── Write ─────────────────────────────────────────────────────────────────

    async def bulk_insert(self, events: list[dict]) -> int:
        """Persist a batch of event dicts to PostgreSQL.

        Args:
            events: List of dicts matching the ``ObservabilityEvent`` schema.
                    Accepted keys: timestamp_ms, level, event_type, message,
                    session_id, request_id, trace_id, screen, metadata.

        Returns:
            Number of rows successfully inserted.
        """
        if not events:
            return 0

        # Enforce cap to prevent accidentally huge inserts
        batch = events[:_MAX_BATCH_SIZE]

        rows = []
        for evt in batch:
            try:
                # metadata arrives as a dict from the JSON body; store as JSON string
                metadata = evt.get("metadata", {})
                if isinstance(metadata, dict):
                    metadata_json = json.dumps(metadata)
                else:
                    metadata_json = "{}"

                rows.append(
                    ObservabilityEvent(
                        id=uuid.uuid4(),
                        timestamp_ms=int(evt.get("timestamp", 0)),
                        level=str(evt.get("level", "INFO")).upper(),
                        event_type=str(evt.get("eventType", evt.get("event_type", "unknown"))),
                        message=str(evt.get("message", ""))[:4096],  # truncate very long messages
                        session_id=str(evt.get("sessionId", evt.get("session_id", ""))),
                        request_id=evt.get("requestId") or evt.get("request_id"),
                        trace_id=evt.get("traceId") or evt.get("trace_id"),
                        screen=evt.get("screen"),
                        metadata_json=metadata_json,
                    )
                )
            except Exception as exc:
                logger.warning("ObservabilityEventRepository: skipping malformed event: %s", exc)
                continue

        if not rows:
            return 0

        self._db.add_all(rows)
        await self._db.commit()
        return len(rows)

    # ── Read ──────────────────────────────────────────────────────────────────

    async def get_recent_errors(
        self,
        minutes: int = 30,
        levels: list[str] | None = None,
        limit: int = 100,
    ) -> list[ObservabilityEvent]:
        """Return recent events at or above the specified severity levels.

        Args:
            minutes: Look-back window in minutes (default 30).
            levels:  List of level strings to include.
                     Defaults to ["ERROR", "CRITICAL"].
            limit:   Maximum rows to return (default 100).

        Returns:
            Events ordered oldest-first (natural log order for the LLM).
        """
        if levels is None:
            levels = ["ERROR", "CRITICAL"]

        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)

        result = await self._db.execute(
            select(ObservabilityEvent)
            .where(
                and_(
                    ObservabilityEvent.level.in_(levels),
                    ObservabilityEvent.received_at >= cutoff,
                )
            )
            .order_by(ObservabilityEvent.received_at.asc())
            .limit(limit)
        )
        return list(result.scalars().all())

    async def get_by_session(
        self,
        session_id: str,
        minutes: int = 60,
        limit: int = 200,
    ) -> list[ObservabilityEvent]:
        """Return all events for a session within the look-back window.

        Useful for gathering full context: what happened before the error?

        Args:
            session_id: App session UUID from the Android app.
            minutes:    Look-back window (default 60 minutes).
            limit:      Maximum rows to return.

        Returns:
            Events ordered oldest-first.
        """
        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)

        result = await self._db.execute(
            select(ObservabilityEvent)
            .where(
                and_(
                    ObservabilityEvent.session_id == session_id,
                    ObservabilityEvent.received_at >= cutoff,
                )
            )
            .order_by(ObservabilityEvent.received_at.asc())
            .limit(limit)
        )
        return list(result.scalars().all())

    async def get_by_id(self, event_id: uuid.UUID) -> ObservabilityEvent | None:
        """Fetch a single event by its UUID."""
        result = await self._db.execute(
            select(ObservabilityEvent).where(ObservabilityEvent.id == event_id)
        )
        return result.scalar_one_or_none()

    # ── Aggregation queries for anomaly detection (Phase 11) ──────────────────

    async def count_errors_in_window(
        self,
        minutes: int = 5,
        levels: list[str] | None = None,
    ) -> int:
        """Count ERROR/CRITICAL events in the last N minutes.

        Used by Stage 1 rule-based detection to check the error rate threshold.

        Args:
            minutes: Look-back window in minutes.
            levels:  Severity levels to count. Defaults to ["ERROR", "CRITICAL"].

        Returns:
            Integer count of matching events.
        """
        from sqlalchemy import func as _func

        if levels is None:
            levels = ["ERROR", "CRITICAL"]

        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)

        result = await self._db.execute(
            select(_func.count(ObservabilityEvent.id)).where(
                and_(
                    ObservabilityEvent.level.in_(levels),
                    ObservabilityEvent.received_at >= cutoff,
                )
            )
        )
        return result.scalar_one() or 0

    async def count_all_in_window(self, minutes: int = 5) -> int:
        """Count ALL events (any level) in the last N minutes.

        Used as the denominator when calculating error rate percentage.

        Args:
            minutes: Look-back window in minutes.

        Returns:
            Integer count of all events.
        """
        from sqlalchemy import func as _func

        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)
        result = await self._db.execute(
            select(_func.count(ObservabilityEvent.id)).where(
                ObservabilityEvent.received_at >= cutoff
            )
        )
        return result.scalar_one() or 0

    async def compute_event_rate_stats(
        self,
        level: str,
        window_minutes: int = 60,
        bucket_minutes: int = 5,
    ) -> dict:
        """Compute rolling mean and standard deviation of event counts.

        Splits the look-back window into fixed-size buckets, counts events per
        bucket, then returns mean, std_dev, and the most recent bucket count.

        Used by Stage 2 statistical detection to identify anomalies beyond
        static thresholds.

        Args:
            level:          Event level to analyse (e.g. "ERROR").
            window_minutes: Total history window in minutes (default 60).
            bucket_minutes: Size of each time bucket in minutes (default 5).

        Returns:
            Dict with keys:
                mean          — average event count per bucket
                std_dev       — standard deviation of bucket counts
                current       — count in the most recent bucket
                bucket_count  — number of buckets used
                is_anomaly    — True if current > mean + 2 * std_dev
        """
        import math

        cutoff = datetime.now(tz=UTC) - timedelta(minutes=window_minutes)

        # Fetch all matching events in the full window
        result = await self._db.execute(
            select(ObservabilityEvent.received_at).where(
                and_(
                    ObservabilityEvent.level == level.upper(),
                    ObservabilityEvent.received_at >= cutoff,
                )
            ).order_by(ObservabilityEvent.received_at.asc())
        )
        timestamps = [row[0] for row in result.all()]

        if not timestamps:
            return {
                "mean": 0.0,
                "std_dev": 0.0,
                "current": 0,
                "bucket_count": 0,
                "is_anomaly": False,
            }

        # Build buckets
        now = datetime.now(tz=UTC)
        num_buckets = window_minutes // bucket_minutes
        buckets: list[int] = [0] * num_buckets

        for ts in timestamps:
            # Make ts timezone-aware if it isn't (defensive)
            if ts.tzinfo is None:
                from datetime import timezone
                ts = ts.replace(tzinfo=timezone.utc)
            age_minutes = (now - ts).total_seconds() / 60
            bucket_idx = int(age_minutes // bucket_minutes)
            if 0 <= bucket_idx < num_buckets:
                # Buckets are indexed oldest→newest; reverse for age
                reversed_idx = num_buckets - 1 - bucket_idx
                buckets[reversed_idx] += 1

        # Statistical computations
        mean = sum(buckets) / len(buckets) if buckets else 0.0
        variance = (
            sum((b - mean) ** 2 for b in buckets) / len(buckets) if buckets else 0.0
        )
        std_dev = math.sqrt(variance)

        # "current" is the most recent bucket (last element after reverse)
        current = buckets[-1] if buckets else 0

        # Anomaly: current count exceeds mean + 2 standard deviations
        is_anomaly = std_dev > 0 and current > (mean + 2 * std_dev)

        return {
            "mean": round(mean, 2),
            "std_dev": round(std_dev, 2),
            "current": current,
            "bucket_count": num_buckets,
            "is_anomaly": is_anomaly,
        }

    async def search_logs(
        self,
        query: str | None = None,
        level: str | None = None,
        event_type: str | None = None,
        minutes: int = 60,
        limit: int = 50,
    ) -> list[ObservabilityEvent]:
        """Full-text-style search over recent observability events.

        Used by the Phase 13 DevOps Assistant ``search_logs`` tool.

        Args:
            query:      Optional substring to match against ``message``.
                        Case-insensitive. None = no message filter.
            level:      Optional severity filter: DEBUG|INFO|WARN|ERROR|CRITICAL.
                        None = all levels.
            event_type: Optional exact event_type match (e.g. "http_error").
                        None = all types.
            minutes:    Look-back window in minutes (default 60).
            limit:      Maximum rows to return (default 50).

        Returns:
            Matching events ordered newest-first so the assistant sees
            the most recent context first.
        """
        from sqlalchemy import func as _func

        cutoff = datetime.now(tz=UTC) - timedelta(minutes=minutes)

        stmt = (
            select(ObservabilityEvent)
            .where(ObservabilityEvent.received_at >= cutoff)
            .order_by(ObservabilityEvent.received_at.desc())
            .limit(limit)
        )

        if level:
            stmt = stmt.where(
                ObservabilityEvent.level == level.upper()
            )

        if event_type:
            stmt = stmt.where(
                ObservabilityEvent.event_type == event_type.lower()
            )

        if query:
            # Postgres ILIKE for case-insensitive substring match
            stmt = stmt.where(
                ObservabilityEvent.message.ilike(f"%{query}%")
            )

        result = await self._db.execute(stmt)
        return list(result.scalars().all())
