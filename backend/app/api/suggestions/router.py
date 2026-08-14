# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/suggestions
# File    : router.py
# Purpose : FastAPI router for context-aware AI suggestion endpoints
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Suggestions router — /api/v1/suggestions/* endpoints.

Provides context-aware AI suggestion generation:

- ``POST /api/v1/suggestions/context`` — return 0–3 AI suggestions based on
  the current screen context sent by the Android client.

All endpoints require a valid ``Authorization: Bearer`` JWT.

The endpoint always returns HTTP 200 — timeouts, AI errors, and privacy mode
all result in an empty suggestions list rather than an error response.

Requirements: 33.1, 33.2, 33.6, 33.7
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.suggestions import ScreenContextPayload, SuggestionsResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.suggestions_service import SuggestionsService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/suggestions",
    tags=["suggestions"],
    dependencies=[Depends(get_current_user)],
)

# Single shared service instance (stateless — safe to reuse across requests).
_suggestions_service = SuggestionsService()


# ---------------------------------------------------------------------------
# POST /api/v1/suggestions/context — generate context-aware suggestions
# ---------------------------------------------------------------------------


@router.post(
    "/context",
    response_model=SuggestionsResponse,
    status_code=200,
    summary="Get context-aware AI suggestions for the current screen",
)
async def get_context_suggestions(
    body: ScreenContextPayload,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SuggestionsResponse:
    """Return 0–3 AI-generated suggestions tailored to the user's current screen.

    Accepts a screen context payload (notes / calendar / chat) and returns
    relevant AI action suggestions. The response is always HTTP 200:
    - An empty list is returned when the user's privacy_mode is True (Req 33.7).
    - An empty list is returned when the AI call exceeds 3 seconds (Req 33.6).
    - An empty list is returned when the AI response cannot be parsed.

    Requirements: 33.1, 33.2, 33.6, 33.7
    """
    user_id = uuid.UUID(current_user.sub)

    suggestions = await _suggestions_service.get_context_suggestions(
        user_id=user_id,
        payload=body,
        db=db,
    )

    logger.debug(
        "POST /api/v1/suggestions/context — user=%s screen_type=%s suggestions_count=%d",
        current_user.sub,
        body.screen_type.value,
        len(suggestions),
    )

    return SuggestionsResponse(suggestions=suggestions)
