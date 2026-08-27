"""ORM model for the ``incidents`` table.

An Incident is created automatically by the anomaly detection pipeline
when a threshold is breached (Stage 1) or a statistical anomaly is detected
(Stage 2). It links the triggering evidence (ObservabilityEvent IDs) to
the AI error analysis result and tracks the incident lifecycle.

Incident lifecycle:
  OPEN → INVESTIGATING → RESOLVED | DISMISSED

Phase 11 — Anomaly Detection
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, uuid_pk


class Incident(Base):
    """A production incident detected by the anomaly detection pipeline."""

    __tablename__ = "incidents"

    # ── Primary key ───────────────────────────────────────────────────────────
    id: Mapped[uuid.UUID] = uuid_pk()

    # ── Identity ──────────────────────────────────────────────────────────────
    # Human-readable title: "High error rate detected (23% in 5 min)"
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Short human-readable description of the anomaly",
    )

    # CRITICAL | HIGH | MEDIUM | LOW
    severity: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        index=True,
        comment="CRITICAL | HIGH | MEDIUM | LOW",
    )

    # OPEN | INVESTIGATING | RESOLVED | DISMISSED
    status: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="OPEN",
        index=True,
        comment="OPEN | INVESTIGATING | RESOLVED | DISMISSED",
    )

    # ── Detection metadata ────────────────────────────────────────────────────
    # Which detection method created this incident
    # "rule_based" | "statistical" | "ml" | "manual"
    detection_method: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="rule_based",
        comment="rule_based | statistical | ml | manual",
    )

    # Which specific rule/check fired (e.g. "error_rate", "p99_latency", "event_spike")
    triggered_by: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        default="",
        comment="Name of the threshold rule or statistical check that triggered",
    )

    # The metric value that breached the threshold
    metric_value: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
        comment="Observed metric value at detection time (e.g. 0.23 for 23% error rate)",
    )

    # The threshold that was breached
    threshold_value: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
        comment="Threshold value that was exceeded (e.g. 0.05 for 5% threshold)",
    )

    # ── AI analysis link ──────────────────────────────────────────────────────
    # UUID of the ErrorAnalysisResponse generated for this incident (Phase 10)
    # Stored as string because ErrorAnalysis is not an ORM model
    analysis_id: Mapped[str | None] = mapped_column(
        String(128),
        nullable=True,
        comment="UUID of the Phase 10 ErrorAnalysisResponse for this incident",
    )

    # AI-generated summary from the error analysis
    ai_summary: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="One-line AI-generated summary from the error analysis pipeline",
    )

    # AI confidence score (0.0 – 1.0)
    ai_confidence: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
        comment="AI confidence score from the Phase 10 error analysis (0.0 – 1.0)",
    )

    # AI recommended fix (snapshot at detection time)
    ai_recommended_fix: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="AI-generated recommended fix from the Phase 10 error analysis",
    )

    # ── Context ───────────────────────────────────────────────────────────────
    # Number of error events that contributed to this detection
    event_count: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        comment="Number of error events that contributed to this detection",
    )

    # Look-back window in minutes that was used for detection
    window_minutes: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=5,
        comment="Time window (minutes) used for the anomaly detection check",
    )

    # ── RCA fields (Phase 12) ─────────────────────────────────────────────────
    # UUID of the RcaAnalysisResponse for this incident
    rca_analysis_id: Mapped[str | None] = mapped_column(
        String(128),
        nullable=True,
        comment="UUID of the Phase 12 RCA run for this incident",
    )

    # One-line RCA summary
    rca_summary: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="One-line RCA summary from chain-of-thought reasoning",
    )

    # Overall RCA confidence (0.0 – 1.0)
    rca_confidence: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
        comment="Overall RCA confidence score 0.0–1.0",
    )

    # JSON-encoded list of RootCauseCandidate dicts (ranked, with per-candidate confidence)
    rca_candidates_json: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="JSON list of ranked root cause candidates with per-candidate confidence",
    )

    # JSON-encoded list of investigation step strings
    rca_investigation_steps_json: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="JSON list of recommended investigation steps from RCA",
    )

    # ── Lifecycle timestamps ──────────────────────────────────────────────────
    detected_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
        comment="UTC timestamp when the anomaly was first detected",
    )

    resolved_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when the incident was resolved or dismissed",
    )

    def __repr__(self) -> str:
        return (
            f"<Incident id={self.id!s} severity={self.severity!r} "
            f"status={self.status!r} triggered_by={self.triggered_by!r}>"
        )
