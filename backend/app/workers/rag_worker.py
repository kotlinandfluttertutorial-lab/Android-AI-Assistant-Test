"""Celery worker task for document ingestion.

The ``ingest_document_task`` task runs the full RAG pipeline:
  1. Updates Job status → running, increments retry_count on each retry
  2. Fetches Document from DB and downloads file from MinIO
  3. Extracts text (raises ExtractionError on failure)
  4. Chunks text with tiktoken
  5. Generates embeddings and stores in ChromaDB + PostgreSQL
  6. Updates Document.ingestion_status → ready, Job.status → completed
  7. Sends FCM push notification

Retry policy (Requirements 27.1–27.4, Property 29):
  - Up to 3 retries with exponential backoff: countdown = 2 ** attempt_number
    (attempt 0 → 1 s, attempt 1 → 2 s, attempt 2 → 4 s)
  - After 3rd failure: permanently mark Document → failed, Job → failed

Requirements: 4.2, 4.4, 4.5, 4.8, 16.1, 27.1, 27.2, 27.3, 27.4
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid

from celery.exceptions import MaxRetriesExceededError

from app.workers.celery_app import celery_app

logger = logging.getLogger(__name__)


def _make_session_factory():
    """Create a fresh async engine + session factory.

    Called once per task execution so each task gets its own connection pool
    and event loop, avoiding asyncpg 'Future attached to a different loop' errors.
    """
    from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

    from app.config.settings import get_settings

    settings = get_settings()
    engine = create_async_engine(
        settings.DATABASE_URL,
        pool_pre_ping=True,
        pool_size=2,
        max_overflow=2,
    )
    session_factory = async_sessionmaker(
        bind=engine,
        class_=AsyncSession,
        expire_on_commit=False,
    )
    return engine, session_factory


@celery_app.task(
    bind=True,
    name="app.workers.rag_worker.ingest_document_task",
    max_retries=3,
)
def ingest_document_task(self, document_id: str, user_id: str) -> dict:
    """Celery task: full RAG ingestion pipeline for a single document."""
    try:
        return asyncio.run(_run_ingestion(self, document_id, user_id))
    except MaxRetriesExceededError:
        # All retries exhausted — perform permanent failure cleanup
        logger.error(
            "ingest_document_task: max retries exceeded for document=%s user=%s",
            document_id,
            user_id,
        )
        asyncio.run(_handle_permanent_failure(document_id, user_id))
        return {"status": "failed", "document_id": document_id}


async def _run_ingestion(task, document_id: str, user_id: str) -> dict:
    """Async ingestion pipeline — creates a fresh DB engine per invocation."""
    from app.models.document import IngestionStatus
    from app.models.job import Job, JobStatus
    from app.repositories.document_repository import DocumentRepository
    from app.repositories.job_repository import JobRepository
    from app.services.rag_service import ExtractionError, rag_service
    from sqlalchemy import select

    doc_uuid = uuid.UUID(document_id)
    user_uuid = uuid.UUID(user_id)

    # Fresh engine + session factory — never reuses connections from another loop
    engine, AsyncSessionLocal = _make_session_factory()

    try:
        async with AsyncSessionLocal() as db:
            doc_repo = DocumentRepository(db)
            job_repo = JobRepository(db)

            # Step 1 — find Job and mark running
            result = await db.execute(
                select(Job)
                .where(Job.user_id == user_uuid)
                .where(Job.job_type == "document_ingestion")
                .order_by(Job.created_at.desc())
                .limit(1)
            )
            job = result.scalar_one_or_none()

            if job is not None:
                await job_repo.update_status(job.id, JobStatus.running)
                current_retry = task.request.retries
                if current_retry > 0:
                    job.retry_count = current_retry
                await db.commit()

            # Step 2 — fetch Document and download from MinIO
            document = await doc_repo.get_by_id(doc_uuid, user_id=user_uuid)
            if document is None:
                logger.error("Document %s not found for user %s", document_id, user_id)
                if job is not None:
                    await job_repo.update_status(
                        job.id, JobStatus.failed,
                        error_message=f"Document {document_id} not found.",
                    )
                    await db.commit()
                return {"status": "failed", "document_id": document_id}

            await doc_repo.update_status(doc_uuid, IngestionStatus.processing)
            await db.commit()

            try:
                file_bytes = await rag_service.download_file_minio(document.minio_key)
            except Exception as exc:
                logger.warning("MinIO download failed (attempt %d): %s", task.request.retries, exc)
                countdown = 2 ** task.request.retries
                try:
                    raise task.retry(exc=exc, countdown=countdown, max_retries=3)
                except MaxRetriesExceededError:
                    await doc_repo.update_status(doc_uuid, IngestionStatus.failed)
                    if job is not None:
                        await job_repo.update_status(
                            job.id, JobStatus.failed,
                            error_message=json.dumps({"error": "download_failed", "detail": str(exc)}),
                        )
                    await db.commit()
                    await rag_service.send_ingestion_failure_notification(user_id, document_id)
                    return {"status": "failed", "document_id": document_id}

            # Step 3 — extract text
            try:
                extracted_text, page_count = await rag_service.extract_text(
                    file_bytes, document.mime_type, document.file_name
                )
            except ExtractionError as exc:
                logger.warning("Extraction failed: %s", exc)
                await doc_repo.update_status(doc_uuid, IngestionStatus.failed)
                if job is not None:
                    await job_repo.update_status(
                        job.id, JobStatus.failed,
                        error_message=json.dumps({
                            "error": "extraction_failed",
                            "stage": exc.stage,
                            "file_name": exc.file_name,
                            "detail": exc.detail,
                        }),
                    )
                await db.commit()
                return {"status": "failed", "document_id": document_id}

            # Step 4 — chunk
            is_plain_text = document.mime_type in {"text/plain", "text/markdown"} or \
                document.file_name.lower().endswith((".txt", ".md"))
            chunks = rag_service.chunk_text(extracted_text, is_plain_text=is_plain_text)

            # Step 5 — embed and store
            try:
                await rag_service.embed_and_store(chunks, document_id, user_id, db)
            except Exception as exc:
                logger.warning("embed_and_store failed (attempt %d): %s", task.request.retries, exc)
                countdown = 2 ** task.request.retries
                try:
                    raise task.retry(exc=exc, countdown=countdown, max_retries=3)
                except MaxRetriesExceededError:
                    await doc_repo.update_status(doc_uuid, IngestionStatus.failed)
                    if job is not None:
                        await job_repo.update_status(
                            job.id, JobStatus.failed,
                            error_message=json.dumps({"error": "embedding_failed", "detail": str(exc)}),
                        )
                    await db.commit()
                    await rag_service.send_ingestion_failure_notification(user_id, document_id)
                    return {"status": "failed", "document_id": document_id}

            # Step 6 — mark ready
            await doc_repo.update_status(doc_uuid, IngestionStatus.ready, page_count=page_count)
            if job is not None:
                await job_repo.update_status(
                    job.id, JobStatus.completed,
                    result_payload={"document_id": document_id, "chunk_count": len(chunks)},
                )
            await db.commit()

    finally:
        await engine.dispose()

    # Step 7 — push notification (outside session)
    await rag_service.send_ingestion_notification(user_id, document_id)
    logger.info("ingest_document_task: completed document=%s chunks=%d", document_id, len(chunks))
    return {"status": "completed", "document_id": document_id}


async def _handle_permanent_failure(document_id: str, user_id: str) -> None:
    """Mark document and job as permanently failed."""
    from app.database import AsyncSessionLocal
    from app.models.document import IngestionStatus
    from app.models.job import Job, JobStatus
    from app.repositories.document_repository import DocumentRepository
    from app.repositories.job_repository import JobRepository
    from app.services.rag_service import rag_service
    from sqlalchemy import select

    doc_uuid = uuid.UUID(document_id)
    user_uuid = uuid.UUID(user_id)
    try:
        async with AsyncSessionLocal() as db:
            doc_repo = DocumentRepository(db)
            job_repo = JobRepository(db)

            await doc_repo.update_status(doc_uuid, IngestionStatus.failed)

            result = await db.execute(
                select(Job)
                .where(Job.user_id == user_uuid)
                .where(Job.job_type == "document_ingestion")
                .order_by(Job.created_at.desc())
                .limit(1)
            )
            job = result.scalar_one_or_none()
            if job is not None:
                await job_repo.update_status(
                    job.id, JobStatus.failed,
                    error_message="Max retries exceeded. Document processing permanently failed.",
                )
                job.retry_count = 3
            await db.commit()
    except Exception as exc:
        logger.error("Failed to write permanent-failure state for document=%s: %s", document_id, exc)

    # Send FCM failure notification (best-effort)
    await rag_service.send_ingestion_failure_notification(user_id, document_id)
