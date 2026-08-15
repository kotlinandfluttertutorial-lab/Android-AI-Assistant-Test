# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/usage
# File    : router.py
# Purpose : FastAPI router for AI Cost Dashboard endpoints
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router with JWT authentication
#
# Key Concepts:
#   - All endpoints are scoped strictly to the authenticated user (Req 34.7)
#   - HTTP 403 when a user attempts to access another user's data (Req 34.7)
#   - HTTP 422 when the 4th spending alert is attempted (Req 34.4)
#   - Response must be within 2 seconds (Req 34.2)
#
# Dependencies:
#   - app.services.cost_service
#   - app.schemas.usage
#   - app.security.dependencies
#   - app.database (AsyncSession)
# ============================================================

"""AI Cost Dashboard API router.

Endpoints:
- GET  /usage/cost                — aggregated token usage + estimated cost (90 days)
- GET  /usage/alerts              — list spending alerts for the authenticated user
- POST /usage/alerts              — create a spending alert threshold
- DELETE /usage/alerts/{alert_id} — remove a spending alert by ID

All endpoints enforce JWT-based per-user scoping: the authenticated user's
``sub`` claim is used as the ``user_id`` for every query; no cross-user data
is ever returned (Requirement 34.7).

Requirements: 34.1, 34.2, 34.4, 34.7
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.usage import (
    CostSummaryResponse,
    DailyCostRowSchema,
    SpendingAlertCreateRequest,
    SpendingAlertDeleteResponse,
    SpendingAlertListResponse,
    SpendingAlertResponse,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services import cost_service

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/usage",
    tags=["usage"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _current_user_id(current_user: TokenPayload) -> uuid.UUID:
    """Extract and return the authenticated user's UUID from the JWT payload.

    Args:
        current_user: Validated JWT payload injected by :func:`get_current_user`.

    Returns:
        UUID of the authenticated user.
    """
    return uuid.UUID(current_user.sub)


def _assert_no_foreign_user(
    claimed_user_id: uuid.UUID | None,
    authenticated_user_id: uuid.UUID,
) -> None:
    """Raise HTTP 403 when *claimed_user_id* is set and differs from *authenticated_user_id*.

    This guard implements the requirement that any request that explicitly
    includes a foreign user's identifier MUST be rejected with 403 rather than
    returning that user's data (Requirement 34.7).

    Args:
        claimed_user_id:       User ID from the request body/query param (if any).
        authenticated_user_id: User ID from the validated JWT.

    Raises:
        :class:`fastapi.HTTPException`: HTTP 403 if the IDs do not match.
    """
    if claimed_user_id is not None and claimed_user_id != authenticated_user_id:
        logger.warning(
            "Per-user cost data isolation violation attempt: authenticated=%s claimed=%s",
            authenticated_user_id,
            claimed_user_id,
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Access to another user's cost data is forbidden.",
        )


# ---------------------------------------------------------------------------
# GET /usage/cost
# ---------------------------------------------------------------------------


@router.get(
    "/cost",
    response_model=CostSummaryResponse,
    summary="Get aggregated AI cost dashboard data",
    description=(
        "Returns token usage and estimated cost (USD) broken down by AI feature, "
        "LLM provider, and calendar day for the last 90 days. "
        "Scoped strictly to the authenticated user; HTTP 403 if a foreign user_id "
        "query parameter is supplied."
    ),
    responses={
        200: {"description": "Aggregated cost summary for the authenticated user"},
        401: {"description": "JWT absent, expired, or invalid"},
        403: {"description": "Request included another user's identifier"},
    },
)
async def get_cost_summary(
    user_id: uuid.UUID | None = None,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> CostSummaryResponse:
    """Aggregated token usage and estimated cost for the authenticated user.

    The optional ``user_id`` query parameter is accepted ONLY so the endpoint
    can detect and reject requests that include a foreign user's identifier
    (Requirement 34.7). When ``user_id`` is absent, the authenticated user's ID
    is used automatically.

    Requirements: 34.1, 34.2, 34.7
    """
    auth_user_id = _current_user_id(current_user)

    # Reject if the caller tried to request another user's data
    _assert_no_foreign_user(user_id, auth_user_id)

    summary = await cost_service.get_user_cost_summary(db=db, user_id=auth_user_id)

    return CostSummaryResponse(
        total_input_tokens=summary.total_input_tokens,
        total_output_tokens=summary.total_output_tokens,
        total_cost_usd=summary.total_cost_usd,
        rows=[
            DailyCostRowSchema(
                feature=row.feature,
                provider=row.provider,
                day=row.day,
                input_tokens=row.input_tokens,
                output_tokens=row.output_tokens,
                cost_usd=row.cost_usd,
            )
            for row in summary.rows
        ],
        window_days=90,
    )


# ---------------------------------------------------------------------------
# GET /usage/alerts
# ---------------------------------------------------------------------------


@router.get(
    "/alerts",
    response_model=SpendingAlertListResponse,
    summary="List spending alert thresholds for the authenticated user",
    responses={
        200: {"description": "List of spending alerts (max 3)"},
        401: {"description": "JWT absent, expired, or invalid"},
    },
)
async def list_alerts(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SpendingAlertListResponse:
    """Return all spending alerts owned by the authenticated user.

    Requirements: 34.4
    """
    user_id = _current_user_id(current_user)
    dtos = await cost_service.list_spending_alerts(db=db, user_id=user_id)

    return SpendingAlertListResponse(
        alerts=[
            SpendingAlertResponse(
                id=dto.id,
                user_id=dto.user_id,
                threshold_usd=dto.threshold_usd,
                is_triggered=dto.is_triggered,
                triggered_at=dto.triggered_at,
                dismissed_at=dto.dismissed_at,
                created_at=dto.created_at,
            )
            for dto in dtos
        ]
    )


# ---------------------------------------------------------------------------
# POST /usage/alerts
# ---------------------------------------------------------------------------


@router.post(
    "/alerts",
    response_model=SpendingAlertResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a new spending alert threshold",
    description=(
        "Create a spending alert that fires an in-app notification when the user's "
        "accumulated daily cost reaches the threshold. "
        "Minimum threshold: $0.01. Maximum threshold: $999.99. "
        "Maximum 3 alerts per user; returns HTTP 422 on the 4th attempt."
    ),
    responses={
        201: {"description": "Alert created successfully"},
        401: {"description": "JWT absent, expired, or invalid"},
        422: {"description": "Validation error: threshold out of range or 4th alert attempt"},
    },
)
async def create_alert(
    body: SpendingAlertCreateRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SpendingAlertResponse:
    """Create a new spending alert for the authenticated user.

    Enforces:
    - threshold_usd in [$0.01, $999.99]
    - max 3 alerts per user (HTTP 422 on 4th attempt)

    Requirements: 34.4
    """
    user_id = _current_user_id(current_user)

    try:
        dto = await cost_service.create_spending_alert(
            db=db,
            user_id=user_id,
            threshold_usd=body.threshold_usd,
        )
        await db.commit()
    except ValueError as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(exc),
        ) from exc

    return SpendingAlertResponse(
        id=dto.id,
        user_id=dto.user_id,
        threshold_usd=dto.threshold_usd,
        is_triggered=dto.is_triggered,
        triggered_at=dto.triggered_at,
        dismissed_at=dto.dismissed_at,
        created_at=dto.created_at,
    )


# ---------------------------------------------------------------------------
# DELETE /usage/alerts/{alert_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/alerts/{alert_id}",
    response_model=SpendingAlertDeleteResponse,
    summary="Delete a spending alert threshold by ID",
    description=(
        "Removes the specified spending alert. Only the owner may delete their own alerts; "
        "attempting to delete another user's alert returns HTTP 404 (the ID simply "
        "does not exist from the requester's perspective)."
    ),
    responses={
        200: {"description": "Alert deleted (deleted=True) or not found (deleted=False)"},
        401: {"description": "JWT absent, expired, or invalid"},
    },
)
async def delete_alert(
    alert_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SpendingAlertDeleteResponse:
    """Delete the spending alert identified by *alert_id*.

    The service layer enforces ownership by filtering on both ``id`` and
    ``user_id``, so a user can only ever delete their own alerts.

    Requirements: 34.4
    """
    user_id = _current_user_id(current_user)

    deleted = await cost_service.delete_spending_alert(
        db=db,
        user_id=user_id,
        alert_id=alert_id,
    )
    if deleted:
        await db.commit()

    return SpendingAlertDeleteResponse(deleted=deleted, alert_id=alert_id)
