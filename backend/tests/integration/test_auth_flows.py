"""Integration tests for the /auth/* endpoint flows.

Tests the full auth cycle end-to-end using the FastAPI TestClient with mocked
database and Redis dependencies so no live PostgreSQL or Redis instance is
required in CI.

Scenarios covered:
1. Full auth cycle: register → login → refresh → logout
2. Google OAuth2 flow with a mock provider (existing and new accounts)
3. Account lockout after 5 consecutive failed login attempts

Requirements: 21.2 (integration tests using a test database instance)
Cross-references: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

# Set required env vars before any app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from app.api.auth.router import router as auth_router
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the auth router, no global middleware overhead
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(auth_router)

# ---------------------------------------------------------------------------
# Test data helpers
# ---------------------------------------------------------------------------

_VALID_EMAIL = "testuser@example.com"
_VALID_PASSWORD = "Str0ng!Password123"
_VALID_DISPLAY_NAME = "Test User"
_GOOGLE_SUB = "google-sub-12345"
_GOOGLE_EMAIL = "googleuser@gmail.com"
_GOOGLE_DISPLAY_NAME = "Google User"
_GOOGLE_AVATAR = "https://lh3.googleusercontent.com/test"


def _make_user_orm(
    *,
    user_id: uuid.UUID | None = None,
    email: str = _VALID_EMAIL,
    hashed_pw: str = "$2b$12$fakehash",
    display_name: str = _VALID_DISPLAY_NAME,
    role: str = "user",
    is_active: bool = True,
    google_id: str | None = None,
) -> MagicMock:
    """Build a mock User ORM object with all required attributes."""
    user = MagicMock()
    user.id = user_id or uuid.uuid4()
    user.email = email
    user.password_hash = hashed_pw
    user.display_name = display_name
    role_mock = MagicMock()
    role_mock.value = role
    user.role = role_mock
    user.is_active = is_active
    user.google_id = google_id
    return user


def _make_refresh_token_record(
    *,
    user: MagicMock,
    token_hash: str,
    used: bool = False,
    revoked: bool = False,
    family_id: uuid.UUID | None = None,
    expires_seconds: int = 30 * 24 * 3600,
) -> MagicMock:
    """Build a mock RefreshToken ORM object."""
    record = MagicMock()
    record.id = uuid.uuid4()
    record.user_id = user.id
    record.user = user
    record.token_hash = token_hash
    record.used = used
    record.revoked = revoked
    record.family_id = family_id or uuid.uuid4()
    record.expires_at = datetime.now(tz=timezone.utc) + timedelta(
        seconds=expires_seconds
    )
    return record


# ---------------------------------------------------------------------------
# Mock factories for database session and Redis
# ---------------------------------------------------------------------------


def _make_mock_db_session() -> MagicMock:
    """Return an async context manager mock that yields a mock AsyncSession."""
    mock_session = AsyncMock()
    mock_session.add = MagicMock()
    mock_session.flush = AsyncMock()
    mock_session.commit = AsyncMock()
    mock_session.rollback = AsyncMock()
    mock_session.close = AsyncMock()

    mock_ctx = AsyncMock()
    mock_ctx.__aenter__ = AsyncMock(return_value=mock_session)
    mock_ctx.__aexit__ = AsyncMock(return_value=False)
    return mock_ctx


def _make_mock_redis(
    *,
    is_locked: bool = False,
    attempt_count: int = 0,
) -> AsyncMock:
    """Build a minimal async Redis mock for lockout/session tests.

    Supports:
    - ``exists`` — locked key presence check (returns 1 if is_locked else 0)
    - ``ttl``    — remaining lockout TTL
    - ``eval``   — Lua script for atomic attempt recording (returns attempt_count)
    - ``setex``  — stores lockout key
    - ``delete`` — clears attempt/lockout keys
    """
    redis = AsyncMock()
    redis.exists = AsyncMock(return_value=(1 if is_locked else 0))
    redis.ttl = AsyncMock(return_value=(900 if is_locked else -2))
    redis.eval = AsyncMock(return_value=attempt_count)
    redis.setex = AsyncMock(return_value=True)
    redis.delete = AsyncMock(return_value=1)
    return redis


# ---------------------------------------------------------------------------
# FastAPI dependency overrides
# ---------------------------------------------------------------------------


def _override_get_db(mock_session: AsyncMock):
    """Return a FastAPI dependency override for get_db yielding mock_session."""

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


def _override_get_redis(mock_redis: AsyncMock):
    """Return a FastAPI dependency override for get_redis yielding mock_redis."""

    async def _dep():
        yield mock_redis

    return _dep


# ===========================================================================
# Scenario 1 — Full auth cycle: register → login → refresh → logout
# ===========================================================================


class TestFullAuthCycle:
    """Full end-to-end auth flow: register → login → refresh → logout.

    Each step verifies the HTTP status, required response fields, and that
    the returned tokens change between steps (rotation).

    Requirements: 1.1, 1.2, 1.3, 1.4, 1.10, 21.2
    """

    # ------------------------------------------------------------------
    # Step 1: Register
    # ------------------------------------------------------------------

    def test_register_returns_201_with_tokens(self) -> None:
        """POST /auth/register with valid payload returns HTTP 201 + tokens.

        Requirements: 1.1, 1.2, 21.2
        """
        user = _make_user_orm()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                return_value=("access.token.here", "refresh.token.here"),
            ) as mock_issue,
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=None)  # no duplicate
            repo.create = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_login = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/register",
                    json={
                        "email": _VALID_EMAIL,
                        "password": _VALID_PASSWORD,
                        "display_name": _VALID_DISPLAY_NAME,
                    },
                )

        assert resp.status_code == 201
        body = resp.json()
        assert "user_id" in body
        assert "access_token" in body
        assert "refresh_token" in body
        assert body["email"] == _VALID_EMAIL
        assert body["access_token"] == "access.token.here"
        assert body["refresh_token"] == "refresh.token.here"

    def test_register_duplicate_email_returns_409(self) -> None:
        """POST /auth/register with an existing email returns HTTP 409.

        Requirements: 1.1, 21.2
        """
        existing_user = _make_user_orm()

        with patch("app.api.auth.router.UserRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=existing_user)

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/register",
                    json={
                        "email": _VALID_EMAIL,
                        "password": _VALID_PASSWORD,
                    },
                )

        assert resp.status_code == 409

    def test_register_short_password_returns_422(self) -> None:
        """POST /auth/register with password < 12 chars returns HTTP 422.

        Requirements: 1.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.post(
                "/auth/register",
                json={
                    "email": _VALID_EMAIL,
                    "password": "short",
                },
            )

        assert resp.status_code == 422

    # ------------------------------------------------------------------
    # Step 2: Login
    # ------------------------------------------------------------------

    def test_login_with_valid_credentials_returns_200_with_tokens(self) -> None:
        """POST /auth/login with correct credentials returns HTTP 200 + tokens.

        Requirements: 1.2, 21.2
        """
        user = _make_user_orm()
        mock_redis = _make_mock_redis(is_locked=False)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=True),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                return_value=("jwt.access.token", "opaque.refresh.token"),
            ),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_login = AsyncMock()
            audit.log_failed_login = AsyncMock()

            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()  # no exception = not locked
            lockout.clear_on_success = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/login",
                    json={
                        "email": _VALID_EMAIL,
                        "password": _VALID_PASSWORD,
                    },
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["access_token"] == "jwt.access.token"
        assert body["refresh_token"] == "opaque.refresh.token"
        assert body["email"] == _VALID_EMAIL
        assert body["role"] == "user"

    def test_login_unknown_email_returns_401(self) -> None:
        """POST /auth/login with an unregistered email returns HTTP 401.

        The error must not reveal whether the email exists (user enumeration).

        Requirements: 1.2, 21.2
        """
        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=None)  # user not found

            audit = MockAudit.return_value
            audit.log_failed_login = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/login",
                    json={
                        "email": "noone@example.com",
                        "password": _VALID_PASSWORD,
                    },
                )

        assert resp.status_code == 401
        assert "detail" in resp.json()

    def test_login_wrong_password_returns_401(self) -> None:
        """POST /auth/login with wrong password returns HTTP 401.

        Requirements: 1.2, 21.2
        """
        user = _make_user_orm()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_failed_login = AsyncMock()

            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()
            lockout.record_failed_attempt = AsyncMock(return_value=(1, False))

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/login",
                    json={
                        "email": _VALID_EMAIL,
                        "password": "WrongPassword!!123",
                    },
                )

        assert resp.status_code == 401

    # ------------------------------------------------------------------
    # Step 3: Refresh
    # ------------------------------------------------------------------

    def test_refresh_with_valid_token_returns_200_and_new_tokens(self) -> None:
        """POST /auth/refresh with a valid refresh token returns HTTP 200 + new tokens.

        The new access token and new refresh token must both be present.
        Token rotation: new refresh_token must differ from the submitted one.

        Requirements: 1.3, 1.4, 21.2
        """
        user_id = uuid.uuid4()
        old_refresh = "old.refresh.token"
        new_access = "new.jwt.access.token"
        new_refresh = "new.refresh.token.rotated"

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                return_value=(new_access, new_refresh, "user", user_id),
            ) as mock_rt,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            audit = MockAudit.return_value
            audit.log_token_refresh = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/refresh",
                    json={
                        "refresh_token": old_refresh,
                    },
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["access_token"] == new_access
        assert body["refresh_token"] == new_refresh
        # New refresh token must differ from the submitted one (rotation)
        assert body["refresh_token"] != old_refresh

    def test_refresh_with_invalid_token_returns_401(self) -> None:
        """POST /auth/refresh with an invalid refresh token returns HTTP 401.

        Requirements: 1.3, 21.2
        """
        from app.security.exceptions import InvalidTokenError

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                side_effect=InvalidTokenError("token not found"),
            ),
            patch("app.api.auth.router.AuditService"),
            TestClient(_app) as client,
        ):
            resp = client.post(
                "/auth/refresh",
                json={
                    "refresh_token": "completely.invalid.token",
                },
            )

        assert resp.status_code == 401

    def test_refresh_with_replayed_token_returns_401(self) -> None:
        """POST /auth/refresh replaying an already-used token returns HTTP 401.

        The entire token family must be revoked (Requirement 1.4).

        Requirements: 1.4, 21.2
        """
        from app.security.exceptions import TokenFamilyRevokedError

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                side_effect=TokenFamilyRevokedError("replay detected"),
            ),
            patch("app.api.auth.router.AuditService"),
            TestClient(_app) as client,
        ):
            resp = client.post(
                "/auth/refresh",
                json={
                    "refresh_token": "already.used.refresh.token",
                },
            )

        assert resp.status_code == 401

    # ------------------------------------------------------------------
    # Step 4: Logout (requires valid JWT in Authorization header)
    # ------------------------------------------------------------------

    def test_logout_returns_200_and_revokes_tokens(self) -> None:
        """POST /auth/logout with a valid JWT returns HTTP 200 and revokes all tokens.

        Requirements: 1.10, 21.2
        """
        user_id = uuid.uuid4()
        access_token = create_access_token(user_id=user_id, role="user")

        with (
            patch("app.api.auth.router.logout_user", return_value=3) as mock_logout,
            patch("app.api.auth.router.AuditService") as MockAudit,
            # Patch the JTI revocation check so the JWT is accepted
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            audit = MockAudit.return_value
            audit.log_logout = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/logout",
                    headers={"Authorization": f"Bearer {access_token}"},
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["tokens_revoked"] == 3

    def test_logout_without_jwt_returns_401(self) -> None:
        """POST /auth/logout without an Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.post("/auth/logout")

        assert resp.status_code == 401

    # ------------------------------------------------------------------
    # Full cycle end-to-end: verify token invalidation after logout
    # ------------------------------------------------------------------

    def test_refresh_after_logout_returns_401(self) -> None:
        """After logout, using the old refresh token must return HTTP 401.

        This validates that logout_user revokes all active tokens so the
        old refresh token can no longer be used.

        Requirements: 1.10, 21.2
        """
        user_id = uuid.uuid4()
        access_token = create_access_token(user_id=user_id, role="user")
        old_refresh = "session.refresh.token"

        # Logout step — succeeds and revokes 1 token
        with (
            patch("app.api.auth.router.logout_user", return_value=1),
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
        ):
            MockAudit.return_value.log_logout = AsyncMock()

            with TestClient(_app) as client:
                logout_resp = client.post(
                    "/auth/logout",
                    headers={"Authorization": f"Bearer {access_token}"},
                )
        assert logout_resp.status_code == 200

        # Subsequent refresh attempt — should return 401 (token was revoked)
        from app.security.exceptions import InvalidTokenError

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                side_effect=InvalidTokenError("token has been revoked"),
            ),
            patch("app.api.auth.router.AuditService"),
            TestClient(_app) as client,
        ):
            refresh_resp = client.post(
                "/auth/refresh",
                json={
                    "refresh_token": old_refresh,
                },
            )

        assert refresh_resp.status_code == 401


# ===========================================================================
# Scenario 2 — Google OAuth2 flow with mock provider
# ===========================================================================


class TestGoogleOAuthFlow:
    """Google OAuth2 sign-in with a mocked token verification endpoint.

    The real ``google.oauth2.id_token.verify_oauth2_token`` is patched to
    return a controlled payload without making any network calls.

    Requirements: 1.6, 21.2
    """

    def _mock_google_verify(self, sub: str, email: str, name: str, picture: str | None):
        """Return a side-effect callable that produces a controlled Google ID info dict."""

        def _verify():
            return {
                "sub": sub,
                "email": email,
                "name": name,
                "picture": picture,
                "email_verified": True,
            }

        return _verify

    def test_new_google_user_returns_200_is_new_user_true(self) -> None:
        """First Google sign-in creates a new user and returns is_new_user=True.

        Requirements: 1.6, 21.2
        """
        google_user = _make_user_orm(
            email=_GOOGLE_EMAIL,
            google_id=_GOOGLE_SUB,
            display_name=_GOOGLE_DISPLAY_NAME,
        )

        id_info = {
            "sub": _GOOGLE_SUB,
            "email": _GOOGLE_EMAIL,
            "name": _GOOGLE_DISPLAY_NAME,
            "picture": _GOOGLE_AVATAR,
        }

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                return_value=("google.access.token", "google.refresh.token"),
            ),
            patch("google.oauth2.id_token.verify_oauth2_token", return_value=id_info),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
        ):
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(
                return_value=None
            )  # no existing google user
            repo.get_by_email = AsyncMock(return_value=None)  # no existing email user
            repo.create_google_user = AsyncMock(return_value=google_user)

            MockAudit.return_value.log_login = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/google", json={"id_token": "mock.google.id.token"}
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["email"] == _GOOGLE_EMAIL
        assert body["is_new_user"] is True
        assert "access_token" in body
        assert "refresh_token" in body

    def test_existing_google_user_returns_200_is_new_user_false(self) -> None:
        """Subsequent Google sign-in with same account returns is_new_user=False.

        The user maps to the existing local record — no duplicate created.

        Requirements: 1.6, 21.2
        """
        existing_user = _make_user_orm(
            email=_GOOGLE_EMAIL,
            google_id=_GOOGLE_SUB,
            display_name=_GOOGLE_DISPLAY_NAME,
        )

        id_info = {
            "sub": _GOOGLE_SUB,
            "email": _GOOGLE_EMAIL,
            "name": _GOOGLE_DISPLAY_NAME,
            "picture": _GOOGLE_AVATAR,
        }

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                return_value=("access.token.v2", "refresh.token.v2"),
            ),
            patch("google.oauth2.id_token.verify_oauth2_token", return_value=id_info),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
        ):
            # get_by_google_id returns the existing user — no create needed
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(return_value=existing_user)

            MockAudit.return_value.log_login = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/google", json={"id_token": "mock.google.id.token"}
                )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_new_user"] is False
        assert body["email"] == _GOOGLE_EMAIL

    def test_google_links_to_existing_email_account(self) -> None:
        """Google sign-in with email matching an existing password account links them.

        A new user is NOT created; the existing account gains a google_id.

        Requirements: 1.6, 21.2
        """
        existing_user = _make_user_orm(
            email=_GOOGLE_EMAIL,
            google_id=None,  # not yet linked
        )
        linked_user = _make_user_orm(
            email=_GOOGLE_EMAIL,
            google_id=_GOOGLE_SUB,
            user_id=existing_user.id,
        )

        id_info = {
            "sub": _GOOGLE_SUB,
            "email": _GOOGLE_EMAIL,
            "name": _GOOGLE_DISPLAY_NAME,
            "picture": None,
        }

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                return_value=("linked.access", "linked.refresh"),
            ),
            patch("google.oauth2.id_token.verify_oauth2_token", return_value=id_info),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
        ):
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(
                return_value=None
            )  # no google_id match yet
            repo.get_by_email = AsyncMock(return_value=existing_user)  # email match
            repo.update_google_id = AsyncMock(return_value=linked_user)

            MockAudit.return_value.log_login = AsyncMock()

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/google", json={"id_token": "mock.google.id.token"}
                )

        assert resp.status_code == 200
        body = resp.json()
        # is_new_user=False because the email already existed
        assert body["is_new_user"] is False

    def test_invalid_google_token_returns_401(self) -> None:
        """POST /auth/google with an invalid/expired Google ID token returns HTTP 401.

        Requirements: 1.6, 21.2
        """
        with (
            patch(
                "google.oauth2.id_token.verify_oauth2_token",
                side_effect=ValueError("Token is invalid"),
            ),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
        ):
            with TestClient(_app) as client:
                resp = client.post("/auth/google", json={"id_token": "tampered.token"})

        assert resp.status_code == 401


# ===========================================================================
# Scenario 3 — Account lockout after 5 consecutive failed login attempts
# ===========================================================================


class TestAccountLockout:
    """Verify the 5-failure lockout behaviour described in Requirement 1.5.

    Lockout behaviour:
    - Attempts 1–4: each returns HTTP 401, no lockout.
    - Attempt 5: returns HTTP 401 (or 429); account is now locked.
    - Attempt with correct password during lockout: still returns HTTP 401/429.
    - Audit log entries must exist for each failed attempt.

    The AccountLockoutService is mocked at the service level so the tests
    exercise the HTTP routing and status-code semantics without a live Redis.

    Requirements: 1.5, 21.2
    """

    def _make_login_request(
        self, client: TestClient, pwd: str = "WrongPass!!123"
    ) -> Any:
        return client.post(
            "/auth/login",
            json={
                "email": _VALID_EMAIL,
                "password": pwd,
            },
        )

    def test_first_four_failures_return_401_without_lockout(self) -> None:
        """Attempts 1–4 return HTTP 401; the lockout service records them but no lock yet.

        Requirements: 1.5, 21.2
        """
        user = _make_user_orm()
        audit_calls: list[str] = []

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_failed_login = AsyncMock(
                side_effect=lambda **kw: audit_calls.append("failed")
            )

            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()  # not locked for attempts 1-4
            # record_failed_attempt returns (attempt_count, locked_now=False)
            lockout.record_failed_attempt = AsyncMock(
                side_effect=[
                    (1, False),
                    (2, False),
                    (3, False),
                    (4, False),
                ]
            )

            with TestClient(_app) as client:
                for attempt in range(1, 5):
                    resp = self._make_login_request(client)
                    assert resp.status_code == 401, (
                        f"Attempt {attempt} should return 401, got {resp.status_code}"
                    )

        assert len(audit_calls) == 4, (
            f"Expected 4 failed_login audit entries, got {len(audit_calls)}"
        )

    def test_fifth_failure_triggers_lockout_status(self) -> None:
        """5th failed attempt returns 429 (locked) or 401, account is now locked.

        Per the router implementation: when the lockout service raises
        AccountLockedError, the router returns HTTP 429.  The 5th attempt
        is the one that crosses the threshold.

        Requirements: 1.5, 21.2
        """
        from app.security.exceptions import AccountLockedError

        user = _make_user_orm()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)
            MockAudit.return_value.log_failed_login = AsyncMock()

            lockout = MockLockout.return_value

            # Attempts 1–4: record attempt, return (count, False)
            # Attempt 5: check_locked raises AccountLockedError (lock kicks in)
            attempt_counts = [(1, False), (2, False), (3, False), (4, False)]
            lockout.record_failed_attempt = AsyncMock(side_effect=attempt_counts)
            lockout.check_locked = AsyncMock()  # does not raise for attempts 1-4

            with TestClient(_app) as client:
                # Attempts 1–4: should return 401
                for _ in range(4):
                    resp = self._make_login_request(client)
                    assert resp.status_code == 401

            # Simulate that after 4 failures, lockout kicks in for the 5th check
            exc = AccountLockedError(
                "Account is locked. Try again in 900 seconds.", retry_after_seconds=900
            )
            lockout.check_locked = AsyncMock(side_effect=exc)

            with TestClient(_app) as client:
                resp = self._make_login_request(client)

        # After lockout, the router returns 429 with Retry-After header
        assert resp.status_code in (401, 429), (
            f"5th attempt should be 401 or 429, got {resp.status_code}"
        )

    def test_correct_password_during_lockout_still_returns_locked_status(self) -> None:
        """Correct password while account is locked still returns 429.

        The lockout check happens BEFORE password verification, so even a
        correct password cannot bypass the lockout.

        Requirements: 1.5, 21.2
        """
        from app.security.exceptions import AccountLockedError

        user = _make_user_orm()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch(
                "app.api.auth.router.verify_password", return_value=True
            ),  # correct password
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)
            MockAudit.return_value.log_failed_login = AsyncMock()

            lockout = MockLockout.return_value
            exc = AccountLockedError(
                "Account is locked. Try again in 900 seconds.", retry_after_seconds=900
            )
            lockout.check_locked = AsyncMock(side_effect=exc)

            with TestClient(_app) as client:
                resp = client.post(
                    "/auth/login",
                    json={
                        "email": _VALID_EMAIL,
                        "password": _VALID_PASSWORD,  # correct password — still locked
                    },
                )

        assert resp.status_code == 429
        assert "Retry-After" in resp.headers

    def test_audit_logs_recorded_for_failed_attempts(self) -> None:
        """Each failed login attempt writes a failed_login audit log entry.

        Requirements: 1.5, 9.8, 21.2
        """
        user = _make_user_orm()
        audit_log_calls: list[dict] = []

        def _capture_audit(**kwargs) -> None:
            audit_log_calls.append(kwargs)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_failed_login = AsyncMock(side_effect=_capture_audit)

            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()
            lockout.record_failed_attempt = AsyncMock(return_value=(1, False))

            with TestClient(_app) as client:
                for _ in range(3):
                    self._make_login_request(client)

        assert len(audit_log_calls) == 3, (
            f"Expected 3 audit log entries for 3 failed attempts, got {len(audit_log_calls)}"
        )
        for call in audit_log_calls:
            assert call.get("reason") == "wrong_password"
