# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : document.py
# Purpose : document — models module
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

"""ORM model for the ``documents`` table.

A ``Document`` represents a file uploaded by a user for RAG (Retrieval-Augmented
Generation) processing.  The file itself is stored in MinIO (object storage);
this row tracks the metadata and ingestion pipeline state.

Ingestion lifecycle
-------------------
1. ``pending``    — row created, file written to MinIO, Celery job queued.
2. ``processing`` — Celery worker is extracting text and chunking.
3. ``ready``      — all ``DocumentChunk`` rows written, vectors indexed in ChromaDB.
4. ``failed``     — an unrecoverable error occurred during ingestion; the user can
                    re-upload or request re-processing.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Enum, ForeignKey, Integer, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk


class IngestionStatus(str, enum.Enum):
    """Lifecycle state of a document through the RAG ingestion pipeline."""

    pending = "pending"
    processing = "processing"
    ready = "ready"
    failed = "failed"


class Document(Base):
    """SQLAlchemy ORM model representing an uploaded document.

    Only ``created_at`` is tracked (documents cannot be edited, only deleted and
    re-uploaded), so ``TimestampMixin`` is not used here.
    """

    __tablename__ = "documents"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    file_name: Mapped[str] = mapped_column(String(512), nullable=False)
    mime_type: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        comment="MIME type, e.g. 'application/pdf', 'text/plain'",
    )
    size_bytes: Mapped[int] = mapped_column(
        BigInteger,
        nullable=False,
        comment="File size in bytes; enforced ≤ 50 MB by the upload endpoint",
    )
    minio_key: Mapped[str] = mapped_column(
        String(1024),
        nullable=False,
        comment="MinIO object key (path within the configured bucket)",
    )
    ingestion_status: Mapped[IngestionStatus] = mapped_column(
        Enum(IngestionStatus, name="ingestion_status", create_type=True),
        nullable=False,
        default=IngestionStatus.pending,
        index=True,
    )
    page_count: Mapped[int | None] = mapped_column(
        Integer,
        nullable=True,
        comment="Number of pages (PDF/DOCX); NULL for plain-text files",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="documents")  # noqa: F821
    chunks: Mapped[list[DocumentChunk]] = relationship(  # noqa: F821
        "DocumentChunk", back_populates="document", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return (
            f"<Document id={self.id!s} file_name={self.file_name!r} "
            f"status={self.ingestion_status.value!r}>"
        )
