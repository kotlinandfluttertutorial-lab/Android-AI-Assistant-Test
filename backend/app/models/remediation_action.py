"""ORM model for the ``remediation_actions`` table.

Stores remediation recommendations and their human approval decisions.

Lifecycle:
  RECOMMENDED → APPROVED → EXECUTING → COMPLETED | FAILED
  RECOMMENDED → REJECTED  (human rejects — no execution)

AI Safety guarantee:
  No action with status=APPROVED is executed automatically.
  Execution requires a separate explicit API call AFTER approval.
  The current phase (15 initial delivery) only supports RECOMMENDATION.
  Automated execution is introduced only after the human-approval flow is
  fully tested in production.

Risk tiers (from master plan):
  LOW    — Notify team (Slack/email), create incident ticket
  MEDIUM — Restart service, scale service up/down
  HIGH   — Roll back deployment, modify production config

Phase 15 — AIOps
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, uuid_pk


class RemediationAction(Base):
    """A recommended remediation action linked to an incident."""

    __tablename__ = "remediation_actions"

    # ── Primary key ───────────────────────────────────────────────────────────
    id: Mapped[uuid.UUID] = uuid_pk()

    # ── Incident link ─────────────────────────────────────────────────────────
    incident_id: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        index=True,
        comment="UUID of the parent Incident row",
    )

    # ── Action identity ───────────────────────────────────────────────────────
    # Human-readable title: "Restart ai-assistant-backend service"
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Short human-readable description of the recommended action",
    )

    # Machine-readable action type
    # e.g. "notify_slack" | "restart_service" | "scale_up" | "rollback"
    action_type: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        index=True,
        comment="Machine-readable action identifier",
    )

    # LOW | MEDIUM | HIGH  (see module docstring for definitions)
    risk_tier: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        comment="LOW | MEDIUM | HIGH",
    )

    # ── AI reasoning ──────────────────────────────────────────────────────────
    reasoning: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        comment="Why the AI recommends this action",
    )

    # AI confidence that this action will resolve the incident (0.0 – 1.0)
    confidence: Mapped[float | None] = mapped_column(
        Float,
        nullable=True,
        comment="AI confidence that this action addresses the root cause (0.0–1.0)",
    )

    # Rank among all recommended actions for this incident (1 = highest priority)
    rank: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=1,
        comment="Priority rank — 1 is the most recommended action",
    )

    # ── Parameters (JSON string) ──────────────────────────────────────────────
    # Action-specific parameters, e.g.:
    #   restart_service: {"service": "ai-assistant-backend", "region": "asia-south1"}
    #   scale_up:        {"service": "...", "max_instances": 5}
    #   rollback:        {"service": "...", "revision": "ai-assistant-backend-00041-abc"}
    params_json: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="{}",
        comment="JSON-encoded action parameters",
    )

    # ── Approval workflow ─────────────────────────────────────────────────────
    # RECOMMENDED | APPROVED | REJECTED | EXECUTING | COMPLETED | FAILED
    status: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="RECOMMENDED",
        index=True,
        comment="RECOMMENDED | APPROVED | REJECTED | EXECUTING | COMPLETED | FAILED",
    )

    # Who approved or rejected (user ID)
    reviewed_by: Mapped[str | None] = mapped_column(
        String(128),
        nullable=True,
        comment="User ID of the engineer who approved or rejected",
    )

    # Optional rejection reason
    rejection_reason: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="Human-provided reason for rejection",
    )

    # ── Timestamps ────────────────────────────────────────────────────────────
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
        comment="UTC timestamp when the recommendation was generated",
    )

    reviewed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when the action was approved or rejected",
    )

    executed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when execution started",
    )

    completed_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="UTC timestamp when execution completed (success or failure)",
    )

    def __repr__(self) -> str:
        return (
            f"<RemediationAction id={self.id!s} action_type={self.action_type!r} "
            f"risk_tier={self.risk_tier!r} status={self.status!r}>"
        )
