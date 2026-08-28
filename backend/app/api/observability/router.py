"""Observability event ingest endpoint.

Receives batches of ``ObservabilityEvent`` records uploaded from the Android
app every 15 minutes by ``ObservabilityUploadWorker``.

Endpoints
---------
POST /observability/events   — batch ingest (no auth required — see note below)

AUTH NOTE:
  This endpoint intentionally accepts requests without a Bearer JWT.
  Rationale: events must be uploadable even when the user is logged out
  (e.g. crash events that occur on the login screen). The data contains no
  PII (filtered by PiiFilter on the Android side) and the endpoint is
  rate-limited by IP via the global RateLimitMiddleware.

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.repositories.observability_event_repository import ObservabilityEventRepository

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/observability",
    tags=["observability"],
)


# ── Ingest schemas ────────────────────────────────────────────────────────────


class ObservabilityEventPayload(BaseModel):
    """Single observability event from the Android app.

    Field names use camelCase to match the Android ``ObservabilityEvent``
    Kotlin data class serialized with kotlinx.serialization.
    """

    timestamp: int = Field(description="Epoch milliseconds (UTC) when captured on-device")
    level: str = Field(description="DEBUG | INFO | WARN | ERROR | CRITICAL")
    eventType: str = Field(description="Machine-readable event category")
    message: str = Field(description="PII-filtered human-readable description")
    sessionId: str = Field(description="App session UUID")
    requestId: str | None = Field(default=None, description="Per-HTTP-call UUID")
    traceId: str | None = Field(default=None, description="User-action flow trace ID")
    screen: str | None = Field(default=None, description="Active Compose route")
    metadata: dict[str, Any] = Field(
        default_factory=dict,
        description="Arbitrary key-value context (all values are strings or primitives)",
    )


class IngestRequest(BaseModel):
    """Batch ingest request body — list of events from ObservabilityUploadWorker."""

    events: list[ObservabilityEventPayload] = Field(
        description="Batch of observability events (max 500 per request)",
        max_length=500,
    )


class IngestResponse(BaseModel):
    """Response confirming how many events were persisted."""

    accepted: int = Field(description="Number of events successfully persisted")
    total: int = Field(description="Number of events in the request")


# ── Endpoint ──────────────────────────────────────────────────────────────────


@router.post(
    "/events",
    response_model=IngestResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Ingest a batch of Android observability events",
    description=(
        "Receives and persists a batch of structured observability events "
        "uploaded from the Android app. No authentication required — events "
        "may be uploaded even when the user is logged out. "
        "PII has already been filtered by PiiFilter on the Android side."
    ),
)
async def ingest_events(
    body: IngestRequest,
    db: AsyncSession = Depends(get_db),
) -> IngestResponse:
    """Persist a batch of ObservabilityEvent records from the Android app.

    The Android ``ObservabilityUploadWorker`` calls this endpoint every 15 minutes
    with all events accumulated in the ``ObservabilityManager`` buffer.

    Events are stored in the ``observability_events`` PostgreSQL table and become
    available to the AI error analysis pipeline immediately after persistence.

    Phase 10 — AI Error Analysis
    """
    repo = ObservabilityEventRepository(db)

    # Convert Pydantic models → plain dicts for the repository
    event_dicts = [evt.model_dump() for evt in body.events]

    accepted = await repo.bulk_insert(event_dicts)

    logger.info(
        "observability/events: accepted=%d total=%d",
        accepted,
        len(body.events),
    )

    return IngestResponse(accepted=accepted, total=len(body.events))
