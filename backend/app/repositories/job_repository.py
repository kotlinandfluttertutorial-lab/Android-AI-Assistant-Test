# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : job_repository.py
# Purpose : Database access layer for job entities
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Database access layer for the Job model.

All queries operate on the ``jobs`` table via the SQLAlchemy async session.
Workers call ``update_status`` to reflect task progress; the Android client
polls ``get_by_id`` via the REST API.

Requirements: 4.5, 9.3
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.job import Job, JobStatus


class JobRepository:
    """CRUD operations for the ``jobs`` table.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        job_type: str,
        celery_task_id: str | None = None,
    ) -> Job:
        """Create a new Job row with status ``queued``.

        Args:
            user_id: UUID of the owning user.
            job_type: Category string, e.g. ``'document_ingestion'``.
            celery_task_id: Celery task UUID assigned after dispatch (may be
                populated later by updating the row).

        Returns:
            The newly created and flushed :class:`~app.models.job.Job`.
        """
        job = Job(
            user_id=user_id,
            job_type=job_type,
            status=JobStatus.queued,
            celery_task_id=celery_task_id,
        )
        self._db.add(job)
        await self._db.flush()
        return job

    async def get_by_id(
        self,
        job_id: uuid.UUID,
        user_id: uuid.UUID | None = None,
    ) -> Job | None:
        """Fetch a job by primary key, optionally scoped to a user.

        Args:
            job_id: UUID of the job.
            user_id: When provided, restricts the query to the owning user so
                that users cannot poll each other's jobs.

        Returns:
            The :class:`~app.models.job.Job` or ``None`` if not found.
        """
        query = select(Job).where(Job.id == job_id)
        if user_id is not None:
            query = query.where(Job.user_id == user_id)
        result = await self._db.execute(query)
        return result.scalar_one_or_none()

    async def update_status(
        self,
        job_id: uuid.UUID,
        status: JobStatus,
        *,
        error_message: str | None = None,
        result_payload: dict[str, object] | None = None,
        celery_task_id: str | None = None,
    ) -> Job | None:
        """Update job status and optional metadata fields.

        Args:
            job_id: UUID of the job.
            status: New :class:`~app.models.job.JobStatus`.
            error_message: Error description when ``status='failed'``.
            result_payload: Structured result when ``status='completed'``.
            celery_task_id: Celery task UUID to record if not yet set.

        Returns:
            The updated :class:`~app.models.job.Job`, or ``None`` if not found.
        """
        job = await self.get_by_id(job_id)
        if job is None:
            return None
        job.status = status
        if error_message is not None:
            job.error_message = error_message
        if result_payload is not None:
            job.result_payload = result_payload
        if celery_task_id is not None:
            job.celery_task_id = celery_task_id
        await self._db.flush()
        return job
