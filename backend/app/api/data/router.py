# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/data
# File    : router.py
# Purpose : Data privacy endpoints — export and account deletion
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - GDPR-compliant data portability (POST /data/export)
#   - Right-to-erasure (DELETE /data/account)
#   - Celery background tasks for async processing
#   - Parameterized queries via SQLAlchemy ORM (no raw string interpolation)
#   - AES-256 encryption at rest for all LLM API keys (see security/encryption.py)
#
# Design Decisions:
#   - Endpoints live at /api/v1/data/ as a dedicated data-privacy namespace
#   - Export and delete operations are always async (Celery); responses are
#     immediate with an estimated completion time so the API stays fast
#   - Email address confirmation is required before deletion to prevent
#     accidental or malicious account erasure
#   - Context-aware output encoding: all responses go through Pydantic v2
#     schemas so user-supplied strings are type-validated before serialisation
#     and FastAPI's JSONResponse applies JSON-safe encoding automatically
#
# Requirements: 9.2, 9.10, 28.1, 28.2, 28.4, 28.5
# ============================================================

"""Data privacy router — /api/v1/data/* endpoints.

Endpoint summary
----------------
POST   /api/v1/data/export    — enqueue a full JSON data-export job (Req 28.1)
DELETE /api/v1/data/account   — schedule permanent account deletion (Req 28.2)

Security notes
--------------
- All endpoints require a valid JWT (authenticated users only).
- Parameterized queries are enforced via SQLAlchemy ORM throughout; no raw
  SQL string interpolation is used in this module (Req 9.2).
- LLM provider API keys are stored AES-256 encrypted; they are NEVER returned
  in any response or written to logs (Req 9.10).
- All response bodies are produced by Pydantic v2 ``model_validate`` / FastAPI
  serialisation, which provides context-aware output encoding (Req 9.2):
    - Strings are JSON-escaped automatically.
    - UUID, datetime, and Decimal types are serialised to their standard string
      representations, not raw Python reprs.
    - No user-supplied content is reflected back unencoded.

Requirements: 9.2, 9.10, 28.1, 28.2, 28.4, 28.5
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.repositories.job_repository import JobRepository
from app.repositories.user_repository import UserRepository
from app.schemas.users import (
    AccountDeletionRequest,
    AccountDeletionResponse,
    DataExportResponse,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/data",
    tags=["data-privacy"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# POST /api/v1/data/export
# ---------------------------------------------------------------------------


@router.post(
    "/export",
    response_model=DataExportResponse,
    status_code=status.HTTP_200_OK,
    summary="Request a full JSON data export archive",
    description=(
        "Enqueue an async Celery job that assembles a full JSON archive of all "
        "the authenticated user's data (conversations, messages, documents, "
        "memories, notes, todos, calendar events, reminders, habits). The job "
        "ID is returned immediately. The archive is stored in the job result "
        "payload within 24 hours. If the export is not ready within 24 hours, "
        "the user is notified via push notification."
    ),
)
async def export_user_data(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DataExportResponse:
    """Enqueue a GDPR data export job and return the tracking job ID.

    All database queries in this endpoint use the SQLAlchemy ORM which
    automatically applies parameterized queries — no raw SQL interpolation.

    Args:
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session (parameterized queries only).

    Returns:
        :class:`DataExportResponse` with ``job_id`` and estimated completion.

    Requirements: 28.1, 9.2
    """
    user_id = uuid.UUID(current_user.sub)

    # Parameterized ORM insert — no raw SQL
    job_repo = JobRepository(db)
    job = await job_repo.create(user_id=user_id, job_type="data_export")
    await db.commit()

    # Dispatch background Celery task
    from app.workers.gdpr_worker import export_user_data_task

    export_user_data_task.delay(str(user_id), str(job.id))

    logger.info("data/export: enqueued export job=%s for user=%s", job.id, user_id)

    estimated = (datetime.now(tz=UTC) + timedelta(hours=24)).isoformat()

    # DataExportResponse uses Pydantic v2 — all fields are type-validated and
    # JSON-encoded before being returned; no user-supplied content is reflected
    # back without encoding (Req 9.2 context-aware output encoding).
    return DataExportResponse(
        job_id=job.id,
        estimated_completion=estimated,
    )


# ---------------------------------------------------------------------------
# DELETE /api/v1/data/account
# ---------------------------------------------------------------------------


@router.delete(
    "/account",
    response_model=AccountDeletionResponse,
    status_code=status.HTTP_200_OK,
    summary="Schedule permanent account deletion",
    description=(
        "Permanently removes all of the authenticated user's data — including "
        "PostgreSQL records and all ChromaDB embeddings — within 72 hours. "
        "The caller must supply their email address as an explicit confirmation "
        "step. If deletion is not completed within 72 hours, the user and an "
        "Admin are notified via push notification."
    ),
)
async def delete_user_account(
    body: AccountDeletionRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> AccountDeletionResponse:
    """Require email confirmation, then schedule permanent account deletion.

    All database queries in this endpoint use the SQLAlchemy ORM which
    automatically applies parameterized queries — no raw SQL interpolation.

    Args:
        body: JSON body containing the user's email address as confirmation.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session (parameterized queries only).

    Returns:
        :class:`AccountDeletionResponse` with scheduled timestamp and
        estimated completion.

    Raises:
        HTTP 400: When the supplied email does not match the account email.
        HTTP 404: When the user record cannot be found (defensive guard).

    Requirements: 28.2, 9.2
    """
    user_id = uuid.UUID(current_user.sub)

    # Parameterized ORM select — no raw SQL
    user_repo = UserRepository(db)
    user = await user_repo.get_by_id(user_id)

    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found.",
        )

    # Case-insensitive email confirmation to prevent accidental deletion
    if body.email.strip().lower() != user.email.strip().lower():
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Email confirmation does not match your account email.",
        )

    # Dispatch background Celery task
    from app.workers.gdpr_worker import delete_user_data_task

    delete_user_data_task.delay(str(user_id))

    scheduled_at = datetime.now(tz=UTC)
    estimated = (scheduled_at + timedelta(hours=72)).isoformat()

    logger.info(
        "data/account: deletion scheduled for user=%s at %s",
        user_id,
        scheduled_at,
    )

    # AccountDeletionResponse uses Pydantic v2 — context-aware output encoding
    # is applied automatically before the JSON response is returned (Req 9.2).
    return AccountDeletionResponse(
        scheduled_at=scheduled_at,
        estimated_completion=estimated,
    )
