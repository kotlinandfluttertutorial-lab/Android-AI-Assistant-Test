# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/rag
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the rag domain
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

"""RAG router — /documents/* and /jobs/* endpoints.

Endpoints
---------
POST  /documents                — validate, store, create job, dispatch Celery task (canonical)
POST  /documents/upload         — alias for POST /documents (legacy/convenience path)
GET   /documents                — list user's documents
POST  /documents/query          — semantic search with top-K retrieval and citations
POST  /documents/{id}/query     — semantic search scoped to a single document with citations
POST  /documents/{id}/reingest  — re-run ingestion pipeline for an existing document
DELETE /documents/{document_id} — delete document, chunks, MinIO object, ChromaDB vectors
GET   /jobs/{job_id}            — poll ingestion job status

Property 26: format/size validation happens BEFORE any storage I/O.
Property 9:  every RAG response includes citations with document name and page number.

Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.10, 4.11, 9.1
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, UploadFile, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.repositories.document_repository import DocumentRepository
from app.repositories.job_repository import JobRepository
from app.schemas.rag import (
    Citation,
    DocumentListResponse,
    DocumentQueryRequest,
    DocumentQueryResponse,
    DocumentResponse,
    DocumentUploadResponse,
    JobStatusResponse,
    PerDocumentQueryRequest,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.rag_service import rag_service

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Routers
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/documents",
    tags=["rag"],
    dependencies=[Depends(get_current_user)],
)

jobs_router = APIRouter(
    prefix="/jobs",
    tags=["rag"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# Shared ingestion logic
# ---------------------------------------------------------------------------


async def _ingest_document(
    file: UploadFile,
    current_user: TokenPayload,
    db: AsyncSession,
) -> DocumentUploadResponse:
    """Core ingestion logic shared by POST /documents and POST /documents/upload.

    1. Validate file format and size (HTTP 422 if invalid — nothing stored).
    2. Create Document row (pending).
    3. Store file in MinIO.
    4. Create Job row.
    5. Dispatch Celery ``ingest_document_task``.
    6. Return DocumentUploadResponse with document_id and job_id.

    Requirements: 4.1, 4.2, Property 26
    """
    user_id = uuid.UUID(current_user.sub)

    # ---------------------------------------------------------------
    # Step 1 — validate BEFORE any storage (Property 26)
    # ---------------------------------------------------------------
    content_type = file.content_type or "application/octet-stream"
    filename = file.filename or "upload"

    # Read file bytes now so we know the actual size
    file_bytes = await file.read()
    size_bytes = len(file_bytes)

    # Raises HTTP 422 if format or size is invalid — no storage has happened yet
    rag_service.validate_mime_and_upload(filename, size_bytes, content_type)

    # ---------------------------------------------------------------
    # Step 2 — create Document row
    # ---------------------------------------------------------------
    doc_repo = DocumentRepository(db)
    document = await doc_repo.create(
        user_id=user_id,
        file_name=filename,
        mime_type=content_type.split(";")[0].strip().lower(),
        size_bytes=size_bytes,
        minio_key="",  # filled in after MinIO upload
    )

    # ---------------------------------------------------------------
    # Step 3 — store in MinIO
    # ---------------------------------------------------------------
    try:
        minio_key = await rag_service.store_file_minio(
            file_bytes,
            filename,
            str(user_id),
            document_id=str(document.id),
        )
    except Exception as exc:
        logger.error("MinIO upload failed: %s", exc)
        # Roll back the document row
        await doc_repo.delete(document.id, user_id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to store the uploaded file. Please try again.",
        ) from exc

    # Update the minio_key now that we have it
    document.minio_key = minio_key
    await db.flush()

    # ---------------------------------------------------------------
    # Step 4 — create Job row
    # ---------------------------------------------------------------
    job_id = await rag_service.create_ingestion_job(document.id, user_id, db)

    # Commit everything before dispatching the task
    await db.commit()

    # ---------------------------------------------------------------
    # Step 5 — dispatch Celery task
    # ---------------------------------------------------------------
    try:
        from app.workers.rag_worker import ingest_document_task

        celery_result = ingest_document_task.delay(str(document.id), str(user_id))
        logger.info(
            "Dispatched ingest_document_task celery_task_id=%s document_id=%s",
            celery_result.id,
            document.id,
        )

        # Record the Celery task ID for tracking
        job_repo = JobRepository(db)
        from app.models.job import JobStatus

        await job_repo.update_status(
            job_id,
            JobStatus.queued,
            celery_task_id=celery_result.id,
        )
        await db.commit()
    except Exception as exc:
        # Non-fatal: the job row exists, the user can check status later
        logger.warning("Failed to dispatch Celery task: %s", exc)

    return DocumentUploadResponse(
        document_id=document.id,
        job_id=job_id,
        status="pending",
    )


# ---------------------------------------------------------------------------
# POST /documents  (canonical — Requirement 4.1, 4.2)
# ---------------------------------------------------------------------------


@router.post(
    "",
    response_model=DocumentUploadResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload a document for RAG ingestion",
    description=(
        "Accepts PDF, DOCX, TXT, and Markdown files up to 50 MB. "
        "Validates format and size BEFORE storing anything (Property 26). "
        "Returns a document_id and job_id immediately; ingestion runs asynchronously."
    ),
)
async def ingest_document(
    file: UploadFile,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentUploadResponse:
    """Canonical POST /documents endpoint.

    Validate, store, and queue a document for ingestion.
    Returns HTTP 202 with document_id and job_id.

    Requirements: 4.1, 4.2, Property 26
    """
    return await _ingest_document(file, current_user, db)


# ---------------------------------------------------------------------------
# POST /documents/upload  (legacy convenience alias)
# ---------------------------------------------------------------------------


@router.post(
    "/upload",
    response_model=DocumentUploadResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload a document for RAG ingestion (alias for POST /documents)",
    description=(
        "Alias for POST /documents. "
        "Accepts PDF, DOCX, TXT, and Markdown files up to 50 MB. "
        "Validates format and size BEFORE storing anything (Property 26). "
        "Returns a document_id and job_id immediately; ingestion runs asynchronously."
    ),
)
async def upload_document(
    file: UploadFile,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentUploadResponse:
    """Legacy alias — delegates to the canonical POST /documents handler.

    Requirements: 4.1, 4.2, Property 26
    """
    user_id = uuid.UUID(current_user.sub)

    # ---------------------------------------------------------------
    # Step 1 — validate BEFORE any storage (Property 26)
    # ---------------------------------------------------------------
    content_type = file.content_type or "application/octet-stream"
    filename = file.filename or "upload"

    # Read file bytes now so we know the actual size
    file_bytes = await file.read()
    size_bytes = len(file_bytes)

    # Raises HTTP 422 if format or size is invalid — no storage has happened yet
    rag_service.validate_mime_and_upload(filename, size_bytes, content_type)

    # ---------------------------------------------------------------
    # Step 2 — create Document row
    # ---------------------------------------------------------------
    doc_repo = DocumentRepository(db)
    document = await doc_repo.create(
        user_id=user_id,
        file_name=filename,
        mime_type=content_type.split(";")[0].strip().lower(),
        size_bytes=size_bytes,
        minio_key="",  # filled in after MinIO upload
    )

    # ---------------------------------------------------------------
    # Step 3 — store in MinIO
    # ---------------------------------------------------------------
    try:
        minio_key = await rag_service.store_file_minio(
            file_bytes,
            filename,
            str(user_id),
            document_id=str(document.id),
        )
    except Exception as exc:
        logger.error("MinIO upload failed: %s", exc)
        # Roll back the document row
        await doc_repo.delete(document.id, user_id)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Failed to store the uploaded file. Please try again.",
        ) from exc

    # Update the minio_key now that we have it
    document.minio_key = minio_key
    await db.flush()

    # ---------------------------------------------------------------
    # Step 4 — create Job row
    # ---------------------------------------------------------------
    job_id = await rag_service.create_ingestion_job(document.id, user_id, db)

    # Commit everything before dispatching the task
    await db.commit()

    # ---------------------------------------------------------------
    # Step 5 — dispatch Celery task
    # ---------------------------------------------------------------
    try:
        from app.workers.rag_worker import ingest_document_task

        celery_result = ingest_document_task.delay(str(document.id), str(user_id))
        logger.info(
            "Dispatched ingest_document_task celery_task_id=%s document_id=%s",
            celery_result.id,
            document.id,
        )

        # Record the Celery task ID for tracking
        job_repo = JobRepository(db)
        from app.models.job import JobStatus

        await job_repo.update_status(
            job_id,
            JobStatus.queued,
            celery_task_id=celery_result.id,
        )
        await db.commit()
    except Exception as exc:
        # Non-fatal: the job row exists, the user can check status later
        logger.warning("Failed to dispatch Celery task: %s", exc)

    return DocumentUploadResponse(
        document_id=document.id,
        job_id=job_id,
        status="pending",
    )


# ---------------------------------------------------------------------------
# GET /documents
# ---------------------------------------------------------------------------


@router.get(
    "",
    response_model=DocumentListResponse,
    summary="List user's documents",
)
async def list_documents(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentListResponse:
    """Return all documents owned by the authenticated user.

    Requirements: 4.3
    """
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)
    documents = await doc_repo.list_by_user(user_id)

    doc_responses = [DocumentResponse.model_validate(doc) for doc in documents]

    return DocumentListResponse(documents=doc_responses, total=len(doc_responses))


# ---------------------------------------------------------------------------
# POST /documents/query
# ---------------------------------------------------------------------------


@router.post(
    "/query",
    response_model=DocumentQueryResponse,
    summary="Query documents using semantic search with AI-generated answer",
    description=(
        "Retrieve top-K semantically relevant chunks from the user's documents "
        "using cosine similarity, then forward the assembled context and citations "
        "to the AI Orchestrator for a cited answer. Every response includes citations "
        "with document name and page number for each retrieved chunk (Property 9). "
        "Default K=5 (Requirement 4.6)."
    ),
)
async def query_documents(
    request: DocumentQueryRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentQueryResponse:
    """Semantic retrieval with citation assembly and AI-generated answer.

    1. Generate query embedding using the same model used during ingestion.
    2. Query the user-scoped ChromaDB collection (Property 8).
    3. Optionally filter by document_ids when provided.
    4. Fetch chunk content and document metadata from PostgreSQL.
    5. Assemble context window with inline citation markers.
    6. Build citation list — one entry per retrieved chunk (Property 9).
    7. Forward context + query to AI Orchestrator for answer generation.
    8. Return DocumentQueryResponse with answer, citations, and context_used.

    Requirements: 4.6, 4.7
    Property 9: Citation completeness — every retrieved chunk includes document name + page number.
    """
    user_id = uuid.UUID(current_user.sub)

    # ----------------------------------------------------------------
    # Step 1-5: Semantic retrieval with optional document_ids filter
    # ----------------------------------------------------------------
    result = await rag_service.query_documents(
        user_id=user_id,
        query=request.query,
        document_ids=request.document_ids,
        top_k=request.top_k,
        db=db,
    )

    # ----------------------------------------------------------------
    # Step 6: Format citations (Property 9)
    # ----------------------------------------------------------------
    citation_dicts = rag_service._format_citations(result.retrieved_chunks)
    citations = [
        Citation(
            document_name=c["document_name"],
            page_number=c["page_number"],
            chunk_index=c["chunk_index"],
        )
        for c in citation_dicts
    ]

    # ----------------------------------------------------------------
    # Step 7: Forward context + query to AI Orchestrator
    # ----------------------------------------------------------------
    answer = ""
    if result.retrieved_chunks:
        try:
            import asyncio as _asyncio

            from app.services.ai_orchestrator import (
                AIOrchestrator,
                LLMProvider,
            )

            orchestrator = AIOrchestrator(db=db)

            # Build a RAG-specific prompt that includes the assembled context
            rag_prompt = (
                f"Use the following retrieved context to answer the user's question. "
                f"Always cite the source document and page number in your answer.\n\n"
                f"{result.context}\n\n"
                f"Question: {request.query}\n\n"
                f"Provide a concise, accurate answer based solely on the retrieved context. "
                f"Include citations in the format [Source: <document>, Page <n>] for each "
                f"piece of information you use."
            )

            # Determine provider from settings (default to openai)
            from app.config.settings import get_settings

            settings = get_settings()
            default_provider_str = settings.DEFAULT_LLM_PROVIDER.lower()
            try:
                provider = LLMProvider(default_provider_str)
            except ValueError:
                provider = LLMProvider.openai

            # 60s hard timeout — Gemini flash is usually <15s; 60s covers cold starts.
            completion = await _asyncio.wait_for(
                orchestrator.complete(
                    prompt=rag_prompt,
                    provider=provider,
                    max_tokens=1024,
                    user_id=str(user_id),
                ),
                timeout=60.0,
            )
            answer = completion.text

        except Exception as exc:
            logger.warning(
                "AI Orchestrator unavailable for RAG query; returning context only. "
                "exc_type=%s repr=%r",
                type(exc).__name__,
                repr(exc),
                exc_info=True,
            )
            # Graceful degradation: return the assembled context as the answer
            answer = result.context
    else:
        answer = "No relevant documents found for your query."

    return DocumentQueryResponse(
        answer=answer,
        citations=citations,
        context_used=result.context,
    )


# ---------------------------------------------------------------------------
# POST /documents/{document_id}/query
# ---------------------------------------------------------------------------


@router.post(
    "/{document_id}/query",
    response_model=DocumentQueryResponse,
    summary="Query a specific document using semantic search",
    description=(
        "Retrieve top-K semantically relevant chunks from a single document "
        "owned by the authenticated user.  The document must exist and belong to "
        "the caller.  Citations are included in the response (Property 9). "
        "Default K=5 (Requirement 4.6).  For TXT/Markdown files the citation "
        "uses character offset ranges instead of page numbers (Requirement 4.7)."
    ),
)
async def query_document_by_id(
    document_id: uuid.UUID,
    request: PerDocumentQueryRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentQueryResponse:
    """Semantic retrieval scoped to a single document.

    1. Verify the document exists and belongs to the authenticated user.
    2. Generate query embedding using the ingestion model.
    3. Query the user-scoped ChromaDB collection filtered to this document.
    4. Fetch chunk content and metadata from PostgreSQL.
    5. Assemble context window with inline citation markers.
    6. Build citation list — one entry per retrieved chunk (Property 9).
    7. Forward context + query to AI Orchestrator for answer generation.
    8. Return DocumentQueryResponse with answer, citations, and context_used.

    Requirements: 4.6, 4.7
    Property 9: Citation completeness — every retrieved chunk includes document name +
               page number (PDF/DOCX) or character offset range (TXT/Markdown).
    """
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)

    # Verify the document exists and belongs to this user
    document = await doc_repo.get_by_id(document_id, user_id=user_id)
    if document is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Document {document_id} not found.",
        )

    # Delegate to the existing query_documents with a document_ids filter
    result = await rag_service.query_documents(
        user_id=user_id,
        query=request.query,
        document_ids=[str(document_id)],
        top_k=request.top_k,
        db=db,
    )

    # Format citations (Property 9)
    citation_dicts = rag_service._format_citations(result.retrieved_chunks)
    citations = [
        Citation(
            document_name=c["document_name"],
            page_number=c["page_number"],
            chunk_index=c["chunk_index"],
            citation_type=c.get("citation_type", "page"),
            char_offset_start=c.get("char_offset_start"),
            char_offset_end=c.get("char_offset_end"),
        )
        for c in citation_dicts
    ]

    # Forward context + query to AI Orchestrator
    answer = ""
    if result.retrieved_chunks:
        try:
            import asyncio as _asyncio

            from app.services.ai_orchestrator import (
                AIOrchestrator,
                LLMProvider,
            )

            orchestrator = AIOrchestrator(db=db)

            rag_prompt = (
                f"Use the following retrieved context to answer the user's question. "
                f"Always cite the source document and page/offset reference in your answer.\n\n"
                f"{result.context}\n\n"
                f"Question: {request.query}\n\n"
                f"Provide a concise, accurate answer based solely on the retrieved context. "
                f"Include citations for each piece of information you use."
            )

            from app.config.settings import get_settings

            settings = get_settings()
            default_provider_str = settings.DEFAULT_LLM_PROVIDER.lower()
            try:
                provider = LLMProvider(default_provider_str)
            except ValueError:
                provider = LLMProvider.openai

            # 60s hard timeout — Gemini flash is usually <15s; 60s covers cold starts.
            completion = await _asyncio.wait_for(
                orchestrator.complete(
                    prompt=rag_prompt,
                    provider=provider,
                    max_tokens=1024,
                    user_id=str(user_id),
                ),
                timeout=60.0,
            )
            answer = completion.text

        except Exception as exc:
            logger.warning(
                "AI Orchestrator unavailable for per-document RAG query; "
                "returning context only. Error: %s",
                exc,
            )
            answer = result.context
    else:
        answer = "No relevant content found in this document for your query."

    return DocumentQueryResponse(
        answer=answer,
        citations=citations,
        context_used=result.context,
    )


# ---------------------------------------------------------------------------
# POST /documents/{document_id}/reingest  — re-run ingestion pipeline
# ---------------------------------------------------------------------------


@router.post(
    "/{document_id}/reingest",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=DocumentUploadResponse,
    summary="Re-run the ingestion pipeline for an existing document",
    description=(
        "Resets the document status to pending and re-dispatches the Celery "
        "ingest_document_task. Useful when a previous ingestion completed with "
        "ingestion_status=ready but ChromaDB vectors were not stored (e.g. due "
        "to a connectivity issue during the initial ingest)."
    ),
)
async def reingest_document(
    document_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentUploadResponse:
    """Re-ingest an existing document — reset status and re-dispatch Celery task."""
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)

    document = await doc_repo.get_by_id(document_id, user_id=user_id)
    if document is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Document {document_id} not found.",
        )

    from app.models.document import IngestionStatus

    # Reset to pending so the worker picks it up fresh
    await doc_repo.update_status(document_id, IngestionStatus.pending)

    # Delete any stale ChromaDB embeddings from the previous (failed) run
    try:
        await rag_service.delete_embeddings(str(document_id), str(user_id))
    except Exception:
        logger.warning("Could not clear stale ChromaDB embeddings for %s (continuing)", document_id)

    # Create a new Job row for this re-ingestion attempt
    job_id = await rag_service.create_ingestion_job(document_id, user_id, db)
    await db.commit()

    # Dispatch Celery task
    try:
        from app.workers.rag_worker import ingest_document_task

        celery_result = ingest_document_task.delay(str(document_id), str(user_id))
        logger.info(
            "Re-dispatched ingest_document_task celery_task_id=%s document_id=%s",
            celery_result.id,
            document_id,
        )

        job_repo = JobRepository(db)
        from app.models.job import JobStatus

        await job_repo.update_status(
            job_id,
            JobStatus.queued,
            celery_task_id=celery_result.id,
        )
        await db.commit()
    except Exception as exc:
        logger.warning("Failed to dispatch re-ingest Celery task: %s", exc)

    return DocumentUploadResponse(
        document_id=document_id,
        job_id=job_id,
        status="pending",
    )


# ---------------------------------------------------------------------------
# DELETE /documents/{document_id}
# ---------------------------------------------------------------------------


@router.delete(
    "/{document_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a document and all associated data",
)
async def delete_document(
    document_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a document from PostgreSQL, ChromaDB, and MinIO.

    Cascade delete in PostgreSQL removes DocumentChunk rows automatically.

    Requirements: 4.4
    """
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)

    document = await doc_repo.get_by_id(document_id, user_id=user_id)
    if document is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Document {document_id} not found.",
        )

    minio_key = document.minio_key

    # Remove from PostgreSQL (cascades to chunks)
    await doc_repo.delete(document_id, user_id)
    await db.commit()

    # Remove embeddings from ChromaDB (best-effort, graceful degradation)
    try:
        await rag_service.delete_embeddings(str(document_id), str(user_id))
    except Exception:
        logger.warning(
            "ChromaDB embedding deletion failed for document %s (best-effort)",
            document_id,
        )

    # Remove file from MinIO (best-effort, graceful degradation)
    try:
        await rag_service.delete_file_minio(minio_key)
    except Exception:
        logger.warning("MinIO file deletion failed for document %s (best-effort)", document_id)


# ---------------------------------------------------------------------------
# GET /jobs/{job_id}
# ---------------------------------------------------------------------------


@jobs_router.get(
    "/{job_id}",
    response_model=JobStatusResponse,
    summary="Get ingestion job status",
)
async def get_job_status(
    job_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> JobStatusResponse:
    """Return the current status of a background job.

    Requirements: 4.5
    """
    user_id = uuid.UUID(current_user.sub)
    job_repo = JobRepository(db)

    job = await job_repo.get_by_id(job_id, user_id=user_id)
    if job is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Job {job_id} not found.",
        )

    # Resolve document_id from the result_payload if available
    document_id: uuid.UUID | None = None
    if job.result_payload and "document_id" in job.result_payload:
        try:
            document_id = uuid.UUID(job.result_payload["document_id"])
        except (ValueError, KeyError):
            pass

    return JobStatusResponse(
        job_id=job.id,
        status=_normalise_job_status(job.status.value),
        document_id=document_id,
        error_message=job.error_message,
    )


def _normalise_job_status(raw_status: str) -> str:
    """Map internal job status values to the canonical API-facing values.

    The Celery worker uses ``running`` to indicate a task in progress, but the
    public API contract (Requirement 4.11) exposes ``processing`` for this state.

    Mapping:
      ``running``    → ``processing``
      Everything else remains unchanged (``queued``, ``completed``, ``failed``).

    Args:
        raw_status: The raw string value from the ``JobStatus`` enum.

    Returns:
        The canonical API-facing status string.
    """
    if raw_status == "running":
        return "processing"
    return raw_status


# ===========================================================================
# /api/v1/documents  — Versioned document management router
# ===========================================================================
#
# Follows the existing versioning convention: a separate APIRouter with the
# /api/v1/... prefix lives in the same file and is registered independently
# in main.py.  All business logic delegates to existing services and helpers
# rather than duplicating them.
#
# Endpoints:
#   POST   /api/v1/documents/upload   — upload a document (starts ingestion)
#   GET    /api/v1/documents          — list all documents owned by the user
#   GET    /api/v1/documents/{id}     — get single document details
#   DELETE /api/v1/documents/{id}     — delete document + all associated data
# ===========================================================================

from app.schemas.rag import (  # noqa: E402 (re-import for v1 schemas)
    V1DocumentDetailResponse,
    V1SearchRequest,
    V1SearchResponse,
    V1SearchSource,
    V1AskRequest,
    V1AskResponse,
    V1AskSource,
)

v1_documents_router = APIRouter(
    prefix="/api/v1/documents",
    tags=["documents-v1"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# POST /api/v1/documents/upload
# ---------------------------------------------------------------------------


@v1_documents_router.post(
    "/upload",
    response_model=DocumentUploadResponse,
    status_code=status.HTTP_202_ACCEPTED,
    summary="Upload a document for RAG ingestion",
    description=(
        "**POST /api/v1/documents/upload**\n\n"
        "Accepts PDF, DOCX, TXT, and Markdown files up to 50 MB.\n\n"
        "**Validation** happens before any I/O — an invalid format or oversized file "
        "returns HTTP 422 immediately without writing anything to storage.\n\n"
        "**Response** — HTTP 202 Accepted:\n"
        "```json\n"
        '{"document_id": "<uuid>", "job_id": "<uuid>", "status": "pending"}\n'
        "```\n"
        "Poll `GET /api/v1/documents/{document_id}` for ingestion progress.\n\n"
        "Supported MIME types: `application/pdf`, "
        "`application/vnd.openxmlformats-officedocument.wordprocessingml.document`, "
        "`text/plain`, `text/markdown`."
    ),
    responses={
        202: {"description": "Document accepted and queued for ingestion."},
        401: {"description": "Missing or invalid Bearer token."},
        422: {"description": "Unsupported file format or file exceeds size limit."},
        500: {"description": "Storage error — retry is safe (nothing was persisted)."},
    },
)
async def v1_upload_document(
    file: UploadFile,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentUploadResponse:
    """Upload a document and queue it for asynchronous RAG ingestion.

    Delegates to the shared ``_ingest_document`` helper used by the
    non-versioned ``POST /documents`` endpoint.

    Ownership: the uploaded document is scoped to ``current_user.sub``.
    """
    return await _ingest_document(file, current_user, db)


# ---------------------------------------------------------------------------
# GET /api/v1/documents
# ---------------------------------------------------------------------------


@v1_documents_router.get(
    "",
    response_model=DocumentListResponse,
    summary="List all documents owned by the authenticated user",
    description=(
        "**GET /api/v1/documents**\n\n"
        "Returns every document the authenticated user has uploaded, ordered by "
        "`created_at` descending.\n\n"
        "Documents at any ingestion stage (`pending`, `processing`, `ready`, `failed`) "
        "are included.\n\n"
        "**Response:**\n"
        "```json\n"
        '{"documents": [...], "total": 3}\n'
        "```"
    ),
    responses={
        200: {"description": "List of documents owned by the user."},
        401: {"description": "Missing or invalid Bearer token."},
    },
)
async def v1_list_documents(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DocumentListResponse:
    """Return all documents owned by the authenticated user."""
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)
    documents = await doc_repo.list_by_user(user_id)
    doc_responses = [DocumentResponse.model_validate(doc) for doc in documents]
    return DocumentListResponse(documents=doc_responses, total=len(doc_responses))


# ---------------------------------------------------------------------------
# GET /api/v1/documents/{document_id}
# ---------------------------------------------------------------------------


@v1_documents_router.get(
    "/{document_id}",
    response_model=V1DocumentDetailResponse,
    summary="Get document details",
    description=(
        "**GET /api/v1/documents/{document_id}**\n\n"
        "Returns metadata for a single document owned by the authenticated user.\n\n"
        "**Ownership enforcement:** documents belonging to other users return HTTP 404 "
        "(not HTTP 403) to avoid leaking whether a document UUID exists.\n\n"
        "**Response** includes `ingestion_status` which reflects the current "
        "pipeline stage: `pending` → `processing` → `ready` | `failed`."
    ),
    responses={
        200: {"description": "Document found and owned by the user."},
        401: {"description": "Missing or invalid Bearer token."},
        404: {"description": "Document not found or not owned by the requesting user."},
    },
)
async def v1_get_document(
    document_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> V1DocumentDetailResponse:
    """Get details for a single document, enforcing user ownership."""
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)

    document = await doc_repo.get_by_id(document_id, user_id=user_id)
    if document is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Document {document_id} not found.",
        )

    return V1DocumentDetailResponse.model_validate(document)


# ---------------------------------------------------------------------------
# DELETE /api/v1/documents/{document_id}
# ---------------------------------------------------------------------------


@v1_documents_router.delete(
    "/{document_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a document and all associated data",
    description=(
        "**DELETE /api/v1/documents/{document_id}**\n\n"
        "Permanently deletes the document record, all embedding chunks, the "
        "vector-store index entries, and the raw file from object storage.\n\n"
        "**Ownership enforcement:** only the owning user can delete a document. "
        "Attempting to delete another user's document returns HTTP 404.\n\n"
        "Object-storage and vector-store deletions are **best-effort** — the "
        "database record is removed first. Orphaned storage objects do not "
        "affect API correctness.\n\n"
        "Returns **HTTP 204 No Content** on success."
    ),
    responses={
        204: {"description": "Document deleted successfully."},
        401: {"description": "Missing or invalid Bearer token."},
        404: {"description": "Document not found or not owned by the requesting user."},
    },
)
async def v1_delete_document(
    document_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a document and all associated storage and index data.

    Mirrors the non-versioned ``DELETE /documents/{document_id}`` handler.
    """
    user_id = uuid.UUID(current_user.sub)
    doc_repo = DocumentRepository(db)

    document = await doc_repo.get_by_id(document_id, user_id=user_id)
    if document is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Document {document_id} not found.",
        )

    minio_key = document.minio_key

    # Remove from PostgreSQL (cascades to DocumentChunk rows)
    await doc_repo.delete(document_id, user_id)
    await db.commit()

    # Remove embeddings — best-effort (graceful degradation)
    try:
        await rag_service.delete_embeddings(str(document_id), str(user_id))
    except Exception:
        logger.warning(
            "v1: embedding deletion failed for document %s (best-effort)", document_id
        )

    # Remove raw file from MinIO — best-effort
    try:
        await rag_service.delete_file_minio(minio_key)
    except Exception:
        logger.warning(
            "v1: MinIO file deletion failed for document %s (best-effort)", document_id
        )


# ===========================================================================
# /api/v1/rag  — Versioned RAG retrieval and question-answering router
# ===========================================================================
#
# Endpoints:
#   POST /api/v1/rag/search   — semantic retrieval (no LLM, returns ranked chunks)
#   POST /api/v1/rag/ask      — retrieval + LLM answer generation
# ===========================================================================

v1_rag_router = APIRouter(
    prefix="/api/v1/rag",
    tags=["rag-v1"],
    dependencies=[Depends(get_current_user)],
)


def _get_rag_pipeline():
    """Build a RAGPipeline from application-level singletons.

    Using lazy imports keeps startup fast and avoids circular imports.
    The embedding provider and vector store share the same configuration
    as the document ingestion pipeline so embeddings are comparable.
    """
    from app.embedding import EmbeddingConfig, SentenceTransformerEmbeddingProvider
    from app.llm.service import get_llm_service
    from app.rag.pipeline import RAGPipeline
    from app.rag.retriever import RetrievalConfig, VectorRetriever
    from app.vector import ChromaConfig, ChromaVectorStore

    embedding_provider = SentenceTransformerEmbeddingProvider(EmbeddingConfig())
    vector_store = ChromaVectorStore(ChromaConfig.from_settings())
    retriever = VectorRetriever(
        embedding_provider=embedding_provider,
        vector_store=vector_store,
        llm_service=get_llm_service(),
        config=RetrievalConfig(),
    )
    return RAGPipeline(retriever=retriever)


# ---------------------------------------------------------------------------
# POST /api/v1/rag/search
# ---------------------------------------------------------------------------


@v1_rag_router.post(
    "/search",
    response_model=V1SearchResponse,
    status_code=status.HTTP_200_OK,
    summary="Semantic search over user documents",
    description=(
        "**POST /api/v1/rag/search**\n\n"
        "Performs vector similarity search over the authenticated user's document "
        "corpus and returns the most relevant chunks **without** generating an LLM "
        "answer.  Use `POST /api/v1/rag/ask` when an LLM-generated answer is needed.\n\n"
        "**Request:**\n"
        "```json\n"
        '{"query": "...", "top_k": 5, "min_similarity": 0.4, "document_ids": null}\n'
        "```\n\n"
        "**Response** — ordered by similarity descending:\n"
        "```json\n"
        '{"query": "...", "sources": [{...}], "total_sources": 3}\n'
        "```\n\n"
        "**When no relevant documents are found** the response contains an empty "
        "`sources` list with `total_sources: 0` — HTTP 200 is still returned.\n\n"
        "Each source includes `document_name`, `page_number`, `excerpt`, `similarity`, "
        "and `retrieval_path` for full citation support."
    ),
    responses={
        200: {"description": "Search completed. Sources list may be empty."},
        400: {"description": "Blank or invalid query."},
        401: {"description": "Missing or invalid Bearer token."},
        422: {"description": "Request body validation failed."},
    },
)
async def v1_rag_search(
    request: V1SearchRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> V1SearchResponse:
    """Semantic retrieval — returns ranked chunks without LLM generation.

    Embedding and vector search failures are handled gracefully: an empty
    sources list is returned rather than raising a 5xx error, preventing
    internal infrastructure details from leaking to API consumers.
    """
    user_id = current_user.sub

    try:
        from app.rag.retriever import RetrievalConfig, VectorRetriever
        from app.embedding import EmbeddingConfig, SentenceTransformerEmbeddingProvider
        from app.vector import ChromaConfig, ChromaVectorStore

        embedding_provider = SentenceTransformerEmbeddingProvider(EmbeddingConfig())
        vector_store = ChromaVectorStore(ChromaConfig.from_settings())
        retriever = VectorRetriever(
            embedding_provider=embedding_provider,
            vector_store=vector_store,
            config=RetrievalConfig(
                top_k=request.top_k,
                min_similarity=request.min_similarity,
            ),
        )

        result = await retriever.retrieve(
            user_id=user_id,
            query=request.query,
            document_ids=request.document_ids,
        )

    except Exception as exc:
        logger.warning(
            "v1 RAG search failed gracefully for user=%s query=%r: %s",
            user_id, request.query[:80], exc,
        )
        return V1SearchResponse(query=request.query, sources=[], total_sources=0)

    sources = [
        V1SearchSource(
            document_id=rc.chunk.document_id,
            document_name=rc.chunk.document_name,
            page_number=rc.chunk.page_number,
            chunk_index=rc.chunk.chunk_index,
            excerpt=rc.chunk.text[:200],
            similarity=rc.similarity,
            retrieval_path=rc.retrieval_path,
        )
        for rc in result.chunks
    ]

    return V1SearchResponse(
        query=request.query,
        sources=sources,
        total_sources=len(sources),
    )


# ---------------------------------------------------------------------------
# POST /api/v1/rag/ask
# ---------------------------------------------------------------------------


@v1_rag_router.post(
    "/ask",
    response_model=V1AskResponse,
    status_code=status.HTTP_200_OK,
    summary="Answer a question using retrieved document context (RAG)",
    description=(
        "**POST /api/v1/rag/ask**\n\n"
        "Performs the full RAG pipeline: semantic retrieval → context assembly → "
        "LLM answer generation.\n\n"
        "**Request:**\n"
        "```json\n"
        '{"question": "...", "top_k": 5, "min_similarity": 0.0, "document_ids": null}\n'
        "```\n\n"
        "**Response:**\n"
        "```json\n"
        '{"question": "...", "answer": "...", "sources": [{...}], "has_sources": true}\n'
        "```\n\n"
        "**No-results handling:** when no relevant documents are found the LLM is "
        "**not** called and `answer` contains a graceful 'not found' message. "
        "`has_sources` is `false`.\n\n"
        "**LLM failure handling:** if the LLM call fails, `sources` are still returned "
        "with a generic `answer` so the client can display retrieved content even "
        "without a generated response.\n\n"
        "All citations include `document_name` and `page_number` (PDF/DOCX) so clients "
        "can render full source references."
    ),
    responses={
        200: {"description": "Answer generated. May contain a 'not found' message when no documents matched."},
        400: {"description": "Blank or invalid question."},
        401: {"description": "Missing or invalid Bearer token."},
        422: {"description": "Request body validation failed."},
    },
)
async def v1_rag_ask(
    request: V1AskRequest,
    current_user: TokenPayload = Depends(get_current_user),
) -> V1AskResponse:
    """Full RAG QA: retrieve relevant chunks and generate a grounded answer.

    Delegates to :class:`~app.rag.pipeline.RAGPipeline` which handles all
    error cases internally — this endpoint never returns a 5xx for LLM or
    vector-search failures.
    """
    user_id = current_user.sub

    try:
        pipeline = _get_rag_pipeline()
        rag_answer = await pipeline.ask(
            user_id=user_id,
            question=request.question,
            top_k=request.top_k,
            document_ids=request.document_ids,
        )
    except Exception as exc:
        # Catch any unexpected pipeline-construction errors (e.g. config missing)
        # so internal details are never exposed.
        logger.error(
            "v1 RAG ask pipeline construction failed for user=%s: %s", user_id, exc
        )
        return V1AskResponse(
            question=request.question,
            answer="An error occurred while processing your request. Please try again.",
            sources=[],
            has_sources=False,
        )

    sources = [
        V1AskSource(
            document_id=src.get("document_id", ""),
            document_name=src.get("document_name", ""),
            page_number=src.get("page_number"),
            chunk_index=src.get("chunk_index", 0),
            excerpt=src.get("excerpt", ""),
        )
        for src in rag_answer.sources
    ]

    return V1AskResponse(
        question=request.question,
        answer=rag_answer.answer,
        sources=sources,
        has_sources=rag_answer.has_sources,
    )
