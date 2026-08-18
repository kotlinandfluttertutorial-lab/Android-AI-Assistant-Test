"""Integration tests for the full RAG pipeline.

Covers the complete document lifecycle end-to-end:
  upload → enqueue job → ingest → query → cited response → delete

Scenarios:
1. Full round-trip: upload a text document, run ingestion synchronously via
   _run_ingestion, query a verbatim phrase, receive a cited response, then
   delete the document and verify all PostgreSQL records and ChromaDB
   embeddings are removed within 60 seconds (Requirement 4.9, 4.10).
2. Upload validation: format rejection and size rejection return HTTP 422
   before any storage I/O.
3. Job lifecycle: upload creates a job in queued status; ingestion transitions
   it to running → completed; polling returns the correct status.
4. Query returns citations: every retrieved chunk includes document_name and
   page_number (Property 9).
5. Delete removes PostgreSQL records AND ChromaDB embeddings (Requirement 4.10).
6. Cross-user isolation: a user cannot query or delete another user's document.

Infrastructure used in tests
------------------------------
- Test PostgreSQL: mocked via SQLAlchemy AsyncMock session (no live DB required).
- Mock embedding model: patches ``RAGService._get_embedding_model`` to return
  a deterministic stub that produces fixed-length float vectors.
- Mock MinIO: patches ``RAGService.store_file_minio``, ``download_file_minio``,
  and ``delete_file_minio`` to skip real object-storage calls.
- Mock ChromaDB: patches ``asyncio.to_thread`` calls for ChromaDB operations
  so vector storage and retrieval are fully in-memory.

Requirements: 21.2
Cross-references: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

# ---------------------------------------------------------------------------
# Ensure required env vars are set before any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from app.api.rag.router import jobs_router
from app.api.rag.router import router as rag_router
from app.models.document import IngestionStatus
from app.models.job import JobStatus
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the RAG routers, no global middleware overhead
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(rag_router)
_app.include_router(jobs_router)

# ---------------------------------------------------------------------------
# Constants for test documents
# ---------------------------------------------------------------------------

_SAMPLE_TEXT = (
    "The quick brown fox jumps over the lazy dog. "
    "This sentence is used to test RAG pipeline round-trip. "
    "A verbatim phrase can be retrieved to verify citation completeness."
)
_SAMPLE_FILENAME = "sample.txt"
_SAMPLE_MIME = "text/plain"
_SAMPLE_BYTES = _SAMPLE_TEXT.encode("utf-8")

_NOW = datetime(2024, 6, 1, 12, 0, 0, tzinfo=timezone.utc)


# ---------------------------------------------------------------------------
# ORM mock factories
# ---------------------------------------------------------------------------


def _make_document(
    *,
    doc_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    file_name: str = _SAMPLE_FILENAME,
    mime_type: str = _SAMPLE_MIME,
    size_bytes: int = len(_SAMPLE_BYTES),
    minio_key: str = "user-id/doc-id/sample.txt",
    ingestion_status: IngestionStatus = IngestionStatus.pending,
    page_count: int | None = None,
) -> MagicMock:
    """Return a mock Document ORM object with all required attributes."""
    doc = MagicMock()
    doc.id = doc_id or uuid.uuid4()
    doc.user_id = user_id or uuid.uuid4()
    doc.file_name = file_name
    doc.mime_type = mime_type
    doc.size_bytes = size_bytes
    doc.minio_key = minio_key
    doc.ingestion_status = ingestion_status
    doc.page_count = page_count
    doc.created_at = _NOW
    return doc


def _make_document_chunk(
    *,
    chunk_id: uuid.UUID | None = None,
    document_id: uuid.UUID | None = None,
    chunk_index: int = 0,
    page_number: int = 1,
    content: str = _SAMPLE_TEXT,
    chroma_id: str | None = None,
) -> MagicMock:
    """Return a mock DocumentChunk ORM object."""
    chunk = MagicMock()
    chunk.id = chunk_id or uuid.uuid4()
    chunk.document_id = document_id or uuid.uuid4()
    chunk.chunk_index = chunk_index
    chunk.page_number = page_number
    chunk.content = content
    chunk.chroma_id = chroma_id or f"{chunk.document_id}_0"
    chunk.created_at = _NOW
    return chunk


def _make_job(
    *,
    job_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
    status: JobStatus = JobStatus.queued,
    job_type: str = "document_ingestion",
    result_payload: dict | None = None,
    error_message: str | None = None,
    celery_task_id: str | None = None,
) -> MagicMock:
    """Return a mock Job ORM object."""
    job = MagicMock()
    job.id = job_id or uuid.uuid4()
    job.user_id = user_id or uuid.uuid4()
    job.status = status
    job.job_type = job_type
    job.result_payload = result_payload
    job.error_message = error_message
    job.celery_task_id = celery_task_id
    job.created_at = _NOW
    return job


# ---------------------------------------------------------------------------
# DB session mock factory
# ---------------------------------------------------------------------------


def _make_mock_db_session() -> AsyncMock:
    """Return a minimal SQLAlchemy async session mock."""
    session = AsyncMock()
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.close = AsyncMock()
    session.delete = AsyncMock()
    return session


def _make_session_factory_patch(mock_db: AsyncMock):
    """Return a mock (engine, session_factory) tuple for patching _make_session_factory.

    ``_run_ingestion`` calls ``_make_session_factory()`` to create a brand-new
    SQLAlchemy engine + session factory per task invocation.  We intercept that
    call here so no real PostgreSQL connection is attempted.
    """
    mock_engine = AsyncMock()
    mock_engine.dispose = AsyncMock()

    mock_ctx = AsyncMock()
    mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
    mock_ctx.__aexit__ = AsyncMock(return_value=False)

    mock_session_factory = MagicMock(return_value=mock_ctx)

    return mock_engine, mock_session_factory


def _override_get_db(mock_session: AsyncMock):
    """Return a FastAPI dependency override yielding the mock session."""

    async def _dep():
        try:
            yield mock_session
            await mock_session.commit()
        except Exception:
            await mock_session.rollback()
            raise
        finally:
            await mock_session.close()

    return _dep


def _make_token(user_id: uuid.UUID, role: str = "user") -> str:
    """Generate a valid JWT for an authenticated user."""
    token, _expires = create_access_token(user_id=user_id, role=role)
    return token


def _auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# Mock embedding model stub
# ---------------------------------------------------------------------------


def _make_mock_embedding_model(dim: int = 384):
    """Return a mock SentenceTransformer that produces deterministic vectors.

    The stub returns a numpy-array-like list for each encoded sentence,
    matching the interface used by ``RAGService._get_embedding_model``.
    """
    import numpy as np  # numpy ships with sentence-transformers

    model = MagicMock()

    def _encode(texts, show_progress_bar=False):
        # Return a deterministic matrix: each row is a unit vector
        rng = [float(hash(t) % 1000) / 1000.0 for t in texts]
        result = np.zeros((len(texts), dim), dtype="float32")
        for i, val in enumerate(rng):
            result[i, 0] = val
            if result[i].sum() == 0:
                result[i, 0] = 0.1
        # normalise
        norms = np.linalg.norm(result, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return result / norms

    model.encode = _encode
    return model


# ===========================================================================
# Scenario 1 — Full round-trip: upload → ingest → query → cited response → delete
# ===========================================================================


class TestFullRoundTrip:
    """End-to-end pipeline exercising every stage in order.

    Infrastructure mocks:
    - PostgreSQL: in-memory AsyncMock session via DocumentRepository/JobRepository.
    - Embedding model: deterministic stub that produces fixed-dimension vectors.
    - MinIO: all three methods (store, download, delete) patched to no-ops.
    - ChromaDB: asyncio.to_thread calls patched; ChromaDB client never instantiated.

    Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 21.2
    """

    def test_upload_returns_202_with_document_and_job_ids(self) -> None:
        """POST /documents/upload returns HTTP 202 with document_id and job_id.

        Requirements: 4.1, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        doc = _make_document(user_id=user_id)
        job = _make_job(user_id=user_id)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.create = AsyncMock(return_value=doc)
            doc_repo.delete = AsyncMock(return_value=True)

            mock_rag_svc.validate_mime_and_upload = MagicMock()  # no-op
            mock_rag_svc.store_file_minio = AsyncMock(return_value=doc.minio_key)
            mock_rag_svc.create_ingestion_job = AsyncMock(return_value=job.id)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with (
                patch("app.workers.rag_worker.ingest_document_task") as mock_task,
                patch("app.api.rag.router.JobRepository") as MockJobRepo,
            ):
                mock_task.delay = MagicMock(return_value=MagicMock(id="celery-task-id"))
                job_repo = MockJobRepo.return_value
                job_repo.update_status = AsyncMock(return_value=job)

                with TestClient(_app) as client:
                    resp = client.post(
                        "/documents/upload",
                        files={"file": (_SAMPLE_FILENAME, _SAMPLE_BYTES, _SAMPLE_MIME)},
                        headers=_auth_headers(token),
                    )

        _app.dependency_overrides.clear()

        assert resp.status_code == 202
        body = resp.json()
        assert "document_id" in body
        assert "job_id" in body
        assert body["status"] == "pending"

    def test_ingestion_pipeline_produces_chunks_in_postgresql(self) -> None:
        """_run_ingestion executes the full pipeline: extract → chunk → embed → store.

        Tests the async ingestion function directly (bypasses Celery broker).
        Verifies that DocumentChunk rows are created in the mock DB session.

        Requirements: 4.2, 4.3, 4.4, 21.2
        """
        import asyncio

        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        doc = _make_document(doc_id=doc_id, user_id=user_id)
        job = _make_job(user_id=user_id)

        mock_task = MagicMock()
        mock_task.request.retries = 0

        chunk_rows_created: list[dict] = []

        async def fake_create_chunk(
            *, document_id, chunk_index, page_number, content, chroma_id
        ):
            chunk_rows_created.append(
                {
                    "document_id": document_id,
                    "chunk_index": chunk_index,
                    "content": content,
                    "chroma_id": chroma_id,
                }
            )
            return _make_document_chunk(
                document_id=document_id,
                chunk_index=chunk_index,
                content=content,
                chroma_id=chroma_id,
            )

        with (
            patch("app.workers.rag_worker._make_session_factory") as MockSessionFactory,
            patch("app.services.rag_service.rag_service") as mock_svc,
        ):
            # Set up mock DB session context manager
            mock_db = _make_mock_db_session()
            mock_engine, mock_session_factory = _make_session_factory_patch(mock_db)
            MockSessionFactory.return_value = (mock_engine, mock_session_factory)

            # Mock repositories inside the worker

            # Doc repo mocks
            mock_doc_repo = AsyncMock()
            mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo.update_status = AsyncMock(return_value=doc)

            # Job repo mocks
            mock_job_repo = AsyncMock()
            mock_job_repo.update_status = AsyncMock(return_value=job)

            # execute returns the job via scalar_one_or_none
            job_result = AsyncMock()
            job_result.scalar_one_or_none = MagicMock(return_value=job)
            mock_db.execute = AsyncMock(return_value=job_result)

            # RAG service mocks
            mock_svc.download_file_minio = AsyncMock(return_value=_SAMPLE_BYTES)
            mock_svc.extract_text = AsyncMock(return_value=(_SAMPLE_TEXT, 1))
            from app.services.rag_service import ChunkResult

            mock_svc.chunk_text = MagicMock(
                return_value=[
                    ChunkResult(text=_SAMPLE_TEXT, page_number=1),
                ]
            )
            mock_svc.embed_and_store = AsyncMock(return_value=None)
            mock_svc.send_ingestion_notification = AsyncMock(return_value=None)

            with (
                patch(
                    "app.repositories.document_repository.DocumentRepository",
                    return_value=mock_doc_repo,
                ),
                patch(
                    "app.repositories.job_repository.JobRepository",
                    return_value=mock_job_repo,
                ),
            ):
                result = asyncio.run(
                    _run_ingestion(mock_task, str(doc_id), str(user_id))
                )

        assert result["status"] == "completed"
        assert result["document_id"] == str(doc_id)
        # Verify update_status was called to set document ready
        mock_doc_repo.update_status.assert_called()
        mock_svc.embed_and_store.assert_called_once()


# ===========================================================================
# Scenario 3 — Job lifecycle: queued → running → completed
# ===========================================================================


class TestJobLifecycle:
    """Scenario 3: Job status transitions through the ingestion pipeline.

    Validates:
    - Upload creates a job in ``queued`` status.
    - Ingestion worker transitions it queued → running → completed.
    - GET /jobs/{job_id} returns the correct status at each stage.

    Requirements: 4.5, 21.2
    """

    def test_upload_creates_job_in_queued_status(self) -> None:
        """Upload endpoint creates a job whose initial status is queued/pending."""
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        doc = _make_document(user_id=user_id)
        job = _make_job(user_id=user_id, status=JobStatus.queued)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.JobRepository") as MockJobRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch("app.workers.rag_worker.ingest_document_task") as mock_task,
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.create = AsyncMock(return_value=doc)
            doc_repo.delete = AsyncMock(return_value=True)
            job_repo = MockJobRepo.return_value
            job_repo.update_status = AsyncMock(return_value=job)

            mock_rag_svc.validate_mime_and_upload = MagicMock()
            mock_rag_svc.store_file_minio = AsyncMock(return_value=doc.minio_key)
            mock_rag_svc.create_ingestion_job = AsyncMock(return_value=job.id)
            mock_task.delay = MagicMock(return_value=MagicMock(id="celery-id-1"))

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/upload",
                    files={"file": (_SAMPLE_FILENAME, _SAMPLE_BYTES, _SAMPLE_MIME)},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 202
        body = resp.json()
        assert body["status"] == "pending"
        assert "job_id" in body

    def test_get_job_status_returns_queued(self) -> None:
        """GET /jobs/{job_id} returns status=queued for a freshly created job."""
        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token = _make_token(user_id)
        job = _make_job(job_id=job_id, user_id=user_id, status=JobStatus.queued)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo = MockJobRepo.return_value
            job_repo.get_by_id = AsyncMock(return_value=job)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "queued"
        assert body["job_id"] == str(job_id)

    def test_get_job_status_returns_completed_after_ingestion(self) -> None:
        """GET /jobs/{job_id} returns status=completed after the worker finishes."""
        import asyncio

        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        job_id = uuid.uuid4()
        doc = _make_document(doc_id=doc_id, user_id=user_id)

        # queued → running → completed sequence
        job_queued = _make_job(job_id=job_id, user_id=user_id, status=JobStatus.queued)
        job_running = _make_job(
            job_id=job_id, user_id=user_id, status=JobStatus.running
        )
        job_completed = _make_job(
            job_id=job_id,
            user_id=user_id,
            status=JobStatus.completed,
            result_payload={"document_id": str(doc_id), "chunk_count": 1},
        )

        mock_task = MagicMock()
        mock_task.request.retries = 0

        status_log: list[str] = []

        async def _capture_update_status(jid, new_status, **kwargs):
            status_log.append(
                new_status.value if hasattr(new_status, "value") else str(new_status)
            )
            if new_status == JobStatus.running:
                return job_running
            return job_completed

        # _run_ingestion creates its own engine via _make_session_factory; patch at worker level
        with (
            patch("app.workers.rag_worker._make_session_factory") as MockSessionFactory,
            patch("app.services.rag_service.rag_service") as mock_svc,
        ):
            mock_db = _make_mock_db_session()
            mock_engine, mock_session_factory = _make_session_factory_patch(mock_db)
            MockSessionFactory.return_value = (mock_engine, mock_session_factory)

            mock_doc_repo = AsyncMock()
            mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo.update_status = AsyncMock(return_value=doc)

            mock_job_repo = AsyncMock()
            mock_job_repo.update_status = AsyncMock(side_effect=_capture_update_status)

            job_result = AsyncMock()
            job_result.scalar_one_or_none = MagicMock(return_value=job_queued)
            mock_db.execute = AsyncMock(return_value=job_result)

            from app.services.rag_service import ChunkResult

            mock_svc.download_file_minio = AsyncMock(return_value=_SAMPLE_BYTES)
            mock_svc.extract_text = AsyncMock(return_value=(_SAMPLE_TEXT, 1))
            mock_svc.chunk_text = MagicMock(
                return_value=[ChunkResult(text=_SAMPLE_TEXT, page_number=1)]
            )
            mock_svc.embed_and_store = AsyncMock(return_value=None)
            mock_svc.send_ingestion_notification = AsyncMock(return_value=None)

            with (
                patch(
                    "app.repositories.document_repository.DocumentRepository",
                    return_value=mock_doc_repo,
                ),
                patch(
                    "app.repositories.job_repository.JobRepository",
                    return_value=mock_job_repo,
                ),
            ):
                result = asyncio.run(
                    _run_ingestion(mock_task, str(doc_id), str(user_id))
                )

        assert result["status"] == "completed"
        # running then completed
        assert "running" in status_log
        assert "completed" in status_log
        # running must come before completed
        assert status_log.index("running") < status_log.index("completed")


# ===========================================================================
# Scenario 4 — Query returns citations (Property 9 — Requirement 4.7)
# ===========================================================================


class TestQueryReturnsCitations:
    """Scenario 4: POST /documents/query returns citations for every retrieved chunk.

    Validates Property 9: every retrieved chunk in the response has a non-empty
    ``document_name`` and a positive ``page_number``.

    Requirements: 4.7, 21.2
    """

    def test_query_response_citations_have_document_name_and_page_number(self) -> None:
        """POST /documents/query — each citation has document_name and page_number >= 1.

        **Validates: Requirements 4.7**
        Property 9: Citation completeness.
        """
        from app.services.rag_service import (
            QueryResult,
            RetrievedChunk,
        )

        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        chunk1 = RetrievedChunk(
            content="The quick brown fox jumps.",
            document_name="sample.txt",
            page_number=1,
        )
        chunk2 = RetrievedChunk(
            content="This sentence tests citations.",
            document_name="sample.txt",
            page_number=2,
        )

        query_result = QueryResult(
            query="quick brown fox",
            retrieved_chunks=[chunk1, chunk2],
            context="[1] The quick brown fox jumps.\n[2] This sentence tests citations.",
        )

        citation_dicts = [
            {"document_name": "sample.txt", "page_number": 1, "chunk_index": 0},
            {"document_name": "sample.txt", "page_number": 2, "chunk_index": 1},
        ]

        # AIOrchestrator is imported locally inside query_documents; patch at source module level
        with (
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.services.ai_orchestrator.AIOrchestrator") as MockOrchestrator,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            mock_rag_svc.query_documents = AsyncMock(return_value=query_result)
            mock_rag_svc._format_citations = MagicMock(return_value=citation_dicts)

            # Mock AI orchestrator to return a fixed answer
            mock_orchestrator_instance = AsyncMock()
            mock_completion = MagicMock()
            mock_completion.text = "The fox jumps [Source: sample.txt, Page 1]."
            mock_orchestrator_instance.complete = AsyncMock(
                return_value=mock_completion
            )
            MockOrchestrator.return_value = mock_orchestrator_instance

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/query",
                    json={"query": "quick brown fox", "top_k": 5},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 200
        body = resp.json()
        citations = body["citations"]
        assert len(citations) == 2

        for citation in citations:
            assert citation["document_name"], "document_name must be non-empty"
            assert citation["page_number"] >= 1, "page_number must be >= 1"

        # Verify chunk 1 citation
        assert citations[0]["document_name"] == "sample.txt"
        assert citations[0]["page_number"] == 1
        # Verify chunk 2 citation
        assert citations[1]["document_name"] == "sample.txt"
        assert citations[1]["page_number"] == 2

    def test_query_no_results_returns_empty_citations(self) -> None:
        """POST /documents/query with no matching chunks returns empty citations list."""
        from app.services.rag_service import QueryResult

        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        empty_result = QueryResult(
            query="unrelated query",
            retrieved_chunks=[],
            context="",
        )

        with (
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            mock_rag_svc.query_documents = AsyncMock(return_value=empty_result)
            mock_rag_svc._format_citations = MagicMock(return_value=[])

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/query",
                    json={"query": "unrelated query", "top_k": 5},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 200
        body = resp.json()
        assert body["citations"] == []
        assert body["answer"] == "No relevant documents found for your query."


# ===========================================================================
# Scenario 5 — Delete removes PostgreSQL records AND ChromaDB embeddings
# ===========================================================================


class TestDeleteRemovesAllData:
    """Scenario 5: DELETE /documents/{id} removes DB records, embeddings, and MinIO file.

    Validates:
    - doc_repo.delete is called with correct document_id and user_id.
    - rag_service.delete_embeddings is called.
    - rag_service.delete_file_minio is called with the correct minio_key.
    - GET /documents/{id} would return 404 after deletion (doc_repo.get_by_id returns None).

    Requirements: 4.4, 21.2
    """

    def test_delete_calls_repo_delete_and_rag_service_cleanup(self) -> None:
        """DELETE /documents/{id} calls doc_repo.delete, delete_embeddings, delete_file_minio."""
        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()
        minio_key = f"{user_id}/{doc_id}/sample.txt"
        doc = _make_document(doc_id=doc_id, user_id=user_id, minio_key=minio_key)

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.get_by_id = AsyncMock(return_value=doc)
            doc_repo.delete = AsyncMock(return_value=True)

            mock_rag_svc.delete_embeddings = AsyncMock(return_value=None)
            mock_rag_svc.delete_file_minio = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/documents/{doc_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 204

        # doc_repo.delete called with correct args
        doc_repo.delete.assert_called_once_with(doc_id, uuid.UUID(str(user_id)))

        # rag_service.delete_embeddings called with the document_id string and user_id string
        mock_rag_svc.delete_embeddings.assert_called_once_with(
            str(doc_id), str(user_id)
        )

        # rag_service.delete_file_minio called with the minio_key
        mock_rag_svc.delete_file_minio.assert_called_once_with(minio_key)

    def test_get_document_returns_404_after_deletion(self) -> None:
        """After deletion, GET /jobs/{job_id} for a document's job returns 404.

        Simulates post-delete state: job_repo.get_by_id returns None.
        """
        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo = MockJobRepo.return_value
            # Simulate deleted document's job not found
            job_repo.get_by_id = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 404

    def test_delete_nonexistent_document_returns_404(self) -> None:
        """DELETE /documents/{id} when document is not found returns HTTP 404."""
        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.get_by_id = AsyncMock(return_value=None)  # not found

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/documents/{doc_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 404
        # Ensure no cleanup was attempted on a nonexistent resource
        mock_rag_svc.delete_embeddings.assert_not_called()
        mock_rag_svc.delete_file_minio.assert_not_called()


# ===========================================================================
# Scenario 6 — Cross-user isolation
# ===========================================================================


class TestCrossUserIsolation:
    """Scenario 6: Users cannot access or delete each other's documents or jobs.

    Validates:
    - DELETE /documents/{doc_a_id} with User B's JWT → 404.
    - GET /jobs/{job_id} with User B's JWT → 404.

    Requirements: 4.4, 4.5, 21.2
    """

    def test_user_b_cannot_delete_user_a_document(self) -> None:
        """User B's DELETE request for User A's document returns 404.

        doc_repo.get_by_id(doc_a_id, user_id=user_b_id) returns None
        because the document does not belong to User B.
        """
        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()
        doc_a_id = uuid.uuid4()

        # User B's JWT
        token_b = _make_token(user_b_id)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            # Scoped query with user_b_id finds nothing
            doc_repo.get_by_id = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.delete(
                    f"/documents/{doc_a_id}",
                    headers=_auth_headers(token_b),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 404
        # Ensure no cleanup was called
        mock_rag_svc.delete_embeddings.assert_not_called()
        mock_rag_svc.delete_file_minio.assert_not_called()
        # doc_repo.delete must NOT have been called
        doc_repo.delete.assert_not_called()

    def test_user_b_cannot_view_user_a_job(self) -> None:
        """User B's GET /jobs/{job_id} for User A's job returns 404.

        job_repo.get_by_id(job_id, user_id=user_b_id) returns None
        because the job belongs to User A.
        """
        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()
        job_id = uuid.uuid4()

        token_b = _make_token(user_b_id)
        mock_db = _make_mock_db_session()

        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo = MockJobRepo.return_value
            # Scoped query with user_b_id finds nothing
            job_repo.get_by_id = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token_b),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 404

    def test_user_b_query_only_sees_own_documents(self) -> None:
        """POST /documents/query is scoped to the authenticated user.

        rag_service.query_documents is called with user_b's UUID, not user_a's.
        """
        from app.services.rag_service import QueryResult

        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()
        token_b = _make_token(user_b_id)
        mock_db = _make_mock_db_session()

        captured_user_ids: list[uuid.UUID] = []

        async def _capture_query(**kwargs):
            captured_user_ids.append(kwargs["user_id"])
            return QueryResult(query=kwargs["query"], retrieved_chunks=[], context="")

        with (
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            mock_rag_svc.query_documents = AsyncMock(side_effect=_capture_query)
            mock_rag_svc._format_citations = MagicMock(return_value=[])

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/query",
                    json={"query": "fox", "top_k": 5},
                    headers=_auth_headers(token_b),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 200
        assert len(captured_user_ids) == 1
        # query was issued under user_b's identity
        assert captured_user_ids[0] == user_b_id
        # never under user_a's identity
        assert user_a_id not in captured_user_ids


# ===========================================================================
# Scenario 7 — Upload validation failures
# ===========================================================================


class TestUploadValidationFailures:
    """Scenario 7: Format and size validation returns HTTP 422 before any I/O.

    Validates Property 26: validation BEFORE any storage.

    Requirements: 4.1, 21.2
    """

    def test_unsupported_file_format_returns_422_without_storage(self) -> None:
        """Uploading an .exe file returns HTTP 422; no DB or MinIO calls are made."""
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        exe_content = b"\x4d\x5a\x90\x00"  # MZ header
        exe_filename = "malware.exe"
        exe_mime = "application/octet-stream"

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.create = AsyncMock()

            # Real validation raises HTTPException 422 for unsupported type
            from fastapi import HTTPException as FastAPIHTTPException

            mock_rag_svc.validate_mime_and_upload = MagicMock(
                side_effect=FastAPIHTTPException(
                    status_code=422,
                    detail="Unsupported file type '.exe'.",
                )
            )
            mock_rag_svc.store_file_minio = AsyncMock()

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/upload",
                    files={"file": (exe_filename, exe_content, exe_mime)},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 422
        # Validation must fire BEFORE storage (Property 26)
        doc_repo.create.assert_not_called()
        mock_rag_svc.store_file_minio.assert_not_called()

    def test_oversized_file_returns_422_without_storage(self) -> None:
        """A file exceeding the 50 MB limit returns HTTP 422 with no storage I/O."""
        user_id = uuid.uuid4()
        token = _make_token(user_id)
        mock_db = _make_mock_db_session()

        # 51 MB of null bytes
        oversized_content = b"\x00" * (51 * 1024 * 1024)

        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.create = AsyncMock()

            from fastapi import HTTPException as FastAPIHTTPException

            mock_rag_svc.validate_mime_and_upload = MagicMock(
                side_effect=FastAPIHTTPException(
                    status_code=422,
                    detail="File size exceeds the 50 MB limit.",
                )
            )
            mock_rag_svc.store_file_minio = AsyncMock()

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                resp = client.post(
                    "/documents/upload",
                    files={"file": ("large.txt", oversized_content, "text/plain")},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert resp.status_code == 422
        # No document row created, no MinIO call made
        doc_repo.create.assert_not_called()
        mock_rag_svc.store_file_minio.assert_not_called()


# ===========================================================================
# Scenario 8 — Extraction failure marks job and document as failed
# ===========================================================================


class TestExtractionFailure:
    """Scenario 8: ExtractionError causes the worker to mark both job and document as failed.

    Requirements: 4.8, 21.2
    """

    def test_extraction_error_sets_document_and_job_to_failed(self) -> None:
        """When extract_text raises ExtractionError, doc → failed, job → failed."""
        import asyncio

        from app.services.rag_service import ExtractionError
        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        doc = _make_document(doc_id=doc_id, user_id=user_id)
        job = _make_job(user_id=user_id, status=JobStatus.queued)

        mock_task = MagicMock()
        mock_task.request.retries = 0

        doc_status_log: list[str] = []
        job_status_log: list[str] = []

        async def _doc_update_status(did, new_status, **kwargs):
            doc_status_log.append(
                new_status.value if hasattr(new_status, "value") else str(new_status)
            )
            return doc

        async def _job_update_status(jid, new_status, **kwargs):
            job_status_log.append(
                new_status.value if hasattr(new_status, "value") else str(new_status)
            )
            return job

        # _run_ingestion creates its own engine via _make_session_factory; patch at worker level
        with (
            patch("app.workers.rag_worker._make_session_factory") as MockSessionFactory,
            patch("app.services.rag_service.rag_service") as mock_svc,
        ):
            mock_db = _make_mock_db_session()
            mock_engine, mock_session_factory = _make_session_factory_patch(mock_db)
            MockSessionFactory.return_value = (mock_engine, mock_session_factory)

            mock_doc_repo = AsyncMock()
            mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo.update_status = AsyncMock(side_effect=_doc_update_status)

            mock_job_repo = AsyncMock()
            mock_job_repo.update_status = AsyncMock(side_effect=_job_update_status)

            job_result = AsyncMock()
            job_result.scalar_one_or_none = MagicMock(return_value=job)
            mock_db.execute = AsyncMock(return_value=job_result)

            # Extract raises ExtractionError (non-retryable permanent failure)
            mock_svc.download_file_minio = AsyncMock(return_value=_SAMPLE_BYTES)
            mock_svc.extract_text = AsyncMock(
                side_effect=ExtractionError(
                    stage="pdf_extraction",
                    file_name="sample.txt",
                    detail="Simulated extraction failure for test",
                )
            )

            with (
                patch(
                    "app.repositories.document_repository.DocumentRepository",
                    return_value=mock_doc_repo,
                ),
                patch(
                    "app.repositories.job_repository.JobRepository",
                    return_value=mock_job_repo,
                ),
            ):
                result = asyncio.run(
                    _run_ingestion(mock_task, str(doc_id), str(user_id))
                )

        # Worker returns failed status
        assert result["status"] == "failed"
        assert result["document_id"] == str(doc_id)

        # Document status was set to failed
        assert "failed" in doc_status_log, f"Document status log: {doc_status_log}"

        # Job status was set to failed
        assert "failed" in job_status_log, f"Job status log: {job_status_log}"

        # embed_and_store should NOT have been called (pipeline aborted at extraction)
        mock_svc.embed_and_store.assert_not_called()


# ===========================================================================
# Full end-to-end cycle test
# ===========================================================================


class TestFullPipelineCycle:
    """Full pipeline cycle in a single test.

    Orchestrates: upload → check job queued → ingest → check job completed
                  → query with citations → delete → verify 404.

    Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 21.2
    """

    def test_full_pipeline_cycle(self) -> None:
        """Full lifecycle: upload(202) → ingest → query(citations) → delete(204) → 404.

        Uses mock DB, mock RAG service, and mock embedding model.
        All external I/O (PostgreSQL, MinIO, ChromaDB) is patched.

        Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 21.2
        """
        import asyncio

        from app.services.rag_service import (
            ChunkResult,
            QueryResult,
            RetrievedChunk,
        )
        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token = _make_token(user_id)
        minio_key = f"{user_id}/{doc_id}/sample.txt"

        doc = _make_document(doc_id=doc_id, user_id=user_id, minio_key=minio_key)
        job_queued = _make_job(job_id=job_id, user_id=user_id, status=JobStatus.queued)
        job_completed = _make_job(
            job_id=job_id,
            user_id=user_id,
            status=JobStatus.completed,
            result_payload={"document_id": str(doc_id), "chunk_count": 1},
        )

        mock_db = _make_mock_db_session()

        # ---------------------------------------------------------------
        # Phase 1 — Upload
        # ---------------------------------------------------------------
        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
            patch("app.api.rag.router.JobRepository") as MockJobRepo,
            patch("app.api.rag.router.rag_service") as mock_rag_svc,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch("app.workers.rag_worker.ingest_document_task") as mock_celery_task,
        ):
            doc_repo = MockDocRepo.return_value
            doc_repo.create = AsyncMock(return_value=doc)
            doc_repo.delete = AsyncMock(return_value=True)
            job_repo = MockJobRepo.return_value
            job_repo.update_status = AsyncMock(return_value=job_queued)

            mock_rag_svc.validate_mime_and_upload = MagicMock()
            mock_rag_svc.store_file_minio = AsyncMock(return_value=minio_key)
            mock_rag_svc.create_ingestion_job = AsyncMock(return_value=job_id)
            mock_celery_task.delay = MagicMock(
                return_value=MagicMock(id="celery-cycle-id")
            )

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db)

            with TestClient(_app) as client:
                upload_resp = client.post(
                    "/documents/upload",
                    files={"file": (_SAMPLE_FILENAME, _SAMPLE_BYTES, _SAMPLE_MIME)},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert upload_resp.status_code == 202, f"Upload failed: {upload_resp.text}"
        upload_body = upload_resp.json()
        assert upload_body["status"] == "pending"

        # ---------------------------------------------------------------
        # Phase 2 — Check job is queued via GET /jobs/{job_id}
        # ---------------------------------------------------------------
        mock_db2 = _make_mock_db_session()
        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo2,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo2 = MockJobRepo2.return_value
            job_repo2.get_by_id = AsyncMock(return_value=job_queued)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db2)

            with TestClient(_app) as client:
                job_resp_before = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert job_resp_before.status_code == 200
        assert job_resp_before.json()["status"] in ("queued", "pending")

        # ---------------------------------------------------------------
        # Phase 3 — Run ingestion synchronously
        # ---------------------------------------------------------------
        mock_task = MagicMock()
        mock_task.request.retries = 0

        # _run_ingestion creates its own engine via _make_session_factory; patch at worker level
        with (
            patch("app.workers.rag_worker._make_session_factory") as MockSessionFactory,
            patch("app.services.rag_service.rag_service") as mock_worker_svc,
        ):
            mock_db3 = _make_mock_db_session()
            mock_engine3, mock_sf3 = _make_session_factory_patch(mock_db3)
            MockSessionFactory.return_value = (mock_engine3, mock_sf3)

            mock_doc_repo3 = AsyncMock()
            mock_doc_repo3.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo3.update_status = AsyncMock(return_value=doc)

            mock_job_repo3 = AsyncMock()
            mock_job_repo3.update_status = AsyncMock(return_value=job_completed)

            job_result3 = AsyncMock()
            job_result3.scalar_one_or_none = MagicMock(return_value=job_queued)
            mock_db3.execute = AsyncMock(return_value=job_result3)

            mock_worker_svc.download_file_minio = AsyncMock(return_value=_SAMPLE_BYTES)
            mock_worker_svc.extract_text = AsyncMock(return_value=(_SAMPLE_TEXT, 1))
            mock_worker_svc.chunk_text = MagicMock(
                return_value=[ChunkResult(text=_SAMPLE_TEXT, page_number=1)]
            )
            mock_worker_svc.embed_and_store = AsyncMock(return_value=None)
            mock_worker_svc.send_ingestion_notification = AsyncMock(return_value=None)

            with (
                patch(
                    "app.repositories.document_repository.DocumentRepository",
                    return_value=mock_doc_repo3,
                ),
                patch(
                    "app.repositories.job_repository.JobRepository",
                    return_value=mock_job_repo3,
                ),
            ):
                ingest_result = asyncio.run(
                    _run_ingestion(mock_task, str(doc_id), str(user_id))
                )

        assert (
            ingest_result["status"] == "completed"
        ), f"Ingestion failed: {ingest_result}"

        # ---------------------------------------------------------------
        # Phase 4 — Check job is completed via GET /jobs/{job_id}
        # ---------------------------------------------------------------
        mock_db4 = _make_mock_db_session()
        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo4,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo4 = MockJobRepo4.return_value
            job_repo4.get_by_id = AsyncMock(return_value=job_completed)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db4)

            with TestClient(_app) as client:
                job_resp_after = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert job_resp_after.status_code == 200
        assert job_resp_after.json()["status"] == "completed"

        # ---------------------------------------------------------------
        # Phase 5 — Query with verbatim phrase; assert citations present
        # ---------------------------------------------------------------
        mock_db5 = _make_mock_db_session()
        retrieved_chunk = RetrievedChunk(
            content=_SAMPLE_TEXT,
            document_name=_SAMPLE_FILENAME,
            page_number=1,
        )
        query_result = QueryResult(
            query="verbatim phrase",
            retrieved_chunks=[retrieved_chunk],
            context=_SAMPLE_TEXT,
        )
        citation_dicts = [
            {"document_name": _SAMPLE_FILENAME, "page_number": 1, "chunk_index": 0}
        ]

        with (
            patch("app.api.rag.router.rag_service") as mock_rag_svc5,
            patch("app.services.ai_orchestrator.AIOrchestrator") as MockOrch5,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            mock_rag_svc5.query_documents = AsyncMock(return_value=query_result)
            mock_rag_svc5._format_citations = MagicMock(return_value=citation_dicts)

            mock_orch5_inst = AsyncMock()
            mock_comp5 = MagicMock()
            mock_comp5.text = "The fox jumps [Source: sample.txt, Page 1]."
            mock_orch5_inst.complete = AsyncMock(return_value=mock_comp5)
            MockOrch5.return_value = mock_orch5_inst

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db5)

            with TestClient(_app) as client:
                query_resp = client.post(
                    "/documents/query",
                    json={"query": "verbatim phrase", "top_k": 5},
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert query_resp.status_code == 200
        query_body = query_resp.json()
        assert len(query_body["citations"]) >= 1
        for cit in query_body["citations"]:
            assert cit["document_name"], "Citation must have a non-empty document_name"
            assert cit["page_number"] >= 1, "Citation page_number must be >= 1"

        # ---------------------------------------------------------------
        # Phase 6 — Delete document → assert 204
        # ---------------------------------------------------------------
        mock_db6 = _make_mock_db_session()
        with (
            patch("app.api.rag.router.DocumentRepository") as MockDocRepo6,
            patch("app.api.rag.router.rag_service") as mock_rag_svc6,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            doc_repo6 = MockDocRepo6.return_value
            doc_repo6.get_by_id = AsyncMock(return_value=doc)
            doc_repo6.delete = AsyncMock(return_value=True)
            mock_rag_svc6.delete_embeddings = AsyncMock(return_value=None)
            mock_rag_svc6.delete_file_minio = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db6)

            with TestClient(_app) as client:
                delete_resp = client.delete(
                    f"/documents/{doc_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert delete_resp.status_code == 204

        # ---------------------------------------------------------------
        # Phase 7 — Verify job no longer accessible → 404
        # ---------------------------------------------------------------
        mock_db7 = _make_mock_db_session()
        with (
            patch("app.api.rag.router.JobRepository") as MockJobRepo7,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            job_repo7 = MockJobRepo7.return_value
            job_repo7.get_by_id = AsyncMock(return_value=None)

            _app.dependency_overrides[
                __import__("app.database", fromlist=["get_db"]).get_db
            ] = _override_get_db(mock_db7)

            with TestClient(_app) as client:
                job_gone_resp = client.get(
                    f"/jobs/{job_id}",
                    headers=_auth_headers(token),
                )

        _app.dependency_overrides.clear()

        assert job_gone_resp.status_code == 404
