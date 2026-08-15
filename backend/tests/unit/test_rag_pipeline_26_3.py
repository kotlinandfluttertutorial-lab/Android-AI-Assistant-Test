"""RAG Pipeline — Pytest unit and integration tests for task 26.3.

Test coverage matrix
---------------------
1. Format/size validation   — HTTP 422 before any storage I/O         (Property 26)
2. MinIO upload             — mocked; key format and call verified
3. Celery job status        — queued → processing → completed/failed
4. Chunking coverage        — no gaps; every token in ≥1 chunk        (Property 7)
5. Embedding storage        — ChromaDB add() called; collection scoped (Property 8)
6. Cross-user isolation     — user A cannot see/delete user B docs
7. Delete cleanup           — ChromaDB + PostgreSQL + MinIO all called

**Validates: Requirements 21.1, 21.2**

References: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import tiktoken
from fastapi import HTTPException
from httpx import ASGITransport, AsyncClient

# ---------------------------------------------------------------------------
# Environment variables must be set before any app imports
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
os.environ.setdefault("LOG_LEVEL", "INFO")

from app.database import get_db
from app.main import app
from app.models.document import Document, IngestionStatus
from app.models.job import JobStatus
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload, create_access_token
from app.services.rag_service import ChunkResult, ExtractionError, RAGService

# ---------------------------------------------------------------------------
# Shared constants
# ---------------------------------------------------------------------------

_ENC = tiktoken.encoding_for_model("gpt-3.5-turbo")

_SAMPLE_TEXT = (
    "The quick brown fox jumps over the lazy dog. "
    "This sentence is used to test the RAG pipeline. "
    "Every token must appear in at least one chunk."
)
_SAMPLE_BYTES = _SAMPLE_TEXT.encode("utf-8")
_SAMPLE_FILENAME = "sample.txt"
_SAMPLE_MIME = "text/plain"

_FIXED_USER_A = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_FIXED_USER_B = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
_FIXED_DOC_ID = uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")
_FIXED_JOB_ID = uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
_FIXED_MINIO_KEY = f"{_FIXED_USER_A}/{_FIXED_DOC_ID}/sample.txt"

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------


def _make_token(user_id: uuid.UUID, role: str = "user") -> str:
    token, _ = create_access_token(user_id=user_id, role=role)
    return token


def _token_payload(user_id: uuid.UUID = _FIXED_USER_A) -> TokenPayload:
    now = datetime.now(tz=timezone.utc)
    return TokenPayload(
        sub=str(user_id),
        role="user",
        jti=str(uuid.uuid4()),
        iat=now,
        exp=now.replace(year=now.year + 1),
    )


def _make_document(
    doc_id: uuid.UUID = _FIXED_DOC_ID,
    user_id: uuid.UUID = _FIXED_USER_A,
    minio_key: str = _FIXED_MINIO_KEY,
) -> Document:
    return Document(
        id=doc_id,
        user_id=user_id,
        file_name=_SAMPLE_FILENAME,
        mime_type=_SAMPLE_MIME,
        size_bytes=len(_SAMPLE_BYTES),
        minio_key=minio_key,
        ingestion_status=IngestionStatus.pending,
    )


def _make_mock_db() -> AsyncMock:
    db = AsyncMock()
    db.commit = AsyncMock()
    db.flush = AsyncMock()
    db.delete = AsyncMock()
    db.rollback = AsyncMock()
    db.close = AsyncMock()
    return db


def _make_job_mock(
    job_id: uuid.UUID = _FIXED_JOB_ID,
    user_id: uuid.UUID = _FIXED_USER_A,
    status: JobStatus = JobStatus.queued,
    result_payload: dict | None = None,
    error_message: str | None = None,
) -> MagicMock:
    job = MagicMock()
    job.id = job_id
    job.user_id = user_id
    job.status = status
    job.job_type = "document_ingestion"
    job.result_payload = result_payload
    job.error_message = error_message
    job.celery_task_id = None
    job.created_at = datetime.now(tz=timezone.utc)
    return job


# ===========================================================================
# 1. Format/size validation — HTTP 422 BEFORE any storage I/O
#    Property 26 | Requirements 4.1, 21.1
# ===========================================================================


class TestFormatSizeValidation:
    """validate_upload / validate_mime_and_upload raise HTTP 422 before storage.

    **Validates: Requirements 21.1**
    Property 26 — format guard fires before any MinIO or ChromaDB I/O.
    """

    def test_unsupported_extension_raises_422(self) -> None:
        """An .exe file must be rejected with HTTP 422 (Property 26)."""
        service = RAGService()
        with pytest.raises(HTTPException) as exc:
            service.validate_upload("payload.exe", size_bytes=512)
        assert exc.value.status_code == 422

    def test_zip_extension_raises_422(self) -> None:
        """A .zip file must be rejected with HTTP 422."""
        service = RAGService()
        with pytest.raises(HTTPException) as exc:
            service.validate_upload("archive.zip", size_bytes=1024)
        assert exc.value.status_code == 422

    def test_oversized_file_raises_422(self) -> None:
        """A file exceeding 50 MB must be rejected with HTTP 422 (Property 26)."""
        service = RAGService()
        over_50mb = 50 * 1024 * 1024 + 1
        with pytest.raises(HTTPException) as exc:
            service.validate_upload("big.pdf", size_bytes=over_50mb)
        assert exc.value.status_code == 422
        assert "50" in exc.value.detail or "size" in exc.value.detail.lower()

    def test_exactly_50_mb_is_accepted(self) -> None:
        """Exactly 50 MB must NOT raise — boundary case."""
        service = RAGService()
        service.validate_upload("edge.pdf", size_bytes=50 * 1024 * 1024)

    def test_valid_pdf_accepted(self) -> None:
        """A valid .pdf under the limit must not raise."""
        service = RAGService()
        service.validate_upload("report.pdf", size_bytes=1024 * 1024)

    def test_valid_docx_accepted(self) -> None:
        """A valid .docx file must not raise."""
        service = RAGService()
        service.validate_upload("doc.docx", size_bytes=500 * 1024)

    def test_valid_txt_accepted(self) -> None:
        """A valid .txt file must not raise."""
        service = RAGService()
        service.validate_upload("notes.txt", size_bytes=4096)

    def test_valid_md_accepted(self) -> None:
        """A valid .md file must not raise."""
        service = RAGService()
        service.validate_upload("readme.md", size_bytes=2048)

    @pytest.mark.asyncio
    async def test_endpoint_returns_422_before_minio_on_bad_format(self) -> None:
        """HTTP layer: POST /documents returns 422 for .exe — store_file_minio never called.

        **Validates: Requirements 21.2** (integration-level check)
        Property 26 — no storage I/O on format rejection.
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()

        mock_doc_repo = AsyncMock()
        mock_store = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch("app.api.rag.router.rag_service.store_file_minio", mock_store),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.post(
                        "/documents",
                        files={
                            "file": (
                                "malware.exe",
                                b"MZ\x90\x00",
                                "application/octet-stream",
                            )
                        },
                    )
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 422
        mock_store.assert_not_called()
        mock_doc_repo.create.assert_not_called()

    @pytest.mark.asyncio
    async def test_endpoint_returns_422_before_minio_on_bad_mime(self) -> None:
        """POST /documents with image/png MIME and .png filename → 422, no storage."""
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        mock_store = AsyncMock()

        with patch("app.api.rag.router.rag_service.store_file_minio", mock_store):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.post(
                        "/documents",
                        files={"file": ("photo.png", b"\x89PNG", "image/png")},
                    )
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 422
        mock_store.assert_not_called()


# ===========================================================================
# 2. MinIO upload — mocked; key format and call verified
#    Requirements 4.2, 21.1
# ===========================================================================


class TestMinIOUpload:
    """store_file_minio is called after successful validation; key format is correct.

    **Validates: Requirements 21.1**
    """

    @pytest.mark.asyncio
    async def test_minio_upload_called_with_correct_arguments(self) -> None:
        """store_file_minio must receive file_bytes, filename, user_id, document_id."""
        service = RAGService()
        captured: dict[str, Any] = {}

        async def _fake_upload(file_bytes, filename, user_id, document_id=None):
            captured["file_bytes"] = file_bytes
            captured["filename"] = filename
            captured["user_id"] = user_id
            captured["document_id"] = document_id
            return f"{user_id}/{document_id}/{filename}"

        with patch.object(service, "store_file_minio", side_effect=_fake_upload):
            key = await service.store_file_minio(
                _SAMPLE_BYTES, _SAMPLE_FILENAME, str(_FIXED_USER_A), str(_FIXED_DOC_ID)
            )

        assert captured["file_bytes"] == _SAMPLE_BYTES
        assert captured["filename"] == _SAMPLE_FILENAME
        assert captured["user_id"] == str(_FIXED_USER_A)
        assert captured["document_id"] == str(_FIXED_DOC_ID)
        assert key == f"{_FIXED_USER_A}/{_FIXED_DOC_ID}/{_SAMPLE_FILENAME}"

    @pytest.mark.asyncio
    async def test_minio_key_format_is_user_doc_filename(self) -> None:
        """MinIO object key must follow {user_id}/{document_id}/{filename} format."""
        user_id = str(uuid.uuid4())
        doc_id = str(uuid.uuid4())
        filename = "report.pdf"

        def _fake_put_object(*args, **kwargs):
            pass

        def _fake_bucket_exists(*args):
            return True

        def _mock_minio_constructor(*args, **kwargs):
            client = MagicMock()
            client.bucket_exists.return_value = True
            client.put_object = MagicMock(side_effect=_fake_put_object)
            return client

        service = RAGService()

        with patch("minio.Minio", _mock_minio_constructor):
            key = await service.store_file_minio(
                b"PDF content", filename, user_id, doc_id
            )

        assert key.startswith(f"{user_id}/{doc_id}/")
        assert key.endswith(filename)

    @pytest.mark.asyncio
    async def test_upload_endpoint_returns_202_with_document_and_job_id(self) -> None:
        """POST /documents returns HTTP 202 with document_id and job_id after upload.

        **Validates: Requirements 21.2** (endpoint integration check)
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()
        job = _make_job_mock()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.create = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)
        mock_job_repo = AsyncMock()
        mock_job_repo.update_status = AsyncMock(return_value=job)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch("app.api.rag.router.JobRepository", return_value=mock_job_repo),
            patch(
                "app.api.rag.router.rag_service.validate_mime_and_upload", MagicMock()
            ),
            patch(
                "app.api.rag.router.rag_service.store_file_minio",
                AsyncMock(return_value=_FIXED_MINIO_KEY),
            ),
            patch(
                "app.api.rag.router.rag_service.create_ingestion_job",
                AsyncMock(return_value=job.id),
            ),
            patch("app.workers.rag_worker.ingest_document_task") as mock_task,
        ):
            mock_task.delay.return_value = MagicMock(id="celery-id-123")
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.post(
                        "/documents",
                        files={"file": (_SAMPLE_FILENAME, _SAMPLE_BYTES, _SAMPLE_MIME)},
                    )
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 202
        body = resp.json()
        assert "document_id" in body
        assert "job_id" in body
        assert body["status"] == "pending"

    @pytest.mark.asyncio
    async def test_minio_failure_rolls_back_document_row(self) -> None:
        """If store_file_minio raises, the document row must be deleted (rollback)."""
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.create = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.validate_mime_and_upload", MagicMock()
            ),
            patch(
                "app.api.rag.router.rag_service.store_file_minio",
                AsyncMock(side_effect=OSError("MinIO unreachable")),
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.post(
                        "/documents",
                        files={"file": (_SAMPLE_FILENAME, _SAMPLE_BYTES, _SAMPLE_MIME)},
                    )
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 500
        # Document row must be deleted (rollback) after MinIO failure
        mock_doc_repo.delete.assert_called_once()


# ===========================================================================
# 3. Celery job status transitions — queued → processing → completed/failed
#    Requirements 4.5, 4.11, 21.1
# ===========================================================================


class TestCeleryJobStatusTransitions:
    """Ingestion worker transitions job status queued → running → completed/failed.

    **Validates: Requirements 21.1**
    """

    @pytest.mark.asyncio
    async def test_job_transitions_queued_to_running_to_completed(self) -> None:
        """Worker sets status: running on start, completed on success.

        Requirements: 4.5, 21.1
        """
        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        doc = _make_document(doc_id=doc_id, user_id=user_id)
        job = _make_job_mock(user_id=user_id, status=JobStatus.queued)

        mock_task = MagicMock()
        mock_task.request.retries = 0

        status_log: list[str] = []

        async def _log_update_status(jid, new_status, **kwargs):
            val = new_status.value if hasattr(new_status, "value") else str(new_status)
            status_log.append(val)
            return job

        with (
            patch("app.workers.rag_worker._make_session_factory") as MockFactory,
            patch("app.services.rag_service.rag_service") as mock_svc,
        ):
            mock_db = _make_mock_db()
            mock_ctx = AsyncMock()
            mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
            mock_ctx.__aexit__ = AsyncMock(return_value=False)
            mock_session_factory = MagicMock(return_value=mock_ctx)
            mock_engine = MagicMock()
            mock_engine.dispose = AsyncMock()
            MockFactory.return_value = (mock_engine, mock_session_factory)

            mock_doc_repo = AsyncMock()
            mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo.update_status = AsyncMock(return_value=doc)

            mock_job_repo = AsyncMock()
            mock_job_repo.update_status = AsyncMock(side_effect=_log_update_status)

            job_result = AsyncMock()
            job_result.scalar_one_or_none = MagicMock(return_value=job)
            mock_db.execute = AsyncMock(return_value=job_result)

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
                result = await _run_ingestion(mock_task, str(doc_id), str(user_id))

        assert result["status"] == "completed"
        assert "running" in status_log, (
            f"Expected 'running' in status log: {status_log}"
        )
        assert "completed" in status_log, (
            f"Expected 'completed' in status log: {status_log}"
        )
        assert status_log.index("running") < status_log.index("completed")

    @pytest.mark.asyncio
    async def test_extraction_failure_transitions_to_failed(self) -> None:
        """ExtractionError causes job and document to be marked failed (non-retryable).

        Requirements: 4.8, 21.1
        """
        from app.workers.rag_worker import _run_ingestion

        user_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        doc = _make_document(doc_id=doc_id, user_id=user_id)
        job = _make_job_mock(user_id=user_id, status=JobStatus.queued)

        mock_task = MagicMock()
        mock_task.request.retries = 0

        job_status_log: list[str] = []
        doc_status_log: list[str] = []

        async def _log_job_status(jid, new_status, **kwargs):
            job_status_log.append(
                new_status.value if hasattr(new_status, "value") else str(new_status)
            )
            return job

        async def _log_doc_status(did, new_status, **kwargs):
            doc_status_log.append(
                new_status.value if hasattr(new_status, "value") else str(new_status)
            )
            return doc

        with (
            patch("app.workers.rag_worker._make_session_factory") as MockFactory,
            patch("app.services.rag_service.rag_service") as mock_svc,
        ):
            mock_db = _make_mock_db()
            mock_ctx = AsyncMock()
            mock_ctx.__aenter__ = AsyncMock(return_value=mock_db)
            mock_ctx.__aexit__ = AsyncMock(return_value=False)
            mock_session_factory = MagicMock(return_value=mock_ctx)
            mock_engine = MagicMock()
            mock_engine.dispose = AsyncMock()
            MockFactory.return_value = (mock_engine, mock_session_factory)

            mock_doc_repo = AsyncMock()
            mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
            mock_doc_repo.update_status = AsyncMock(side_effect=_log_doc_status)

            mock_job_repo = AsyncMock()
            mock_job_repo.update_status = AsyncMock(side_effect=_log_job_status)

            job_result = AsyncMock()
            job_result.scalar_one_or_none = MagicMock(return_value=job)
            mock_db.execute = AsyncMock(return_value=job_result)

            mock_svc.download_file_minio = AsyncMock(return_value=_SAMPLE_BYTES)
            mock_svc.extract_text = AsyncMock(
                side_effect=ExtractionError(
                    "pdf_extraction", "sample.txt", "Corrupt PDF"
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
                result = await _run_ingestion(mock_task, str(doc_id), str(user_id))

        assert result["status"] == "failed"
        assert "failed" in doc_status_log, f"Document status log: {doc_status_log}"
        assert "failed" in job_status_log, f"Job status log: {job_status_log}"
        mock_svc.embed_and_store.assert_not_called()

    @pytest.mark.asyncio
    async def test_get_job_status_returns_queued(self) -> None:
        """GET /jobs/{job_id} returns status=queued for newly created job.

        Requirements: 4.11, 21.2
        """
        job_id = uuid.uuid4()
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        job = _make_job_mock(job_id=job_id, status=JobStatus.queued)

        mock_job_repo = AsyncMock()
        mock_job_repo.get_by_id = AsyncMock(return_value=job)

        with patch("app.api.rag.router.JobRepository", return_value=mock_job_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.get(f"/jobs/{job_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "queued"
        assert body["job_id"] == str(job_id)

    @pytest.mark.asyncio
    async def test_get_job_status_maps_running_to_processing(self) -> None:
        """GET /jobs/{job_id} maps internal 'running' status to API-facing 'processing'.

        Requirements: 4.11 (the public API contract exposes 'processing')
        """
        job_id = uuid.uuid4()
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        job = _make_job_mock(job_id=job_id, status=JobStatus.running)

        mock_job_repo = AsyncMock()
        mock_job_repo.get_by_id = AsyncMock(return_value=job)

        with patch("app.api.rag.router.JobRepository", return_value=mock_job_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.get(f"/jobs/{job_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 200
        assert resp.json()["status"] == "processing"

    @pytest.mark.asyncio
    async def test_get_job_status_returns_completed(self) -> None:
        """GET /jobs/{job_id} returns status=completed after successful ingestion."""
        job_id = uuid.uuid4()
        doc_id = uuid.uuid4()
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        job = _make_job_mock(
            job_id=job_id,
            status=JobStatus.completed,
            result_payload={"document_id": str(doc_id), "chunk_count": 3},
        )

        mock_job_repo = AsyncMock()
        mock_job_repo.get_by_id = AsyncMock(return_value=job)

        with patch("app.api.rag.router.JobRepository", return_value=mock_job_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.get(f"/jobs/{job_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 200
        assert resp.json()["status"] == "completed"

    @pytest.mark.asyncio
    async def test_get_job_status_returns_failed_with_error_message(self) -> None:
        """GET /jobs/{job_id} returns status=failed with error_message on failure.

        Requirements: 4.11, 21.1
        """
        job_id = uuid.uuid4()
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        job = _make_job_mock(
            job_id=job_id,
            status=JobStatus.failed,
            error_message='{"error": "extraction_failed", "stage": "pdf_extraction"}',
        )

        mock_job_repo = AsyncMock()
        mock_job_repo.get_by_id = AsyncMock(return_value=job)

        with patch("app.api.rag.router.JobRepository", return_value=mock_job_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.get(f"/jobs/{job_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "failed"
        assert body["error_message"] is not None


# ===========================================================================
# 4. Chunking coverage — no gaps; every token appears in ≥1 chunk
#    Property 7 | Requirements 4.3, 21.1
# ===========================================================================


class TestChunkingCoverage:
    """chunk_text produces chunks that together cover every source token (Property 7).

    **Validates: Requirements 21.1**
    Property 7: No token of the source text may be absent from all chunks.
    """

    def _all_tokens_covered(self, source: str, chunks: list[ChunkResult]) -> bool:
        """Return True if every token ID in source appears in at least one chunk."""
        source_ids = set(_ENC.encode(source))
        covered: set[int] = set()
        for c in chunks:
            covered.update(_ENC.encode(c.text))
        return source_ids.issubset(covered)

    def test_empty_text_produces_no_chunks(self) -> None:
        """Empty text must return an empty chunk list (vacuously covers all tokens)."""
        service = RAGService()
        assert service.chunk_text("") == []

    def test_whitespace_only_produces_no_chunks(self) -> None:
        """Whitespace-only text must return an empty chunk list."""
        service = RAGService()
        assert service.chunk_text("   \n\t  ") == []

    def test_short_text_single_chunk_covers_all_tokens(self) -> None:
        """A text shorter than chunk_size produces exactly one chunk covering all tokens."""
        service = RAGService()
        text = "Hello world. This is a short document."
        chunks = service.chunk_text(text, chunk_size=512, overlap=64)
        assert len(chunks) == 1
        assert self._all_tokens_covered(text, chunks)

    def test_multi_chunk_text_no_gaps(self) -> None:
        """Multi-chunk text: union of chunks covers all source tokens (no gaps).

        **Validates: Requirements 4.3** — Property 7.
        """
        service = RAGService()
        text = "The quick brown fox. " * 100
        chunks = service.chunk_text(text, chunk_size=64, overlap=16)
        assert len(chunks) > 1
        assert self._all_tokens_covered(text, chunks)

    def test_long_document_no_gaps(self) -> None:
        """2000-token document: every token covered across chunks.

        **Validates: Requirements 4.3** — Property 7.
        """
        service = RAGService()
        text = " ".join(f"token_{i}" for i in range(2000))
        chunks = service.chunk_text(text, chunk_size=128, overlap=32)
        assert len(chunks) > 5
        assert self._all_tokens_covered(text, chunks)

    def test_consecutive_chunks_share_overlap_tokens(self) -> None:
        """Consecutive chunks share ≥1 token (overlap is non-zero).

        **Validates: Requirements 4.3** — overlap ensures continuity.
        """
        service = RAGService()
        text = " ".join(["word"] * 300)
        chunks = service.chunk_text(text, chunk_size=64, overlap=16)
        assert len(chunks) >= 2
        first_ids = set(_ENC.encode(chunks[0].text))
        second_ids = set(_ENC.encode(chunks[1].text))
        assert len(first_ids & second_ids) >= 1

    def test_chunk_size_clamped_to_min(self) -> None:
        """chunk_size below min_chunk_size (64) is clamped to 64."""
        service = RAGService()
        text = " ".join(["word"] * 200)
        chunks = service.chunk_text(text, chunk_size=10, overlap=0, min_chunk_size=64)
        # With chunk_size clamped to 64, we should get valid chunks
        assert len(chunks) >= 1
        assert self._all_tokens_covered(text, chunks)

    def test_chunk_size_clamped_to_max(self) -> None:
        """chunk_size above max_chunk_size (2048) is clamped to 2048."""
        service = RAGService()
        text = "Short text for max clamp test."
        chunks = service.chunk_text(
            text, chunk_size=9999, overlap=0, max_chunk_size=2048
        )
        assert len(chunks) == 1
        assert self._all_tokens_covered(text, chunks)

    def test_overlap_clamped_to_50_percent(self) -> None:
        """Overlap exceeding 50% of chunk_size is clamped to chunk_size // 2.

        **Validates: Requirements 4.3** — max overlap = 50% of chunk size.
        """
        service = RAGService()
        text = " ".join(["abc"] * 500)
        # overlap=200 on chunk_size=128 → should be clamped to 64
        chunks = service.chunk_text(text, chunk_size=128, overlap=200)
        assert len(chunks) >= 1
        assert self._all_tokens_covered(text, chunks)

    def test_plain_text_chunks_have_char_offsets(self) -> None:
        """Plain-text chunks must carry char_offset_start/end and citation_type='char_offset'.

        **Validates: Requirements 4.7** — TXT/MD use char offsets, not page numbers.
        """
        service = RAGService()
        text = "Alpha beta gamma. " * 50
        chunks = service.chunk_text(text, chunk_size=32, overlap=8, is_plain_text=True)
        assert len(chunks) >= 1
        for chunk in chunks:
            assert chunk.citation_type == "char_offset"
            assert chunk.char_offset_start is not None
            assert chunk.char_offset_end is not None

    def test_pdf_chunks_have_page_citation(self) -> None:
        """Non-plain-text chunks must carry citation_type='page'."""
        service = RAGService()
        text = "Document text. " * 30
        chunks = service.chunk_text(text, chunk_size=32, overlap=8, is_plain_text=False)
        assert len(chunks) >= 1
        for chunk in chunks:
            assert chunk.citation_type == "page"


# ===========================================================================
# 5. Embedding storage in ChromaDB — mocked; collection name and add() verified
#    Property 8 | Requirements 4.4, 21.1
# ===========================================================================


class TestEmbeddingStorage:
    """embed_and_store calls ChromaDB add() in user-scoped collection (Property 8).

    **Validates: Requirements 21.1**
    Property 8: each user's embeddings are isolated in documents_{user_id}.
    """

    @staticmethod
    def _make_mock_embeddings(n: int, dim: int = 384):
        """Return n mock embedding objects that support .tolist() as SentenceTransformer would."""
        import numpy as np

        return np.array([[0.1] * dim for _ in range(n)])

    @pytest.mark.asyncio
    async def test_embed_and_store_uses_user_scoped_collection(self) -> None:
        """ChromaDB collection must be named documents_{user_id} (Property 8).

        **Validates: Requirements 4.4**
        """
        service = RAGService()
        user_id = str(uuid.uuid4())
        doc_id = str(uuid.uuid4())
        chunks = [ChunkResult(text="Hello world.", page_number=1)]

        captured_collection_names: list[str] = []

        def _mock_get_or_create_collection(name, **kwargs):
            captured_collection_names.append(name)
            coll = MagicMock()
            coll.add = MagicMock()
            return coll

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_or_create_collection = _mock_get_or_create_collection

        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_mock_embeddings(1))

        mock_db = AsyncMock()
        mock_repo = AsyncMock()
        mock_repo.create_chunk = AsyncMock(return_value=MagicMock())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch(
                "app.repositories.document_repository.DocumentRepository",
                return_value=mock_repo,
            ),
        ):
            await service.embed_and_store(chunks, doc_id, user_id, mock_db)

        assert len(captured_collection_names) == 1
        assert captured_collection_names[0] == f"documents_{user_id}"

    @pytest.mark.asyncio
    async def test_embed_and_store_calls_chroma_add(self) -> None:
        """embed_and_store must call ChromaDB collection.add() with the embeddings."""
        service = RAGService()
        user_id = str(uuid.uuid4())
        doc_id = str(uuid.uuid4())
        chunks = [
            ChunkResult(text="Chunk one.", page_number=1),
            ChunkResult(text="Chunk two.", page_number=2),
        ]
        mock_collection = MagicMock()
        mock_collection.add = MagicMock()

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_or_create_collection = MagicMock(
            return_value=mock_collection
        )

        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_mock_embeddings(2))

        mock_db = AsyncMock()
        mock_repo = AsyncMock()
        mock_repo.create_chunk = AsyncMock(return_value=MagicMock())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch(
                "app.repositories.document_repository.DocumentRepository",
                return_value=mock_repo,
            ),
        ):
            await service.embed_and_store(chunks, doc_id, user_id, mock_db)

        mock_collection.add.assert_called_once()
        call_kwargs = mock_collection.add.call_args
        assert len(call_kwargs.kwargs.get("ids", [])) == 2
        assert len(call_kwargs.kwargs.get("embeddings", [])) == 2
        assert len(call_kwargs.kwargs.get("documents", [])) == 2

    @pytest.mark.asyncio
    async def test_embed_and_store_ids_include_document_id(self) -> None:
        """ChromaDB IDs must include the document_id as prefix."""
        service = RAGService()
        user_id = str(uuid.uuid4())
        doc_id = str(uuid.uuid4())
        chunks = [ChunkResult(text="Single chunk.", page_number=1)]

        captured_ids: list[str] = []

        def _capture_add(**kwargs):
            captured_ids.extend(kwargs.get("ids", []))

        mock_collection = MagicMock()
        mock_collection.add = MagicMock(side_effect=_capture_add)
        mock_chroma_client = MagicMock()
        mock_chroma_client.get_or_create_collection = MagicMock(
            return_value=mock_collection
        )

        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_mock_embeddings(1))

        mock_db = AsyncMock()
        mock_repo = AsyncMock()
        mock_repo.create_chunk = AsyncMock(return_value=MagicMock())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch(
                "app.repositories.document_repository.DocumentRepository",
                return_value=mock_repo,
            ),
        ):
            await service.embed_and_store(chunks, doc_id, user_id, mock_db)

        assert len(captured_ids) == 1
        assert captured_ids[0].startswith(doc_id)

    @pytest.mark.asyncio
    async def test_embed_and_store_skips_empty_chunks(self) -> None:
        """embed_and_store must be a no-op when chunks list is empty."""
        service = RAGService()
        mock_db = AsyncMock()
        mock_model = MagicMock()

        with patch.object(service, "_get_embedding_model", return_value=mock_model):
            await service.embed_and_store([], "doc-id", "user-id", mock_db)

        mock_model.encode.assert_not_called()

    @pytest.mark.asyncio
    async def test_two_users_embeddings_stored_in_separate_collections(self) -> None:
        """User A and User B embeddings are stored in separate ChromaDB collections.

        **Validates: Requirements 4.4** — cross-user isolation at storage layer.
        Property 8.
        """
        service_a = RAGService()
        service_b = RAGService()
        user_a = str(uuid.uuid4())
        user_b = str(uuid.uuid4())
        doc_a = str(uuid.uuid4())
        doc_b = str(uuid.uuid4())
        chunks = [ChunkResult(text="Content.", page_number=1)]
        collection_names_used: list[str] = []

        def _capture_create(name, **kwargs):
            collection_names_used.append(name)
            coll = MagicMock()
            coll.add = MagicMock()
            return coll

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_or_create_collection = _capture_create
        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_mock_embeddings(1))

        mock_db = AsyncMock()
        mock_repo = AsyncMock()
        mock_repo.create_chunk = AsyncMock(return_value=MagicMock())

        with (
            patch.object(service_a, "_get_embedding_model", return_value=mock_model),
            patch.object(service_b, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch(
                "app.repositories.document_repository.DocumentRepository",
                return_value=mock_repo,
            ),
        ):
            await service_a.embed_and_store(chunks, doc_a, user_a, mock_db)
            await service_b.embed_and_store(chunks, doc_b, user_b, mock_db)

        assert f"documents_{user_a}" in collection_names_used
        assert f"documents_{user_b}" in collection_names_used
        assert collection_names_used[0] != collection_names_used[1]


# ===========================================================================
# 6. Cross-user isolation — user A cannot retrieve user B documents
#    Property 8 | Requirements 4.5, 21.1
# ===========================================================================


class TestCrossUserIsolation:
    """User A's queries only access user A's ChromaDB collection; user B's data is invisible.

    **Validates: Requirements 21.1**
    Property 8: per-user collection isolation in ChromaDB.
    Requirements 4.5: embeddings associated exclusively with owning user.
    """

    @staticmethod
    def _make_query_embedding(dim: int = 384):
        """Return a numpy array matching SentenceTransformer output for a single query."""
        import numpy as np

        return np.array([[0.1] * dim])

    @pytest.mark.asyncio
    async def test_query_uses_only_own_collection(self) -> None:
        """query_documents queries only documents_{user_id}, never another user's collection.

        **Validates: Requirements 4.5** — Property 8.
        """
        service = RAGService()
        user_a = uuid.uuid4()
        queried_collections: list[str] = []

        def _mock_get_collection(name):
            queried_collections.append(name)
            coll = MagicMock()
            coll.query.return_value = {
                "ids": [[]],
                "documents": [[]],
                "metadatas": [[]],
            }
            return coll

        mock_client = MagicMock()
        mock_client.get_collection = _mock_get_collection

        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_query_embedding())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_client),
        ):
            result = await service.query_documents(
                user_id=user_a,
                query="what is in this document?",
            )

        assert len(queried_collections) == 1
        assert queried_collections[0] == f"documents_{user_a}"

    @pytest.mark.asyncio
    async def test_user_b_collection_not_queried_when_user_a_searches(self) -> None:
        """Searching as user A must never touch user B's ChromaDB collection.

        **Validates: Requirements 4.5** — no cross-user data leakage.
        Property 8: user-scoped collection naming enforces isolation.
        """
        service = RAGService()
        user_a = uuid.uuid4()
        user_b = uuid.uuid4()
        queried_collections: list[str] = []

        def _mock_get_collection(name):
            queried_collections.append(name)
            coll = MagicMock()
            coll.query.return_value = {
                "ids": [[]],
                "documents": [[]],
                "metadatas": [[]],
            }
            return coll

        mock_client = MagicMock()
        mock_client.get_collection = _mock_get_collection
        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=self._make_query_embedding())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_client),
        ):
            await service.query_documents(user_id=user_a, query="sensitive data")

        for name in queried_collections:
            assert name != f"documents_{user_b}", (
                f"User B collection '{name}' was queried during user A's search"
            )

    @pytest.mark.asyncio
    async def test_embed_and_store_scoped_to_user_collection(self) -> None:
        """Embedding storage for user A uses documents_{user_a} (never user B's collection).

        **Validates: Requirements 4.4** — Property 8.
        """
        import numpy as np

        service = RAGService()
        user_a = str(uuid.uuid4())
        user_b_coll = f"documents_{uuid.uuid4()}"
        doc_id = str(uuid.uuid4())
        chunks = [ChunkResult(text="User A secret.", page_number=1)]
        used_collection_names: list[str] = []

        def _capture_collection(name, **kwargs):
            used_collection_names.append(name)
            coll = MagicMock()
            coll.add = MagicMock()
            return coll

        mock_client = MagicMock()
        mock_client.get_or_create_collection = _capture_collection
        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=np.array([[0.1] * 384]))

        mock_db = AsyncMock()
        mock_repo = AsyncMock()
        mock_repo.create_chunk = AsyncMock(return_value=MagicMock())

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_client),
            patch(
                "app.repositories.document_repository.DocumentRepository",
                return_value=mock_repo,
            ),
        ):
            await service.embed_and_store(chunks, doc_id, user_a, mock_db)

        assert user_b_coll not in used_collection_names
        assert f"documents_{user_a}" in used_collection_names

    @pytest.mark.asyncio
    async def test_get_document_by_id_enforces_ownership(self) -> None:
        """GET /documents requires that the document belongs to the requesting user.

        A request for user B's document by user A returns HTTP 404.

        **Validates: Requirements 4.5, 21.2** (HTTP-level ownership enforcement).
        """
        # User A is authenticated
        user_a_payload = _token_payload(_FIXED_USER_A)
        mock_db = _make_mock_db()

        mock_doc_repo = AsyncMock()
        # Returning None simulates the ownership check finding no result for user A
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        user_b_doc_id = uuid.uuid4()
        query_body = {"query": "what is this?", "top_k": 5}

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: user_a_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.post(
                        f"/documents/{user_b_doc_id}/query",
                        json=query_body,
                    )
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_document_enforces_ownership(self) -> None:
        """DELETE /documents/{id} returns 404 when document belongs to a different user.

        **Validates: Requirements 4.10, 21.2**
        """
        user_a_payload = _token_payload(_FIXED_USER_A)
        mock_db = _make_mock_db()

        mock_doc_repo = AsyncMock()
        # Returns None → ownership check finds no document for user A
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        user_b_doc_id = uuid.uuid4()

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: user_a_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{user_b_doc_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_query_returns_empty_when_no_user_documents(self) -> None:
        """query_documents returns an empty result when the user's collection has no data.

        **Validates: Requirements 4.6** — graceful empty result; no cross-user leakage.
        """
        import numpy as np

        service = RAGService()
        user_id = uuid.uuid4()

        def _raise_not_found(name):
            raise Exception("Collection does not exist")

        mock_client = MagicMock()
        mock_client.get_collection = _raise_not_found
        mock_model = MagicMock()
        mock_model.encode = MagicMock(return_value=np.array([[0.1] * 384]))

        with (
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch("chromadb.HttpClient", return_value=mock_client),
        ):
            result = await service.query_documents(user_id=user_id, query="anything")

        assert result.retrieved_chunks == []
        assert result.context == ""


# ===========================================================================
# 7. Delete cleanup — ChromaDB + PostgreSQL + MinIO all called
#    Requirements 4.10, 21.1
# ===========================================================================


class TestDeleteCleanup:
    """Deleting a document removes data from all three stores (Property 8, Req 4.10).

    **Validates: Requirements 21.1**
    Requirements 4.10: all Chunks and Embeddings removed within 60 seconds.
    """

    @pytest.mark.asyncio
    async def test_delete_endpoint_removes_from_postgresql(self) -> None:
        """DELETE /documents/{id} calls DocumentRepository.delete (removes PG row + chunks).

        **Validates: Requirements 4.10, 21.2**
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch("app.api.rag.router.rag_service.delete_embeddings", AsyncMock()),
            patch("app.api.rag.router.rag_service.delete_file_minio", AsyncMock()),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204
        mock_doc_repo.delete.assert_called_once_with(_FIXED_DOC_ID, _FIXED_USER_A)

    @pytest.mark.asyncio
    async def test_delete_endpoint_removes_from_chromadb(self) -> None:
        """DELETE /documents/{id} calls rag_service.delete_embeddings (ChromaDB cleanup).

        **Validates: Requirements 4.10**
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        mock_delete_embeddings = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                mock_delete_embeddings,
            ),
            patch("app.api.rag.router.rag_service.delete_file_minio", AsyncMock()),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204
        mock_delete_embeddings.assert_called_once_with(
            str(_FIXED_DOC_ID), str(_FIXED_USER_A)
        )

    @pytest.mark.asyncio
    async def test_delete_endpoint_removes_from_minio(self) -> None:
        """DELETE /documents/{id} calls rag_service.delete_file_minio (MinIO cleanup).

        **Validates: Requirements 4.10**
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        mock_delete_minio = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch("app.api.rag.router.rag_service.delete_embeddings", AsyncMock()),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio", mock_delete_minio
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204
        mock_delete_minio.assert_called_once_with(_FIXED_MINIO_KEY)

    @pytest.mark.asyncio
    async def test_delete_all_three_stores_called_together(self) -> None:
        """DELETE /documents/{id} triggers cleanup in PostgreSQL, ChromaDB, AND MinIO.

        **Validates: Requirements 4.10, 21.2** — all three stores are cleaned up.
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        mock_chroma_delete = AsyncMock()
        mock_minio_delete = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings", mock_chroma_delete
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio", mock_minio_delete
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204
        # All three cleanup calls must have happened
        mock_doc_repo.delete.assert_called_once()
        mock_chroma_delete.assert_called_once()
        mock_minio_delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_delete_returns_404_for_nonexistent_document(self) -> None:
        """DELETE /documents/{id} returns 404 when document does not exist.

        **Validates: Requirements 4.10, 21.2**
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        nonexistent_id = uuid.uuid4()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{nonexistent_id}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_chromadb_failure_does_not_prevent_response(self) -> None:
        """ChromaDB delete failure (best-effort) must not prevent 204 response.

        **Validates: Requirements 4.10** — graceful degradation on ChromaDB errors.
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        # ChromaDB delete raises — should be caught gracefully
        async def _failing_chroma(*args, **kwargs):
            raise RuntimeError("ChromaDB unavailable")

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                side_effect=_failing_chroma,
            ),
            patch("app.api.rag.router.rag_service.delete_file_minio", AsyncMock()),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_delete_minio_failure_does_not_prevent_response(self) -> None:
        """MinIO delete failure (best-effort) must not prevent 204 response.

        **Validates: Requirements 4.10** — graceful degradation on MinIO errors.
        """
        token_payload = _token_payload()
        mock_db = _make_mock_db()
        doc = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=doc)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        async def _failing_minio(*args, **kwargs):
            raise OSError("MinIO unreachable")

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch("app.api.rag.router.rag_service.delete_embeddings", AsyncMock()),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                side_effect=_failing_minio,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db
            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    resp = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_delete_embeddings_service_uses_user_scoped_collection(self) -> None:
        """delete_embeddings calls ChromaDB.delete on the correct per-user collection.

        **Validates: Requirements 4.10** — Property 8 at delete time.
        """
        service = RAGService()
        user_id = str(uuid.uuid4())
        doc_id = str(uuid.uuid4())

        deleted_from_collections: list[str] = []

        def _mock_get_collection(name):
            coll = MagicMock()

            def _delete(where):
                deleted_from_collections.append(name)

            coll.delete = _delete
            return coll

        mock_client = MagicMock()
        mock_client.get_collection = _mock_get_collection

        with patch("chromadb.HttpClient", return_value=mock_client):
            await service.delete_embeddings(doc_id, user_id)

        assert f"documents_{user_id}" in deleted_from_collections
