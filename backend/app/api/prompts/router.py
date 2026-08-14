# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/prompts
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the prompts domain
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

"""Prompts router — /prompts/* endpoints (admin-only).

Provides versioned management of system prompt templates:

- ``GET  /prompts``                      — list all distinct template names
- ``GET  /prompts/{name}``               — get the currently active version
- ``GET  /prompts/{name}/history``       — list all versions (full audit trail)
- ``PATCH /prompts/{name}``              — create a new version (content update)
- ``POST /prompts/{name}/rollback``      — restore content from a prior version

All endpoints require a valid ``Authorization: Bearer`` JWT.  Admin role is
enforced per-endpoint so the role check is explicit and auditable.

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.prompts import (
    PromptRollbackResponse,
    PromptTemplateListResponse,
    PromptTemplateResponse,
    PromptTemplateUpdateRequest,
    PromptVersionHistoryResponse,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.prompt_service import PromptService, TemplateNotFoundError

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/prompts",
    tags=["prompts"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _require_admin(current_user: TokenPayload) -> TokenPayload:
    """Raise HTTP 403 if the authenticated user is not an admin.

    Args:
        current_user: The validated JWT payload from ``get_current_user``.

    Returns:
        The same ``current_user`` when the role check passes.

    Raises:
        HTTPException: HTTP 403 when ``current_user.role != "admin"``.
    """
    if current_user.role != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Admin role required to manage prompt templates.",
        )
    return current_user


# ---------------------------------------------------------------------------
# GET /prompts — list all distinct template names
# ---------------------------------------------------------------------------


@router.get(
    "",
    response_model=PromptTemplateListResponse,
    status_code=status.HTTP_200_OK,
    summary="List all prompt template names",
)
async def list_prompt_names(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PromptTemplateListResponse:
    """Return a sorted list of all distinct logical prompt template names.

    Requirements: 25.1
    """
    _require_admin(current_user)
    names = await PromptService.list_template_names(db)
    return PromptTemplateListResponse(names=names, total=len(names))


# ---------------------------------------------------------------------------
# GET /prompts/{name} — get the active version
# ---------------------------------------------------------------------------


@router.get(
    "/{name}",
    response_model=PromptTemplateResponse,
    status_code=status.HTTP_200_OK,
    summary="Get the currently active version of a prompt template",
)
async def get_current_prompt(
    name: str,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PromptTemplateResponse:
    """Return the active version for the named template.

    Requirements: 25.1
    """
    _require_admin(current_user)
    try:
        template = await PromptService.get_current_template(db, name)
    except TemplateNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc
    return PromptTemplateResponse.model_validate(template)


# ---------------------------------------------------------------------------
# GET /prompts/{name}/history — list all versions
# ---------------------------------------------------------------------------


@router.get(
    "/{name}/history",
    response_model=PromptVersionHistoryResponse,
    status_code=status.HTTP_200_OK,
    summary="List all versions of a named prompt template",
)
async def get_prompt_history(
    name: str,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PromptVersionHistoryResponse:
    """Return the complete version history for the named template.

    Versions are ordered by version number ascending so the caller sees the
    chronological progression from v1 to the current version.

    Requirements: 25.1
    """
    _require_admin(current_user)
    versions = await PromptService.list_versions(db, name)
    if not versions:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No versions found for template name={name!r}",
        )
    return PromptVersionHistoryResponse(
        name=name,
        versions=[PromptTemplateResponse.model_validate(v) for v in versions],
        total=len(versions),
    )


# ---------------------------------------------------------------------------
# PATCH /prompts/{name} — create a new version
# ---------------------------------------------------------------------------


@router.patch(
    "/{name}",
    response_model=PromptTemplateResponse,
    status_code=status.HTTP_200_OK,
    summary="Create a new version of a named prompt template",
)
async def update_prompt(
    name: str,
    body: PromptTemplateUpdateRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PromptTemplateResponse:
    """Create a new version for the named template with the provided content.

    The previous active version is preserved in the history but deactivated.
    The new version becomes the sole active version for ``name``.

    Requirements: 25.1
    """
    _require_admin(current_user)
    author_id = uuid.UUID(current_user.sub)
    new_template = await PromptService.create_version(
        db=db,
        name=name,
        content=body.content,
        author_id=author_id,
    )
    logger.info(
        "Admin %s created prompt template version %d for name=%r",
        current_user.sub,
        new_template.version,
        name,
    )
    return PromptTemplateResponse.model_validate(new_template)


# ---------------------------------------------------------------------------
# POST /prompts/{name}/rollback — restore a prior version
# ---------------------------------------------------------------------------


@router.post(
    "/{name}/rollback",
    response_model=PromptRollbackResponse,
    status_code=status.HTTP_200_OK,
    summary="Roll back a named prompt template to a specific version",
)
async def rollback_prompt(
    name: str,
    version: int = Query(
        ...,
        alias="version",
        ge=1,
        description="Version number to restore. Must be a positive integer.",
    ),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PromptRollbackResponse:
    """Restore the content of a named template to what it was at ``version``.

    This is a non-destructive operation: a new version row is created whose
    content is a copy of the requested historical version.  The full history
    (all prior rows) remains intact and unmodified.

    Requirements: 25.2
    """
    _require_admin(current_user)
    try:
        restored = await PromptService.rollback(
            db=db,
            name=name,
            version_number=version,
        )
    except TemplateNotFoundError as exc:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=str(exc),
        ) from exc

    logger.info(
        "Admin %s rolled back prompt template name=%r to version %d (new version=%d)",
        current_user.sub,
        name,
        version,
        restored.version,
    )
    return PromptRollbackResponse(
        message=(
            f"Template '{name}' rolled back to version {version} content. "
            f"New active version is {restored.version}."
        ),
        template=PromptTemplateResponse.model_validate(restored),
    )
