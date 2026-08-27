"""Remediation Service — Phase 15 AIOps.

Generates ranked remediation recommendations for a specific incident by
combining the incident's AI analysis (Phase 10) and RCA (Phase 12) with
a knowledge base of known fixes (Phase 9).

Phase 15 initial delivery — RECOMMENDATION ONLY.
Automated execution is introduced only after the human-approval flow is
fully tested in production (see AI Safety section below).

AI Safety Principles applied:
  1. NEVER auto-execute — recommendations require explicit human approval.
  2. Risk tiers are hard-coded — HIGH risk actions require extra confirmation.
  3. The service exposes a dry-run description, not an execution function.
  4. Every recommendation links back to the evidence that motivated it.
  5. Human approval + rejection are both logged with reviewer identity.

Remediation action catalogue (risk-tiered):

  LOW (safe to execute immediately after approval):
    notify_slack       — send alert to Slack channel
    create_ticket      — create Jira/GitHub issue

  MEDIUM (requires brief pause to verify no side effects):
    restart_service    — gcloud run services update (same image → new revision)
    scale_up           — increase max-instances
    scale_down         — decrease max-instances

  HIGH (requires careful review — may affect production data):
    rollback           — route traffic to previous Cloud Run revision
    modify_config      — update a Cloud Run env var or Secret Manager secret

Phase 15 — AIOps
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime

from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.incident import Incident
from app.models.remediation_action import RemediationAction
from app.repositories.incident_repository import IncidentRepository

logger = logging.getLogger(__name__)

# ── Constants ─────────────────────────────────────────────────────────────────

_LOW_CONFIDENCE_THRESHOLD = 0.6

# Catalogue of known action types and their risk tiers.
# Used both to generate recommendations and to validate approval requests.
ACTION_CATALOGUE: dict[str, str] = {
    "notify_slack":    "LOW",
    "create_ticket":   "LOW",
    "restart_service": "MEDIUM",
    "scale_up":        "MEDIUM",
    "scale_down":      "MEDIUM",
    "rollback":        "HIGH",
    "modify_config":   "HIGH",
}


@dataclass
class RemediationRecommendation:
    """A single ranked remediation recommendation."""
    rank:         int
    action_type:  str
    risk_tier:    str
    title:        str
    reasoning:    str
    confidence:   float
    params:       dict = field(default_factory=dict)


@dataclass
class RemediationPlan:
    """Full remediation plan for one incident."""
    incident_id:     str
    incident_title:  str
    ai_summary:      str
    recommendations: list[RemediationRecommendation] = field(default_factory=list)
    low_confidence_warning: str | None = None


class RemediationService:
    """Generates and manages remediation recommendations for incidents.

    Usage (recommendation)::

        service = RemediationService(db)
        plan = await service.recommend(incident_id)

    Usage (approval)::

        action = await service.approve(action_id, reviewer_user_id)

    Usage (rejection)::

        action = await service.reject(action_id, reviewer_user_id, reason)
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db       = db
        self._inc_repo = IncidentRepository(db)

    # ── Recommendation ────────────────────────────────────────────────────────

    async def recommend(self, incident_id: uuid.UUID) -> RemediationPlan:
        """Generate a ranked list of remediation recommendations for an incident.

        Reads the incident's Phase 10 AI analysis and Phase 12 RCA, then uses
        rule-based mapping to suggest appropriate actions ranked by relevance.

        Never auto-executes. Returns a plan that the human reviews and approves.

        Phase 15 — AIOps
        """
        incident = await self._inc_repo.get_by_id(incident_id)
        if incident is None:
            return RemediationPlan(
                incident_id   = str(incident_id),
                incident_title= "Incident not found",
                ai_summary    = "",
                recommendations=[],
                low_confidence_warning="Incident not found — cannot recommend remediation.",
            )

        # Build recommendations from the incident's existing AI analysis
        recommendations = self._build_recommendations(incident)

        # Persist recommendations to the DB
        for rec in recommendations:
            action = RemediationAction(
                id            = uuid.uuid4(),
                incident_id   = str(incident_id),
                title         = rec.title,
                action_type   = rec.action_type,
                risk_tier     = rec.risk_tier,
                reasoning     = rec.reasoning,
                confidence    = rec.confidence,
                rank          = rec.rank,
                params_json   = json.dumps(rec.params),
                status        = "RECOMMENDED",
            )
            self._db.add(action)

        await self._db.commit()

        low_conf_warning: str | None = None
        if incident.ai_confidence and incident.ai_confidence < _LOW_CONFIDENCE_THRESHOLD:
            low_conf_warning = (
                f"AI confidence is {incident.ai_confidence:.0%} — below the 0.6 threshold. "
                "Review evidence carefully before approving any action."
            )

        return RemediationPlan(
            incident_id      = str(incident_id),
            incident_title   = incident.title,
            ai_summary       = incident.ai_summary or "",
            recommendations  = recommendations,
            low_confidence_warning = low_conf_warning,
        )

    def _build_recommendations(
        self, incident: Incident
    ) -> list[RemediationRecommendation]:
        """Map incident characteristics to ranked remediation actions.

        Logic:
        - All incidents → LOW: notify_slack + create_ticket
        - HIGH/CRITICAL incidents → MEDIUM: restart_service
        - Incidents triggered by error_rate / error_count → MEDIUM: scale_up
        - Incidents with RCA candidates mentioning "deployment" → HIGH: rollback
        - Incidents with RCA candidates mentioning "config" → HIGH: modify_config
        """
        recs: list[RemediationRecommendation] = []
        severity  = incident.severity
        triggered = (incident.triggered_by or "").lower()
        rca_text  = (incident.rca_summary or "").lower()
        ai_text   = (incident.ai_summary  or "").lower()
        ai_conf   = incident.ai_confidence or 0.5

        # ── Always recommend: notify + ticket ─────────────────────────────────
        recs.append(RemediationRecommendation(
            rank        = 1,
            action_type = "notify_slack",
            risk_tier   = "LOW",
            title       = "Notify team via Slack",
            reasoning   = (
                f"Incident '{incident.title}' (severity={severity}) was auto-detected. "
                "Team notification ensures awareness before any action is taken."
            ),
            confidence  = 0.95,
            params      = {
                "channel": "#incidents",
                "message": f"[{severity}] {incident.title}",
            },
        ))
        recs.append(RemediationRecommendation(
            rank        = 2,
            action_type = "create_ticket",
            risk_tier   = "LOW",
            title       = "Create incident ticket",
            reasoning   = (
                "A tracked ticket provides an audit trail and ensures the incident "
                "is not forgotten if Slack notifications are missed."
            ),
            confidence  = 0.9,
            params      = {
                "title":    incident.title,
                "severity": severity,
            },
        ))

        # ── MEDIUM: restart for HIGH/CRITICAL ─────────────────────────────────
        if severity in ("HIGH", "CRITICAL"):
            recs.append(RemediationRecommendation(
                rank        = 3,
                action_type = "restart_service",
                risk_tier   = "MEDIUM",
                title       = "Restart ai-assistant-backend (new revision)",
                reasoning   = (
                    f"{severity} severity incident detected. A service restart clears "
                    "in-memory state, resets connection pools, and forces a new "
                    "Cloud Run revision. Zero-downtime — traffic routes to new instance "
                    "before old one is terminated."
                ),
                confidence  = ai_conf * 0.8,  # scaled by AI analysis confidence
                params      = {
                    "service": "ai-assistant-backend",
                    "region":  "asia-south1",
                },
            ))

        # ── MEDIUM: scale_up for error_rate / error_count ─────────────────────
        if "error_rate" in triggered or "error_count" in triggered:
            recs.append(RemediationRecommendation(
                rank        = 4,
                action_type = "scale_up",
                risk_tier   = "MEDIUM",
                title       = "Scale up max-instances to 5",
                reasoning   = (
                    "High error rate may indicate the service is overwhelmed. "
                    "Increasing max-instances allows Cloud Run to handle more "
                    "parallel requests while the root cause is investigated."
                ),
                confidence  = 0.6,
                params      = {
                    "service":       "ai-assistant-backend",
                    "max_instances": "5",
                },
            ))

        # ── HIGH: rollback if deployment-related ──────────────────────────────
        if any(kw in rca_text or kw in ai_text for kw in
               ("deploy", "deployment", "release", "new version")):
            recs.append(RemediationRecommendation(
                rank        = 5,
                action_type = "rollback",
                risk_tier   = "HIGH",
                title       = "Roll back to previous Cloud Run revision",
                reasoning   = (
                    "RCA suggests the incident may be linked to a recent deployment. "
                    "Rolling back routes traffic to the previous revision immediately. "
                    "⚠️ HIGH RISK — verify the previous revision is stable before approving."
                ),
                confidence  = ai_conf * 0.7,
                params      = {
                    "service": "ai-assistant-backend",
                    "region":  "asia-south1",
                    "note":    "Run: gcloud run services update-traffic ai-assistant-backend "
                               "--to-revisions=PREVIOUS_REVISION=100 --region=asia-south1",
                },
            ))

        # ── HIGH: modify_config if config/secret related ──────────────────────
        if any(kw in rca_text or kw in ai_text for kw in
               ("config", "secret", "env var", "timeout", "pool_size")):
            recs.append(RemediationRecommendation(
                rank        = 6,
                action_type = "modify_config",
                risk_tier   = "HIGH",
                title       = "Update Cloud Run configuration",
                reasoning   = (
                    "RCA suggests a configuration value (timeout, pool size, env var) "
                    "may need adjustment. "
                    "⚠️ HIGH RISK — modifying production config may affect all users."
                ),
                confidence  = ai_conf * 0.6,
                params      = {
                    "service": "ai-assistant-backend",
                    "region":  "asia-south1",
                    "note":    "Update the specific env var/secret identified in the RCA.",
                },
            ))

        # Sort by confidence descending, re-assign ranks
        recs.sort(key=lambda r: r.confidence, reverse=True)
        for i, rec in enumerate(recs):
            rec.rank = i + 1

        return recs

    # ── Approval ──────────────────────────────────────────────────────────────

    async def approve(
        self,
        action_id:        uuid.UUID,
        reviewer_user_id: str,
    ) -> RemediationAction | None:
        """Record human approval of a remediation recommendation.

        Sets status to APPROVED and records the reviewer's user ID.
        Does NOT execute the action — execution is a separate deliberate step.

        Phase 15 AIOps — initial delivery is RECOMMENDATION ONLY.
        Execution support is introduced after the approval flow is validated.

        Phase 15 — AIOps
        """
        action = await self._get_action(action_id)
        if action is None:
            return None

        if action.status != "RECOMMENDED":
            logger.warning(
                "remediation: approve called on action %s with status=%s — skipping",
                action_id, action.status,
            )
            return action

        action.status      = "APPROVED"
        action.reviewed_by = reviewer_user_id
        action.reviewed_at = datetime.now(tz=UTC)
        await self._db.commit()

        logger.info(
            "remediation: action %s APPROVED by user=%s (risk_tier=%s)",
            action_id, reviewer_user_id, action.risk_tier,
        )
        return action

    # ── Rejection ─────────────────────────────────────────────────────────────

    async def reject(
        self,
        action_id:        uuid.UUID,
        reviewer_user_id: str,
        reason:           str = "",
    ) -> RemediationAction | None:
        """Record human rejection of a remediation recommendation.

        Sets status to REJECTED. No execution will ever happen for this action.

        Phase 15 — AIOps
        """
        action = await self._get_action(action_id)
        if action is None:
            return None

        if action.status not in ("RECOMMENDED", "APPROVED"):
            return action

        action.status           = "REJECTED"
        action.reviewed_by      = reviewer_user_id
        action.reviewed_at      = datetime.now(tz=UTC)
        action.rejection_reason = reason or "No reason provided"
        await self._db.commit()

        logger.info(
            "remediation: action %s REJECTED by user=%s reason=%r",
            action_id, reviewer_user_id, reason,
        )
        return action

    # ── List ──────────────────────────────────────────────────────────────────

    async def list_actions(
        self,
        incident_id: uuid.UUID,
    ) -> list[RemediationAction]:
        """Return all remediation actions for an incident, newest first."""
        result = await self._db.execute(
            select(RemediationAction)
            .where(RemediationAction.incident_id == str(incident_id))
            .order_by(RemediationAction.rank.asc())
        )
        return list(result.scalars().all())

    # ── Helper ────────────────────────────────────────────────────────────────

    async def _get_action(self, action_id: uuid.UUID) -> RemediationAction | None:
        result = await self._db.execute(
            select(RemediationAction).where(RemediationAction.id == action_id)
        )
        return result.scalar_one_or_none()
