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
