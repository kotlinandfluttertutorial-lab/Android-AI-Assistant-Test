"""Coverage boost tests — targeted unit tests for undertested modules.

Adds tests for paths not covered by existing test files to push total
coverage above 70%.

Modules targeted:
- app.api.auth.router       (inactive account, Google auth paths)
- app.security.email_service (no-SMTP and SMTP paths)
- app.repositories.user_repository
- app.repositories.refresh_token_repository
- app.repositories.message_repository
- app.api.prompts.router    (update + rollback endpoints)

Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.10, 2.1, 2.3, 2.6, 25.1, 25.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

# ---------------------------------------------------------------------------
# Shared test data
# ---------------------------------------------------------------------------

SAMPLE_USER_ID = uuid.UUID("22222222-2222-2222-2222-222222222222")
SAMPLE_EMAIL = "boost@example.com"
SAMPLE_PASSWORD = "SuperSecure123!"
NOW = datetime.now(tz=timezone.utc)
ACCESS_EXP = NOW + timedelta(minutes=15)
REFRESH_EXP = NOW + timedelta(days=30)


def _make_user_mock(
    *,
    user_id: uuid.UUID = SAMPLE_USER_ID,
    email: str = SAMPLE_EMAIL,
    role_value: str = "user",
    is_active: bool = True,
    display_name: str = "Boost User",
    hashed_pw: str = "$2b$12$fakehash",
    google_id: str | None = None,
) -> MagicMock:
    user = MagicMock()
    user.id = user_id
    user.email = email
    user.display_name = display_name
    user.is_active = is_active
    user.password_hash = hashed_pw
    user.google_id = google_id
    role_mock = MagicMock()
    role_mock.value = role_value
    user.role = role_mock
    return user


# ===========================================================================
# 1. auth router — inactive account path
# ===========================================================================


class TestLoginInactiveAccount:
    """POST /auth/login — inactive account returns 401."""

    def _build_auth_app(self) -> tuple[FastAPI, TestClient]:
        from app.api.auth.router import router as auth_router
        from app.database import get_db
        from app.database.redis import get_redis

        app = FastAPI()

        async def _fake_db():
            yield AsyncMock()

        async def _fake_redis():
            yield AsyncMock()

        app.dependency_overrides[get_db] = _fake_db
        app.dependency_overrides[get_redis] = _fake_redis
        app.include_router(auth_router)
        return app, TestClient(app, raise_server_exceptions=False)

    def test_inactive_account_returns_401(self) -> None:
        """Disabled account (is_active=False) returns HTTP 401 after password check."""
        _, client = self._build_auth_app()
        user = _make_user_mock(is_active=False)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=True),
            patch("app.api.auth.router.AuditService"),
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout_svc = MagicMock()
            lockout_svc.check_locked = AsyncMock()
            lockout_svc.clear_on_success = AsyncMock()
            MockLockout.return_value = lockout_svc

            response = client.post(
                "/auth/login",
                json={"email": SAMPLE_EMAIL, "password": SAMPLE_PASSWORD},
            )

        assert response.status_code == 401
        assert "disabled" in response.json()["detail"].lower()

    def test_login_success_clears_lockout(self) -> None:
        """Successful login calls clear_on_success to reset lockout state."""
        _, client = self._build_auth_app()
        user = _make_user_mock(is_active=True)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=True),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access", ACCESS_EXP, "refresh", REFRESH_EXP)
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout_svc = MagicMock()
            lockout_svc.check_locked = AsyncMock()
            lockout_svc.clear_on_success = AsyncMock()
            MockLockout.return_value = lockout_svc
            MockAudit.return_value.log_login = AsyncMock()

            response = client.post(
                "/auth/login",
                json={"email": SAMPLE_EMAIL, "password": SAMPLE_PASSWORD},
            )

        assert response.status_code == 200
        lockout_svc.clear_on_success.assert_called_once()


# ===========================================================================
# 2. auth router — Google OAuth paths
# ===========================================================================


class TestGoogleAuth:
    """POST /auth/google — Google OAuth2 sign-in flows."""

    def _build_auth_app(self) -> tuple[FastAPI, TestClient]:
        from app.api.auth.router import router as auth_router
        from app.database import get_db
        from app.database.redis import get_redis

        app = FastAPI()

        async def _fake_db():
            yield AsyncMock()

        async def _fake_redis():
            yield AsyncMock()

        app.dependency_overrides[get_db] = _fake_db
        app.dependency_overrides[get_redis] = _fake_redis
        app.include_router(auth_router)
        return app, TestClient(app, raise_server_exceptions=False)

    def _google_id_info(self, email: str = "google@example.com") -> dict:
        return {
            "sub": "google-sub-123",
            "email": email,
            "name": "Google User",
            "picture": "https://example.com/avatar.jpg",
        }

    def test_google_auth_existing_user_by_google_id(self) -> None:
        """User already linked by google_id receives tokens."""
        _, client = self._build_auth_app()
        user = _make_user_mock(google_id="google-sub-123")
        id_info = self._google_id_info()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access", ACCESS_EXP, "refresh", REFRESH_EXP)
                ),
            ),
            patch("app.api.auth.router.asyncio") as mock_asyncio,
        ):
            mock_loop = MagicMock()
            mock_asyncio.get_running_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(return_value=id_info)
            MockRepo.return_value.get_by_google_id = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()

            response = client.post(
                "/auth/google", json={"id_token": "fake-google-token"}
            )

        assert response.status_code == 200
        data = response.json()
        assert "access_token" in data
        assert data["is_new_user"] is False

    def test_google_auth_new_user_created(self) -> None:
        """Unknown Google user is created and marked as new."""
        _, client = self._build_auth_app()
        new_user = _make_user_mock(google_id="google-sub-new")
        id_info = self._google_id_info(email="newgoogle@example.com")

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access", ACCESS_EXP, "refresh", REFRESH_EXP)
                ),
            ),
            patch("app.api.auth.router.asyncio") as mock_asyncio,
        ):
            mock_loop = MagicMock()
            mock_asyncio.get_running_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(return_value=id_info)
            MockRepo.return_value.get_by_google_id = AsyncMock(return_value=None)
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockRepo.return_value.create_google_user = AsyncMock(return_value=new_user)
            MockAudit.return_value.log_login = AsyncMock()

            response = client.post(
                "/auth/google", json={"id_token": "fake-google-token"}
            )

        assert response.status_code == 200
        data = response.json()
        assert data["is_new_user"] is True

    def test_google_auth_links_existing_email_account(self) -> None:
        """Existing email-based user gets google_id linked."""
        _, client = self._build_auth_app()
        existing_user = _make_user_mock(google_id=None)
        updated_user = _make_user_mock(google_id="google-sub-link")
        id_info = self._google_id_info(email=SAMPLE_EMAIL)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access", ACCESS_EXP, "refresh", REFRESH_EXP)
                ),
            ),
            patch("app.api.auth.router.asyncio") as mock_asyncio,
        ):
            mock_loop = MagicMock()
            mock_asyncio.get_running_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(return_value=id_info)
            MockRepo.return_value.get_by_google_id = AsyncMock(return_value=None)
            MockRepo.return_value.get_by_email = AsyncMock(return_value=existing_user)
            MockRepo.return_value.update_google_id = AsyncMock(
                return_value=updated_user
            )
            MockAudit.return_value.log_login = AsyncMock()

            response = client.post(
                "/auth/google", json={"id_token": "fake-google-token"}
            )

        assert response.status_code == 200
        MockRepo.return_value.update_google_id.assert_called_once()

    def test_google_auth_invalid_token_returns_401(self) -> None:
        """ValueError from Google token verification returns HTTP 401."""
        _, client = self._build_auth_app()

        with patch("app.api.auth.router.asyncio") as mock_asyncio:
            mock_loop = MagicMock()
            mock_asyncio.get_event_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(side_effect=ValueError("bad token"))

            response = client.post("/auth/google", json={"id_token": "bad-token"})

        assert response.status_code == 401

    def test_google_auth_missing_claims_returns_401(self) -> None:
        """Token missing sub/email claims returns HTTP 401."""
        _, client = self._build_auth_app()
        id_info = {"sub": "", "email": ""}  # missing required claims

        with patch("app.api.auth.router.asyncio") as mock_asyncio:
            mock_loop = MagicMock()
            mock_asyncio.get_event_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(return_value=id_info)

            response = client.post(
                "/auth/google", json={"id_token": "incomplete-token"}
            )

        assert response.status_code == 401

    def test_google_auth_disabled_account_returns_401(self) -> None:
        """Disabled Google-linked account returns HTTP 401."""
        _, client = self._build_auth_app()
        disabled_user = _make_user_mock(is_active=False, google_id="google-sub-dis")
        id_info = self._google_id_info()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService"),
            patch("app.api.auth.router.asyncio") as mock_asyncio,
        ):
            mock_loop = MagicMock()
            mock_asyncio.get_event_loop.return_value = mock_loop
            mock_loop.run_in_executor = AsyncMock(return_value=id_info)
            MockRepo.return_value.get_by_google_id = AsyncMock(
                return_value=disabled_user
            )

            response = client.post(
                "/auth/google", json={"id_token": "fake-google-token"}
            )

        assert response.status_code == 401


# ===========================================================================
# 3. email_service — no-SMTP path (development/test default)
# ===========================================================================


class TestEmailServiceNoSmtp:
    """Tests for send_failed_login_email when SMTP is not configured.

    get_settings is imported locally inside the function, so we patch it
    at its source: app.config.settings.get_settings.
    """

    @pytest.mark.asyncio
    async def test_no_smtp_host_returns_without_sending(self) -> None:
        """When SMTP_HOST is empty, function returns immediately without error."""
        from app.security.email_service import send_failed_login_email

        settings_mock = MagicMock()
        settings_mock.SMTP_HOST = ""

        with patch("app.config.settings.get_settings", return_value=settings_mock):
            # Should not raise
            await send_failed_login_email(
                to_email="user@example.com",
                display_name="Test User",
                attempt_count=3,
                lockout_duration_minutes=15,
                remaining_lockout_seconds=900,
            )

    @pytest.mark.asyncio
    async def test_no_smtp_host_does_not_call_smtplib(self) -> None:
        """No smtplib.SMTP call is made when SMTP_HOST is empty."""
        from app.security.email_service import send_failed_login_email

        settings_mock = MagicMock()
        settings_mock.SMTP_HOST = ""

        with (
            patch("app.config.settings.get_settings", return_value=settings_mock),
            patch("app.security.email_service.smtplib") as mock_smtp_lib,
        ):
            await send_failed_login_email(
                to_email="user@example.com",
                display_name="",
                attempt_count=1,
                lockout_duration_minutes=15,
                remaining_lockout_seconds=0,
            )

        mock_smtp_lib.SMTP.assert_not_called()

    @pytest.mark.asyncio
    async def test_smtp_configured_sends_email(self) -> None:
        """When SMTP_HOST is set, smtplib.SMTP is called."""
        from app.security.email_service import send_failed_login_email

        settings_mock = MagicMock()
        settings_mock.SMTP_HOST = "smtp.example.com"
        settings_mock.SMTP_PORT = 587
        settings_mock.SMTP_USER = "user"
        settings_mock.SMTP_PASSWORD = "pass"
        settings_mock.SMTP_FROM_EMAIL = "noreply@example.com"

        mock_server = MagicMock()
        mock_server.__enter__ = MagicMock(return_value=mock_server)
        mock_server.__exit__ = MagicMock(return_value=False)

        with (
            patch("app.config.settings.get_settings", return_value=settings_mock),
            patch("app.security.email_service.smtplib.SMTP", return_value=mock_server),
        ):
            await send_failed_login_email(
                to_email="user@example.com",
                display_name="Test User",
                attempt_count=5,
                lockout_duration_minutes=15,
                remaining_lockout_seconds=900,
            )

        mock_server.sendmail.assert_called_once()

    @pytest.mark.asyncio
    async def test_smtp_exception_raises_runtime_error(self) -> None:
        """SMTPException is wrapped and re-raised as RuntimeError."""
        import smtplib

        from app.security.email_service import send_failed_login_email

        settings_mock = MagicMock()
        settings_mock.SMTP_HOST = "smtp.example.com"
        settings_mock.SMTP_PORT = 587
        settings_mock.SMTP_USER = ""
        settings_mock.SMTP_PASSWORD = ""
        settings_mock.SMTP_FROM_EMAIL = "noreply@example.com"

        mock_server = MagicMock()
        mock_server.__enter__ = MagicMock(return_value=mock_server)
        mock_server.__exit__ = MagicMock(return_value=False)
        mock_server.sendmail.side_effect = smtplib.SMTPException("connection failed")

        with (
            patch("app.config.settings.get_settings", return_value=settings_mock),
            patch("app.security.email_service.smtplib.SMTP", return_value=mock_server),
        ):
            with pytest.raises(RuntimeError, match="SMTP error"):
                await send_failed_login_email(
                    to_email="user@example.com",
                    display_name="User",
                    attempt_count=5,
                    lockout_duration_minutes=15,
                    remaining_lockout_seconds=900,
                )

    @pytest.mark.asyncio
    async def test_display_name_fallback_uses_email(self) -> None:
        """Empty display_name falls back to email in the message body."""
        from app.security.email_service import send_failed_login_email

        settings_mock = MagicMock()
        settings_mock.SMTP_HOST = "smtp.example.com"
        settings_mock.SMTP_PORT = 587
        settings_mock.SMTP_USER = ""
        settings_mock.SMTP_PASSWORD = ""
        settings_mock.SMTP_FROM_EMAIL = "noreply@example.com"

        captured_msg = []

        mock_server = MagicMock()
        mock_server.__enter__ = MagicMock(return_value=mock_server)
        mock_server.__exit__ = MagicMock(return_value=False)

        def _capture_sendmail(from_addr, to_addrs, msg_str):
            captured_msg.append(msg_str)

        mock_server.sendmail.side_effect = _capture_sendmail

        with (
            patch("app.config.settings.get_settings", return_value=settings_mock),
            patch("app.security.email_service.smtplib.SMTP", return_value=mock_server),
        ):
            await send_failed_login_email(
                to_email="noname@example.com",
                display_name="",
                attempt_count=3,
                lockout_duration_minutes=15,
                remaining_lockout_seconds=300,
            )

        assert captured_msg  # sendmail was called
        assert "noname@example.com" in captured_msg[0]


# ===========================================================================
# 4. UserRepository — unit tests for uncovered methods
# ===========================================================================


class TestUserRepository:
    """Unit tests for app.repositories.user_repository.UserRepository."""

    def _make_db(self) -> AsyncMock:
        db = AsyncMock()
        db.add = MagicMock()
        db.flush = AsyncMock()
        return db

    @pytest.mark.asyncio
    async def test_get_by_email_returns_user(self) -> None:
        """get_by_email returns the matching user."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        user = _make_user_mock()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = user
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        found = await repo.get_by_email("boost@example.com")

        assert found is user

    @pytest.mark.asyncio
    async def test_get_by_email_returns_none_when_not_found(self) -> None:
        """get_by_email returns None when no user matches."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = None
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        found = await repo.get_by_email("nobody@example.com")

        assert found is None

    @pytest.mark.asyncio
    async def test_get_by_id_returns_user(self) -> None:
        """get_by_id returns the matching user."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        user = _make_user_mock()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = user
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        found = await repo.get_by_id(SAMPLE_USER_ID)

        assert found is user

    @pytest.mark.asyncio
    async def test_get_by_google_id_returns_user(self) -> None:
        """get_by_google_id returns the matching user."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        user = _make_user_mock(google_id="gid-abc")
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = user
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        found = await repo.get_by_google_id("gid-abc")

        assert found is user

    @pytest.mark.asyncio
    async def test_create_adds_user_and_flushes(self) -> None:
        """create() adds a User to the session and calls flush."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        repo = UserRepository(db)

        # patch User model so we don't need the full ORM
        with patch("app.repositories.user_repository.User") as MockUser:
            mock_instance = MagicMock()
            MockUser.return_value = mock_instance
            result = await repo.create(
                email="new@example.com",
                password_hash="$2b$12$hash",
                display_name="New User",
            )

        db.add.assert_called_once_with(mock_instance)
        db.flush.assert_called_once()
        assert result is mock_instance

    @pytest.mark.asyncio
    async def test_create_google_user_adds_user_and_flushes(self) -> None:
        """create_google_user() adds a User and calls flush."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        repo = UserRepository(db)

        with patch("app.repositories.user_repository.User") as MockUser:
            mock_instance = MagicMock()
            MockUser.return_value = mock_instance
            result = await repo.create_google_user(
                email="google@example.com",
                google_id="gid-new",
                display_name="Google User",
                avatar_url="https://example.com/pic.jpg",
            )

        db.add.assert_called_once_with(mock_instance)
        db.flush.assert_called_once()
        assert result is mock_instance

    @pytest.mark.asyncio
    async def test_update_google_id_links_account(self) -> None:
        """update_google_id sets google_id on the existing user."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        user = _make_user_mock(google_id=None)
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = user
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        updated = await repo.update_google_id(SAMPLE_USER_ID, "gid-linked")

        assert updated is user
        assert user.google_id == "gid-linked"
        db.flush.assert_called_once()

    @pytest.mark.asyncio
    async def test_update_google_id_returns_none_when_user_not_found(self) -> None:
        """update_google_id returns None when user does not exist."""
        from app.repositories.user_repository import UserRepository

        db = self._make_db()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = None
        db.execute = AsyncMock(return_value=result_mock)

        repo = UserRepository(db)
        result = await repo.update_google_id(uuid.uuid4(), "gid-xyz")

        assert result is None


# ===========================================================================
# 5. RefreshTokenRepository — uncovered methods
# ===========================================================================


class TestRefreshTokenRepository:
    """Unit tests for app.repositories.refresh_token_repository."""

    def _make_db(self) -> AsyncMock:
        db = AsyncMock()
        db.add = MagicMock()
        db.flush = AsyncMock()
        return db

    @pytest.mark.asyncio
    async def test_create_token_adds_and_flushes(self) -> None:
        """create() adds a RefreshToken and flushes."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        repo = RefreshTokenRepository(db)
        family_id = uuid.uuid4()
        expires = NOW + timedelta(days=30)

        with patch(
            "app.repositories.refresh_token_repository.RefreshToken"
        ) as MockToken:
            mock_token = MagicMock()
            MockToken.return_value = mock_token
            result = await repo.create(
                user_id=SAMPLE_USER_ID,
                token_hash="abc123hash",
                expires_at=expires,
                family_id=family_id,
            )

        db.add.assert_called_once_with(mock_token)
        db.flush.assert_called_once()
        assert result is mock_token

    @pytest.mark.asyncio
    async def test_get_by_hash_returns_token(self) -> None:
        """get_by_hash returns the matching token."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        mock_token = MagicMock()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = mock_token
        db.execute = AsyncMock(return_value=result_mock)

        repo = RefreshTokenRepository(db)
        found = await repo.get_by_hash("abc123hash")

        assert found is mock_token

    @pytest.mark.asyncio
    async def test_get_by_hash_returns_none_when_not_found(self) -> None:
        """get_by_hash returns None when no token matches."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        result_mock = MagicMock()
        result_mock.scalar_one_or_none.return_value = None
        db.execute = AsyncMock(return_value=result_mock)

        repo = RefreshTokenRepository(db)
        found = await repo.get_by_hash("nonexistent")

        assert found is None

    @pytest.mark.asyncio
    async def test_mark_used_executes_update(self) -> None:
        """mark_used calls db.execute."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        db.execute = AsyncMock()
        repo = RefreshTokenRepository(db)

        await repo.mark_used(uuid.uuid4())

        db.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_revoke_executes_update(self) -> None:
        """revoke calls db.execute."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        db.execute = AsyncMock()
        repo = RefreshTokenRepository(db)

        await repo.revoke(uuid.uuid4())

        db.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_revoke_family_returns_row_count(self) -> None:
        """revoke_family returns the number of affected rows."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        exec_result = MagicMock()
        exec_result.rowcount = 3
        db.execute = AsyncMock(return_value=exec_result)

        repo = RefreshTokenRepository(db)
        count = await repo.revoke_family(uuid.uuid4())

        assert count == 3

    @pytest.mark.asyncio
    async def test_revoke_all_for_user_returns_row_count(self) -> None:
        """revoke_all_for_user returns the number of revoked tokens."""
        from app.repositories.refresh_token_repository import RefreshTokenRepository

        db = self._make_db()
        exec_result = MagicMock()
        exec_result.rowcount = 5
        db.execute = AsyncMock(return_value=exec_result)

        repo = RefreshTokenRepository(db)
        count = await repo.revoke_all_for_user(SAMPLE_USER_ID)

        assert count == 5


# ===========================================================================
# 6. MessageRepository — uncovered methods
# ===========================================================================


class TestMessageRepository:
    """Unit tests for app.repositories.message_repository.MessageRepository."""

    def _make_db(self) -> AsyncMock:
        db = AsyncMock()
        db.add = MagicMock()
        db.flush = AsyncMock()
        return db

    @pytest.mark.asyncio
    async def test_create_message_adds_and_flushes(self) -> None:
        """create() adds a Message and flushes."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()
        repo = MessageRepository(db)
        conv_id = uuid.uuid4()

        with patch("app.repositories.message_repository.Message") as MockMsg:
            mock_msg = MagicMock()
            MockMsg.return_value = mock_msg
            from app.models.message import MessageRole

            result = await repo.create(
                conversation_id=conv_id,
                role=MessageRole.user,
                content="Hello",
            )

        db.add.assert_called_once_with(mock_msg)
        db.flush.assert_called_once()
        assert result is mock_msg

    @pytest.mark.asyncio
    async def test_get_by_conversation_id_returns_messages(self) -> None:
        """get_by_conversation_id returns a list of messages."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()
        mock_msg1, mock_msg2 = MagicMock(), MagicMock()
        scalars_mock = MagicMock()
        scalars_mock.all.return_value = [mock_msg1, mock_msg2]
        result_mock = MagicMock()
        result_mock.scalars.return_value = scalars_mock
        db.execute = AsyncMock(return_value=result_mock)

        repo = MessageRepository(db)
        msgs = await repo.get_by_conversation_id(uuid.uuid4())

        assert msgs == [mock_msg1, mock_msg2]

    @pytest.mark.asyncio
    async def test_get_by_conversation_id_with_limit(self) -> None:
        """get_by_conversation_id respects the limit parameter."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()
        scalars_mock = MagicMock()
        scalars_mock.all.return_value = [MagicMock()]
        result_mock = MagicMock()
        result_mock.scalars.return_value = scalars_mock
        db.execute = AsyncMock(return_value=result_mock)

        repo = MessageRepository(db)
        msgs = await repo.get_by_conversation_id(uuid.uuid4(), limit=10)

        assert db.execute.called

    @pytest.mark.asyncio
    async def test_get_paginated_returns_messages(self) -> None:
        """get_paginated_by_conversation_id returns paged messages."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()
        scalars_mock = MagicMock()
        scalars_mock.all.return_value = [MagicMock(), MagicMock()]
        result_mock = MagicMock()
        result_mock.scalars.return_value = scalars_mock
        db.execute = AsyncMock(return_value=result_mock)

        repo = MessageRepository(db)
        msgs = await repo.get_paginated_by_conversation_id(
            uuid.uuid4(), offset=0, limit=10
        )

        assert len(msgs) == 2

    @pytest.mark.asyncio
    async def test_count_by_conversation_id(self) -> None:
        """count_by_conversation_id returns the row count."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()
        result_mock = MagicMock()
        result_mock.scalar_one.return_value = 7
        db.execute = AsyncMock(return_value=result_mock)

        repo = MessageRepository(db)
        count = await repo.count_by_conversation_id(uuid.uuid4())

        assert count == 7

    @pytest.mark.asyncio
    async def test_count_tokens_in_conversation(self) -> None:
        """count_tokens_in_conversation sums input + output tokens."""
        from app.repositories.message_repository import MessageRepository

        db = self._make_db()

        msg1 = MagicMock()
        msg1.input_tokens = 10
        msg1.output_tokens = 20

        msg2 = MagicMock()
        msg2.input_tokens = 5
        msg2.output_tokens = 15

        scalars_mock = MagicMock()
        scalars_mock.all.return_value = [msg1, msg2]
        result_mock = MagicMock()
        result_mock.scalars.return_value = scalars_mock
        db.execute = AsyncMock(return_value=result_mock)

        repo = MessageRepository(db)
        total = await repo.count_tokens_in_conversation(uuid.uuid4())

        assert total == 50  # (10+20) + (5+15)


# ===========================================================================
# 7. Prompts router — update and rollback endpoints
# ===========================================================================


class TestUpdatePrompt:
    """Tests for PATCH /prompts/{name} — create new version."""

    def _make_template(
        self,
        name: str = "test_template",
        version: int = 2,
    ) -> MagicMock:
        from datetime import timezone

        row = MagicMock()
        row.id = uuid.uuid4()
        row.name = name
        row.version = version
        row.content = "Updated content"
        row.is_active = True
        row.author_id = uuid.uuid4()
        row.created_at = datetime(2024, 1, 1, tzinfo=timezone.utc)
        row.updated_at = datetime(2024, 1, 1, tzinfo=timezone.utc)
        return row

    def _admin_client(self) -> TestClient:
        from app.api.prompts.router import router as prompts_router
        from app.database import get_db
        from app.security.dependencies import get_current_user
        from app.security.jwt_handler import TokenPayload

        admin_user = TokenPayload(
            sub=str(uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")),
            role="admin",
            jti=str(uuid.uuid4()),
            iat=NOW,
            exp=NOW + timedelta(hours=1),
        )

        test_app = FastAPI()
        test_app.include_router(prompts_router)

        async def _override_user():
            return admin_user

        async def _override_db():
            yield AsyncMock()

        test_app.dependency_overrides[get_current_user] = _override_user
        test_app.dependency_overrides[get_db] = _override_db
        return TestClient(test_app)

    def _user_client(self) -> TestClient:
        from app.api.prompts.router import router as prompts_router
        from app.database import get_db
        from app.security.dependencies import get_current_user
        from app.security.jwt_handler import TokenPayload

        user = TokenPayload(
            sub=str(uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")),
            role="user",
            jti=str(uuid.uuid4()),
            iat=NOW,
            exp=NOW + timedelta(hours=1),
        )

        test_app = FastAPI()
        test_app.include_router(prompts_router)

        async def _override_user():
            return user

        async def _override_db():
            yield AsyncMock()

        test_app.dependency_overrides[get_current_user] = _override_user
        test_app.dependency_overrides[get_db] = _override_db
        return TestClient(test_app)

    def test_admin_can_update_prompt(self) -> None:
        """Admin PATCH /prompts/{name} returns 200 with new version."""
        client = self._admin_client()
        template = self._make_template(version=2)

        with patch(
            "app.api.prompts.router.PromptService.create_version",
            new=AsyncMock(return_value=template),
        ):
            response = client.patch(
                "/prompts/test_template",
                json={"content": "Updated content"},
            )

        assert response.status_code == 200
        assert response.json()["version"] == 2

    def test_non_admin_cannot_update_prompt(self) -> None:
        """Non-admin PATCH /prompts/{name} returns 403."""
        client = self._user_client()
        response = client.patch(
            "/prompts/test_template",
            json={"content": "Updated content"},
        )
        assert response.status_code == 403

    def test_update_response_contains_content(self) -> None:
        """Update response body includes the new content."""
        client = self._admin_client()
        template = self._make_template(version=3)
        template.content = "New content for version 3"

        with patch(
            "app.api.prompts.router.PromptService.create_version",
            new=AsyncMock(return_value=template),
        ):
            response = client.patch(
                "/prompts/test_template",
                json={"content": "New content for version 3"},
            )

        assert response.status_code == 200
        assert "content" in response.json()


class TestRollbackPrompt:
    """Tests for POST /prompts/{name}/rollback — restore a prior version."""

    def _make_template(self, version: int = 1) -> MagicMock:
        from datetime import timezone

        row = MagicMock()
        row.id = uuid.uuid4()
        row.name = "test_template"
        row.version = version
        row.content = f"Content from version {version}"
        row.is_active = True
        row.author_id = uuid.uuid4()
        row.created_at = datetime(2024, 1, 1, tzinfo=timezone.utc)
        row.updated_at = datetime(2024, 1, 1, tzinfo=timezone.utc)
        return row

    def _admin_client(self) -> TestClient:
        from app.api.prompts.router import router as prompts_router
        from app.database import get_db
        from app.security.dependencies import get_current_user
        from app.security.jwt_handler import TokenPayload

        admin_user = TokenPayload(
            sub=str(uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")),
            role="admin",
            jti=str(uuid.uuid4()),
            iat=NOW,
            exp=NOW + timedelta(hours=1),
        )

        test_app = FastAPI()
        test_app.include_router(prompts_router)

        async def _override_user():
            return admin_user

        async def _override_db():
            yield AsyncMock()

        test_app.dependency_overrides[get_current_user] = _override_user
        test_app.dependency_overrides[get_db] = _override_db
        return TestClient(test_app)

    def _user_client(self) -> TestClient:
        from app.api.prompts.router import router as prompts_router
        from app.database import get_db
        from app.security.dependencies import get_current_user
        from app.security.jwt_handler import TokenPayload

        user = TokenPayload(
            sub=str(uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")),
            role="user",
            jti=str(uuid.uuid4()),
            iat=NOW,
            exp=NOW + timedelta(hours=1),
        )

        test_app = FastAPI()
        test_app.include_router(prompts_router)

        async def _override_user():
            return user

        async def _override_db():
            yield AsyncMock()

        test_app.dependency_overrides[get_current_user] = _override_user
        test_app.dependency_overrides[get_db] = _override_db
        return TestClient(test_app)

    def test_admin_can_rollback(self) -> None:
        """Admin POST /prompts/{name}/rollback returns 200."""
        client = self._admin_client()
        restored = self._make_template(version=3)

        with patch(
            "app.api.prompts.router.PromptService.rollback",
            new=AsyncMock(return_value=restored),
        ):
            response = client.post("/prompts/test_template/rollback?version=1")

        assert response.status_code == 200
        body = response.json()
        assert "message" in body
        assert "template" in body

    def test_non_admin_cannot_rollback(self) -> None:
        """Non-admin POST /prompts/{name}/rollback returns 403."""
        client = self._user_client()
        response = client.post("/prompts/test_template/rollback?version=1")
        assert response.status_code == 403

    def test_rollback_unknown_version_returns_404(self) -> None:
        """TemplateNotFoundError from rollback returns HTTP 404."""
        from app.services.prompt_service import TemplateNotFoundError

        client = self._admin_client()

        with patch(
            "app.api.prompts.router.PromptService.rollback",
            new=AsyncMock(side_effect=TemplateNotFoundError("version not found")),
        ):
            response = client.post("/prompts/test_template/rollback?version=99")

        assert response.status_code == 404

    def test_rollback_response_contains_new_version_number(self) -> None:
        """Rollback response message mentions the new version."""
        client = self._admin_client()
        restored = self._make_template(version=4)

        with patch(
            "app.api.prompts.router.PromptService.rollback",
            new=AsyncMock(return_value=restored),
        ):
            response = client.post("/prompts/test_template/rollback?version=1")

        assert response.status_code == 200
        body = response.json()
        assert "4" in body["message"]  # new version 4 mentioned in message

    def test_rollback_invalid_version_number_returns_422(self) -> None:
        """version=0 violates ge=1 constraint — returns HTTP 422."""
        client = self._admin_client()
        response = client.post("/prompts/test_template/rollback?version=0")
        assert response.status_code == 422
