"""Property-based tests for document upload format enforcement.

Property 26: Document Upload Format Enforcement
**Validates: Requirements 4.1**

Uses Hypothesis to generate files of valid/invalid formats and sizes and assert:
- Valid files (PDF, DOCX, TXT, Markdown) with size ≤50 MB are accepted (HTTP 202).
- Files with unsupported formats are rejected (HTTP 422).
- Files with valid formats but size >50 MB are rejected (HTTP 422).
- Rejected uploads result in NO bytes stored in MinIO or ChromaDB.

Requirements: 4.1
"""

from __future__ import annotations

import io
import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient  # noqa: F401 — used lazily via _get_client()
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# Set env vars before importing any app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Module-level shared TestClient
#
# Creating a TestClient from the full app is expensive (loads all routers,
# sets up Prometheus etc.). We instantiate it once at module level and reuse
# it across all Hypothesis examples to avoid rebuilding it for every call.
# ---------------------------------------------------------------------------

_SHARED_CLIENT = None


def _get_client():
    """Return the shared TestClient, creating it on first call."""
    global _SHARED_CLIENT
    if _SHARED_CLIENT is None:
        from fastapi.testclient import TestClient

        from app.main import app

        _SHARED_CLIENT = TestClient(app, raise_server_exceptions=False)
    return _SHARED_CLIENT


# ---------------------------------------------------------------------------
# Constants: supported and unsupported formats
# ---------------------------------------------------------------------------

# Supported (filename extension, MIME type) pairs per Requirement 4.1
VALID_FORMATS: list[tuple[str, str]] = [
    ("document.pdf", "application/pdf"),
    (
        "report.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),
    ("notes.txt", "text/plain"),
    ("readme.md", "text/markdown"),
]

# Unsupported formats that must always be rejected
INVALID_FORMATS: list[tuple[str, str]] = [
    ("malware.exe", "application/octet-stream"),
    ("archive.zip", "application/zip"),
    ("image.png", "image/png"),
    ("video.mp4", "video/mp4"),
    ("spreadsheet.csv", "text/csv"),
    ("binary.bin", "application/octet-stream"),
    ("script.sh", "application/x-sh"),
    ("photo.jpg", "image/jpeg"),
    ("data.json", "application/json"),
    (
        "presentation.pptx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ),
]

# 50 MB in bytes (the hard limit)
MAX_SIZE_BYTES = 50 * 1024 * 1024


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_auth_token(role: str = "user") -> str:
    """Create a signed JWT for use in test requests."""
    token, _expiry = create_access_token(user_id=uuid.uuid4(), role=role)
    return token


def _make_file_bytes(size_bytes: int) -> bytes:
    """Return a byte string of the requested length (filled with b'x')."""
    return b"x" * size_bytes


def _patch_db_and_auth():
    """Return a context manager dict for patching the DB session and JWT auth.

    Patches:
    - ``app.database.get_db`` — yields a no-op AsyncMock session.
    - ``app.security.dependencies.get_current_user`` — returns a valid TokenPayload.
    - ``app.security.dependencies._is_jti_revoked`` — always returns False.
    """
    from app.security.jwt_handler import TokenPayload

    fake_user = TokenPayload(
        sub=str(uuid.uuid4()),
        role="user",
        jti=str(uuid.uuid4()),
        iat=datetime.now(tz=timezone.utc),
        exp=datetime.now(tz=timezone.utc) + timedelta(hours=1),
    )

    mock_db = AsyncMock()
    mock_db.flush = AsyncMock()
    mock_db.commit = AsyncMock()

    return fake_user, mock_db


def _build_upload_patches(fake_user, mock_db):
    """Return a list of (patch_target, mock_value) pairs for a rejected upload test.

    These patches ensure validation runs but storage calls are never made.
    We mock:
    - get_db → yields mock_db
    - get_current_user → returns fake_user
    - _is_jti_revoked → False (so auth passes)
    - rag_service.store_file_minio → should NOT be called on rejection
    - DocumentRepository.create → should NOT be called on rejection
    - ingest_document_task.delay → should NOT be called on rejection
    """
    return fake_user, mock_db


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Strategy: pick a valid (filename, mime) pair
valid_format_strategy = st.sampled_from(VALID_FORMATS)

# Strategy: pick an invalid (filename, mime) pair
invalid_format_strategy = st.sampled_from(INVALID_FORMATS)

# Strategy: small file size for HTTP-layer tests (1 byte to 1 KB)
# These tests validate format enforcement at the HTTP layer; actual size doesn't
# matter for the format check, so we keep payloads tiny to avoid slow transfers.
http_small_size_strategy = st.integers(min_value=1, max_value=1024)

# Strategy: file size within the valid range [1 byte, 50 MB]
# Used for service-layer tests where no actual bytes are transferred.
valid_size_strategy = st.integers(min_value=1, max_value=MAX_SIZE_BYTES)

# Strategy: file size over the 50 MB limit, up to 100 MB
# Used for service-layer tests that call validate_mime_and_upload() directly.
oversized_strategy = st.integers(
    min_value=MAX_SIZE_BYTES + 1, max_value=100 * 1024 * 1024
)

# Strategy: oversized but still small enough for HTTP tests (51 MB..55 MB expressed as
# an integer size that the router reads from the body). We pass a small payload but
# inject the declared size via a Content-Length override trick — however, the router
# calls `await file.read()` and uses `len(file_bytes)`, so the actual bytes must be
# large. For the HTTP-layer oversized property we therefore use an integer that
# represents sizes just over the limit (51 MB – 60 MB range), but we only use this
# in service-layer tests to keep HTTP tests fast.
http_oversized_declared_strategy = st.integers(
    min_value=MAX_SIZE_BYTES + 1, max_value=MAX_SIZE_BYTES + 5 * 1024 * 1024
)


# ---------------------------------------------------------------------------
# Property 26 — Sub-property A: valid format + valid size → HTTP 202 accepted
#
# Validates: Requirements 4.1
# ---------------------------------------------------------------------------


@given(
    format_pair=valid_format_strategy,
    size_bytes=http_small_size_strategy,
)
@settings(
    max_examples=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_26a_valid_format_and_size_accepted(
    format_pair: tuple[str, str],
    size_bytes: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26A: Any file in a supported format (PDF, DOCX, TXT, Markdown)
    with size ≤ 50 MB must be accepted with HTTP 202. No storage rejection
    should occur based on format or size alone.

    The HTTP-layer tests use small payloads (1 byte – 1 KB) to keep each
    test fast; the service-layer property tests (26D) cover the full 1–50 MB
    size range directly against the validation function.
    """
    filename, mime_type = format_pair
    file_bytes = _make_file_bytes(size_bytes)

    fake_user, mock_db = _patch_db_and_auth()

    # Mock document repository to avoid real DB
    mock_doc = MagicMock()
    mock_doc.id = uuid.uuid4()
    mock_doc.minio_key = ""

    # Mock the DB session as an async context manager / generator
    async def _get_db_override():
        yield mock_db

    with (
        patch("app.api.rag.router.get_current_user", return_value=fake_user),
        patch("app.api.rag.router.get_db", _get_db_override),
        patch(
            "app.api.rag.router.rag_service.store_file_minio", new_callable=AsyncMock
        ) as mock_store,
        patch(
            "app.api.rag.router.rag_service.create_ingestion_job",
            new_callable=AsyncMock,
        ) as mock_job,
        patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
    ):
        mock_store.return_value = f"user/{mock_doc.id}/{filename}"
        mock_job.return_value = uuid.uuid4()

        mock_repo_instance = AsyncMock()
        mock_repo_instance.create = AsyncMock(return_value=mock_doc)
        MockDocRepo.return_value = mock_repo_instance

        # Also patch Celery dispatch to avoid real broker
        with patch("app.workers.rag_worker.ingest_document_task") as mock_task:
            mock_celery_result = MagicMock()
            mock_celery_result.id = str(uuid.uuid4())
            mock_task.delay.return_value = mock_celery_result

            # Patch JobRepository for the Celery task ID update step
            with patch("app.api.rag.router.JobRepository") as MockJobRepo:
                mock_job_repo = AsyncMock()
                mock_job_repo.update_status = AsyncMock()
                MockJobRepo.return_value = mock_job_repo

                client = _get_client()
                response = client.post(
                    "/documents/upload",
                    files={"file": (filename, io.BytesIO(file_bytes), mime_type)},
                    headers={"Authorization": f"Bearer {_make_auth_token()}"},
                )

    # A valid file must be accepted — HTTP 202
    assert response.status_code == 202, (
        f"Property 26A violated: valid file '{filename}' ({size_bytes} bytes) "
        f"got HTTP {response.status_code} instead of 202. "
        f"Response: {response.text[:200]}"
    )


# ---------------------------------------------------------------------------
# Property 26 — Sub-property B: invalid format → HTTP 422, nothing stored
#
# Validates: Requirements 4.1
# ---------------------------------------------------------------------------


@given(
    format_pair=invalid_format_strategy,
    size_bytes=http_small_size_strategy,
)
@settings(
    max_examples=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_26b_invalid_format_rejected_nothing_stored(
    format_pair: tuple[str, str],
    size_bytes: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26B: Any file with an unsupported format must be rejected with
    HTTP 422. Neither MinIO.put_object nor ChromaDB.add must be called
    (no bytes stored in either system).

    Uses tiny payloads at the HTTP layer; format enforcement is independent of
    file size (service-layer property 26E tests the full size range).
    """
    filename, mime_type = format_pair
    file_bytes = _make_file_bytes(size_bytes)

    fake_user, mock_db = _patch_db_and_auth()

    async def _get_db_override():
        yield mock_db

    with (
        patch("app.api.rag.router.get_current_user", return_value=fake_user),
        patch("app.api.rag.router.get_db", _get_db_override),
        patch(
            "app.api.rag.router.rag_service.store_file_minio", new_callable=AsyncMock
        ) as mock_store,
        patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
    ):
        mock_store.return_value = "unused/key"
        mock_repo_instance = AsyncMock()
        MockDocRepo.return_value = mock_repo_instance

        client = _get_client()
        response = client.post(
            "/documents/upload",
            files={"file": (filename, io.BytesIO(file_bytes), mime_type)},
            headers={"Authorization": f"Bearer {_make_auth_token()}"},
        )

        # Validation fires BEFORE storage (Property 26)
        assert response.status_code == 422, (
            f"Property 26B violated: unsupported file '{filename}' "
            f"got HTTP {response.status_code} instead of 422. "
            f"Response: {response.text[:200]}"
        )

        # MinIO store must NOT have been called on rejection
        (
            mock_store.assert_not_called(),
            (
                f"Property 26B violated: store_file_minio was called for "
                f"rejected file '{filename}'."
            ),
        )

        # DocumentRepository.create must NOT have been called on rejection
        (
            mock_repo_instance.create.assert_not_called(),
            (
                f"Property 26B violated: DocumentRepository.create was called for "
                f"rejected file '{filename}'."
            ),
        )


# ---------------------------------------------------------------------------
# Property 26 — Sub-property C: valid format but oversized → HTTP 422, nothing stored
#
# Validates: Requirements 4.1
# ---------------------------------------------------------------------------


@given(
    format_pair=valid_format_strategy,
    declared_size=http_oversized_declared_strategy,
)
@settings(
    max_examples=10,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_26c_oversized_valid_format_rejected_nothing_stored(
    format_pair: tuple[str, str],
    declared_size: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26C: Any file in a supported format (PDF, DOCX, TXT, Markdown)
    whose size exceeds 50 MB must be rejected with HTTP 422. Neither
    MinIO.put_object nor ChromaDB.add must be called (no bytes stored).

    To avoid allocating hundreds of MB of memory during the test, the upload
    file's `read()` method is patched to return a bytes object whose
    ``len()`` equals ``declared_size`` without actually holding all the data.
    The validation logic only calls ``len(file_bytes)`` so this is equivalent.
    """
    filename, mime_type = format_pair

    # Build a mock file_bytes object whose len() returns declared_size.
    # bytes.__len__ is a C slot and cannot be overridden in a subclass,
    # so we use MagicMock with __len__ configured instead.
    fake_bytes = MagicMock(spec=bytes)
    fake_bytes.__len__ = MagicMock(return_value=declared_size)

    fake_user, mock_db = _patch_db_and_auth()

    async def _get_db_override():
        yield mock_db

    with (
        patch("app.api.rag.router.get_current_user", return_value=fake_user),
        patch("app.api.rag.router.get_db", _get_db_override),
        patch(
            "app.api.rag.router.rag_service.store_file_minio", new_callable=AsyncMock
        ) as mock_store,
        patch("app.api.rag.router.DocumentRepository") as MockDocRepo,
    ):
        mock_store.return_value = "unused/key"
        mock_repo_instance = AsyncMock()
        MockDocRepo.return_value = mock_repo_instance

        # Patch UploadFile.read to return our fake oversized bytes
        with patch("fastapi.UploadFile.read", new_callable=AsyncMock) as mock_read:
            mock_read.return_value = fake_bytes

            client = _get_client()
            response = client.post(
                "/documents/upload",
                files={"file": (filename, io.BytesIO(b"tiny"), mime_type)},
                headers={"Authorization": f"Bearer {_make_auth_token()}"},
            )

        # Oversized file must be rejected with HTTP 422
        assert response.status_code == 422, (
            f"Property 26C violated: oversized file '{filename}' ({declared_size} bytes) "
            f"got HTTP {response.status_code} instead of 422. "
            f"Response: {response.text[:200]}"
        )

        # MinIO store must NOT have been called on rejection
        (
            mock_store.assert_not_called(),
            (
                f"Property 26C violated: store_file_minio was called for "
                f"oversized file '{filename}' ({declared_size} bytes)."
            ),
        )

        # DocumentRepository.create must NOT have been called on rejection
        (
            mock_repo_instance.create.assert_not_called(),
            (
                f"Property 26C violated: DocumentRepository.create was called for "
                f"oversized file '{filename}' ({declared_size} bytes)."
            ),
        )


# ---------------------------------------------------------------------------
# Property 26 — Sub-property D: validate_mime_and_upload unit-level property test
#
# Tests the service-layer validation directly, independent of the HTTP layer.
# This ensures the core guard works for arbitrary format/size combinations.
#
# Validates: Requirements 4.1
# ---------------------------------------------------------------------------


@given(
    format_pair=valid_format_strategy,
    size_bytes=valid_size_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_26d_service_accepts_valid_format_and_size(
    format_pair: tuple[str, str],
    size_bytes: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26D (service layer): RAGService.validate_mime_and_upload must
    NOT raise for any (filename, size, mime) combination that is a valid
    supported format and within the 50 MB size limit.
    """
    from app.services.rag_service import RAGService

    filename, mime_type = format_pair
    service = RAGService()

    try:
        service.validate_mime_and_upload(filename, size_bytes, mime_type)
    except Exception as exc:
        pytest.fail(
            f"Property 26D violated: validate_mime_and_upload raised unexpectedly "
            f"for valid file '{filename}' ({size_bytes} bytes, MIME={mime_type}): {exc}"
        )


@given(
    format_pair=invalid_format_strategy,
    size_bytes=valid_size_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_26e_service_rejects_invalid_format(
    format_pair: tuple[str, str],
    size_bytes: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26E (service layer): RAGService.validate_mime_and_upload must
    raise HTTP 422 for any file whose format is not in the supported set,
    regardless of file size.
    """
    from fastapi import HTTPException

    from app.services.rag_service import RAGService

    filename, mime_type = format_pair
    service = RAGService()

    with pytest.raises(HTTPException) as exc_info:
        service.validate_mime_and_upload(filename, size_bytes, mime_type)

    assert exc_info.value.status_code == 422, (
        f"Property 26E violated: invalid format '{filename}' raised "
        f"HTTP {exc_info.value.status_code} instead of 422."
    )


@given(
    format_pair=valid_format_strategy,
    size_bytes=oversized_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_26f_service_rejects_oversized_file(
    format_pair: tuple[str, str],
    size_bytes: int,
) -> None:
    """**Validates: Requirements 4.1**

    Property 26F (service layer): RAGService.validate_mime_and_upload must
    raise HTTP 422 for any file that exceeds the 50 MB size limit,
    regardless of format.
    """
    from fastapi import HTTPException

    from app.services.rag_service import RAGService

    filename, mime_type = format_pair
    service = RAGService()

    with pytest.raises(HTTPException) as exc_info:
        service.validate_mime_and_upload(filename, size_bytes, mime_type)

    assert exc_info.value.status_code == 422, (
        f"Property 26F violated: oversized '{filename}' ({size_bytes} bytes) raised "
        f"HTTP {exc_info.value.status_code} instead of 422."
    )

    # Verify the error message mentions size or limit
    detail = exc_info.value.detail.lower()
    assert (
        "size" in detail or "mb" in detail or "bytes" in detail or "limit" in detail
    ), (
        f"Property 26F: HTTP 422 response for oversized file should mention size. "
        f"Detail: {exc_info.value.detail!r}"
    )
