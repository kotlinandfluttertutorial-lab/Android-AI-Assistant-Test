# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : rag.py
# Purpose : rag — schemas module
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 schemas for RAG document ingestion and query endpoints.

Covers upload responses, job status, document listing, extraction errors,
and RAG query/retrieval with citation support.

Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 9.7
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.security.input_sanitizer import sanitize_user_string

# Maximum length for a query string
_MAX_QUERY_LEN = 2_000


class DocumentUploadResponse(BaseModel):
    """Response body for POST /documents/upload (HTTP 202).

    Requirements: 4.1, 4.2
    """

    model_config = ConfigDict(from_attributes=True)

    document_id: uuid.UUID = Field(
        description="UUID of the newly created document row."
    )
    job_id: uuid.UUID = Field(description="UUID of the background ingestion job.")
    status: str = Field(
        default="pending",
        description="Initial ingestion status — always 'pending' at upload time.",
    )


class JobStatusResponse(BaseModel):
    """Response body for GET /jobs/{job_id}.

    Requirements: 4.5
    """

    model_config = ConfigDict(from_attributes=True)

    job_id: uuid.UUID = Field(description="UUID of the job.")
    status: str = Field(
        description="Current job status: queued | running | completed | failed."
    )
    document_id: uuid.UUID | None = Field(
        default=None,
        description="UUID of the related document (populated for document_ingestion jobs).",
    )
    error_message: str | None = Field(
        default=None,
        description="Human-readable error when status='failed'.",
    )


class DocumentResponse(BaseModel):
    """Single document representation returned in list/get operations.

    Requirements: 4.1, 4.3
    """

    model_config = ConfigDict(from_attributes=True)

    document_id: uuid.UUID = Field(alias="id", description="UUID of the document.")
    file_name: str = Field(description="Original uploaded filename.")
    mime_type: str = Field(description="Detected MIME type.")
    size_bytes: int = Field(description="File size in bytes.")
    ingestion_status: str = Field(description="pending | processing | ready | failed.")
    page_count: int | None = Field(
        default=None, description="Page count for PDF/DOCX; null for text."
    )
    created_at: datetime = Field(description="Upload timestamp (UTC).")

    model_config = ConfigDict(from_attributes=True, populate_by_name=True)


class DocumentListResponse(BaseModel):
    """Response body for GET /documents.

    Requirements: 4.3
    """

    documents: list[DocumentResponse] = Field(
        description="List of documents owned by the user."
    )
    total: int = Field(description="Total number of documents.")


class ExtractionErrorResponse(BaseModel):
    """Structured error response returned when text extraction fails.

    Requirements: 4.8
    """

    error: str = Field(default="extraction_failed", description="Error category.")
    stage: str = Field(
        description=(
            "Pipeline stage where the failure occurred: "
            "pdf_extraction | ocr | docx_extraction | text_read."
        )
    )
    file_name: str = Field(description="Original filename that caused the failure.")
    detail: str = Field(
        default="", description="Additional error detail from the underlying library."
    )


class DocumentQueryRequest(BaseModel):
    """Request body for POST /documents/query.

    Requirements: 4.6, 9.7
    """

    query: str = Field(
        description="Natural language query to search documents.",
        min_length=1,
        max_length=_MAX_QUERY_LEN,
    )
    document_ids: list[str] | None = Field(
        default=None,
        description="Optional list of document UUIDs to restrict retrieval to.",
    )
    top_k: int = Field(
        default=5,
        description="Number of most relevant chunks to retrieve (default K=5).",
        ge=1,
        le=20,
    )

    @field_validator("query")
    @classmethod
    def sanitize_query(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class PerDocumentQueryRequest(BaseModel):
    """Request body for POST /documents/{id}/query.

    Scoped to a single document; document_id is taken from the URL path.

    Requirements: 4.6, 4.7
    """

    query: str = Field(
        description="Natural language query to search the specific document.",
        min_length=1,
        max_length=_MAX_QUERY_LEN,
    )
    top_k: int = Field(
        default=5,
        description=(
            "Number of most relevant chunks to retrieve from this document (default K=5)."
        ),
        ge=1,
        le=20,
    )

    @field_validator("query")
    @classmethod
    def sanitize_query(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class Citation(BaseModel):
    """A single citation entry for a retrieved chunk.

    Used in RAGQueryResult to provide document attribution for every retrieved
    chunk (Property 9).

    For PDF and DOCX files, ``citation_type`` is ``"page"`` and ``page_number``
    holds the 1-based page number.  For TXT and Markdown files (which have no
    page structure), ``citation_type`` is ``"char_offset"`` and
    ``char_offset_start`` / ``char_offset_end`` hold the byte-offset range of
    the chunk within the source file.

    Requirements: 4.7
    """

    document_name: str = Field(description="Original filename of the source document.")
    page_number: int = Field(
        description="1-based page number where the chunk originates."
    )
    chunk_index: int = Field(
        description="Zero-based index of the chunk within the document."
    )
    citation_type: str = Field(
        default="page",
        description=(
            "Citation reference type: 'page' for PDF/DOCX (uses page_number), "
            "'char_offset' for TXT/Markdown (uses char_offset_start/end)."
        ),
    )
    char_offset_start: int | None = Field(
        default=None,
        description="Character offset of the start of the chunk (TXT/Markdown only).",
    )
    char_offset_end: int | None = Field(
        default=None,
        description="Character offset of the end of the chunk (TXT/Markdown only).",
    )


class RetrievedChunk(BaseModel):
    """A single retrieved chunk with full citation metadata and similarity score.

    Richer than ChunkResult — includes chunk_id, document_id, and similarity
    score for callers that need to trace provenance or filter by score.

    Requirements: 4.6, 4.7 (Property 9)
    """

    chunk_id: str = Field(description="ChromaDB document ID / chroma_id for the chunk.")
    document_id: str = Field(description="UUID of the parent document.")
    document_name: str = Field(description="Original filename of the source document.")
    page_number: int = Field(
        description="1-based page number where the chunk originates."
    )
    content: str = Field(description="Raw chunk text.")
    similarity_score: float = Field(
        description="Cosine similarity score from the vector search (0.0 – 1.0).",
        ge=0.0,
        le=1.0,
    )


class RAGQueryResult(BaseModel):
    """Full structured result of a RAG semantic retrieval operation.

    Returned internally by the RAG service and consumed by the AI Orchestrator
    when assembling context for LLM injection.

    Requirements: 4.6, 4.7 (Property 9)
    """

    context: str = Field(
        description=(
            "Pre-formatted context string with inline citation markers, ready for LLM injection."
        )
    )
    chunks: list[RetrievedChunk] = Field(
        description="List of retrieved chunks with full metadata."
    )
    citations: list[Citation] = Field(
        description="Citation list — one entry per retrieved chunk."
    )


class DocumentQueryResponse(BaseModel):
    """Response body for POST /documents/query (AI Orchestrator answer + citations).

    Requirements: 4.6, 4.7 (Property 9)
    """

    answer: str = Field(
        description="AI-generated answer based on the retrieved context."
    )
    citations: list[Citation] = Field(
        description="Citations for every chunk used in the answer (document name + page number)."
    )
    context_used: str = Field(
        description="The assembled context string that was injected into the LLM prompt."
    )


class ChunkResult(BaseModel):
    """A single retrieved chunk with citation metadata.

    Requirements: 4.6, 4.7 (Property 9)
    """

    content: str = Field(description="Raw chunk text.")
    document_name: str = Field(description="Original filename of the source document.")
    page_number: int = Field(
        description="1-based page number where the chunk originates."
    )
    citation: str = Field(
        description="Formatted citation: [Source: {document_name}, Page {page_number}]"
    )
    citation_type: str = Field(
        default="page",
        description="'page' for PDF/DOCX, 'char_offset' for TXT/Markdown.",
    )
    char_offset_start: int | None = Field(
        default=None,
        description="Character offset start of the chunk within the source (TXT/MD only).",
    )
    char_offset_end: int | None = Field(
        default=None,
        description="Character offset end of the chunk within the source (TXT/MD only).",
    )


class ChunkQueryResponse(BaseModel):
    """Response body for POST /documents/query (chunk-level view).

    Returns the raw retrieved chunks with citations — used by the RAG endpoint
    when the caller wants chunk-level results rather than an LLM-generated answer.

    Requirements: 4.6, 4.7 (Property 9)
    """

    query: str = Field(description="The original query string.")
    chunks: list[ChunkResult] = Field(
        description="List of retrieved chunks with citations."
    )
    context: str = Field(
        description=(
            "Pre-formatted context string ready for LLM injection, "
            "includes all chunks with citations."
        )
    )
    total_chunks: int = Field(description="Number of chunks retrieved.")
