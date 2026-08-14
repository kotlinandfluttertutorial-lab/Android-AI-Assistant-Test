"""Unit tests for DELETE /documents/{id} endpoint.

Covers:
- HTTP 204 returned on successful deletion
- HTTP 404 when document does not exist
- HTTP 404 when document belongs to a different user
- DocumentRepository.delete called with correct document_id and user_id
- rag_service.delete_embeddings called with correct document_id and user_id strings
- rag_service.delete_file_minio called with the document's minio_key
- ChromaDB deletion failure does NOT cause the endpoint to fail (graceful degradation)
- MinIO deletion failure does NOT cause the endpoint to fail (graceful degradation)
- db.commit() is called after the PostgreSQL delete (before ChromaDB/MinIO)
- DocumentRepository.delete is called on the parent document (not chunks directly)

Requirements: 4.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

# Environment variables must be set before importing any app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

from app.database import get_db
from app.main import app
from app.models.document import Document, IngestionStatus
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_FIXED_USER_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_FIXED_DOC_ID = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
_FIXED_MINIO_KEY = f"{_FIXED_USER_ID}/{_FIXED_DOC_ID}/test.pdf"


def _make_token_payload(user_id: uuid.UUID = _FIXED_USER_ID) -> TokenPayload:
    """Build a minimal TokenPayload for the given user_id."""
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
    user_id: uuid.UUID = _FIXED_USER_ID,
    minio_key: str = _FIXED_MINIO_KEY,
) -> Document:
    """Return a minimal Document ORM object (not persisted)."""
    doc = Document(
        id=doc_id,
        user_id=user_id,
        file_name="test.pdf",
        mime_type="application/pdf",
        size_bytes=1024,
        minio_key=minio_key,
        ingestion_status=IngestionStatus.ready,
    )
    return doc


def _make_mock_db() -> AsyncMock:
    """Return a mock AsyncSession with commit and flush as AsyncMocks."""
    db = AsyncMock()
    db.commit = AsyncMock()
    db.flush = AsyncMock()
    db.delete = AsyncMock()
    return db


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def mock_db():
    """Provide a mock AsyncSession."""
    return _make_mock_db()


@pytest.fixture
def mock_token_payload():
    """Provide a TokenPayload for the fixed test user."""
    return _make_token_payload()


# ---------------------------------------------------------------------------
# Integration-style tests via httpx AsyncClient + dependency overrides
# ---------------------------------------------------------------------------


class TestDeleteDocumentEndpoint:
    """Router-level tests for DELETE /documents/{document_id}."""

    @pytest.mark.asyncio
    async def test_delete_returns_204_on_success(self) -> None:
        """Successful deletion should return HTTP 204 with no body."""
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                new_callable=AsyncMock,
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                new_callable=AsyncMock,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")

                assert response.status_code == 204
                assert response.content == b""
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

    @pytest.mark.asyncio
    async def test_delete_returns_404_when_document_not_found(self) -> None:
        """HTTP 404 should be returned when the document does not exist."""
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")

                assert response.status_code == 404
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

    @pytest.mark.asyncio
    async def test_delete_returns_404_when_document_belongs_to_different_user(
        self,
    ) -> None:
        """HTTP 404 should be returned when the document belongs to another user.

        DocumentRepository.get_by_id filters by user_id, so a document owned by
        a different user is indistinguishable from a missing document from the
        caller's perspective (no information leakage).
        """
        mock_db = _make_mock_db()
        # Authenticated as user A, but document belongs to user B
        user_a_id = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        token_payload = _make_token_payload(user_id=user_a_id)

        mock_doc_repo = AsyncMock()
        # Simulates the repo returning None because user_id doesn't match
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")

                assert response.status_code == 404
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

    @pytest.mark.asyncio
    async def test_delete_calls_repo_delete_with_correct_ids(self) -> None:
        """DocumentRepository.delete must be called with the correct document_id and user_id."""
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                new_callable=AsyncMock,
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                new_callable=AsyncMock,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        mock_doc_repo.delete.assert_called_once_with(_FIXED_DOC_ID, _FIXED_USER_ID)

    @pytest.mark.asyncio
    async def test_delete_calls_delete_embeddings_with_string_ids(self) -> None:
        """rag_service.delete_embeddings must be called with document_id and user_id as strings."""
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        mock_delete_embeddings = AsyncMock()
        mock_delete_minio = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                mock_delete_embeddings,
            ),
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
                    await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        mock_delete_embeddings.assert_called_once_with(
            str(_FIXED_DOC_ID), str(_FIXED_USER_ID)
        )

    @pytest.mark.asyncio
    async def test_delete_calls_delete_file_minio_with_minio_key(self) -> None:
        """rag_service.delete_file_minio must be called with the document's minio_key."""
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        mock_delete_embeddings = AsyncMock()
        mock_delete_minio = AsyncMock()

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                mock_delete_embeddings,
            ),
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
                    await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        mock_delete_minio.assert_called_once_with(_FIXED_MINIO_KEY)

    @pytest.mark.asyncio
    async def test_chromadb_failure_does_not_fail_request(self) -> None:
        """ChromaDB deletion failure must NOT cause the endpoint to return an error.

        This tests graceful degradation: even if delete_embeddings raises,
        the HTTP response must still be 204 and the DB commit must have occurred.
        """
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        async def _failing_delete_embeddings(*args, **kwargs):
            raise ConnectionError("ChromaDB unreachable")

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                side_effect=_failing_delete_embeddings,
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                new_callable=AsyncMock,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        # The request must succeed despite ChromaDB failure
        assert response.status_code == 204
        # The DB commit must have occurred before the ChromaDB call
        mock_db.commit.assert_called_once()

    @pytest.mark.asyncio
    async def test_minio_failure_does_not_fail_request(self) -> None:
        """MinIO deletion failure must NOT cause the endpoint to return an error.

        This tests graceful degradation: even if delete_file_minio raises,
        the HTTP response must still be 204.
        """
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)
        mock_doc_repo.delete = AsyncMock(return_value=True)

        async def _failing_delete_minio(*args, **kwargs):
            raise OSError("MinIO connection refused")

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                new_callable=AsyncMock,
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                side_effect=_failing_delete_minio,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert response.status_code == 204

    @pytest.mark.asyncio
    async def test_db_commit_called_before_chromadb_and_minio(self) -> None:
        """db.commit() must be called immediately after the PostgreSQL delete.

        The order must be:
        1. doc_repo.delete(document_id, user_id)   — marks the ORM object for deletion
        2. db.commit()                              — commits the SQL DELETE
        3. rag_service.delete_embeddings(...)       — best-effort ChromaDB cleanup
        4. rag_service.delete_file_minio(...)       — best-effort MinIO cleanup

        This is verified by tracking the call order using side_effect counters.
        """
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()
        document = _make_document()

        call_order: list[str] = []

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=document)

        async def _repo_delete(*args, **kwargs):
            call_order.append("repo.delete")
            return True

        async def _db_commit(*args, **kwargs):
            call_order.append("db.commit")

        async def _delete_embeddings(*args, **kwargs):
            call_order.append("delete_embeddings")

        async def _delete_minio(*args, **kwargs):
            call_order.append("delete_file_minio")

        mock_doc_repo.delete = AsyncMock(side_effect=_repo_delete)
        mock_db.commit = AsyncMock(side_effect=_db_commit)

        with (
            patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo),
            patch(
                "app.api.rag.router.rag_service.delete_embeddings",
                side_effect=_delete_embeddings,
            ),
            patch(
                "app.api.rag.router.rag_service.delete_file_minio",
                side_effect=_delete_minio,
            ),
        ):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{_FIXED_DOC_ID}")
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)

        assert response.status_code == 204
        assert call_order == [
            "repo.delete",
            "db.commit",
            "delete_embeddings",
            "delete_file_minio",
        ], f"Unexpected call order: {call_order}"


# ---------------------------------------------------------------------------
# Repository-layer tests (no HTTP stack)
# ---------------------------------------------------------------------------


class TestDocumentRepositoryDelete:
    """Tests for DocumentRepository.delete — verifies cascade is via the ORM parent."""

    @pytest.mark.asyncio
    async def test_delete_calls_db_delete_on_parent_document(self) -> None:
        """delete() should call db.delete(document) on the parent Document object.

        The cascade to DocumentChunk rows is handled by SQLAlchemy's
        cascade="all, delete-orphan" relationship, NOT by explicit chunk deletion.
        """
        from app.repositories.document_repository import DocumentRepository

        mock_db = _make_mock_db()
        document = _make_document()
        mock_db.execute = AsyncMock(
            return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=document))
        )
        mock_db.delete = AsyncMock()
        mock_db.flush = AsyncMock()

        repo = DocumentRepository(mock_db)

        # Patch get_by_id to return our document directly
        with patch.object(repo, "get_by_id", AsyncMock(return_value=document)):
            result = await repo.delete(_FIXED_DOC_ID, _FIXED_USER_ID)

        assert result is True
        # db.delete must be called with the document object (cascade via ORM)
        mock_db.delete.assert_called_once_with(document)
        # db.flush must be called to materialise the delete in the session
        mock_db.flush.assert_called_once()

    @pytest.mark.asyncio
    async def test_delete_returns_false_when_document_not_found(self) -> None:
        """delete() should return False when no matching document is found."""
        from app.repositories.document_repository import DocumentRepository

        mock_db = _make_mock_db()
        repo = DocumentRepository(mock_db)

        with patch.object(repo, "get_by_id", AsyncMock(return_value=None)):
            result = await repo.delete(_FIXED_DOC_ID, _FIXED_USER_ID)

        assert result is False
        mock_db.delete.assert_not_called()

    @pytest.mark.asyncio
    async def test_delete_does_not_call_delete_chunks_directly(self) -> None:
        """The delete method must NOT call delete_chunks_by_document directly.

        Chunk deletion is managed by the SQLAlchemy cascade, not explicit calls.
        """
        from app.repositories.document_repository import DocumentRepository

        mock_db = _make_mock_db()
        document = _make_document()
        repo = DocumentRepository(mock_db)

        with (
            patch.object(repo, "get_by_id", AsyncMock(return_value=document)),
            patch.object(
                repo, "delete_chunks_by_document", AsyncMock()
            ) as mock_chunk_delete,
        ):
            await repo.delete(_FIXED_DOC_ID, _FIXED_USER_ID)

        # Chunk deletion must NOT be called explicitly — cascade handles it
        mock_chunk_delete.assert_not_called()


# ---------------------------------------------------------------------------
# Service-layer tests — delete_embeddings graceful degradation
# ---------------------------------------------------------------------------


class TestRAGServiceDeleteEmbeddings:
    """Tests for RAGService.delete_embeddings — verifies graceful degradation."""

    @pytest.mark.asyncio
    async def test_delete_embeddings_swallows_chromadb_exceptions(self) -> None:
        """delete_embeddings must not propagate ChromaDB exceptions.

        Even when ChromaDB is unavailable, the method should complete without
        raising so that the DELETE endpoint can still return 204.
        """
        from app.services.rag_service import RAGService

        service = RAGService()

        def _failing_chroma():
            raise ConnectionError("ChromaDB unreachable in test")

        with patch("asyncio.to_thread", side_effect=_failing_chroma):
            # Must not raise
            await service.delete_embeddings(str(_FIXED_DOC_ID), str(_FIXED_USER_ID))

    @pytest.mark.asyncio
    async def test_delete_file_minio_swallows_exceptions(self) -> None:
        """delete_file_minio must not propagate MinIO exceptions.

        Exceptions are caught inside the method and logged as warnings.
        """
        from app.services.rag_service import RAGService

        service = RAGService()

        def _failing_minio():
            raise OSError("MinIO connection refused in test")

        with patch("asyncio.to_thread", side_effect=_failing_minio):
            # Must not raise
            await service.delete_file_minio(_FIXED_MINIO_KEY)


# ---------------------------------------------------------------------------
# Edge case: invalid UUID in path
# ---------------------------------------------------------------------------


class TestDeleteEndpointEdgeCases:
    """Edge case and validation tests for the delete endpoint."""

    @pytest.mark.asyncio
    async def test_delete_with_invalid_uuid_returns_422(self) -> None:
        """A non-UUID path parameter should return HTTP 422 (validation error)."""
        token_payload = _make_token_payload()
        mock_db = _make_mock_db()

        app.dependency_overrides[get_current_user] = lambda: token_payload
        app.dependency_overrides[get_db] = lambda: mock_db

        try:
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                response = await client.delete("/documents/not-a-valid-uuid")

            assert response.status_code == 422
        finally:
            app.dependency_overrides.pop(get_current_user, None)
            app.dependency_overrides.pop(get_db, None)

    @pytest.mark.asyncio
    async def test_delete_with_nonexistent_uuid_returns_404(self) -> None:
        """A valid UUID that matches no document should return HTTP 404."""
        nonexistent_id = uuid.uuid4()
        mock_db = _make_mock_db()
        token_payload = _make_token_payload()

        mock_doc_repo = AsyncMock()
        mock_doc_repo.get_by_id = AsyncMock(return_value=None)

        with patch("app.api.rag.router.DocumentRepository", return_value=mock_doc_repo):
            app.dependency_overrides[get_current_user] = lambda: token_payload
            app.dependency_overrides[get_db] = lambda: mock_db

            try:
                async with AsyncClient(
                    transport=ASGITransport(app=app), base_url="http://test"
                ) as client:
                    response = await client.delete(f"/documents/{nonexistent_id}")

                assert response.status_code == 404
            finally:
                app.dependency_overrides.pop(get_current_user, None)
                app.dependency_overrides.pop(get_db, None)
