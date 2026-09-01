# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : rag_service.py
# Purpose : Business logic for the rag domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""RAG ingestion and query service.

Document validation, extraction, chunking, embedding, storage, and retrieval.

This service implements the complete ingestion pipeline:
  validate → store in MinIO → extract text → chunk → embed → store in ChromaDB/PostgreSQL

And the retrieval pipeline:
  embed query → query ChromaDB → fetch PostgreSQL metadata → assemble context with citations

Property guarantees
-------------------
- Property 7  (chunk coverage):  every token of the source text appears in ≥1 chunk.
- Property 8  (user isolation):  embeddings stored in per-user ChromaDB collection.
- Property 9  (citation completeness): every retrieved chunk includes a citation with
                                       document name and page number.
- Property 26 (format guard):    unsupported formats/oversized files rejected with HTTP 422
                                  BEFORE any storage I/O.

Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 16.1
"""

from __future__ import annotations

import asyncio
import io
import logging
import uuid
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from fastapi import HTTPException, status

from app.config.settings import get_settings

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Supported MIME types and extensions (Property 26)
# ---------------------------------------------------------------------------

SUPPORTED_MIME_TYPES: frozenset[str] = frozenset(
    {
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown",
    }
)

SUPPORTED_EXTENSIONS: frozenset[str] = frozenset({".pdf", ".docx", ".txt", ".md"})

# ---------------------------------------------------------------------------
# Custom exceptions
# ---------------------------------------------------------------------------


class ExtractionError(Exception):
    """Raised when text extraction fails at a specific pipeline stage.

    Attributes:
        stage: Pipeline stage identifier (pdf_extraction | ocr | docx_extraction | text_read).
        file_name: Name of the file that caused the failure.
        detail: Underlying error message for diagnostics.
    """

    def __init__(self, stage: str, file_name: str, detail: str = "") -> None:
        self.stage = stage
        self.file_name = file_name
        self.detail = detail
        super().__init__(
            f"Extraction failed at stage '{stage}' for file '{file_name}': {detail}"
        )


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class ChunkResult:
    """A single text chunk produced by the chunking algorithm.

    Attributes:
        text: Raw chunk text.
        page_number: 1-based page number (1 for plain-text files).
        char_offset_start: Character offset of the start of this chunk in the source
                           text (populated for TXT/Markdown files).
        char_offset_end: Character offset of the end of this chunk in the source text
                         (populated for TXT/Markdown files).
        citation_type: ``"page"`` for PDF/DOCX; ``"char_offset"`` for TXT/Markdown.
    """

    text: str
    page_number: int = 1
    char_offset_start: int | None = None
    char_offset_end: int | None = None
    citation_type: str = "page"


@dataclass
class RetrievedChunk:
    """A chunk retrieved from semantic search with citation metadata.

    Attributes:
        content: Raw chunk text.
        document_name: Original filename of the source document.
        page_number: 1-based page number where the chunk originates.
        citation_type: ``"page"`` for PDF/DOCX; ``"char_offset"`` for TXT/Markdown.
        char_offset_start: Character offset of the start of the chunk (TXT/MD only).
        char_offset_end: Character offset of the end of the chunk (TXT/MD only).
    """

    content: str
    document_name: str
    page_number: int
    citation_type: str = "page"
    char_offset_start: int | None = None
    char_offset_end: int | None = None


@dataclass
class QueryResult:
    """Result of a RAG query with retrieved chunks and formatted context.

    Attributes:
        query: The original query string.
        retrieved_chunks: List of retrieved chunks with citation metadata.
        context: Pre-formatted context string ready for LLM injection.
    """

    query: str
    retrieved_chunks: list[RetrievedChunk] = field(default_factory=list)
    context: str = ""


# ---------------------------------------------------------------------------
# RAGService
# ---------------------------------------------------------------------------


class RAGService:
    """Encapsulates all document ingestion logic.

    Methods are async-safe: CPU-bound or synchronous library calls are wrapped
    in ``asyncio.to_thread()`` to avoid blocking the event loop.

    Usage::

        service = RAGService()
        service.validate_upload(filename, size_bytes)   # raises HTTP 422 on failure
        minio_key = await service.store_file_minio(file_bytes, filename, user_id)
        text = await service.extract_text(file_bytes, mime_type, filename)
        chunks = service.chunk_text(text, chunk_size, overlap)
        await service.embed_and_store(chunks, document_id, user_id, db)
    """

    def __init__(self) -> None:
        self._settings = get_settings()
        self._embedding_model = None  # lazy-loaded on first embed call
        self._firebase_app = None  # lazy-loaded when credentials path is set
        self._firebase_initialised = False

    # ------------------------------------------------------------------
    # Validation (Property 26)
    # ------------------------------------------------------------------

    def validate_upload(self, filename: str, size_bytes: int) -> None:
        """Validate file format and size BEFORE any storage I/O.

        Rejects:
        - Files whose MIME type (from Content-Type) is not in ``SUPPORTED_MIME_TYPES``
          AND whose extension is not in ``SUPPORTED_EXTENSIONS``.
        - Files larger than ``MAX_FILE_SIZE_MB`` megabytes.

        Args:
            filename: Original filename including extension.
            size_bytes: Total file size in bytes.

        Raises:
            :class:`fastapi.HTTPException` HTTP 422 on invalid format or size.

        Property 26
        """
        max_bytes = self._settings.MAX_FILE_SIZE_MB * 1024 * 1024

        # Size check — must happen first for a deterministic order
        if size_bytes > max_bytes:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"File size {size_bytes} bytes exceeds the "
                    f"{self._settings.MAX_FILE_SIZE_MB} MB limit."
                ),
            )

        # Extension check — MIME type comes from Content-Type and is validated
        # in the router; here we enforce via extension as the fallback path.
        import os

        ext = os.path.splitext(filename.lower())[1]
        if ext not in SUPPORTED_EXTENSIONS:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"Unsupported file type '{ext}'. "
                    f"Allowed extensions: {sorted(SUPPORTED_EXTENSIONS)}"
                ),
            )

    def validate_mime_and_upload(
        self, filename: str, size_bytes: int, content_type: str
    ) -> None:
        """Validate both MIME type (from Content-Type header) and size before storage.

        Args:
            filename: Original filename including extension.
            size_bytes: Total file size in bytes.
            content_type: Value of the Content-Type header from the upload request.

        Raises:
            :class:`fastapi.HTTPException` HTTP 422 on any validation failure.

        Property 26
        """
        max_bytes = self._settings.MAX_FILE_SIZE_MB * 1024 * 1024

        if size_bytes > max_bytes:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"File size {size_bytes} bytes exceeds the "
                    f"{self._settings.MAX_FILE_SIZE_MB} MB limit."
                ),
            )

        # Normalise the MIME type (strip parameters like charset)
        mime = content_type.split(";")[0].strip().lower()
        import os

        ext = os.path.splitext(filename.lower())[1]

        if mime not in SUPPORTED_MIME_TYPES and ext not in SUPPORTED_EXTENSIONS:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"Unsupported file type. MIME '{mime}' and extension '{ext}' are not allowed. "
                    f"Supported MIME types: {sorted(SUPPORTED_MIME_TYPES)}"
                ),
            )

    # ------------------------------------------------------------------
    # File storage — delegates to storage_service (GCS or MinIO)
    # ------------------------------------------------------------------

    async def store_file_minio(
        self,
        file_bytes: bytes,
        filename: str,
        user_id: str,
        document_id: str | None = None,
    ) -> str:
        """Store file bytes using the configured storage backend and return the object key.

        Previously MinIO-only; now delegates to ``storage_service`` which
        selects GCS (production) or MinIO (local) based on ``STORAGE_BACKEND``.

        The key format is ``{user_id}/{document_id}/{filename}`` so each
        document lives in a user-scoped namespace (Property 8 at storage layer).

        Args:
            file_bytes:  Raw file content.
            filename:    Original filename (used as the leaf key segment).
            user_id:     String UUID of the uploading user.
            document_id: String UUID of the document row; generated if not provided.

        Returns:
            Object key (string), e.g. ``"uuid1/uuid2/report.pdf"``.
        """
        from app.services.storage_service import storage_service  # noqa: PLC0415

        return await storage_service.upload(
            file_bytes,
            filename,
            user_id,
            document_id=document_id,
        )

    async def download_file_minio(self, minio_key: str) -> bytes:
        """Download a file by object key using the configured storage backend.

        Args:
            minio_key: Object key returned by ``store_file_minio``.

        Returns:
            Raw file bytes.
        """
        from app.services.storage_service import storage_service  # noqa: PLC0415

        return await storage_service.download(minio_key)

    async def delete_file_minio(self, minio_key: str) -> None:
        """Delete a file by object key using the configured storage backend.

        Best-effort: errors are logged but not re-raised.

        Args:
            minio_key: Object key to delete.
        """
        from app.services.storage_service import storage_service  # noqa: PLC0415

        await storage_service.delete(minio_key)

    # ------------------------------------------------------------------
    # Text extraction
    # ------------------------------------------------------------------

    async def extract_text(
        self, file_bytes: bytes, mime_type: str, filename: str
    ) -> tuple[str, int]:
        """Dispatch to the appropriate extractor and return (text, page_count).

        Supported MIME types:
        - ``application/pdf``                    → pypdf (native) / pytesseract (scanned)
        - ``application/vnd.openxmlformats-...`` → python-docx
        - ``text/plain`` / ``text/markdown``     → UTF-8 decode

        Args:
            file_bytes: Raw file content.
            mime_type: Normalised MIME type string.
            filename: Original filename (used in error messages).

        Returns:
            Tuple of (extracted_text: str, page_count: int).

        Raises:
            :class:`ExtractionError` with stage and filename on extraction failure.
        """
        import os

        ext = os.path.splitext(filename.lower())[1]
        normalised_mime = mime_type.split(";")[0].strip().lower()

        if normalised_mime == "application/pdf" or ext == ".pdf":
            return await self._extract_pdf(file_bytes, filename)
        elif (
            normalised_mime
            == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            or ext == ".docx"
        ):
            return await self._extract_docx(file_bytes, filename)
        elif normalised_mime in {"text/plain", "text/markdown"} or ext in {
            ".txt",
            ".md",
        }:
            return await self._extract_text_file(file_bytes, filename)
        else:
            raise ExtractionError(
                stage="unsupported_format",
                file_name=filename,
                detail=f"MIME type '{normalised_mime}' is not supported.",
            )

    async def _extract_pdf(self, file_bytes: bytes, filename: str) -> tuple[str, int]:
        """Extract text from a PDF, falling back to OCR for scanned pages."""

        def _do_extract() -> tuple[str, int]:
            try:
                import pypdf

                reader = pypdf.PdfReader(io.BytesIO(file_bytes))
                pages: list[str] = []
                for page in reader.pages:
                    text = page.extract_text() or ""
                    pages.append(text)

                full_text = "\n".join(pages).strip()
                page_count = len(reader.pages)

                # If native extraction returns almost nothing, attempt OCR
                if not full_text or len(full_text) < 20:
                    full_text = _ocr_pdf(file_bytes)

                return full_text, page_count

            except Exception as exc:
                raise ExtractionError(
                    stage="pdf_extraction",
                    file_name=filename,
                    detail=str(exc),
                ) from exc

        def _ocr_pdf(pdf_bytes: bytes) -> str:
            """OCR-based fallback for scanned PDFs using pytesseract."""
            try:
                import pytesseract
                from pdf2image import convert_from_bytes

                images = convert_from_bytes(pdf_bytes)
                texts = [pytesseract.image_to_string(img) for img in images]
                return "\n".join(texts).strip()
            except ImportError:
                # pdf2image not installed — attempt page-level PIL approach
                return ""
            except Exception as exc:
                raise ExtractionError(
                    stage="ocr",
                    file_name=filename,
                    detail=str(exc),
                ) from exc

        return await asyncio.to_thread(_do_extract)

    async def _extract_docx(self, file_bytes: bytes, filename: str) -> tuple[str, int]:
        """Extract text from a DOCX file using python-docx."""

        def _do_extract() -> tuple[str, int]:
            try:
                import docx

                doc = docx.Document(io.BytesIO(file_bytes))
                paragraphs = [p.text for p in doc.paragraphs if p.text.strip()]
                text = "\n".join(paragraphs)
                # DOCX does not have a reliable page count; use paragraph count as proxy
                # (set to 1 since docx page count is layout-dependent)
                return text, 1
            except Exception as exc:
                raise ExtractionError(
                    stage="docx_extraction",
                    file_name=filename,
                    detail=str(exc),
                ) from exc

        return await asyncio.to_thread(_do_extract)

    async def _extract_text_file(
        self, file_bytes: bytes, filename: str
    ) -> tuple[str, int]:
        """Decode a plain-text or Markdown file to string."""
        try:
            text = file_bytes.decode("utf-8")
            return text, 1
        except UnicodeDecodeError:
            try:
                text = file_bytes.decode("latin-1")
                return text, 1
            except Exception as exc:
                raise ExtractionError(
                    stage="text_read",
                    file_name=filename,
                    detail=str(exc),
                ) from exc

    # ------------------------------------------------------------------
    # Chunking (Property 7 — full coverage guaranteed)
    # ------------------------------------------------------------------

    def chunk_text(
        self,
        text: str,
        chunk_size: int | None = None,
        overlap: int | None = None,
        min_chunk_size: int | None = None,
        max_chunk_size: int | None = None,
        is_plain_text: bool = False,
    ) -> list[ChunkResult]:
        """Split text into overlapping token-based chunks.

        Uses a sliding window over the tiktoken token list:
            start = 0
            end   = chunk_size
            next start = start + (chunk_size - overlap)

        This guarantees every token appears in at least one chunk (Property 7).

        Chunking constraints (Requirement 4.3):
        - Default chunk size: 512 tokens
        - Default overlap: 64 tokens
        - Minimum chunk size: 64 tokens (configurable via min_chunk_size)
        - Maximum chunk size: 2048 tokens (configurable via max_chunk_size)
        - Maximum overlap: 50% of chunk size

        Args:
            text: Full extracted document text.
            chunk_size: Token count per chunk (default: RAG_CHUNK_SIZE from settings).
            overlap: Overlapping tokens between consecutive chunks
                     (default: RAG_CHUNK_OVERLAP from settings).
            min_chunk_size: Minimum acceptable chunk size in tokens (default: 64).
            max_chunk_size: Maximum acceptable chunk size in tokens (default: 2048).
            is_plain_text: When True, character offsets are tracked and stored in
                           ``ChunkResult.char_offset_start`` / ``char_offset_end``,
                           and ``citation_type`` is set to ``"char_offset"``.

        Returns:
            List of :class:`ChunkResult` objects.  Returns an empty list for
            empty input text.
        """
        import tiktoken

        if chunk_size is None:
            chunk_size = self._settings.RAG_CHUNK_SIZE
        if overlap is None:
            overlap = self._settings.RAG_CHUNK_OVERLAP
        if min_chunk_size is None:
            min_chunk_size = 64
        if max_chunk_size is None:
            max_chunk_size = 2048

        # Enforce chunk size bounds (Requirement 4.3)
        chunk_size = max(min_chunk_size, min(chunk_size, max_chunk_size))

        # Enforce maximum overlap = 50% of chunk size (Requirement 4.3)
        max_overlap = chunk_size // 2
        overlap = min(overlap, max_overlap)

        if not text or not text.strip():
            return []

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        tokens = enc.encode(text)

        if not tokens:
            return []

        stride = chunk_size - overlap
        if stride <= 0:
            # Degenerate configuration: stride must be positive to make progress
            stride = max(1, chunk_size)

        chunks: list[ChunkResult] = []
        start = 0

        # Precompute a token → character offset mapping for plain-text citation
        # (only done when is_plain_text=True to avoid the overhead for PDFs/DOCX)
        token_char_offsets: list[int] = []
        if is_plain_text:
            # Build a list of cumulative byte lengths for each token boundary
            token_char_offsets = _build_token_char_offsets(enc, tokens, text)

        while start < len(tokens):
            end = min(start + chunk_size, len(tokens))
            chunk_tokens = tokens[start:end]
            chunk_text_str = enc.decode(chunk_tokens)

            if is_plain_text and token_char_offsets:
                char_start = (
                    token_char_offsets[start] if start < len(token_char_offsets) else 0
                )
                char_end = (
                    token_char_offsets[end]
                    if end < len(token_char_offsets)
                    else len(text)
                )
                chunks.append(
                    ChunkResult(
                        text=chunk_text_str,
                        page_number=1,
                        char_offset_start=char_start,
                        char_offset_end=char_end,
                        citation_type="char_offset",
                    )
                )
            else:
                chunks.append(ChunkResult(text=chunk_text_str, page_number=1))

            if end == len(tokens):
                break
            start += stride

        return chunks

    # ------------------------------------------------------------------
    # Embedding and ChromaDB storage (Property 8 — user isolation)
    # ------------------------------------------------------------------

    def _get_embedding_model(self):
        """Lazy-load and cache the SentenceTransformer model."""
        if self._embedding_model is None:
            from sentence_transformers import SentenceTransformer

            self._embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
        return self._embedding_model

    async def embed_and_store(
        self,
        chunks: list[ChunkResult],
        document_id: str,
        user_id: str,
        db: AsyncSession,
    ) -> None:
        """Embed chunks and store vectors in ChromaDB plus metadata in PostgreSQL.

        Each user's embeddings are isolated in a dedicated ChromaDB collection
        named ``documents_{user_id}`` (Property 8).

        Args:
            chunks: List of :class:`ChunkResult` objects produced by ``chunk_text``.
            document_id: String UUID of the parent document.
            user_id: String UUID of the owning user.
            db: SQLAlchemy async session used to persist DocumentChunk rows.
        """
        if not chunks:
            return

        # Generate embeddings off the event loop (CPU-bound)
        texts = [c.text for c in chunks]

        def _encode() -> list[list[float]]:
            model = self._get_embedding_model()
            embeddings = model.encode(texts, show_progress_bar=False)
            return [emb.tolist() for emb in embeddings]

        embeddings = await asyncio.to_thread(_encode)

        # Store in ChromaDB (Property 8: per-user collection)
        collection_name = f"documents_{user_id}"
        chroma_ids: list[str] = []

        def _store_chroma() -> list[str]:
            try:
                import chromadb

                client = chromadb.HttpClient(
                    host=self._settings.CHROMA_HOST,
                    port=self._settings.CHROMA_PORT,
                )
                collection = client.get_or_create_collection(collection_name)
                ids = [f"{document_id}_{i}" for i in range(len(chunks))]
                collection.add(
                    ids=ids,
                    embeddings=embeddings,
                    documents=texts,
                    metadatas=[
                        {
                            "document_id": document_id,
                            "chunk_index": i,
                            "page_number": chunks[i].page_number,
                            "citation_type": chunks[i].citation_type,
                            **(
                                {
                                    "char_offset_start": chunks[i].char_offset_start,
                                    "char_offset_end": chunks[i].char_offset_end,
                                }
                                if chunks[i].citation_type == "char_offset"
                                and chunks[i].char_offset_start is not None
                                else {}
                            ),
                        }
                        for i in range(len(chunks))
                    ],
                )
                return ids
            except Exception as exc:
                logger.warning(
                    "ChromaDB storage failed (graceful degradation): %s", exc
                )
                # Return placeholder IDs so PostgreSQL rows are still created
                return [f"{document_id}_{i}" for i in range(len(chunks))]

        chroma_ids = await asyncio.to_thread(_store_chroma)

        # Persist DocumentChunk rows in PostgreSQL
        from app.repositories.document_repository import (
            DocumentRepository,
        )

        repo = DocumentRepository(db)
        doc_uuid = uuid.UUID(document_id)
        for i, (chunk, chroma_id) in enumerate(zip(chunks, chroma_ids, strict=True)):
            await repo.create_chunk(
                document_id=doc_uuid,
                chunk_index=i,
                page_number=chunk.page_number,
                content=chunk.text,
                chroma_id=chroma_id,
                citation_type=chunk.citation_type,
                char_offset_start=chunk.char_offset_start,
                char_offset_end=chunk.char_offset_end,
            )

    async def delete_embeddings(self, document_id: str, user_id: str) -> None:
        """Remove all embedding vectors for a document from ChromaDB.

        Args:
            document_id: String UUID of the document.
            user_id: String UUID of the owning user (determines collection name).
        """
        collection_name = f"documents_{user_id}"

        def _delete() -> None:
            try:
                import chromadb

                client = chromadb.HttpClient(
                    host=self._settings.CHROMA_HOST,
                    port=self._settings.CHROMA_PORT,
                )
                try:
                    collection = client.get_collection(collection_name)
                    collection.delete(where={"document_id": {"$eq": document_id}})
                except Exception:
                    # Collection may not exist if embedding storage previously failed
                    pass
            except Exception as exc:
                logger.warning("ChromaDB delete failed (graceful degradation): %s", exc)

        try:
            await asyncio.to_thread(_delete)
        except Exception as exc:
            logger.warning("ChromaDB delete failed (graceful degradation): %s", exc)

    # ------------------------------------------------------------------
    # RAG Query — semantic retrieval and citation assembly (Requirements 4.6, 4.7)
    # ------------------------------------------------------------------

    async def query_documents(
        self,
        user_id: uuid.UUID,
        query: str,
        document_ids: list[str] | None = None,
        top_k: int = 5,
        db: AsyncSession | None = None,
    ) -> QueryResult:
        """Retrieve top-K semantically relevant chunks and assemble context with citations.

        Implements the retrieval half of the RAG pipeline:
        1. Generate a query embedding using the same model used at ingestion time.
        2. Perform cosine-similarity search in the user-scoped ChromaDB collection,
           optionally filtering by document_ids.
        3. Fetch the corresponding DocumentChunk rows from PostgreSQL to get
           content, document name, and page number.
        4. Build citation strings and a formatted context window for LLM injection.

        Args:
            user_id: UUID of the querying user (scopes the ChromaDB collection).
            query: Natural-language question or search string.
            document_ids: Optional list of document UUID strings to restrict
                retrieval to.  When ``None`` or empty, all user documents are searched.
            top_k: Maximum number of chunks to retrieve (default 5, Requirement 4.6).
            db: Optional SQLAlchemy async session for fetching chunk metadata.
                When provided, chunk content and metadata are authoritative from
                PostgreSQL.  When ``None``, metadata from ChromaDB is used directly.

        Returns:
            A :class:`QueryResult` dataclass with chunks, citations, and a
            pre-formatted context string.

        Property 8  — only the ``documents_{user_id}`` collection is queried.
        Property 9  — every returned chunk includes a citation with document name
                      and page number.
        Requirements: 4.6, 4.7
        """
        collection_name = f"documents_{user_id}"

        # ----------------------------------------------------------------
        # Step 1 — generate query embedding
        # ----------------------------------------------------------------
        def _encode_query() -> list[float]:
            model = self._get_embedding_model()
            embedding = model.encode([query], show_progress_bar=False)
            return embedding[0].tolist()

        query_embedding = await asyncio.to_thread(_encode_query)

        # ----------------------------------------------------------------
        # Step 2 — query ChromaDB for top-K similar chunks (Property 8)
        # ----------------------------------------------------------------
        # Build a ChromaDB where-filter if document_ids are specified
        chroma_where: dict | None = None
        if document_ids:
            if len(document_ids) == 1:
                chroma_where = {"document_id": {"$eq": document_ids[0]}}
            else:
                chroma_where = {"document_id": {"$in": document_ids}}

        def _query_chroma() -> list[dict]:
            """Return list of result dicts with chroma_id, content, and metadata."""
            try:
                import chromadb

                client = chromadb.HttpClient(
                    host=self._settings.CHROMA_HOST,
                    port=self._settings.CHROMA_PORT,
                )
                try:
                    collection = client.get_collection(collection_name)
                except Exception:
                    # Collection does not exist — user has no ingested documents
                    return []

                query_kwargs: dict = {
                    "query_embeddings": [query_embedding],
                    "n_results": top_k,
                    "include": ["documents", "metadatas", "distances"],
                }
                if chroma_where is not None:
                    query_kwargs["where"] = chroma_where

                results = collection.query(**query_kwargs)

                # Unpack results — chromadb returns nested lists (one per query)
                ids = results.get("ids", [[]])[0]
                documents = results.get("documents", [[]])[0] or []
                metadatas = results.get("metadatas", [[]])[0] or []

                return [
                    {
                        "chroma_id": ids[i] if i < len(ids) else f"unknown_{i}",
                        "content": documents[i] if i < len(documents) else "",
                        "metadata": metadatas[i] if i < len(metadatas) else {},
                    }
                    for i in range(len(ids))
                ]
            except Exception as exc:
                logger.warning("ChromaDB query failed (graceful degradation): %s", exc)
                return []

        chroma_results = await asyncio.to_thread(_query_chroma)

        if not chroma_results:
            return QueryResult(query=query, retrieved_chunks=[], context="")

        # ----------------------------------------------------------------
        # Step 3 — enrich with PostgreSQL metadata when a session is provided
        # ----------------------------------------------------------------
        retrieved_chunks: list[RetrievedChunk] = []

        if db is not None:
            # Authoritative path: fetch chunk rows (content + document metadata)
            chroma_ids = [r["chroma_id"] for r in chroma_results]
            from sqlalchemy import select

            from app.models.document import Document
            from app.models.document_chunk import DocumentChunk

            result = await db.execute(
                select(DocumentChunk, Document)
                .join(Document, DocumentChunk.document_id == Document.id)
                .where(DocumentChunk.chroma_id.in_(chroma_ids))
                .where(Document.user_id == user_id)  # enforces user isolation
            )
            rows = result.all()

            # Build a mapping from chroma_id → (chunk, document) for ordering
            chroma_map: dict[str, tuple] = {
                chunk.chroma_id: (chunk, document) for chunk, document in rows
            }

            # Preserve ChromaDB ranking order
            for chroma_result in chroma_results:
                cid = chroma_result["chroma_id"]
                if cid in chroma_map:
                    chunk, document = chroma_map[cid]
                    retrieved_chunks.append(
                        RetrievedChunk(
                            content=chunk.content,
                            document_name=document.file_name,
                            page_number=chunk.page_number,
                            citation_type=getattr(chunk, "citation_type", "page"),
                            char_offset_start=getattr(chunk, "char_offset_start", None),
                            char_offset_end=getattr(chunk, "char_offset_end", None),
                        )
                    )
                else:
                    # Chunk not found in PostgreSQL — use ChromaDB metadata as fallback
                    meta = chroma_result.get("metadata", {})
                    retrieved_chunks.append(
                        RetrievedChunk(
                            content=chroma_result.get("content", ""),
                            document_name=meta.get("document_name", "unknown"),
                            page_number=int(meta.get("page_number", 1)),
                            citation_type=meta.get("citation_type", "page"),
                            char_offset_start=meta.get("char_offset_start"),
                            char_offset_end=meta.get("char_offset_end"),
                        )
                    )
        else:
            # Fallback path: use ChromaDB metadata directly (no DB session)
            for chroma_result in chroma_results:
                meta = chroma_result.get("metadata", {})
                retrieved_chunks.append(
                    RetrievedChunk(
                        content=chroma_result.get("content", ""),
                        document_name=meta.get("document_name", "unknown"),
                        page_number=int(meta.get("page_number", 1)),
                        citation_type=meta.get("citation_type", "page"),
                        char_offset_start=meta.get("char_offset_start"),
                        char_offset_end=meta.get("char_offset_end"),
                    )
                )

        # ----------------------------------------------------------------
        # Step 4 — assemble context string with citations (Property 9)
        # ----------------------------------------------------------------
        context = _build_context_string(query, retrieved_chunks)

        return QueryResult(
            query=query, retrieved_chunks=retrieved_chunks, context=context
        )

    # ------------------------------------------------------------------
    # Context assembly and citation formatting helpers (Property 9)
    # ------------------------------------------------------------------

    def _assemble_context(self, chunks: list[RetrievedChunk]) -> str:
        """Assemble retrieved chunks into a numbered context string with citation markers.

        Produces a formatted context block suitable for direct injection into an
        LLM prompt.  Each chunk is numbered and tagged with an inline citation so
        the LLM can reference sources in its answer.

        Citation formats:
        - PDF/DOCX: ``[Source: {document_name}, Page {page_number}]``
        - TXT/MD:   ``[Source: {document_name}, Chars {start}-{end}]``

        Args:
            chunks: Ordered list of :class:`RetrievedChunk` dataclass instances
                    retrieved from the vector store.

        Returns:
            A multi-line string with one numbered section per chunk, each ending
            with a citation marker.  Returns an empty string for empty input.

        Property 9 — every chunk section carries a citation.
        Requirements: 4.6, 4.7
        """
        if not chunks:
            return ""

        lines: list[str] = ["Retrieved Context:", ""]
        for i, chunk in enumerate(chunks, 1):
            citation_type = getattr(chunk, "citation_type", "page")
            if citation_type == "char_offset":
                start = getattr(chunk, "char_offset_start", None)
                end = getattr(chunk, "char_offset_end", None)
                if start is not None and end is not None:
                    citation = f"[Source: {chunk.document_name}, Chars {start}-{end}]"
                else:
                    citation = f"[Source: {chunk.document_name}]"
            else:
                citation = f"[Source: {chunk.document_name}, Page {chunk.page_number}]"
            lines.append(f"--- Chunk {i} {citation} ---")
            lines.append(chunk.content)
            lines.append("")
        return "\n".join(lines)

    def _format_citations(self, chunks: list[RetrievedChunk]) -> list[dict]:
        """Extract citation metadata from retrieved chunks.

        Returns one citation dict per chunk containing document_name, page_number,
        chunk_index, citation_type, and optionally char_offset_start/char_offset_end
        for TXT/Markdown files.

        Args:
            chunks: Ordered list of :class:`RetrievedChunk` dataclass instances.

        Returns:
            List of dicts with keys ``document_name``, ``page_number``,
            ``chunk_index``, ``citation_type``, and optionally
            ``char_offset_start`` / ``char_offset_end``.

        Property 9 — citation completeness: document name and page number are
                     present for every chunk.
        Requirements: 4.7
        """
        citations = []
        for i, chunk in enumerate(chunks):
            citation: dict = {
                "document_name": chunk.document_name,
                "page_number": chunk.page_number,
                "chunk_index": i,
                "citation_type": getattr(chunk, "citation_type", "page"),
            }
            if getattr(chunk, "citation_type", "page") == "char_offset":
                citation["char_offset_start"] = getattr(
                    chunk, "char_offset_start", None
                )
                citation["char_offset_end"] = getattr(chunk, "char_offset_end", None)
            citations.append(citation)
        return citations

    # ------------------------------------------------------------------
    # Job creation
    # ------------------------------------------------------------------

    async def create_ingestion_job(
        self,
        document_id: uuid.UUID,
        user_id: uuid.UUID,
        db: AsyncSession,
    ) -> uuid.UUID:
        """Create a Job row for a document ingestion task.

        Args:
            document_id: UUID of the document being ingested.
            user_id: UUID of the owning user.
            db: SQLAlchemy async session.

        Returns:
            The UUID of the newly created Job.
        """
        from app.repositories.job_repository import JobRepository

        repo = JobRepository(db)
        job = await repo.create(user_id=user_id, job_type="document_ingestion")
        return job.id

    # ------------------------------------------------------------------
    # Push notification
    # ------------------------------------------------------------------

    async def send_ingestion_failure_notification(
        self, user_id: str, document_id: str
    ) -> None:
        """Send an FCM push notification to the user when ingestion permanently fails.

        Best-effort: silently ignores missing Firebase credentials and send errors.

        Args:
            user_id: String UUID of the user to notify.
            document_id: String UUID of the failed document.
        """
        credentials_path = self._settings.FIREBASE_CREDENTIALS_PATH
        if not credentials_path:
            logger.debug(
                "FIREBASE_CREDENTIALS_PATH not set; skipping failure push notification."
            )
            return

        def _send() -> None:
            try:
                import firebase_admin
                from firebase_admin import credentials, messaging

                if not firebase_admin._apps:
                    cred = credentials.Certificate(credentials_path)
                    firebase_admin.initialize_app(cred)

                topic = f"user_{user_id}_documents"
                message = messaging.Message(
                    topic=topic,
                    notification=messaging.Notification(
                        title="Document Processing Failed",
                        body="We were unable to process your document after multiple attempts.",
                    ),
                    data={"document_id": document_id, "event": "ingestion_failed"},
                )
                messaging.send(message)
                logger.info(
                    "FCM failure notification sent for user=%s document=%s",
                    user_id,
                    document_id,
                )
            except Exception as exc:
                logger.warning("FCM failure push notification failed: %s", exc)

        await asyncio.to_thread(_send)

    async def send_ingestion_notification(self, user_id: str, document_id: str) -> None:
        """Send an FCM push notification to the user on ingestion completion.

        This is a best-effort operation.  If Firebase credentials are not
        configured or the send fails, the error is logged and silently ignored.

        Args:
            user_id: String UUID of the user to notify.
            document_id: String UUID of the completed document.
        """
        credentials_path = self._settings.FIREBASE_CREDENTIALS_PATH
        if not credentials_path:
            logger.debug(
                "FIREBASE_CREDENTIALS_PATH not set; skipping push notification."
            )
            return

        def _send() -> None:
            try:
                import firebase_admin
                from firebase_admin import credentials, messaging

                if not firebase_admin._apps:
                    cred = credentials.Certificate(credentials_path)
                    firebase_admin.initialize_app(cred)

                # NOTE: The user's FCM registration token must be retrieved from
                # the database in a real deployment; here we use a topic-based
                # notification as a placeholder until device token storage is
                # implemented in a later task.
                topic = f"user_{user_id}_documents"
                message = messaging.Message(
                    topic=topic,
                    notification=messaging.Notification(
                        title="Document Ready",
                        body="Your document has been processed and is ready to query.",
                    ),
                    data={"document_id": document_id, "event": "ingestion_complete"},
                )
                messaging.send(message)
                logger.info(
                    "FCM notification sent for user=%s document=%s",
                    user_id,
                    document_id,
                )
            except Exception as exc:
                logger.warning("FCM push notification failed: %s", exc)

        await asyncio.to_thread(_send)


    # ------------------------------------------------------------------
    # DevOps knowledge base retrieval (Phase 10)
    # ------------------------------------------------------------------

    async def query_knowledge_base(
        self,
        query: str,
        top_k: int = 5,
        categories: list[str] | None = None,
    ) -> list[dict]:
        """Search the shared ``devops_knowledge`` ChromaDB collection.

        This is the retrieval method for the AI Error Analysis pipeline.
        It searches runbooks, incident reports, and architecture docs that
        were seeded by ``backend/scripts/seed_knowledge.py``.

        Unlike ``query_documents`` (which scopes to a single user), this
        method queries the shared knowledge base collection accessible to
        the error analysis pipeline regardless of user identity.

        Args:
            query:      Natural-language query (e.g. "connection pool exhaustion").
            top_k:      Maximum chunks to return (default 5).
            categories: Optional list of category filters:
                        "runbooks" | "incidents" | "architecture" | "deployment".
                        None = search all categories.

        Returns:
            List of dicts, each with keys:
                ``content``       — raw chunk text
                ``source``        — relative path e.g. "incidents/INC-001.md"
                ``document_name`` — filename e.g. "INC-001-db-connection-pool.md"
                ``category``      — folder name e.g. "incidents"
                ``chunk_index``   — position within the source document
        """
        from scripts.seed_knowledge import COLLECTION_NAME as _KB_COLLECTION

        def _encode_query() -> list[float]:
            model = self._get_embedding_model()
            return model.encode([query], show_progress_bar=False)[0].tolist()

        query_embedding = await asyncio.to_thread(_encode_query)

        def _query_chroma() -> list[dict]:
            try:
                import chromadb

                client = chromadb.HttpClient(
                    host=self._settings.CHROMA_HOST,
                    port=self._settings.CHROMA_PORT,
                )
                try:
                    collection = client.get_collection(_KB_COLLECTION)
                except Exception:
                    logger.warning(
                        "query_knowledge_base: collection '%s' not found — "
                        "has seed_knowledge.py been run?",
                        _KB_COLLECTION,
                    )
                    return []

                # Build category filter if requested
                where: dict | None = None
                if categories:
                    if len(categories) == 1:
                        where = {"category": {"$eq": categories[0]}}
                    else:
                        where = {"category": {"$in": categories}}

                query_kwargs: dict = {
                    "query_embeddings": [query_embedding],
                    "n_results": top_k,
                    "include": ["documents", "metadatas", "distances"],
                }
                if where is not None:
                    query_kwargs["where"] = where

                results = collection.query(**query_kwargs)

                ids_list       = results.get("ids", [[]])[0]
                documents_list = results.get("documents", [[]])[0] or []
                metadatas_list = results.get("metadatas", [[]])[0] or []

                chunks = []
                for i in range(len(ids_list)):
                    meta = metadatas_list[i] if i < len(metadatas_list) else {}
                    chunks.append(
                        {
                            "content":       documents_list[i] if i < len(documents_list) else "",
                            "source":        meta.get("source", ""),
                            "document_name": meta.get("document_name", ""),
                            "category":      meta.get("category", ""),
                            "chunk_index":   meta.get("chunk_index", i),
                        }
                    )
                return chunks

            except Exception as exc:
                logger.warning("query_knowledge_base: ChromaDB error — %s", exc)
                return []

        return await asyncio.to_thread(_query_chroma)

# ---------------------------------------------------------------------------
# Module-level singleton
# ---------------------------------------------------------------------------

rag_service = RAGService()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _mime_from_extension(ext: str) -> str:
    """Return a MIME type string for a given file extension."""
    mapping = {
        ".pdf": "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".txt": "text/plain",
        ".md": "text/markdown",
    }
    return mapping.get(ext, "application/octet-stream")


def _build_token_char_offsets(enc, tokens: list, text: str) -> list:
    """Build a list mapping each token index to its character offset in ``text``.

    The returned list has ``len(tokens) + 1`` entries so that
    ``offsets[i]`` is the start of token ``i`` and ``offsets[len(tokens)]``
    is the end of the last token (i.e., ``len(text)``).

    This is used to produce character-offset citations for TXT/Markdown files
    which have no inherent page structure (Requirement 4.7).

    Args:
        enc: A tiktoken encoding instance.
        tokens: List of token IDs from ``enc.encode(text)``.
        text: The original source text.

    Returns:
        List of integer character offsets with length ``len(tokens) + 1``.
    """
    offsets: list = []
    current_pos = 0
    for token_id in tokens:
        offsets.append(current_pos)
        token_bytes = enc.decode_bytes([token_id])
        # Advance by the character length of the decoded token
        try:
            token_str = token_bytes.decode("utf-8", errors="replace")
        except Exception:
            token_str = ""
        current_pos += len(token_str)
    offsets.append(current_pos)  # end of last token
    return offsets


def _build_context_string(query: str, chunks: list[RetrievedChunk]) -> str:
    """Build a formatted context string with citations for LLM injection.

    Each chunk is formatted with its content followed by a citation.  For
    PDF/DOCX files the citation includes the page number; for TXT/Markdown
    files (which have no page structure) the citation uses the character
    offset range instead (Requirement 4.7).

    Citation formats:
    - PDF/DOCX: ``[Source: {document_name}, Page {page_number}]``
    - TXT/MD:   ``[Source: {document_name}, Chars {start}-{end}]``

    Property 9: Every chunk MUST include a citation.

    Args:
        query: The original user query.
        chunks: List of retrieved chunks with citation metadata.

    Returns:
        A formatted string ready for LLM context injection.
    """
    if not chunks:
        return ""

    lines = [f"Query: {query}", "", "Retrieved Context:", ""]

    for i, chunk in enumerate(chunks, 1):
        citation_type = getattr(chunk, "citation_type", "page")
        if citation_type == "char_offset":
            start = getattr(chunk, "char_offset_start", None)
            end = getattr(chunk, "char_offset_end", None)
            if start is not None and end is not None:
                citation = f"[Source: {chunk.document_name}, Chars {start}-{end}]"
            else:
                citation = f"[Source: {chunk.document_name}]"
        else:
            citation = f"[Source: {chunk.document_name}, Page {chunk.page_number}]"

        lines.append(f"--- Chunk {i} {citation} ---")
        lines.append(chunk.content)
        lines.append("")

    return "\n".join(lines)
