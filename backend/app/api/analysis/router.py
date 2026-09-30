"""AI Error Analysis endpoints — Phase 10.

Endpoints
---------
POST /analysis/errors              — Analyse recent error events (time window or all)
POST /analysis/errors/session      — Analyse all events for a specific session
GET  /analysis/errors/{event_id}   — Analyse the session context around one specific event

All endpoints require a valid JWT (authenticated user).

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.repositories.observability_event_repository import ObservabilityEventRepository
from app.schemas.error_analysis import AnalyseErrorRequest, ErrorAnalysisResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.error_analysis_service import ErrorAnalysisService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/analysis",
    tags=["analysis"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# POST /analysis/errors
# ---------------------------------------------------------------------------


@router.post(
    "/errors",
    response_model=ErrorAnalysisResponse,
    summary="Analyse recent application errors using AI",
    description=(
        "Runs the full Phase 10 AI error analysis pipeline:\n\n"
        "1. **Collect evidence** — recent ERROR/CRITICAL ObservabilityEvents from "
        "the Android app (uploaded every 15 min by WorkManager).\n"
        "2. **Retrieve runbooks** — RAG search against the devops_knowledge "
        "ChromaDB collection for relevant operational procedures.\n"
        "3. **Retrieve incidents** — RAG search for similar historical incidents.\n"
        "4. **LLM reasoning** — combines evidence + knowledge into a structured "
        "root cause analysis with confidence score.\n"
        "5. **AI safety gate** — confidence < 0.6 triggers a manual investigation warning.\n\n"
        "The recommended fix is a SUGGESTION only. No automated production action "
        "is taken without explicit human approval."
    ),
)
async def analyse_errors(
    request: AnalyseErrorRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ErrorAnalysisResponse:
    """Run the full AI error analysis pipeline.

    Accepts three modes via the request body:

    **Mode 1 — specific event:**
    ```json
    {"event_id": "uuid-of-event"}
    ```
    Fetches the event and all other events from the same session (last 60 min).

    **Mode 2 — specific session:**
    ```json
    {"session_id": "uuid-of-session", "lookback_minutes": 60}
    ```
    Fetches all ERROR/CRITICAL events for that session.

    **Mode 3 — recent errors (default):**
    ```json
    {"lookback_minutes": 30}
    ```
    Fetches all ERROR/CRITICAL events across all sessions in the last N minutes.

    Phase 10 — AI Error Analysis
    """
    logger.info(
        "analysis/errors: user=%s event_id=%s session_id=%s lookback=%dm",
        current_user.sub,
        request.event_id,
        request.session_id,
        request.lookback_minutes,
    )

    try:
        service = ErrorAnalysisService(db)
        result = await service.analyse(request)
        return result

    except Exception as exc:
        logger.error("analysis/errors: unexpected error — %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=(
                "AI error analysis failed unexpectedly. "
                "Check application logs for details."
            ),
        ) from exc


# ---------------------------------------------------------------------------
# POST /analysis/errors/session
# ---------------------------------------------------------------------------


@router.post(
    "/errors/session",
    response_model=ErrorAnalysisResponse,
    summary="Analyse all events for a specific app session",
    description=(
        "Shortcut endpoint — equivalent to POST /analysis/errors with "
        "session_id in the request body. Useful when the caller already knows "
        "the session ID (e.g. from an Android crash report)."
    ),
)
async def analyse_session(
    session_id: str,
    lookback_minutes: int = 60,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ErrorAnalysisResponse:
    """Analyse all error events for a specific Android app session.

    Args:
        session_id:       The Android app session UUID.
        lookback_minutes: Time window to look back (default 60 minutes).

    Phase 10 — AI Error Analysis
    """
    request = AnalyseErrorRequest(
        session_id=session_id,
        lookback_minutes=lookback_minutes,
    )

    try:
        service = ErrorAnalysisService(db)
        return await service.analyse(request)
    except Exception as exc:
        logger.error("analysis/errors/session: unexpected error — %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="AI error analysis failed unexpectedly.",
        ) from exc


# ---------------------------------------------------------------------------
# GET /analysis/errors/{event_id}
# ---------------------------------------------------------------------------


@router.get(
    "/errors/{event_id}",
    response_model=ErrorAnalysisResponse,
    summary="Analyse the session context around a specific event",
    description=(
        "Fetches a single ``ObservabilityEvent`` by its UUID and runs the full "
        "Phase 10 AI error analysis pipeline against it and all other events "
        "from the same session (60-minute look-back window).\n\n"
        "This endpoint is designed for:\n"
        "- Drilling into a specific error from the Android crash reporter\n"
        "- Investigating one particular event that the dashboard flagged\n"
        "- Re-running analysis for an event already known to the caller\n\n"
        "Returns **404** if the event UUID does not exist in the database.\n\n"
        "The recommended fix is a SUGGESTION only — no automated action is taken "
        "without explicit human approval."
    ),
)
async def analyse_event(
    event_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ErrorAnalysisResponse:
    """Run AI error analysis centred on a single known ObservabilityEvent.

    The pipeline fetches the event and its full session context (all events
    from the same ``session_id`` within the last 60 minutes) then runs the
    same 8-step analysis pipeline as ``POST /analysis/errors``.

    Args:
        event_id: UUID of the ``ObservabilityEvent`` to analyse.

    Returns:
        :class:`ErrorAnalysisResponse` with root cause, confidence score,
        facts/inference separation, and recommended fix.

    Raises:
        404: If the event_id does not exist in the database.
        500: If the analysis pipeline raises an unexpected error.

    Phase 10 — AI Error Analysis
    """
    logger.info(
        "analysis/errors/%s: triggered by user=%s",
        event_id,
        current_user.sub,
    )

    # Verify the event exists before running the (potentially expensive) pipeline
    repo = ObservabilityEventRepository(db)
    event = await repo.get_by_id(event_id)
    if event is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"ObservabilityEvent {event_id} not found.",
        )

    request = AnalyseErrorRequest(event_id=str(event_id))

    try:
        service = ErrorAnalysisService(db)
        return await service.analyse(request)
    except Exception as exc:
        logger.error(
            "analysis/errors/%s: unexpected error — %s", event_id, exc, exc_info=True
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="AI error analysis failed unexpectedly.",
        ) from exc
