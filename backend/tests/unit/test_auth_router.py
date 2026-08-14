"""Unit tests for /auth/* router endpoints.

Covers register, login, refresh, and logout endpoints with mocked
service/repository/lockout layers. No live DB or Redis required.

Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 1.10, 9.3, 9.8
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.auth.router import router as auth_router
from app.database import get_db
from app.database.redis import get_redis

# ---------------------------------------------------------------------------
# Shared test data
# ---------------------------------------------------------------------------
SAMPLE_USER_ID = uuid.UUID("11111111-1111-1111-1111-111111111111")
SAMPLE_EMAIL = "test@example.com"
SAMPLE_PASSWORD = "SuperSecure123!"
SAMPLE_ROLE = "user"

NOW = datetime.now(tz=timezone.utc)
ACCESS_EXP = NOW + timedelta(minutes=15)
REFRESH_EXP = NOW + timedelta(days=30)

# ---------------------------------------------------------------------------
# Mock helpers
# ---------------------------------------------------------------------------


def _make_user_mock(
    *,
    user_id: uuid.UUID = SAMPLE_USER_ID,
    email: str = SAMPLE_EMAIL,
    role_value: str = SAMPLE_ROLE,
    is_active: bool = True,
    display_name: str = "Test User",
    password_hash: str = "$2b$12$fakehash",
) -> MagicMock:
    """Build a mock User ORM object."""
    user = MagicMock()
    user.id = user_id
    user.email = email
    user.display_name = display_name
    user.is_active = is_active
    user.password_hash = password_hash
    role_mock = MagicMock()
    role_mock.value = role_value
    user.role = role_mock
    return user


def _build_test_app() -> tuple[FastAPI, TestClient]:
    """Build a minimal FastAPI app with the auth router mounted."""
    app = FastAPI()

    # Override dependencies with noop stubs
    async def _fake_db():
        yield AsyncMock()

    async def _fake_redis():
        yield AsyncMock()

    app.dependency_overrides[get_db] = _fake_db
    app.dependency_overrides[get_redis] = _fake_redis
    app.include_router(auth_router)
    return app, TestClient(app, raise_server_exceptions=False)


# ---------------------------------------------------------------------------
# POST /auth/register
# ---------------------------------------------------------------------------


class TestRegisterEndpoint:
    """Tests for POST /auth/register — Requirements: 1.1"""

    def test_register_success_returns_201(self) -> None:
        """Valid registration returns HTTP 201 with tokens."""
        _, client = _build_test_app()
        user = _make_user_mock()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access_tok", ACCESS_EXP, "refresh_tok", REFRESH_EXP)
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockRepo.return_value.create = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()

            response = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                    "display_name": "Test User",
                },
            )

        assert response.status_code == 201
        data = response.json()
        assert data["email"] == SAMPLE_EMAIL
        assert "access_token" in data
        assert "refresh_token" in data

    def test_register_duplicate_email_returns_409(self) -> None:
        """Duplicate email returns HTTP 409."""
        _, client = _build_test_app()
        existing_user = _make_user_mock()

        with patch("app.api.auth.router.UserRepository") as MockRepo:
            MockRepo.return_value.get_by_email = AsyncMock(return_value=existing_user)

            response = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert response.status_code == 409

    def test_register_password_too_short_returns_422(self) -> None:
        """Password < 12 chars must return HTTP 422 (Pydantic validation)."""
        _, client = _build_test_app()
        response = client.post(
            "/auth/register",
            json={
                "email": SAMPLE_EMAIL,
                "password": "short",
            },
        )
        assert response.status_code == 422

    def test_register_invalid_email_returns_422(self) -> None:
        """Invalid email format returns HTTP 422."""
        _, client = _build_test_app()
        response = client.post(
            "/auth/register",
            json={
                "email": "not-an-email",
                "password": SAMPLE_PASSWORD,
            },
        )
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# POST /auth/login
# ---------------------------------------------------------------------------


class TestLoginEndpoint:
    """Tests for POST /auth/login — Requirements: 1.2, 1.5"""

    def test_login_success_returns_200_with_tokens(self) -> None:
        """Valid credentials return HTTP 200 with access and refresh tokens."""
        _, client = _build_test_app()
        user = _make_user_mock()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=True),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("access_tok", ACCESS_EXP, "refresh_tok", REFRESH_EXP)
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
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert response.status_code == 200
        data = response.json()
        assert data["role"] == SAMPLE_ROLE
        assert "access_token" in data
        assert "refresh_token" in data

    def test_login_wrong_password_returns_401(self) -> None:
        """Wrong password returns HTTP 401."""
        _, client = _build_test_app()
        user = _make_user_mock()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout_svc = MagicMock()
            lockout_svc.check_locked = AsyncMock()
            lockout_svc.record_failed_attempt = AsyncMock(return_value=(1, False))
            MockLockout.return_value = lockout_svc
            MockAudit.return_value.log_failed_login = AsyncMock()

            response = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": "WrongPassword!!",
                },
            )

        assert response.status_code == 401

    def test_login_unknown_email_returns_401(self) -> None:
        """Unknown email returns generic 401 (no user enumeration)."""
        _, client = _build_test_app()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockAudit.return_value.log_failed_login = AsyncMock()

            response = client.post(
                "/auth/login",
                json={
                    "email": "unknown@example.com",
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert response.status_code == 401

    def test_login_locked_account_returns_429(self) -> None:
        """Locked account returns HTTP 429 with Retry-After header."""
        from app.security.exceptions import AccountLockedError

        _, client = _build_test_app()
        user = _make_user_mock()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout_svc = MagicMock()
            lockout_svc.check_locked = AsyncMock(
                side_effect=AccountLockedError("locked", retry_after_seconds=750)
            )
            MockLockout.return_value = lockout_svc
            MockAudit.return_value.log_failed_login = AsyncMock()

            response = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert response.status_code == 429
        assert "Retry-After" in response.headers


# ---------------------------------------------------------------------------
# POST /auth/refresh
# ---------------------------------------------------------------------------


class TestRefreshEndpoint:
    """Tests for POST /auth/refresh — Requirements: 1.3, 1.4"""

    def test_refresh_success_returns_new_tokens(self) -> None:
        """Valid refresh token returns HTTP 200 with new token pair."""
        _, client = _build_test_app()

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                new=AsyncMock(
                    return_value=(
                        "new_access",
                        ACCESS_EXP,
                        "new_refresh",
                        REFRESH_EXP,
                        SAMPLE_ROLE,
                        SAMPLE_USER_ID,
                    )
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockAudit.return_value.log_token_refresh = AsyncMock()

            response = client.post(
                "/auth/refresh", json={"refresh_token": "valid-refresh-token"}
            )

        assert response.status_code == 200
        data = response.json()
        assert data["access_token"] == "new_access"
        assert data["refresh_token"] == "new_refresh"

    def test_refresh_invalid_token_returns_401(self) -> None:
        """Invalid/expired refresh token returns HTTP 401."""
        from app.security.exceptions import InvalidTokenError

        _, client = _build_test_app()

        with patch(
            "app.api.auth.router.refresh_tokens",
            new=AsyncMock(side_effect=InvalidTokenError("expired")),
        ):
            response = client.post(
                "/auth/refresh", json={"refresh_token": "expired-token"}
            )

        assert response.status_code == 401

    def test_refresh_replay_detection_returns_401(self) -> None:
        """Replay of already-used token returns HTTP 401 (vague message)."""
        from app.security.exceptions import TokenFamilyRevokedError

        _, client = _build_test_app()

        with patch(
            "app.api.auth.router.refresh_tokens",
            new=AsyncMock(side_effect=TokenFamilyRevokedError("replay")),
        ):
            response = client.post(
                "/auth/refresh", json={"refresh_token": "reused-token"}
            )

        assert response.status_code == 401
        # Replay detail must be vague — must not mention "replay" or "family"
        detail = response.json().get("detail", "")
        assert "replay" not in detail.lower()
        assert "family" not in detail.lower()


# ---------------------------------------------------------------------------
# POST /auth/logout
# ---------------------------------------------------------------------------


class TestLogoutEndpoint:
    """Tests for POST /auth/logout — Requirements: 1.10"""

    def _make_valid_jwt(self) -> str:
        """Create a real short-lived JWT for use in logout tests."""
        from app.security.jwt_handler import create_access_token

        token, _ = create_access_token(
            SAMPLE_USER_ID, SAMPLE_ROLE, expires_delta=timedelta(minutes=5)
        )
        return token

    def test_logout_authenticated_user_returns_200(self) -> None:
        """Authenticated user logout returns HTTP 200."""
        _, client = _build_test_app()
        token = self._make_valid_jwt()

        with (
            patch("app.api.auth.router.logout_user", new=AsyncMock(return_value=2)),
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.security.dependencies._is_jti_revoked",
                new=AsyncMock(return_value=False),
            ),
        ):
            MockAudit.return_value.log_logout = AsyncMock()

            response = client.post(
                "/auth/logout",
                headers={"Authorization": f"Bearer {token}"},
            )

        assert response.status_code == 200
        assert response.json()["tokens_revoked"] == 2

    def test_logout_without_token_returns_401(self) -> None:
        """Logout without a JWT returns HTTP 401."""
        _, client = _build_test_app()
        response = client.post("/auth/logout")
        assert response.status_code == 401


# ---------------------------------------------------------------------------
# RBAC enforcement (Requirements: 1.8, 1.11, 9.2)
# ---------------------------------------------------------------------------


class TestRBACEnforcement:
    """Tests that the RBAC dependency returns HTTP 403 for insufficient roles."""

    def test_require_admin_returns_403_for_user_role(self) -> None:
        """A `user`-role JWT must receive HTTP 403 on admin endpoints."""
        from fastapi import Depends
        from fastapi.testclient import TestClient as TC

        from app.security.jwt_handler import create_access_token
        from app.security.rbac import require_admin

        admin_app = FastAPI()

        @admin_app.get("/admin/test", dependencies=[Depends(require_admin)])
        async def _admin_ep():
            return {"ok": True}

        tc = TC(admin_app, raise_server_exceptions=False)
        user_token, _ = create_access_token(
            SAMPLE_USER_ID, "user", expires_delta=timedelta(minutes=5)
        )

        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            response = tc.get(
                "/admin/test", headers={"Authorization": f"Bearer {user_token}"}
            )

        assert response.status_code == 403

    def test_require_admin_allows_admin_role(self) -> None:
        """An `admin`-role JWT must succeed on admin endpoints."""
        from fastapi import Depends
        from fastapi.testclient import TestClient as TC

        from app.security.jwt_handler import create_access_token
        from app.security.rbac import require_admin

        admin_app = FastAPI()

        @admin_app.get("/admin/test", dependencies=[Depends(require_admin)])
        async def _admin_ep():
            return {"ok": True}

        tc = TC(admin_app, raise_server_exceptions=False)
        admin_token, _ = create_access_token(
            SAMPLE_USER_ID, "admin", expires_delta=timedelta(minutes=5)
        )

        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            response = tc.get(
                "/admin/test", headers={"Authorization": f"Bearer {admin_token}"}
            )

        assert response.status_code == 200

    def test_require_admin_returns_403_for_premium_role(self) -> None:
        """A `premium`-role JWT must receive HTTP 403 on admin-only endpoints."""
        from fastapi import Depends
        from fastapi.testclient import TestClient as TC

        from app.security.jwt_handler import create_access_token
        from app.security.rbac import require_admin

        admin_app = FastAPI()

        @admin_app.get("/admin/test", dependencies=[Depends(require_admin)])
        async def _admin_ep():
            return {"ok": True}

        tc = TC(admin_app, raise_server_exceptions=False)
        token, _ = create_access_token(
            SAMPLE_USER_ID, "premium", expires_delta=timedelta(minutes=5)
        )

        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            response = tc.get(
                "/admin/test", headers={"Authorization": f"Bearer {token}"}
            )

        assert response.status_code == 403
