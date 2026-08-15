# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : document_repository.py
# Purpose : Database access layer for document entities
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

"""Database access layer for Document and DocumentChunk models.

All queries operate on the ``documents`` and ``document_chunks`` tables via the
SQLAlchemy async session.

Requirements: 4.1, 4.3, 4.4, 9.3
"""

from __future__ import annotations

import uuid

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.document import Document, IngestionStatus
from app.models.document_chunk import DocumentChunk


class DocumentRepository:
    """CRUD operations for the ``documents`` and ``document_chunks`` tables.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        file_name: str,
        mime_type: str,
        size_bytes: int,
        minio_key: str,
    ) -> Document:
        """Create a new Document row in ``pending`` status.

        Args:
            user_id: UUID of the owning user.
            file_name: Original filename as submitted.
            mime_type: Detected MIME type.
            size_bytes: File size in bytes.
            minio_key: MinIO object key (bucket-relative path).

        Returns:
            The newly created and flushed :class:`~app.models.document.Document`.
        """
        document = Document(
            user_id=user_id,
            file_name=file_name,
            mime_type=mime_type,
            size_bytes=size_bytes,
            minio_key=minio_key,
            ingestion_status=IngestionStatus.pending,
        )
        self._db.add(document)
        await self._db.flush()
        return document

    async def get_by_id(
        self,
        document_id: uuid.UUID,
        user_id: uuid.UUID | None = None,
    ) -> Document | None:
        """Fetch a document by primary key, optionally scoped to a user.

        Args:
            document_id: UUID of the document.
            user_id: When provided, adds a WHERE clause so users cannot access
                each other's documents.

        Returns:
            The :class:`~app.models.document.Document` or ``None`` if not found.
        """
        query = select(Document).where(Document.id == document_id)
        if user_id is not None:
            query = query.where(Document.user_id == user_id)
        result = await self._db.execute(query)
        return result.scalar_one_or_none()

    async def list_by_user(self, user_id: uuid.UUID) -> list[Document]:
        """Return all documents owned by the given user, newest first.

        Args:
            user_id: UUID of the user.

        Returns:
            List of :class:`~app.models.document.Document` rows.
        """
        result = await self._db.execute(
            select(Document).where(Document.user_id == user_id).order_by(Document.created_at.desc())
        )
        return list(result.scalars().all())

    async def update_status(
        self,
        document_id: uuid.UUID,
        status: IngestionStatus,
        page_count: int | None = None,
    ) -> Document | None:
        """Update the ingestion status (and optionally page_count) for a document.

        Args:
            document_id: UUID of the document.
            status: New :class:`~app.models.document.IngestionStatus`.
            page_count: Optionally update the page count (for PDF/DOCX).

        Returns:
            The updated document, or ``None`` if the document was not found.
        """
        document = await self.get_by_id(document_id)
        if document is None:
            return None
        document.ingestion_status = status
        if page_count is not None:
            document.page_count = page_count
        await self._db.flush()
        return document

    async def delete(self, document_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """Delete a document and its cascaded chunks.

        The ``document_chunks`` table has ``ondelete='CASCADE'`` so chunks are
        removed automatically by the database.

        Args:
            document_id: UUID of the document to delete.
            user_id: Owning user — ensures we only delete the caller's documents.

        Returns:
            ``True`` if the document was found and deleted, ``False`` otherwise.
        """
        document = await self.get_by_id(document_id, user_id=user_id)
        if document is None:
            return False
        await self._db.delete(document)
        await self._db.flush()
        return True

    # ------------------------------------------------------------------
    # DocumentChunk operations
    # ------------------------------------------------------------------

    async def create_chunk(
        self,
        *,
        document_id: uuid.UUID,
        chunk_index: int,
        page_number: int,
        content: str,
        chroma_id: str,
        citation_type: str = "page",
        char_offset_start: int | None = None,
        char_offset_end: int | None = None,
    ) -> DocumentChunk:
        """Persist a single DocumentChunk row.

        Args:
            document_id: UUID of the parent document.
            chunk_index: Zero-based position of this chunk within the document.
            page_number: 1-based source page number (1 for plain-text).
            content: Raw chunk text.
            chroma_id: ChromaDB document ID for the embedding vector.
            citation_type: ``"page"`` for PDF/DOCX; ``"char_offset"`` for TXT/MD.
            char_offset_start: Character offset of chunk start (TXT/MD only).
            char_offset_end: Character offset of chunk end (TXT/MD only).

        Returns:
            The newly created :class:`~app.models.document_chunk.DocumentChunk`.
        """
        chunk = DocumentChunk(
            document_id=document_id,
            chunk_index=chunk_index,
            page_number=page_number,
            content=content,
            chroma_id=chroma_id,
            citation_type=citation_type,
            char_offset_start=char_offset_start,
            char_offset_end=char_offset_end,
        )
        self._db.add(chunk)
        await self._db.flush()
        return chunk

    async def delete_chunks_by_document(self, document_id: uuid.UUID) -> int:
        """Delete all chunks for a given document.

        Returns:
            Number of deleted rows.
        """
        result = await self._db.execute(
            delete(DocumentChunk).where(DocumentChunk.document_id == document_id)
        )
        return result.rowcount  # type: ignore[return-value]
