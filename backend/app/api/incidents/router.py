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
