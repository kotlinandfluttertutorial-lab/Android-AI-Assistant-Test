# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : document_chunk.py
# Purpose : document_chunk — models module
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

"""ORM model for the ``document_chunks`` table.

After a ``Document`` has been ingested, the RAG pipeline splits it into
fixed-size text chunks (512 tokens with 64-token overlap by default).  Each
chunk is stored here alongside its ChromaDB vector ID for later retrieval.

Retrieval flow
--------------
When a user submits a RAG query, the ``RAGService`` calls ChromaDB with the
query embedding, retrieves the top-K ``chroma_id`` values, then fetches these
rows to obtain the raw text and page/document metadata for the cited response.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk


class DocumentChunk(Base):
    """SQLAlchemy ORM model representing one chunk of an ingested document."""

    __tablename__ = "document_chunks"

    id: Mapped[uuid.UUID] = uuid_pk()
    document_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("documents.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    chunk_index: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        comment="Zero-based position of this chunk within the document",
    )
    page_number: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        comment="1-based page number where this chunk originates; 1 for plain-text",
    )
    content: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="Raw extracted text of the chunk (up to RAG_CHUNK_SIZE tokens)",
    )
    chroma_id: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        index=True,
        comment="ChromaDB document ID used to look up the corresponding embedding vector",
    )
    citation_type: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default="page",
        comment="Citation reference type: 'page' for PDF/DOCX; 'char_offset' for TXT/MD",
    )
    char_offset_start: Mapped[int | None] = mapped_column(
        Integer,
        nullable=True,
        comment="Character offset of the start of the chunk within the source text (TXT/MD only)",
    )
    char_offset_end: Mapped[int | None] = mapped_column(
        Integer,
        nullable=True,
        comment="Character offset of the end of the chunk within the source text (TXT/MD only)",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    document: Mapped[Document] = relationship(
        "Document", back_populates="chunks"
    )  # noqa: F821

    def __repr__(self) -> str:
        return (
            f"<DocumentChunk id={self.id!s} document_id={self.document_id!s} "
            f"chunk_index={self.chunk_index} page={self.page_number}>"
        )
