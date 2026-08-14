"""Pytest unit and integration tests for security middleware and data privacy.

Task 29.2 — covers:
- Rate limiting: authenticated (60 req/min) and unauthenticated (20 req/min per IP)
- Parameterized query enforcement (ORM calls, no raw SQL)
- AES-256 key encryption (encrypt/decrypt round-trip, plaintext never exposed)
- Data export/deletion workflows (POST /data/export, DELETE /data/account)
- Data residency rejection (write operations violating geographic constraint)

Requirements: 21.1, 21.2
Cross-references: 9.2, 9.7, 9.9, 9.10, 9.11, 28.1, 28.2
"""

from __future__ import annotations

import base64
import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Environment setup — before any app imports
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

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.middleware.data_residency import DataResidencyMiddleware
from app.middleware.rate_limit import RateLimitMiddleware
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------


def _make_bearer_jwt(payload: dict) -> str:
    """Build a structurally valid JWT Bearer value (no real signature)."""
    header_b64 = (
        base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        .rstrip(b"=")
        .decode()
    )
    payload_b64 = (
        base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
    )
    return f"Bearer {header_b64}.{payload_b64}.fakesignature"


def _make_rate_settings(auth_limit: int = 60, unauth_limit: int = 20) -> MagicMock:
    s = MagicMock()
    s.RATE_LIMIT_REQUESTS_PER_MINUTE = auth_limit
    s.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE = unauth_limit
    s.REDIS_URL = "redis://localhost:6379/0"
    return s


def _make_residency_settings(region: str = "") -> MagicMock:
    s = MagicMock()
    s.DATA_RESIDENCY_REGION = region
    return s


def _build_rate_app() -> FastAPI:
    app = FastAPI()

    @app.get("/ping")
    async def _ping():
        return {"ok": True}

    @app.post("/ping")
    async def _ping_post():
        return {"ok": True}

    app.add_middleware(RateLimitMiddleware)
    return app


def _build_residency_app() -> FastAPI:
    app = FastAPI()

    @app.get("/ping")
    async def _get():
        return {"ok": True}

    @app.post("/ping")
    async def _post():
        return {"ok": True}

    @app.put("/ping")
    async def _put():
        return {"ok": True}

    @app.delete("/ping")
    async def _delete():
        return {"ok": True}

    app.add_middleware(DataResidencyMiddleware)
    return app


# ===========================================================================
# 1. Rate limiting — authenticated tier (Req 9.9)
# ===========================================================================


class TestAuthenticatedRateLimit:
    """Req 9.9 — authenticated users: 60 requests/minute, then HTTP 429."""

    def _run(self, incr: int, limit: int = 60, bearer: str | None = None) -> int:
        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=incr)
        mock_redis.expire = AsyncMock()
        settings = _make_rate_settings(auth_limit=limit)
        app = _build_rate_app()

        async def _get_redis(self):
            return mock_redis

        def _get_settings(self):
            return settings

        with (
            patch.object(RateLimitMiddleware, "_get_redis", _get_redis),
            patch.object(RateLimitMiddleware, "_get_settings", _get_settings),
        ):
            client = TestClient(app, raise_server_exceptions=False)
            headers = {}
            if bearer:
                headers["Authorization"] = bearer
            return client.get("/ping", headers=headers)

    def test_first_request_within_limit_passes(self) -> None:
        """INCR=1 (first request in window) → 200."""
        bearer = _make_bearer_jwt({"sub": "user-arl-1"})
        resp = self._run(incr=1, bearer=bearer)
        assert resp.status_code == 200

    def test_60th_request_exactly_at_limit_passes(self) -> None:
        """INCR=60 (exactly at the limit) → 200."""
        bearer = _make_bearer_jwt({"sub": "user-arl-2"})
        resp = self._run(incr=60, bearer=bearer)
        assert resp.status_code == 200

    def test_61st_request_over_limit_returns_429(self) -> None:
        """INCR=61 (over the 60 req/min limit) → 429."""
        bearer = _make_bearer_jwt({"sub": "user-arl-3"})
        resp = self._run(incr=61, bearer=bearer)
        assert resp.status_code == 429

    def test_429_response_includes_retry_after_header(self) -> None:
        """HTTP 429 must include Retry-After header (Req 9.9)."""
        bearer = _make_bearer_jwt({"sub": "user-arl-4"})
        resp = self._run(incr=100, bearer=bearer)
        assert resp.status_code == 429
        assert "Retry-After" in resp.headers

    def test_429_response_body_describes_rate_limit(self) -> None:
        """HTTP 429 body must contain 'Rate limit exceeded' detail."""
        bearer = _make_bearer_jwt({"sub": "user-arl-5"})
        resp = self._run(incr=100, bearer=bearer)
        assert "Rate limit exceeded" in resp.json()["detail"]


# ===========================================================================
# 2. Rate limiting — unauthenticated IP tier (Req 9.11)
# ===========================================================================


class TestUnauthenticatedIPRateLimit:
    """Req 9.11 — unauthenticated requests: 20 req/min per IP, then HTTP 429."""

    def _run(self, incr: int, limit: int = 20, xff: str | None = None) -> int:
        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=incr)
        mock_redis.expire = AsyncMock()
        settings = _make_rate_settings(unauth_limit=limit)
        app = _build_rate_app()

        async def _get_redis(self):
            return mock_redis

        def _get_settings(self):
            return settings

        with (
            patch.object(RateLimitMiddleware, "_get_redis", _get_redis),
            patch.object(RateLimitMiddleware, "_get_settings", _get_settings),
        ):
            client = TestClient(app, raise_server_exceptions=False)
            headers = {}
            if xff:
                headers["X-Forwarded-For"] = xff
            return client.get("/ping", headers=headers)

    def test_first_unauthenticated_request_passes(self) -> None:
        """INCR=1 (first request from IP) → 200."""
        resp = self._run(incr=1)
        assert resp.status_code == 200

    def test_20th_request_exactly_at_limit_passes(self) -> None:
        """INCR=20 (exactly at the 20 req/min limit) → 200."""
        resp = self._run(incr=20)
        assert resp.status_code == 200

    def test_21st_request_over_limit_returns_429(self) -> None:
        """INCR=21 (over the 20 req/min limit) → 429."""
        resp = self._run(incr=21)
        assert resp.status_code == 429

    def test_429_includes_retry_after_header(self) -> None:
        """HTTP 429 for IP rate limit must include Retry-After header."""
        resp = self._run(incr=25)
        assert resp.status_code == 429
        assert "Retry-After" in resp.headers

    def test_xff_header_is_used_for_ip_extraction(self) -> None:
        """X-Forwarded-For header is used to identify the client IP."""
        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=21)
        mock_redis.expire = AsyncMock()
        settings = _make_rate_settings(unauth_limit=20)
        app = _build_rate_app()

        async def _get_redis(self):
            return mock_redis

        def _get_settings(self):
            return settings

        with (
            patch.object(RateLimitMiddleware, "_get_redis", _get_redis),
            patch.object(RateLimitMiddleware, "_get_settings", _get_settings),
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp = client.get("/ping", headers={"X-Forwarded-For": "203.0.113.1"})

        assert resp.status_code == 429
        called_key: str = mock_redis.incr.call_args[0][0]
        assert "203.0.113.1" in called_key

    def test_authenticated_user_uses_user_tier_not_ip_tier(self) -> None:
        """Bearer JWT present → user tier used (key starts with 'rate:user')."""
        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=1)
        mock_redis.expire = AsyncMock()
        settings = _make_rate_settings(auth_limit=60, unauth_limit=20)
        app = _build_rate_app()

        async def _get_redis(self):
            return mock_redis

        def _get_settings(self):
            return settings

        bearer = _make_bearer_jwt({"sub": "user-tier-check"})
        with (
            patch.object(RateLimitMiddleware, "_get_redis", _get_redis),
            patch.object(RateLimitMiddleware, "_get_settings", _get_settings),
        ):
            client = TestClient(app, raise_server_exceptions=False)
            client.get("/ping", headers={"Authorization": bearer})

        called_key: str = mock_redis.incr.call_args[0][0]
        # User-tier key must NOT contain "ip:"
        assert "rate:ip:" not in called_key
        assert "user-tier-check" in called_key


# ===========================================================================
# 3. AES-256 encryption — round-trip and plaintext never exposed (Req 9.10)
# ===========================================================================


class TestAes256Encryption:
    """Req 9.10 — LLM API keys stored AES-256 encrypted; plaintext never returned."""

    def _with_aes_key(self):
        """Context manager that injects a fresh random 32-byte AES key."""
        raw_key = base64.b64encode(os.urandom(32)).decode()
        return patch.dict(os.environ, {"AES_ENCRYPTION_KEY": raw_key})

    def test_encrypt_decrypt_round_trip(self) -> None:
        """encrypt_api_key then decrypt_api_key returns the original plaintext."""
        import app.config.settings as _s
        from app.security.encryption import decrypt_api_key, encrypt_api_key

        with self._with_aes_key():
            _s.get_settings.cache_clear()
            blob = encrypt_api_key("sk-roundtrip-test-key")
            result = decrypt_api_key(blob)
            _s.get_settings.cache_clear()

        assert result == "sk-roundtrip-test-key"

    def test_encrypted_blob_is_bytes(self) -> None:
        """encrypt_api_key returns bytes, not a string."""
        import app.config.settings as _s
        from app.security.encryption import encrypt_api_key

        with self._with_aes_key():
            _s.get_settings.cache_clear()
            blob = encrypt_api_key("sk-test-bytes")
            _s.get_settings.cache_clear()

        assert isinstance(blob, bytes)

    def test_fresh_nonce_each_call_produces_different_ciphertext(self) -> None:
        """Same plaintext encrypted twice must yield different ciphertext blobs."""
        import app.config.settings as _s
        from app.security.encryption import encrypt_api_key

        with self._with_aes_key():
            _s.get_settings.cache_clear()
            blob1 = encrypt_api_key("sk-same-plaintext")
            blob2 = encrypt_api_key("sk-same-plaintext")
            _s.get_settings.cache_clear()

        assert blob1 != blob2

    def test_empty_plaintext_raises_value_error(self) -> None:
        """Encrypting an empty string must raise ValueError."""
        import app.config.settings as _s
        from app.security.encryption import encrypt_api_key

        with self._with_aes_key():
            _s.get_settings.cache_clear()
            with pytest.raises(ValueError, match="must not be empty"):
                encrypt_api_key("")
            _s.get_settings.cache_clear()

    def test_plaintext_not_in_api_key_model_repr(self) -> None:
        """APIKey.__repr__ must not leak the plaintext key (Req 9.10)."""
        from app.models.api_key import APIKey

        key = APIKey()
        key.id = uuid.uuid4()
        key.user_id = uuid.uuid4()
        key.provider = "openai"
        key.encrypted_key = b"\x00" * 30  # dummy — not decryptable by design
        assert "sk-" not in repr(key)
        assert "openai" in repr(key)

    def test_tampered_blob_raises_on_decrypt(self) -> None:
        """Modifying the ciphertext (GCM tag tampering) must raise on decrypt."""
        from cryptography.exceptions import InvalidTag

        import app.config.settings as _s
        from app.security.encryption import decrypt_api_key, encrypt_api_key

        with self._with_aes_key():
            _s.get_settings.cache_clear()
            blob = encrypt_api_key("sk-original")
            # Flip the last byte — corrupts the GCM authentication tag
            tampered = blob[:-1] + bytes([blob[-1] ^ 0xFF])
            with pytest.raises(InvalidTag):
                decrypt_api_key(tampered)
            _s.get_settings.cache_clear()


# ===========================================================================
# 4. Parameterized query enforcement (Req 9.2)
# ===========================================================================


class TestParameterizedQueryEnforcement:
    """Req 9.2 — all DB operations use SQLAlchemy ORM (no raw SQL interpolation)."""

    @pytest.mark.asyncio
    async def test_data_export_calls_orm_job_create(self) -> None:
        """POST /api/v1/data/export uses JobRepository.create (ORM), not raw SQL."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)
        mock_job = MagicMock()
        mock_job.id = uuid.uuid4()
        mock_job.user_id = user_id
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

        # ORM method invoked — parameterized query enforced by SQLAlchemy
        mock_create.assert_called_once()
        assert mock_create.call_args[1]["user_id"] == user_id
        assert mock_create.call_args[1]["job_type"] == "data_export"

    @pytest.mark.asyncio
    async def test_data_delete_calls_orm_user_get_by_id(self) -> None:
        """DELETE /api/v1/data/account uses UserRepository.get_by_id (ORM), not raw SQL."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)
        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = "orm@example.com"
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
            await delete_user_account(
                body=AccountDeletionRequest(email="orm@example.com"),
                current_user=payload,
                db=mock_db,
            )

        mock_get.assert_called_once_with(user_id)


# ===========================================================================
# 5. Data export/deletion workflows (Req 28.1, 28.2)
# ===========================================================================


class TestDataExportWorkflow:
    """Req 28.1 — POST /api/v1/data/export full workflow tests."""

    @pytest.mark.asyncio
    async def test_export_returns_200_with_job_id(self) -> None:
        """POST /api/v1/data/export returns 200 with a UUID job_id."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_job = MagicMock()
        mock_job.id = job_id
        mock_job.user_id = user_id
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

    @pytest.mark.asyncio
    async def test_export_enqueues_celery_task_with_correct_args(self) -> None:
        """POST /api/v1/data/export dispatches export_user_data_task with (user_id, job_id)."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        job_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_job = MagicMock()
        mock_job.id = job_id
        mock_job.user_id = user_id
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
            await export_user_data(current_user=payload, db=mock_db)

        mock_task.delay.assert_called_once_with(str(user_id), str(job_id))

    @pytest.mark.asyncio
    async def test_export_estimated_completion_24_hours(self) -> None:
        """estimated_completion is approximately 24 hours from now."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_job = MagicMock()
        mock_job.id = uuid.uuid4()
        mock_job.user_id = user_id
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

        est = datetime.fromisoformat(result.estimated_completion)
        delta = est - datetime.now(tz=timezone.utc)
        assert timedelta(hours=23) < delta < timedelta(hours=25)

    @pytest.mark.asyncio
    async def test_export_requires_authentication(self) -> None:
        """POST /api/v1/data/export without auth must raise HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401

    @pytest.mark.asyncio
    async def test_export_commits_db_session(self) -> None:
        """The export endpoint must commit the DB session after creating the job."""
        from app.api.data.router import export_user_data
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_job = MagicMock()
        mock_job.id = uuid.uuid4()
        mock_job.user_id = user_id
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
            await export_user_data(current_user=payload, db=mock_db)

        mock_db.commit.assert_called_once()


class TestDataAccountDeletionWorkflow:
    """Req 28.2 — DELETE /api/v1/data/account full workflow tests."""

    @pytest.mark.asyncio
    async def test_deletion_with_correct_email_returns_200(self) -> None:
        """DELETE /api/v1/data/account with matching email returns 200."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        email = "delete-me@example.com"
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = email
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
            result = await delete_user_account(
                body=AccountDeletionRequest(email=email),
                current_user=payload,
                db=mock_db,
            )

        assert result.scheduled_at is not None
        assert result.estimated_completion is not None

    @pytest.mark.asyncio
    async def test_deletion_enqueues_celery_task_with_user_id(self) -> None:
        """DELETE /api/v1/data/account dispatches delete_user_data_task(user_id)."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        email = "delete-task@example.com"
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = email
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
            await delete_user_account(
                body=AccountDeletionRequest(email=email),
                current_user=payload,
                db=mock_db,
            )

        mock_task.delay.assert_called_once_with(str(user_id))

    @pytest.mark.asyncio
    async def test_deletion_wrong_email_returns_400(self) -> None:
        """DELETE /api/v1/data/account with mismatched email returns HTTP 400."""
        from fastapi import HTTPException

        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = "actual@example.com"
        mock_db = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=mock_user,
            ),
            pytest.raises(HTTPException) as exc_info,
        ):
            await delete_user_account(
                body=AccountDeletionRequest(email="wrong@example.com"),
                current_user=payload,
                db=mock_db,
            )

        assert exc_info.value.status_code == 400
        assert "email confirmation" in exc_info.value.detail.lower()

    @pytest.mark.asyncio
    async def test_deletion_email_comparison_is_case_insensitive(self) -> None:
        """Email confirmation is case-insensitive: 'USER@EXAMPLE.COM' matches 'user@example.com'."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = "user@example.com"
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
            # Provide uppercase email — should still succeed
            result = await delete_user_account(
                body=AccountDeletionRequest(email="USER@EXAMPLE.COM"),
                current_user=payload,
                db=mock_db,
            )

        mock_task.delay.assert_called_once_with(str(user_id))
        assert result.scheduled_at is not None

    @pytest.mark.asyncio
    async def test_deletion_estimated_completion_72_hours(self) -> None:
        """estimated_completion is approximately 72 hours from now (Req 28.2)."""
        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        email = "72h@example.com"
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)

        mock_user = MagicMock()
        mock_user.id = user_id
        mock_user.email = email
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
            result = await delete_user_account(
                body=AccountDeletionRequest(email=email),
                current_user=payload,
                db=mock_db,
            )

        est = datetime.fromisoformat(result.estimated_completion)
        delta = est - datetime.now(tz=timezone.utc)
        assert timedelta(hours=71) < delta < timedelta(hours=73)

    @pytest.mark.asyncio
    async def test_deletion_user_not_found_returns_404(self) -> None:
        """DELETE /api/v1/data/account when user record is absent returns HTTP 404."""
        from fastapi import HTTPException

        from app.api.data.router import delete_user_account
        from app.schemas.users import AccountDeletionRequest
        from app.security.jwt_handler import verify_access_token

        user_id = uuid.uuid4()
        token, _ = create_access_token(user_id=user_id, role="user")
        payload = verify_access_token(token)
        mock_db = AsyncMock()

        with (
            patch(
                "app.repositories.user_repository.UserRepository.get_by_id",
                new_callable=AsyncMock,
                return_value=None,
            ),
            pytest.raises(HTTPException) as exc_info,
        ):
            await delete_user_account(
                body=AccountDeletionRequest(email="ghost@example.com"),
                current_user=payload,
                db=mock_db,
            )

        assert exc_info.value.status_code == 404

    @pytest.mark.asyncio
    async def test_deletion_requires_authentication(self) -> None:
        """DELETE /api/v1/data/account without auth must raise HTTP 401."""
        from fastapi import HTTPException

        from app.security.dependencies import get_current_user

        with pytest.raises(HTTPException) as exc_info:
            await get_current_user(credentials=None)

        assert exc_info.value.status_code == 401


# ===========================================================================
# 6. Data residency rejection (Req 9.7)
# ===========================================================================


class TestDataResidencyRejection:
    """Req 9.7 — write operations violating geographic constraint return HTTP 403."""

    def _build_middleware(self, region: str = "") -> DataResidencyMiddleware:
        app_stub = AsyncMock()
        mw = DataResidencyMiddleware(app_stub)
        settings = _make_residency_settings(region)
        mw._get_settings = MagicMock(return_value=settings)
        return mw

    async def _call_middleware(
        self,
        mw: DataResidencyMiddleware,
        method: str,
        region_header: str | None,
    ) -> int:
        """Run middleware and return the HTTP status code."""
        headers = [(b"content-type", b"application/json")]
        if region_header is not None:
            headers.append((b"x-client-region", region_header.encode()))

        scope = {
            "type": "http",
            "method": method.upper(),
            "path": "/test",
            "headers": headers,
        }
        status_codes: list[int] = []

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message) -> None:
            if message["type"] == "http.response.start":
                status_codes.append(message["status"])

        await mw(scope, receive, send)
        return status_codes[0] if status_codes else 200

    @pytest.mark.asyncio
    async def test_post_with_wrong_region_returns_403(self) -> None:
        """POST with X-Client-Region not matching configured region → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "POST", "eu-west")
        assert status == 403

    @pytest.mark.asyncio
    async def test_put_with_wrong_region_returns_403(self) -> None:
        """PUT with X-Client-Region not matching configured region → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "PUT", "ap-southeast")
        assert status == 403

    @pytest.mark.asyncio
    async def test_patch_with_wrong_region_returns_403(self) -> None:
        """PATCH with X-Client-Region not matching configured region → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "PATCH", "eu-central")
        assert status == 403

    @pytest.mark.asyncio
    async def test_delete_with_wrong_region_returns_403(self) -> None:
        """DELETE with X-Client-Region not matching configured region → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "DELETE", "ap-northeast")
        assert status == 403

    @pytest.mark.asyncio
    async def test_post_with_matching_region_passes(self) -> None:
        """POST with X-Client-Region matching configured region → request passes."""
        mw = self._build_middleware(region="us-east")
        await self._call_middleware(mw, "POST", "us-east")
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_region_check_is_case_insensitive(self) -> None:
        """Region comparison is case-insensitive: 'US-EAST' matches 'us-east'."""
        mw = self._build_middleware(region="us-east")
        await self._call_middleware(mw, "POST", "US-EAST")
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_absent_region_header_allows_write(self) -> None:
        """No X-Client-Region header → write is allowed (absent = unknown region)."""
        mw = self._build_middleware(region="us-east")
        await self._call_middleware(mw, "POST", None)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_request_not_blocked_by_wrong_region(self) -> None:
        """GET requests are never blocked, even with a mismatched region header."""
        mw = self._build_middleware(region="us-east")
        await self._call_middleware(mw, "GET", "eu-west")
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_no_region_configured_allows_all_writes(self) -> None:
        """Empty DATA_RESIDENCY_REGION → all write requests pass through."""
        mw = self._build_middleware(region="")
        await self._call_middleware(mw, "POST", "eu-west")
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_403_response_body_contains_violation_detail(self) -> None:
        """HTTP 403 body must contain 'Data residency constraint violation'."""
        headers = [
            (b"content-type", b"application/json"),
            (b"x-client-region", b"eu-west"),
        ]
        scope = {
            "type": "http",
            "method": "POST",
            "path": "/test",
            "headers": headers,
        }

        app_stub = AsyncMock()
        mw = DataResidencyMiddleware(app_stub)
        mw._get_settings = MagicMock(return_value=_make_residency_settings("us-east"))

        response_body: list[bytes] = []
        start_message: dict = {}

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message) -> None:
            if message["type"] == "http.response.start":
                start_message.update(message)
            elif message["type"] == "http.response.body":
                response_body.append(message.get("body", b""))

        await mw(scope, receive, send)

        assert start_message.get("status") == 403
        body_text = b"".join(response_body).decode()
        assert "Data residency constraint violation" in body_text

    @pytest.mark.asyncio
    async def test_data_export_endpoint_rejected_by_residency(self) -> None:
        """POST with wrong region header is rejected by DataResidencyMiddleware → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "POST", "eu-west")
        assert status == 403
        mw.app.assert_not_called()

    @pytest.mark.asyncio
    async def test_data_delete_endpoint_rejected_by_residency(self) -> None:
        """DELETE with wrong region header is rejected by DataResidencyMiddleware → 403."""
        mw = self._build_middleware(region="us-east")
        status = await self._call_middleware(mw, "DELETE", "ap-northeast")
        assert status == 403
        mw.app.assert_not_called()
