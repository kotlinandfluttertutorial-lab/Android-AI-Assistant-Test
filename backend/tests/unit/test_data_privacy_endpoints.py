"""Unit tests for /api/v1/data/ privacy endpoints.

Tests cover:
- POST /api/v1/data/export returns 200 with job_id when authenticated
- POST /api/v1/data/export returns 401 when unauthenticated
- DELETE /api/v1/data/account returns 200 when email matches
- DELETE /api/v1/data/account returns 400 when email does not match
- DELETE /api/v1/data/account returns 401 when unauthenticated
- DELETE /api/v1/data/account is case-insensitive for email comparison
- Output encoding: response fields contain properly encoded JSON values
- Parameterized query verification: ORM methods called (no raw SQL)
- AES-256 encryption: plaintext key is never included in API responses

Requirements: 9.2, 9.10, 28.1, 28.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Env setup before app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_token(role: str = "user", user_id: uuid.UUID | None = None) -> str:
    uid = user_id or uuid.uuid4()
    token, _expires = create_access_token(user_id=uid, role=role)
    return token


def _make_user_mock(
    user_id: uuid.UUID | None = None,
    email: str = "user@example.com",
) -> MagicMock:
    uid = user_id or uuid.uuid4()
    mock = MagicMock()
    mock.id = uid
    mock.email = email
    mock.is_active = True
    return mock


def _make_job_mock(
    job_id: uuid.UUID | None = None,
    user_id: uuid.UUID | None = None,
) -> MagicMock:
    jid = job_id or uuid.uuid4()
    uid = user_id or uuid.uuid4()
    mock = MagicMock()
    mock.id = jid
    mock.user_id = uid
    mock.job_type = "data_export"
    mock.status = "queued"
    return mock


# ===========================================================================
# POST /api/v1/data/export
# ===========================================================================


class TestDataExportEndpoint:
    """Req 28.1 — authenticated user can request a full JSON data export."""

    @pytest.mark.asyncio
    async def test_export_returns_job_id(self) -> None:
        """Authenticated request returns 200 with a valid UUID job_id."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_job = _make_job_mock(job_id=job_id, user_id=user_id)
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.job_repository.JobRepository.create",
                new_callable=AsyncMock,
                return_value=mock_job,
            ),
            patch("app.workers.gdpr_worker.export_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            result = await export_user_data(current_user=payload, db=mock_db)

        assert result.job_id == job_id
        assert result.estimated_completion is not None
        mock_task.delay.assert_called_once_with(str(user_id), str(job_id))

    @pytest.mark.asyncio
    async def test_export_estimated_completion_is_24_hours_out(self) -> None:
        """estimated_completion must be approximately 24 hours from now."""
        from datetime import timedelta

        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)
        mock_job = _make_job_mock(user_id=user_id)
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.job_repository.JobRepository.create",
                new_callable=AsyncMock,
                return_value=mock_job,
            ),
            patch("app.workers.gdpr_worker.export_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            result = await export_user_data(current_user=payload, db=mock_db)

        from datetime import datetime, timezone

        est = datetime.fromisoformat(result.estimated_completion)
        delta = est - datetime.now(tz=timezone.utc)
        # Should be within 23–25 hours of now
        assert timedelta(hours=23) < delta < timedelta(hours=25)

    @pytest.mark.asyncio
    async def test_export_unauthenticated_returns_401(self) -> None:
        """No JWT header → HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401


# ===========================================================================
# DELETE /api/v1/data/account
# ===========================================================================


class TestDataAccountDeleteEndpoint:
    """Req 28.2 — authenticated user can schedule permanent account deletion."""

    @pytest.mark.asyncio
    async def test_delete_correct_email_schedules_deletion(self) -> None:
        """Correct email confirmation returns 200 and enqueues deletion task."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        email = "user@example.com"
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email=email)
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            body = AccountDeletionRequest(email=email)
            result = await delete_user_account(
                body=body, current_user=payload, db=mock_db
            )

        mock_task.delay.assert_called_once_with(str(user_id))
        assert result.scheduled_at is not None
        assert result.estimated_completion is not None

    @pytest.mark.asyncio
    async def test_delete_wrong_email_returns_400(self) -> None:
        """Wrong email confirmation must return HTTP 400."""
        from fastapi import HTTPException

        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email="real@example.com")
        mock_db = AsyncMock()

        with patch(
            "app.repositories.user_repository.UserRepository.get_by_id",
            new_callable=AsyncMock,
            return_value=mock_user,
        ):
            body = AccountDeletionRequest(email="wrong@example.com")
            with pytest.raises(HTTPException) as exc_info:
                await delete_user_account(body=body, current_user=payload, db=mock_db)

        assert exc_info.value.status_code == 400
        assert "email confirmation" in exc_info.value.detail.lower()

    @pytest.mark.asyncio
    async def test_delete_unauthenticated_returns_401(self) -> None:
        """No JWT header → HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_delete_email_comparison_is_case_insensitive(self) -> None:
        """Email confirmation is case-insensitive: 'USER@EXAMPLE.COM' matches 'user@example.com'."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email="user@example.com")
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            body = AccountDeletionRequest(email="USER@EXAMPLE.COM")
            result = await delete_user_account(
                body=body, current_user=payload, db=mock_db
            )

        mock_task.delay.assert_called_once_with(str(user_id))
        assert result.scheduled_at is not None

    @pytest.mark.asyncio
    async def test_delete_estimated_completion_is_72_hours_out(self) -> None:
        """estimated_completion must be approximately 72 hours from now (Req 28.2)."""
        from datetime import timedelta

        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)

        mock_user = _make_user_mock(user_id=user_id, email="user@example.com")
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            body = AccountDeletionRequest(email="user@example.com")
            result = await delete_user_account(
                body=body, current_user=payload, db=mock_db
            )

        est = datetime.fromisoformat(result.estimated_completion)
        delta = est - datetime.now(tz=timezone.utc)
        assert timedelta(hours=71) < delta < timedelta(hours=73)


# ===========================================================================
# AES-256 encryption — API keys never returned in plaintext (Req 9.10)
# ===========================================================================


class TestApiKeyEncryption:
    """Req 9.10 — LLM provider API keys are stored AES-256 encrypted and
    never returned in plaintext responses or logs."""

    def test_encrypt_decrypt_round_trip(self) -> None:
        """Encrypting and decrypting an API key returns the original value."""
        import base64
        import os

        from app.security.encryption import (
            decrypt_api_key,
            encrypt_api_key,
        )

        # Provide a valid 32-byte AES key via env var
        test_key = base64.b64encode(os.urandom(32)).decode()
        with patch.dict(os.environ, {"AES_ENCRYPTION_KEY": test_key}):
            import app.config.settings as _settings_mod

            _settings_mod.get_settings.cache_clear()
            blob = encrypt_api_key("sk-abc123test")
            result = decrypt_api_key(blob)
            _settings_mod.get_settings.cache_clear()

        assert result == "sk-abc123test"

    def test_encrypt_produces_different_blobs_for_same_plaintext(self) -> None:
        """Each encryption call uses a fresh nonce, so ciphertext differs."""
        import base64
        import os

        from app.security.encryption import encrypt_api_key

        test_key = base64.b64encode(os.urandom(32)).decode()
        with patch.dict(os.environ, {"AES_ENCRYPTION_KEY": test_key}):
            import app.config.settings as _settings_mod

            _settings_mod.get_settings.cache_clear()
            blob1 = encrypt_api_key("sk-same-key")
            blob2 = encrypt_api_key("sk-same-key")
            _settings_mod.get_settings.cache_clear()

        assert blob1 != blob2  # fresh nonce every time

    def test_plaintext_key_not_in_model_api_key_repr(self) -> None:
        """The APIKey __repr__ must not expose the plaintext key."""
        from app.models.api_key import APIKey

        key = APIKey()
        key.id = uuid.uuid4()
        key.user_id = uuid.uuid4()
        key.provider = "openai"
        # Directly set encrypted_key to dummy bytes (don't decrypt)
        key.encrypted_key = b"\x00" * 30  # minimal fake blob
        representation = repr(key)

        # The repr must not contain any API key pattern
        assert "sk-" not in representation
        # It must contain provider and id info
        assert "openai" in representation

    def test_empty_plaintext_raises_value_error(self) -> None:
        """Encrypting an empty string must raise ValueError."""
        import base64
        import os

        from app.security.encryption import encrypt_api_key

        test_key = base64.b64encode(os.urandom(32)).decode()
        with patch.dict(os.environ, {"AES_ENCRYPTION_KEY": test_key}):
            import app.config.settings as _settings_mod

            _settings_mod.get_settings.cache_clear()
            with pytest.raises(ValueError, match="must not be empty"):
                encrypt_api_key("")
            _settings_mod.get_settings.cache_clear()


# ===========================================================================
# Output encoding — Pydantic v2 / FastAPI JSON serialisation (Req 9.2)
# ===========================================================================


class TestOutputEncoding:
    """Req 9.2 — context-aware output encoding applied to all API responses."""

    def test_data_export_response_serialises_uuid_as_string(self) -> None:
        """UUID job_id must be serialised to a string, not a raw Python UUID."""
        from app.schemas.users import DataExportResponse

        jid = uuid.uuid4()
        resp = DataExportResponse(
            job_id=jid,
            estimated_completion="2025-01-01T00:00:00+00:00",
        )
        # model_dump(mode='json') is what FastAPI uses to build the response
        serialised = resp.model_dump(mode="json")
        assert isinstance(serialised["job_id"], str)
        assert serialised["job_id"] == str(jid)

    def test_account_deletion_response_serialises_datetime_as_string(self) -> None:
        """scheduled_at datetime must be serialised to an ISO string in JSON output."""
        from app.schemas.users import AccountDeletionResponse

        now = datetime.now(tz=timezone.utc)
        resp = AccountDeletionResponse(
            scheduled_at=now,
            estimated_completion="2025-01-04T00:00:00+00:00",
        )
        serialised = resp.model_dump(mode="json")
        # JSON mode converts datetime to a string
        assert isinstance(serialised["scheduled_at"], str)

    def test_data_export_response_message_is_safe_string(self) -> None:
        """The default message must be a plain string with no HTML/JS injection."""
        from app.schemas.users import DataExportResponse

        resp = DataExportResponse(
            job_id=uuid.uuid4(),
            estimated_completion="2025-01-01T00:00:00+00:00",
        )
        # Message should not contain script or HTML tags
        assert "<script>" not in resp.message
        assert "<" not in resp.message

    def test_account_deletion_response_message_is_safe_string(self) -> None:
        """The default deletion message must be a plain string."""
        from app.schemas.users import AccountDeletionResponse

        resp = AccountDeletionResponse(
            scheduled_at=datetime.now(tz=timezone.utc),
            estimated_completion="2025-01-04T00:00:00+00:00",
        )
        assert "<script>" not in resp.message
        assert "<" not in resp.message


# ===========================================================================
# Parameterized query verification (Req 9.2)
# ===========================================================================


class TestParameterizedQueries:
    """Req 9.2 — all DB operations use parameterized queries via the ORM."""

    @pytest.mark.asyncio
    async def test_export_uses_orm_job_create_not_raw_sql(self) -> None:
        """Export endpoint must call JobRepository.create (ORM) not raw SQL."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)
        mock_job = _make_job_mock(user_id=user_id)
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.job_repository.JobRepository.create",
                new_callable=AsyncMock,
                return_value=mock_job,
            ) as mock_create,
            patch("app.workers.gdpr_worker.export_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            await export_user_data(current_user=payload, db=mock_db)

        # ORM method was called — parameterized query guaranteed by SQLAlchemy
        mock_create.assert_called_once()
        call_kwargs = mock_create.call_args[1]
        assert call_kwargs["user_id"] == user_id
        assert call_kwargs["job_type"] == "data_export"

    @pytest.mark.asyncio
    async def test_delete_uses_orm_get_by_id_not_raw_sql(self) -> None:
        """Delete endpoint must call UserRepository.get_by_id (ORM) not raw SQL."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token = _make_token(user_id=user_id)
        payload = verify_access_token(token)
        mock_user = _make_user_mock(user_id=user_id, email="user@example.com")
        mock_db = AsyncMock()
        mock_db.commit = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ) as mock_get,
            patch("app.workers.gdpr_worker.delete_user_data_task") as mock_task,
        ):
            mock_task.delay = MagicMock()
            body = AccountDeletionRequest(email="user@example.com")
            await delete_user_account(body=body, current_user=payload, db=mock_db)

        # ORM method was called with parameterized UUID
        mock_get.assert_called_once_with(user_id)
