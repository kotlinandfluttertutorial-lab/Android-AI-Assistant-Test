# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/users
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the users domain
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

"""Users router — /users/* endpoints.

Implements GDPR data-privacy endpoints:
- POST /users/me/export   — enqueue async data-export job
- DELETE /users/me        — schedule permanent account deletion

Requirements: 28.1, 28.2
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.repositories.job_repository import JobRepository
from app.repositories.user_repository import UserRepository
from app.schemas.memory import PrivacyModeResponse, PrivacyModeUpdate
from app.schemas.users import (
    AccountDeletionRequest,
    AccountDeletionResponse,
    DataExportResponse,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/users", tags=["users"])


# ---------------------------------------------------------------------------
# POST /users/me/export
# ---------------------------------------------------------------------------


@router.post(
    "/me/export",
    response_model=DataExportResponse,
    status_code=status.HTTP_200_OK,
    summary="Request a full data export archive",
)
async def export_user_data(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DataExportResponse:
    """Enqueue an async Celery job that assembles a full JSON archive of all
    user data (conversations, messages, documents, memories, notes, todos,
    calendar events, reminders, habits).  The job ID is returned immediately;
    the archive is stored in ``job.result_payload`` within 24 hours.

    Requirements: 28.1
    """
    user_id = uuid.UUID(current_user.sub)

    # Create a Job row to track the export
    job_repo = JobRepository(db)
    job = await job_repo.create(user_id=user_id, job_type="data_export")
    await db.commit()

    # Dispatch the Celery task
    from app.workers.gdpr_worker import export_user_data_task

    export_user_data_task.delay(str(user_id), str(job.id))

    logger.info("Data export job %s enqueued for user %s", job.id, user_id)

    from datetime import timedelta

    estimated = (datetime.now(tz=timezone.utc) + timedelta(hours=24)).isoformat()

    return DataExportResponse(
        job_id=job.id,
        estimated_completion=estimated,
    )


# ---------------------------------------------------------------------------
# DELETE /users/me
# ---------------------------------------------------------------------------


@router.delete(
    "/me",
    response_model=AccountDeletionResponse,
    status_code=status.HTTP_200_OK,
    summary="Schedule permanent account deletion",
)
async def delete_user_account(
    body: AccountDeletionRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> AccountDeletionResponse:
    """Require email confirmation before permanently deleting the authenticated
    user's account.  Dispatches a Celery task that removes all PostgreSQL rows
    (via cascade) and all ChromaDB embeddings within 72 hours.

    Returns HTTP 400 when the supplied email does not match the account email.

    Requirements: 28.2
    """
    user_id = uuid.UUID(current_user.sub)

    # Look up the full user record so we can verify the email confirmation
    user_repo = UserRepository(db)
    user = await user_repo.get_by_id(user_id)

    if user is None:
        # Should not happen for a valid token, but guard defensively
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    # Case-insensitive email confirmation
    if body.email.strip().lower() != user.email.strip().lower():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email confirmation does not match your account email.",
        )

    # Dispatch the Celery task
    from app.workers.gdpr_worker import delete_user_data_task

    delete_user_data_task.delay(str(user_id))

    scheduled_at = datetime.now(tz=timezone.utc)
    estimated = (scheduled_at + timedelta(hours=72)).isoformat()

    logger.info("Account deletion scheduled for user %s at %s", user_id, scheduled_at)

    return AccountDeletionResponse(
        scheduled_at=scheduled_at,
        estimated_completion=estimated,
    )


# ---------------------------------------------------------------------------
# PATCH /users/me/privacy-mode
# ---------------------------------------------------------------------------


@router.patch(
    "/me/privacy-mode",
    response_model=PrivacyModeResponse,
    status_code=status.HTTP_200_OK,
    summary="Toggle memory capture on/off without deleting existing memories",
)
async def update_privacy_mode(
    body: PrivacyModeUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PrivacyModeResponse:
    """Toggle the authenticated user's privacy mode on or off.

    When ``privacy_mode`` is ``True``:
    - New memories are NOT captured for the duration of the session.
    - Existing memories are NOT deleted — they remain available for retrieval
      and prompt injection.

    When ``privacy_mode`` is ``False``:
    - Memory capture resumes normally.

    Args:
        body: The new privacy_mode value.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Returns:
        :class:`PrivacyModeResponse` confirming the updated state.

    Raises:
        HTTP 404 if the user cannot be found (should not happen with valid JWT).

    Requirements: 7.6
    """
    user_id = uuid.UUID(current_user.sub)

    from app.services.memory_service import MemoryService

    service = MemoryService(db)
    updated = await service.set_privacy_mode(
        user_id=user_id,
        privacy_mode=body.privacy_mode,
    )
    await db.commit()

    if not updated:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    state_word = "enabled" if body.privacy_mode else "disabled"
    logger.info("Privacy mode %s for user %s", state_word, user_id)

    return PrivacyModeResponse(
        privacy_mode=body.privacy_mode,
        message=(
            f"Privacy mode {state_word}. "
            + (
                "New memories will not be captured. Existing memories are preserved."
                if body.privacy_mode
                else "Memory capture has resumed."
            )
        ),
    )
