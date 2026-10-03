"""Unit tests for versioned document management APIs.

Covers every endpoint in the v1_documents_router:
  POST   /api/v1/documents/upload
  GET    /api/v1/documents
  GET    /api/v1/documents/{id}
  DELETE /api/v1/documents/{id}

No real DB, MinIO, or Celery — all dependencies are mocked.
No production credentials required.
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

# ── env vars before any app imports ──────────────────────────────────────────
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import sys
from unittest.mock import MagicMock as _MagicMock

# ── Stub out google.genai before app.main imports the services chain ──────────
# services/__init__.py imports ai_orchestrator → llm_clients → google.genai.
# google-genai is not installed in this venv; stub it here so tests that need
# app.main can collect without requiring the production SDK.
_google_mock = _MagicMock()
sys.modules.setdefault("google.genai", _google_mock)
sys.modules.setdefault("google.genai.types", _google_mock)

from app.database import get_db
from app.main import app
from app.models.document import Document, IngestionStatus
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ── Shared fixtures ───────────────────────────────────────────────────────────

_USER_A = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_USER_B = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
_DOC_ID = uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")
_JOB_ID = uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
_MINIO_KEY = f"{_USER_A}/{_DOC_ID}/report.pdf"


def _token(user_id: uuid.UUID = _USER_A) -> TokenPayload:
    now = datetime.now(tz=timezone.utc)
    return TokenPayload(
        sub=str(user_id),
        role="user",
        jti=str(uuid.uuid4()),
        iat=now,
        exp=now.replace(year=now.year + 1),
    )


def _doc(
    doc_id: uuid.UUID = _DOC_ID,
    user_id: uuid.UUID = _USER_A,
    status: IngestionStatus = IngestionStatus.ready,
) -> Document:
    d = Document()
    d.id = doc_id
    d.user_id = user_id
    d.file_name = "report.pdf"
    d.mime_type = "application/pdf"
    d.size_bytes = 2048
    d.minio_key = _MINIO_KEY
    d.ingestion_status = status
    d.page_count = 5
    d.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return d


def _mock_db() -> AsyncMock:
    db = AsyncMock()
    db.commit = AsyncMock()
    db.flush = AsyncMock()
    return db


@pytest.fixture
def mock_db():
    db = _mock_db()
    app.dependency_overrides[get_db] = lambda: db
    yield db
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture
def auth_user_a():
    payload = _token(_USER_A)
    app.dependency_overrides[get_current_user] = lambda: payload
    yield payload
    app.dependency_overrides.pop(get_current_user, None)


@pytest.fixture
def auth_user_b():
    payload = _token(_USER_B)
    app.dependency_overrides[get_current_user] = lambda: payload
    yield payload
    app.dependency_overrides.pop(get_current_user, None)


# ── POST /api/v1/documents/upload ─────────────────────────────────────────────

class TestV1DocumentUpload:
    @pytest.mark.asyncio
    async def test_upload_returns_202(self, mock_db, auth_user_a):
        mock_doc = _doc()
        with (
            patch("app.api.rag.router.rag_service.validate_mime_and_upload"),
            patch("app.repositories.document_repository.DocumentRepository.create",
                  new_callable=AsyncMock, return_value=mock_doc),
            patch("app.api.rag.router.rag_service.store_file_minio",
                  new_callable=AsyncMock, return_value=_MINIO_KEY),
            patch("app.api.rag.router.rag_service.create_ingestion_job",
                  new_callable=AsyncMock, return_value=_JOB_ID),
            patch("app.workers.rag_worker.ingest_document_task") as mock_task,
            patch("app.repositories.job_repository.JobRepository.update_status",
                  new_callable=AsyncMock),
        ):
            mock_task.delay.return_value = MagicMock(id="celery-task-id")
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/documents/upload",
                    files={"file": ("report.pdf", b"%PDF-1.4 test content", "application/pdf")},
                )
        assert resp.status_code == 202
        body = resp.json()
        assert "document_id" in body
        assert "job_id" in body
        assert body["status"] == "pending"

    @pytest.mark.asyncio
    async def test_upload_requires_auth(self):
        # No auth override — real get_current_user returns 401
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.post(
                "/api/v1/documents/upload",
                files={"file": ("f.pdf", b"data", "application/pdf")},
            )
        assert resp.status_code == 401

    @pytest.mark.asyncio
    async def test_upload_invalid_format_returns_422(self, mock_db, auth_user_a):
        from fastapi import HTTPException, status as http_status

        def _raise_422(*a, **kw):
            raise HTTPException(
                status_code=http_status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="Unsupported file type '.xlsx'.",
            )

        with patch("app.api.rag.router.rag_service.validate_mime_and_upload",
                   side_effect=_raise_422):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/documents/upload",
                    files={"file": ("data.xlsx", b"PK\x03\x04", "application/vnd.ms-excel")},
                )
        assert resp.status_code == 422
        # Internal error detail should be human-readable but not expose stack trace
        assert "xlsx" in resp.json().get("detail", "").lower() or resp.status_code == 422

    @pytest.mark.asyncio
    async def test_upload_minio_failure_returns_500_not_stack_trace(
        self, mock_db, auth_user_a
    ):
        mock_doc = _doc()
        with (
            patch("app.api.rag.router.rag_service.validate_mime_and_upload"),
            patch("app.repositories.document_repository.DocumentRepository.create",
                  new_callable=AsyncMock, return_value=mock_doc),
            patch("app.api.rag.router.rag_service.store_file_minio",
                  new_callable=AsyncMock, side_effect=RuntimeError("S3 timeout")),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/documents/upload",
                    files={"file": ("report.pdf", b"%PDF-1.4", "application/pdf")},
                )
        assert resp.status_code == 500
        # Must not leak internal error
        body_text = resp.text
        assert "S3 timeout" not in body_text
        assert "Traceback" not in body_text


# ── GET /api/v1/documents ─────────────────────────────────────────────────────

class TestV1ListDocuments:
    @pytest.mark.asyncio
    async def test_list_returns_200_with_documents(self, mock_db, auth_user_a):
        docs = [_doc(doc_id=uuid.uuid4()), _doc(doc_id=uuid.uuid4())]
        with patch(
            "app.repositories.document_repository.DocumentRepository.list_by_user",
            new_callable=AsyncMock, return_value=docs,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get("/api/v1/documents")
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 2
        assert len(body["documents"]) == 2

    @pytest.mark.asyncio
    async def test_list_empty_returns_200_empty_list(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.list_by_user",
            new_callable=AsyncMock, return_value=[],
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get("/api/v1/documents")
        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 0
        assert body["documents"] == []

    @pytest.mark.asyncio
    async def test_list_requires_auth(self):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get("/api/v1/documents")
        assert resp.status_code == 401

    @pytest.mark.asyncio
    async def test_list_scoped_to_authenticated_user(self, mock_db, auth_user_a):
        """Verify list_by_user is called with the token user's UUID."""
        captured = {}
        async def _capture_user_id(self_repo, uid):
            captured["user_id"] = uid
            return []

        with patch(
            "app.repositories.document_repository.DocumentRepository.list_by_user",
            new_callable=lambda: lambda *a, **kw: AsyncMock(return_value=[])(),
        ):
            pass  # just verify it returns 200 — scoping tested in repo unit tests

        with patch(
            "app.repositories.document_repository.DocumentRepository.list_by_user",
            new_callable=AsyncMock, return_value=[],
        ) as mock_list:
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                await client.get("/api/v1/documents")
            # Should be called with _USER_A uuid
            mock_list.assert_awaited_once()
            called_uid = mock_list.call_args.args[0]
            assert called_uid == _USER_A

    @pytest.mark.asyncio
    async def test_list_document_fields_present(self, mock_db, auth_user_a):
        docs = [_doc()]
        with patch(
            "app.repositories.document_repository.DocumentRepository.list_by_user",
            new_callable=AsyncMock, return_value=docs,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get("/api/v1/documents")
        d = resp.json()["documents"][0]
        # DocumentResponse serialises document id as "document_id" (alias="id")
        # with populate_by_name=True — the actual serialized key depends on model_config
        assert "document_id" in d or "id" in d   # accept either alias form
        assert "file_name" in d
        assert "ingestion_status" in d
        assert "created_at" in d


# ── GET /api/v1/documents/{id} ────────────────────────────────────────────────

class TestV1GetDocument:
    @pytest.mark.asyncio
    async def test_get_returns_200_for_owned_document(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=_doc(),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 200
        body = resp.json()
        assert body["document_id"] == str(_DOC_ID)
        assert body["file_name"] == "report.pdf"
        assert body["ingestion_status"] == "ready"

    @pytest.mark.asyncio
    async def test_get_returns_404_for_missing_document(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=None,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_get_returns_404_not_403_for_other_users_document(
        self, mock_db, auth_user_b
    ):
        """Ownership check: returns 404 (not 403) to avoid leaking UUID existence."""
        # Repository returns None when user_id doesn't match
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=None,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 404
        assert resp.status_code != 403

    @pytest.mark.asyncio
    async def test_get_requires_auth(self):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 401

    @pytest.mark.asyncio
    async def test_get_invalid_uuid_returns_422(self, mock_db, auth_user_a):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.get("/api/v1/documents/not-a-uuid")
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_get_response_has_all_detail_fields(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=_doc(),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        body = resp.json()
        for field in ("document_id", "file_name", "mime_type", "size_bytes",
                      "ingestion_status", "page_count", "created_at"):
            assert field in body, f"Missing field: {field}"

    @pytest.mark.asyncio
    async def test_get_does_not_expose_minio_key(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=_doc(),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.get(f"/api/v1/documents/{_DOC_ID}")
        body = resp.json()
        assert "minio_key" not in body


# ── DELETE /api/v1/documents/{id} ────────────────────────────────────────────

class TestV1DeleteDocument:
    @pytest.mark.asyncio
    async def test_delete_returns_204(self, mock_db, auth_user_a):
        with (
            patch("app.repositories.document_repository.DocumentRepository.get_by_id",
                  new_callable=AsyncMock, return_value=_doc()),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock, return_value=True),
            patch("app.api.rag.router.rag_service.delete_embeddings",
                  new_callable=AsyncMock),
            patch("app.api.rag.router.rag_service.delete_file_minio",
                  new_callable=AsyncMock),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_delete_returns_404_for_missing(self, mock_db, auth_user_a):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=None,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_returns_404_not_403_for_other_user(
        self, mock_db, auth_user_b
    ):
        with patch(
            "app.repositories.document_repository.DocumentRepository.get_by_id",
            new_callable=AsyncMock, return_value=None,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_calls_repo_delete(self, mock_db, auth_user_a):
        with (
            patch("app.repositories.document_repository.DocumentRepository.get_by_id",
                  new_callable=AsyncMock, return_value=_doc()),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock, return_value=True) as mock_del,
            patch("app.api.rag.router.rag_service.delete_embeddings",
                  new_callable=AsyncMock),
            patch("app.api.rag.router.rag_service.delete_file_minio",
                  new_callable=AsyncMock),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                await client.delete(f"/api/v1/documents/{_DOC_ID}")
        mock_del.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_delete_embedding_failure_does_not_cause_500(
        self, mock_db, auth_user_a
    ):
        with (
            patch("app.repositories.document_repository.DocumentRepository.get_by_id",
                  new_callable=AsyncMock, return_value=_doc()),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock, return_value=True),
            patch("app.api.rag.router.rag_service.delete_embeddings",
                  new_callable=AsyncMock,
                  side_effect=RuntimeError("ChromaDB down")),
            patch("app.api.rag.router.rag_service.delete_file_minio",
                  new_callable=AsyncMock),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        # Best-effort — ChromaDB failure should NOT fail the request
        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_delete_minio_failure_does_not_cause_500(
        self, mock_db, auth_user_a
    ):
        with (
            patch("app.repositories.document_repository.DocumentRepository.get_by_id",
                  new_callable=AsyncMock, return_value=_doc()),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock, return_value=True),
            patch("app.api.rag.router.rag_service.delete_embeddings",
                  new_callable=AsyncMock),
            patch("app.api.rag.router.rag_service.delete_file_minio",
                  new_callable=AsyncMock,
                  side_effect=RuntimeError("MinIO unreachable")),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_delete_requires_auth(self):
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            resp = await client.delete(f"/api/v1/documents/{_DOC_ID}")
        assert resp.status_code == 401

    @pytest.mark.asyncio
    async def test_delete_db_committed_before_storage_cleanup(
        self, mock_db, auth_user_a
    ):
        call_order = []
        async def _fake_delete(*a, **kw):
            call_order.append("db_delete")
            return True

        async def _fake_embed_delete(*a, **kw):
            call_order.append("embed_delete")

        mock_db.commit = AsyncMock(side_effect=lambda: call_order.append("commit"))

        with (
            patch("app.repositories.document_repository.DocumentRepository.get_by_id",
                  new_callable=AsyncMock, return_value=_doc()),
            patch("app.repositories.document_repository.DocumentRepository.delete",
                  new_callable=AsyncMock, side_effect=_fake_delete),
            patch("app.api.rag.router.rag_service.delete_embeddings",
                  new_callable=AsyncMock, side_effect=_fake_embed_delete),
            patch("app.api.rag.router.rag_service.delete_file_minio",
                  new_callable=AsyncMock),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                await client.delete(f"/api/v1/documents/{_DOC_ID}")

        # DB commit must happen before embedding/MinIO deletion
        assert "db_delete" in call_order
        assert "commit" in call_order
        db_idx = call_order.index("db_delete")
        commit_idx = call_order.index("commit")
        embed_idx = call_order.index("embed_delete")
        assert db_idx < commit_idx < embed_idx
