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
    except Exception as exc:  # noqa: BLE001
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
    except Exception as exc:  # noqa: BLE001
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
    "/",
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

            completion = await orchestrator.complete(
                prompt=rag_prompt,
                provider=provider,
                max_tokens=1024,
                user_id=str(user_id),
            )
            answer = completion.text

        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "AI Orchestrator unavailable for RAG query; returning context only. Error: %s",
                exc,
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

            completion = await orchestrator.complete(
                prompt=rag_prompt,
                provider=provider,
                max_tokens=1024,
                user_id=str(user_id),
            )
            answer = completion.text

        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "AI Orchestrator unavailable for per-document RAG query; returning context only. Error: %s",
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
    except Exception:  # noqa: BLE001
        logger.warning(
            "ChromaDB embedding deletion failed for document %s (best-effort)",
            document_id,
        )

    # Remove file from MinIO (best-effort, graceful degradation)
    try:
        await rag_service.delete_file_minio(minio_key)
    except Exception:  # noqa: BLE001
        logger.warning(
            "MinIO file deletion failed for document %s (best-effort)", document_id
        )


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
