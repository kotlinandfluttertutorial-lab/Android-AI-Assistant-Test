# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/personas
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the personas domain
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

"""Personas router — /api/v1/personas/* endpoints.

Provides persona CRUD operations for authenticated users:

- ``POST  /api/v1/personas``            — create a new persona
- ``GET   /api/v1/personas``            — list accessible personas
- ``PUT   /api/v1/personas/{id}``       — update an existing persona
- ``DELETE /api/v1/personas/{id}``      — delete a persona

All endpoints require a valid ``Authorization: Bearer`` JWT.

Error codes:
- HTTP 404 — persona not found
- HTTP 403 — non-admin attempt to edit/delete an admin-locked persona
- HTTP 422 ``PROMPT_INJECTION_DETECTED`` — system_prompt failed injection check
- HTTP 422 ``PERSONA_LIMIT_REACHED`` — user already holds 20 personas

Requirements: 32.1, 32.2, 32.3, 32.5, 32.6, 32.8
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.personas import (
    PersonaCreate,
    PersonaListResponse,
    PersonaResponse,
    PersonaUpdate,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.persona_service import PersonaService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/personas",
    tags=["personas"],
    dependencies=[Depends(get_current_user)],
)

# Single shared service instance (stateless — safe to reuse across requests).
_persona_service = PersonaService()


# ---------------------------------------------------------------------------
# POST /api/v1/personas — create a persona
# ---------------------------------------------------------------------------


@router.post(
    "",
    response_model=PersonaResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a new AI persona",
)
async def create_persona(
    body: PersonaCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PersonaResponse:
    """Create a new persona owned by the authenticated user.

    - Validates ``system_prompt`` for prompt injection patterns (HTTP 422
      ``PROMPT_INJECTION_DETECTED`` on detection).
    - Enforces the 20-persona limit per user (HTTP 422 ``PERSONA_LIMIT_REACHED``
      when the limit is already reached).

    Requirements: 32.1, 32.3, 32.8
    """
    user_id = uuid.UUID(current_user.sub)
    user_role = current_user.role

    persona = await _persona_service.create_persona(
        user_id=user_id,
        user_role=user_role,
        data=body,
        db=db,
    )

    logger.info(
        "POST /api/v1/personas — created persona id=%s for user=%s",
        persona.id,
        current_user.sub,
    )
    return PersonaResponse.model_validate(persona)


# ---------------------------------------------------------------------------
# GET /api/v1/personas — list accessible personas
# ---------------------------------------------------------------------------


@router.get(
    "",
    response_model=PersonaListResponse,
    status_code=status.HTTP_200_OK,
    summary="List all accessible personas",
)
async def list_personas(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PersonaListResponse:
    """Return all personas accessible to the authenticated user.

    Includes the user's own personas plus any admin-locked personas permitted
    for the user's RBAC role.

    Requirements: 32.6
    """
    user_id = uuid.UUID(current_user.sub)
    user_role = current_user.role

    personas = await _persona_service.list_personas(
        user_id=user_id,
        user_role=user_role,
        db=db,
    )

    return PersonaListResponse(
        items=[PersonaResponse.model_validate(p) for p in personas],
        total=len(personas),
    )


# ---------------------------------------------------------------------------
# PUT /api/v1/personas/{persona_id} — update a persona
# ---------------------------------------------------------------------------


@router.put(
    "/{persona_id}",
    response_model=PersonaResponse,
    status_code=status.HTTP_200_OK,
    summary="Update an existing persona",
)
async def update_persona(
    persona_id: uuid.UUID,
    body: PersonaUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PersonaResponse:
    """Update an existing persona.

    - HTTP 404 when the persona does not exist.
    - HTTP 403 when a non-admin user attempts to edit an admin-locked persona.
    - HTTP 422 ``PROMPT_INJECTION_DETECTED`` when the new system_prompt fails
      the injection check.

    Requirements: 32.5, 32.8
    """
    user_id = uuid.UUID(current_user.sub)
    user_role = current_user.role

    persona = await _persona_service.update_persona(
        persona_id=persona_id,
        user_id=user_id,
        user_role=user_role,
        data=body,
        db=db,
    )

    logger.info(
        "PUT /api/v1/personas/%s — updated by user=%s", persona_id, current_user.sub
    )
    return PersonaResponse.model_validate(persona)


# ---------------------------------------------------------------------------
# DELETE /api/v1/personas/{persona_id} — delete a persona
# ---------------------------------------------------------------------------


@router.delete(
    "/{persona_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_class=Response,
    summary="Delete a persona",
)
async def delete_persona(
    persona_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Response:
    """Delete a persona.

    - HTTP 404 when the persona does not exist.
    - HTTP 403 when a non-admin user attempts to delete an admin-locked persona.

    Requirements: 32.5
    """
    user_id = uuid.UUID(current_user.sub)
    user_role = current_user.role

    await _persona_service.delete_persona(
        persona_id=persona_id,
        user_id=user_id,
        user_role=user_role,
        db=db,
    )

    logger.info(
        "DELETE /api/v1/personas/%s — deleted by user=%s", persona_id, current_user.sub
    )
    return Response(status_code=status.HTTP_204_NO_CONTENT)
