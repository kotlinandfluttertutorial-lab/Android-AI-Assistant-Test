"""ORM model for the ``observability_events`` table.

Stores structured observability events uploaded from the Android app via
``POST /api/v1/observability/events`` (every 15 minutes via WorkManager).

These events are the primary data source for Phase 10 AI Error Analysis.
The analysis pipeline reads recent ERROR/CRITICAL events for a given
session or time window and passes them to the LLM alongside retrieved
runbooks and historical incident reports.

Fields mirror the Android ``ObservabilityEvent`` data class defined in
``core-common/src/main/kotlin/.../observability/ObservabilityEvent.kt``.

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Index, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, uuid_pk


class ObservabilityEvent(Base):
    """Single observability event uploaded from the Android app."""

    __tablename__ = "observability_events"

    # ── Primary key ───────────────────────────────────────────────────────────
    id: Mapped[uuid.UUID] = uuid_pk()

    # ── Event identity ────────────────────────────────────────────────────────
    # epoch millis (UTC) when the event was captured on-device
    timestamp_ms: Mapped[int] = mapped_column(
        BigInteger,
        nullable=False,
        comment="Epoch milliseconds (UTC) when the event was captured on-device",
    )

    # DEBUG | INFO | WARN | ERROR | CRITICAL
    level: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        index=True,
        comment="Event severity: DEBUG | INFO | WARN | ERROR | CRITICAL",
    )

    # e.g. "network_error", "crash_handled", "api_latency"
    event_type: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        index=True,
        comment="Machine-readable event category (use EventType constants)",
    )

    # PII-filtered human-readable description
    message: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="Human-readable, PII-stripped event description",
    )

    # ── Correlation identifiers ───────────────────────────────────────────────
    session_id: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        index=True,
        comment="Groups all events within one app session (process lifetime UUID)",
    )

    request_id: Mapped[str | None] = mapped_column(
        String(128),
        nullable=True,
        index=True,
        comment="Unique per HTTP call — correlates Android log with backend log",
    )

    trace_id: Mapped[str | None] = mapped_column(
        String(128),
        nullable=True,
        comment="Groups related events across a single user action flow",
    )

    # ── Context ───────────────────────────────────────────────────────────────
    # Compose navigation route active when the event fired
    screen: Mapped[str | None] = mapped_column(
        String(256),
        nullable=True,
        comment="Active Compose screen/route at time of event",
    )

    # JSON-encoded metadata dict (all values are strings, no PII)
    metadata_json: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="{}",
        comment="JSON-encoded Map<String,String> of event metadata",
    )

    # ── Ingest timestamp ──────────────────────────────────────────────────────
    # When the backend received this event (not when it was captured on-device)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
        comment="UTC timestamp when this event was received by the backend",
    )

    # ── Composite indexes for common query patterns ───────────────────────────
    __table_args__ = (
        # Most common query: recent ERROR/CRITICAL events for analysis
        Index("ix_obs_evt_level_received", "level", "received_at"),
        # Correlate a full session in one query
        Index("ix_obs_evt_session_received", "session_id", "received_at"),
        # Look up all events for a specific request
        Index("ix_obs_evt_request_id", "request_id"),
    )

    def __repr__(self) -> str:
        return (
            f"<ObservabilityEvent id={self.id!s} level={self.level!r} "
            f"event_type={self.event_type!r} received_at={self.received_at!s}>"
        )
