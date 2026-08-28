"""Incident CRUD endpoints — Phase 11 Anomaly Detection.

Endpoints
---------
GET  /incidents              — list recent incidents (filterable)
GET  /incidents/{id}         — get a single incident with full AI analysis
PATCH /incidents/{id}/status — update incident status (OPEN→INVESTIGATING→RESOLVED)
POST /incidents              — manually create an incident (admin/developer use)

All endpoints require a valid JWT.

Phase 11 — Anomaly Detection
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.incident import Incident
from app.repositories.incident_repository import IncidentRepository
from app.schemas.rca import RcaAnalysisResponse, RcaRequest
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/incidents",
    tags=["incidents"],
    dependencies=[Depends(get_current_user)],
)


# ── Schemas ───────────────────────────────────────────────────────────────────


class IncidentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    title: str
    severity: str
    status: str
    detection_method: str
    triggered_by: str
    metric_value: float | None
    threshold_value: float | None
    analysis_id: str | None
    ai_summary: str | None
    ai_confidence: float | None
    ai_recommended_fix: str | None
    event_count: int
    window_minutes: int
    detected_at: str  # ISO string for JSON serialisation
    resolved_at: str | None

    @classmethod
    def from_orm_model(cls, inc: Incident) -> "IncidentResponse":
        return cls(
            id=inc.id,
            title=inc.title,
            severity=inc.severity,
            status=inc.status,
            detection_method=inc.detection_method,
            triggered_by=inc.triggered_by,
            metric_value=inc.metric_value,
            threshold_value=inc.threshold_value,
            analysis_id=inc.analysis_id,
            ai_summary=inc.ai_summary,
            ai_confidence=inc.ai_confidence,
            ai_recommended_fix=inc.ai_recommended_fix,
            event_count=inc.event_count,
            window_minutes=inc.window_minutes,
            detected_at=inc.detected_at.isoformat() if inc.detected_at else "",
            resolved_at=inc.resolved_at.isoformat() if inc.resolved_at else None,
        )


class IncidentListResponse(BaseModel):
    incidents: list[IncidentResponse]
    total: int
    open_count: int


class UpdateStatusRequest(BaseModel):
    status: str = Field(
        description="New status: INVESTIGATING | RESOLVED | DISMISSED",
    )


class CreateIncidentRequest(BaseModel):
    title: str = Field(max_length=512)
    severity: str = Field(description="CRITICAL | HIGH | MEDIUM | LOW")
    triggered_by: str = Field(default="manual", max_length=128)


# ── Endpoints ─────────────────────────────────────────────────────────────────


@router.get(
    "",
    response_model=IncidentListResponse,
    summary="List recent incidents",
)
async def list_incidents(
    limit: int = Query(default=50, ge=1, le=200),
    status_filter: str | None = Query(default=None, alias="status"),
    severity_filter: str | None = Query(default=None, alias="severity"),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> IncidentListResponse:
    """Return recent incidents, newest first.

    Filter by status (OPEN | INVESTIGATING | RESOLVED | DISMISSED)
    or severity (CRITICAL | HIGH | MEDIUM | LOW).

    Phase 11 — Anomaly Detection
    """
    repo = IncidentRepository(db)
    incidents = await repo.list_recent(
        limit=limit,
        status=status_filter,
        severity=severity_filter,
    )
    open_count = await repo.get_open_count()
    return IncidentListResponse(
        incidents=[IncidentResponse.from_orm_model(i) for i in incidents],
        total=len(incidents),
        open_count=open_count,
    )


@router.get(
    "/{incident_id}",
    response_model=IncidentResponse,
    summary="Get a single incident",
)
async def get_incident(
    incident_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> IncidentResponse:
    """Fetch a single incident by ID including full AI analysis results.

    Phase 11 — Anomaly Detection
    """
    repo = IncidentRepository(db)
    incident = await repo.get_by_id(incident_id)
    if incident is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Incident {incident_id} not found.",
        )
    return IncidentResponse.from_orm_model(incident)


@router.patch(
    "/{incident_id}/status",
    response_model=IncidentResponse,
    summary="Update incident status",
)
async def update_incident_status(
    incident_id: uuid.UUID,
    body: UpdateStatusRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> IncidentResponse:
    """Transition an incident to a new status.

    Valid transitions:
    - OPEN → INVESTIGATING → RESOLVED
    - OPEN → DISMISSED
    - INVESTIGATING → RESOLVED | DISMISSED

    Phase 11 — Anomaly Detection
    """
    valid_statuses = {"OPEN", "INVESTIGATING", "RESOLVED", "DISMISSED"}
    if body.status.upper() not in valid_statuses:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid status '{body.status}'. Must be one of: {sorted(valid_statuses)}",
        )

    repo = IncidentRepository(db)
    incident = await repo.update_status(incident_id, body.status)
    if incident is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Incident {incident_id} not found.",
        )
    await db.commit()
    logger.info(
        "incidents/%s: status updated to %s by user %s",
        incident_id,
        body.status.upper(),
        current_user.sub,
    )
    return IncidentResponse.from_orm_model(incident)


@router.post(
    "",
    response_model=IncidentResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Manually create an incident",
)
async def create_incident(
    body: CreateIncidentRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> IncidentResponse:
    """Create an incident manually (developer / on-call engineer use).

    This is useful for situations the automated detector didn't catch,
    or for testing the incident management workflow.

    Phase 11 — Anomaly Detection
    """
    repo = IncidentRepository(db)
    incident = await repo.create(
        title=body.title,
        severity=body.severity,
        detection_method="manual",
        triggered_by=body.triggered_by,
        event_count=0,
        window_minutes=0,
    )
    await db.commit()
    logger.info(
        "incidents: manually created %s severity=%s by user %s",
        incident.id,
        incident.severity,
        current_user.sub,
    )
    return IncidentResponse.from_orm_model(incident)


# ---------------------------------------------------------------------------
# Phase 12 — Root Cause Analysis endpoints
# ---------------------------------------------------------------------------


@router.post(
    "/{incident_id}/rca",
    summary="Run Root Cause Analysis for an incident",
    description=(
        "Runs the full Phase 12 RCA pipeline for a specific incident:\n\n"
        "1. Load incident and its Phase 10 analysis\n"
        "2. Collect observability events + server error logs in the evidence window\n"
        "3. Build a correlated timeline across all sources\n"
        "4. RAG search the knowledge base for relevant runbooks and incidents\n"
        "5. Chain-of-thought LLM reasoning → ranked root cause candidates\n"
        "6. Apply confidence gate (< 0.6 → manual investigation warning)\n"
        "7. Persist result on the incident row\n\n"
        "If RCA has already been run for this incident, returns the cached result. "
        "Pass ``force_rerun: true`` to override."
    ),
)
async def run_rca(
    incident_id: uuid.UUID,
    body: RcaRequest | None = None,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RcaAnalysisResponse:
    """Trigger RCA for a specific incident.

    Phase 12 — Root Cause Analysis
    """
    from app.services.rca_service import RcaService

    if body is None:
        body = RcaRequest()

    logger.info(
        "incidents/%s/rca: triggered by user=%s window=%dm force=%s",
        incident_id,
        current_user.sub,
        body.evidence_window_minutes,
        body.force_rerun,
    )

    try:
        service = RcaService(db)
        return await service.run(incident_id=incident_id, request=body)
    except Exception as exc:
        logger.error("incidents/%s/rca: unexpected error — %s", incident_id, exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="RCA analysis failed unexpectedly. Check application logs.",
        ) from exc


@router.get(
    "/{incident_id}/rca",
    summary="Fetch cached RCA result for an incident",
    description=(
        "Returns the most recent RCA result stored on the incident row. "
        "If no RCA has been run yet, returns a 404. "
        "Call POST /{id}/rca first to trigger analysis."
    ),
)
async def get_rca(
    incident_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RcaAnalysisResponse:
    """Return the cached RCA result for an incident.

    Phase 12 — Root Cause Analysis
    """
    from app.services.rca_service import RcaService

    service = RcaService(db)
    incident = await service._inc_repo.get_by_id(incident_id)

    if incident is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Incident {incident_id} not found.",
        )

    if not incident.rca_analysis_id:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"No RCA result found for incident {incident_id}. "
                "Call POST /incidents/{id}/rca to trigger analysis."
            ),
        )

    return service._cached_response(rca_id=incident.rca_analysis_id, incident=incident)


# ---------------------------------------------------------------------------
# Phase 15 — AIOps: Remediation endpoints
# ---------------------------------------------------------------------------


class RemediationActionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id:               uuid.UUID
    incident_id:      str
    title:            str
    action_type:      str
    risk_tier:        str
    reasoning:        str
    confidence:       float | None
    rank:             int
    params:           dict
    status:           str
    reviewed_by:      str | None
    rejection_reason: str | None
    created_at:       str
    reviewed_at:      str | None

    @classmethod
    def from_orm_model(cls, a) -> "RemediationActionResponse":
        import json as _json
        try:
            params = _json.loads(a.params_json or "{}")
        except Exception:
            params = {}
        return cls(
            id               = a.id,
            incident_id      = a.incident_id,
            title            = a.title,
            action_type      = a.action_type,
            risk_tier        = a.risk_tier,
            reasoning        = a.reasoning,
            confidence       = a.confidence,
            rank             = a.rank,
            params           = params,
            status           = a.status,
            reviewed_by      = a.reviewed_by,
            rejection_reason = a.rejection_reason,
            created_at       = a.created_at.isoformat() if a.created_at else "",
            reviewed_at      = a.reviewed_at.isoformat() if a.reviewed_at else None,
        )


class RemediationPlanResponse(BaseModel):
    incident_id:             str
    incident_title:          str
    ai_summary:              str
    actions:                 list[RemediationActionResponse]
    low_confidence_warning:  str | None


class ApproveRequest(BaseModel):
    pass   # reviewer ID comes from JWT


class RejectRequest(BaseModel):
    reason: str = Field(default="", description="Optional reason for rejection")


@router.post(
    "/{incident_id}/remediation/recommend",
    response_model=RemediationPlanResponse,
    summary="Generate remediation recommendations for an incident",
    description=(
        "Generates a ranked list of remediation actions based on the incident's "
        "AI analysis (Phase 10) and RCA (Phase 12). "
        "Actions are recommendation-only — NO automated execution happens. "
        "Human approval via POST /{id}/remediation/{action_id}/approve is required. "
        "Phase 15 — AIOps."
    ),
)
async def recommend_remediation(
    incident_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RemediationPlanResponse:
    """Generate remediation recommendations for an incident.

    Phase 15 — AIOps
    """
    from app.services.remediation_service import RemediationService

    service = RemediationService(db)
    plan    = await service.recommend(incident_id)

    return RemediationPlanResponse(
        incident_id            = plan.incident_id,
        incident_title         = plan.incident_title,
        ai_summary             = plan.ai_summary,
        actions                = [
            RemediationActionResponse(
                id               = uuid.uuid4(),  # not yet persisted — temp
                incident_id      = plan.incident_id,
                title            = r.title,
                action_type      = r.action_type,
                risk_tier        = r.risk_tier,
                reasoning        = r.reasoning,
                confidence       = r.confidence,
                rank             = r.rank,
                params           = r.params,
                status           = "RECOMMENDED",
                reviewed_by      = None,
                rejection_reason = None,
                created_at       = "",
                reviewed_at      = None,
            )
            for r in plan.recommendations
        ],
        low_confidence_warning = plan.low_confidence_warning,
    )


@router.get(
    "/{incident_id}/remediation",
    response_model=list[RemediationActionResponse],
    summary="List all remediation actions for an incident",
)
async def list_remediation(
    incident_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[RemediationActionResponse]:
    """Return all remediation actions for an incident ordered by rank.

    Phase 15 — AIOps
    """
    from app.services.remediation_service import RemediationService

    service = RemediationService(db)
    actions = await service.list_actions(incident_id)
    return [RemediationActionResponse.from_orm_model(a) for a in actions]


@router.post(
    "/{incident_id}/remediation/{action_id}/approve",
    response_model=RemediationActionResponse,
    summary="Approve a remediation action",
    description=(
        "Records human approval of a recommended remediation action. "
        "Sets status to APPROVED. "
        "⚠️ Phase 15 initial delivery — APPROVAL IS RECORDED but no automated "
        "execution happens. The engineer executes the action manually using "
        "the params field as a guide. "
        "Phase 15 — AIOps."
    ),
)
async def approve_remediation(
    incident_id: uuid.UUID,
    action_id:   uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RemediationActionResponse:
    """Approve a remediation action.

    Phase 15 — AIOps
    """
    from app.services.remediation_service import RemediationService

    service = RemediationService(db)
    action  = await service.approve(
        action_id        = action_id,
        reviewer_user_id = current_user.sub,
    )
    if action is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Remediation action {action_id} not found.",
        )
    return RemediationActionResponse.from_orm_model(action)


@router.post(
    "/{incident_id}/remediation/{action_id}/reject",
    response_model=RemediationActionResponse,
    summary="Reject a remediation action",
)
async def reject_remediation(
    incident_id: uuid.UUID,
    action_id:   uuid.UUID,
    body:        RejectRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RemediationActionResponse:
    """Reject a remediation action with an optional reason.

    Phase 15 — AIOps
    """
    from app.services.remediation_service import RemediationService

    service = RemediationService(db)
    action  = await service.reject(
        action_id        = action_id,
        reviewer_user_id = current_user.sub,
        reason           = body.reason,
    )
    if action is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Remediation action {action_id} not found.",
        )
    return RemediationActionResponse.from_orm_model(action)
