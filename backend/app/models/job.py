# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : job.py
# Purpose : job — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""ORM model for the ``jobs`` table.

Long-running background operations are modelled as ``Job`` rows.  Celery
workers execute the actual work and update the row's ``status``,
``result_payload``, and ``error_message`` fields as the job progresses.

The Android client polls ``GET /jobs/{id}`` to track ingestion progress and
display status badges.

Job types
---------
- ``document_ingestion`` — RAG pipeline: extract → chunk → embed → store in ChromaDB.
- ``export``             — async export of a conversation to PDF or DOCX.
- ``email``              — background transactional email delivery.

Retry logic
-----------
``retry_count`` tracks how many times the Celery task has been retried.
Combined with Celery's ``max_retries`` setting this prevents infinite retry
loops on permanently failing jobs.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import enum
import uuid

from sqlalchemy import Enum, ForeignKey, Integer, String
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk


class JobStatus(str, enum.Enum):
    """Lifecycle state of a background job.

    The Android client polls GET /jobs/{job_id} for these values.  The spec
    requires: ``queued`` → ``processing`` → ``completed`` / ``failed``.

    ``running`` is kept as an internal alias for ``processing`` for backwards
    compatibility with existing Celery worker code; the API layer maps
    ``running`` → ``processing`` before returning responses.
    """

    queued = "queued"
    running = "running"  # internal; API maps this → "processing"
    processing = "processing"  # canonical API value (Requirement 4.11)
    completed = "completed"
    failed = "failed"


class Job(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a background Celery job."""

    __tablename__ = "jobs"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    job_type: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        index=True,
        comment="Job category, e.g. 'document_ingestion', 'export', 'email'",
    )
    status: Mapped[JobStatus] = mapped_column(
        Enum(JobStatus, name="job_status", create_type=True),
        nullable=False,
        default=JobStatus.queued,
        index=True,
    )
    celery_task_id: Mapped[str | None] = mapped_column(
        String(255),
        nullable=True,
        comment="Celery task UUID assigned when the job is dispatched to a worker",
    )
    result_payload: Mapped[dict | None] = mapped_column(
        JSONB,
        nullable=True,
        comment="Structured result returned by the worker on successful completion",
    )
    error_message: Mapped[str | None] = mapped_column(
        String(2048),
        nullable=True,
        comment="Human-readable error description populated when status='failed'",
    )
    retry_count: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        comment="Number of Celery retry attempts so far",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="jobs")  # noqa: F821

    def __repr__(self) -> str:
        return (
            f"<Job id={self.id!s} job_type={self.job_type!r} "
            f"status={self.status.value!r} retry_count={self.retry_count}>"
        )
