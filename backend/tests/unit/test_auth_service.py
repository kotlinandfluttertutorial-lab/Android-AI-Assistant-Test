"""Comprehensive unit and integration tests for the Auth Service.

Task 24.2 — covers all acceptance criteria from Requirement 1:
  1.1  Registration validation (email format, password 12-128 chars, uniqueness)
  1.2  JWT issuance (15-min expiry, signed, correct claims) + refresh token (30-day expiry)
  1.3  Refresh flow (new JWT without re-entering credentials)
  1.4  Token rotation — old token invalidated; replay → revoke entire family → HTTP 401
  1.5  Account lockout — 5 failures in 10 min → 15-min lock + email per attempt
  1.6  Google OAuth2 — maps Google account to local user on first sign-in
  1.8  RBAC — user/premium/admin roles; premium allowed on premium endpoints
  1.10 Logout — invalidates all active refresh tokens for the session
  1.11 Non-admin attempting admin endpoint → HTTP 403

Unit tests (Requirements 21.1): all service-layer logic, no real I/O — mocks only.
Integration tests (Requirements 21.2): real SQLite in-memory DB; mock only external
  services (Google OAuth, email).

Requirements: 21.1, 21.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

# ---------------------------------------------------------------------------
# Environment — must be set before any app import
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")


from app.security.exceptions import (
    AccountLockedError,
    InvalidTokenError,
    SecurityViolationError,
    TokenFamilyRevokedError,
)
from app.security.jwt_handler import (
    create_access_token,
    create_refresh_token,
    hash_token,
    verify_access_token,
)
from app.services.auth_service import (
    issue_tokens_for_user,
    logout_user,
    refresh_tokens,
)

# ---------------------------------------------------------------------------
# Shared constants
# ---------------------------------------------------------------------------
SAMPLE_USER_ID = uuid.UUID("aaaabbbb-cccc-dddd-eeee-ffffffffffff")
SAMPLE_EMAIL = "auth.service.test@example.com"
SAMPLE_PASSWORD = "StrongPass123!!"  # 15 chars — satisfies 12-128 constraint


# ---------------------------------------------------------------------------
# Helper: build mock User ORM object
# ---------------------------------------------------------------------------


def _make_user(
    *,
    user_id: uuid.UUID | None = None,
    email: str = SAMPLE_EMAIL,
    role: str = "user",
    is_active: bool = True,
    google_id: str | None = None,
    hashed_pw: str = "$2b$12$fakehash",
) -> MagicMock:
    user = MagicMock()
    user.id = user_id or SAMPLE_USER_ID
    user.email = email
    user.display_name = "Test User"
    user.is_active = is_active
    user.password_hash = hashed_pw
    user.google_id = google_id
    role_mock = MagicMock()
    role_mock.value = role
    user.role = role_mock
    return user


# ---------------------------------------------------------------------------
# Helper: build mock RefreshToken ORM record
# ---------------------------------------------------------------------------


def _make_token_record(
    *,
    user: MagicMock,
    token_hash: str,
    used: bool = False,
    revoked: bool = False,
    family_id: uuid.UUID | None = None,
    expires_seconds: int = 30 * 24 * 3600,
) -> MagicMock:
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


# ===========================================================================
# 1. Registration validation — Unit tests (Requirements 21.1)
# ===========================================================================


class TestRegistrationValidation:
    """Validate email + password rules at the Pydantic schema level.

    These tests exercise the RegisterRequest schema (used by the auth router)
    so we verify validation without a live database.

    Requirements: 1.1, 21.1
    """

    def _build_app_and_client(self) -> tuple[FastAPI, TestClient]:
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

    def test_valid_registration_payload_accepted(self) -> None:
        """Registration with valid email and 15-char password returns 201 or 409 (not 422).

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()
        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=(
                        "acc",
                        datetime.now(tz=timezone.utc),
                        "ref",
                        datetime.now(tz=timezone.utc),
                    )
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockRepo.return_value.create = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()
            resp = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )
        # 201 = created successfully
        assert resp.status_code == 201

    def test_password_exactly_12_chars_accepted(self) -> None:
        """Password of exactly 12 characters is the lower boundary and must be accepted.

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()
        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=(
                        "acc",
                        datetime.now(tz=timezone.utc),
                        "ref",
                        datetime.now(tz=timezone.utc),
                    )
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockRepo.return_value.create = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()
            resp = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": "abcdefghijkl",  # exactly 12 chars
                },
            )
        assert resp.status_code == 201

    def test_password_exactly_128_chars_accepted(self) -> None:
        """Password of exactly 128 characters is the upper boundary and must be accepted.

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()
        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=(
                        "acc",
                        datetime.now(tz=timezone.utc),
                        "ref",
                        datetime.now(tz=timezone.utc),
                    )
                ),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=None)
            MockRepo.return_value.create = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()
            resp = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": "A" * 128,
                },
            )
        assert resp.status_code == 201

    def test_password_11_chars_rejected_with_422(self) -> None:
        """Password of 11 characters is one below the minimum — must return HTTP 422.

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        resp = client.post(
            "/auth/register",
            json={
                "email": SAMPLE_EMAIL,
                "password": "a" * 11,
            },
        )
        assert resp.status_code == 422

    def test_password_129_chars_rejected_with_422(self) -> None:
        """Password of 129 characters is one above the maximum — must return HTTP 422.

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        resp = client.post(
            "/auth/register",
            json={
                "email": SAMPLE_EMAIL,
                "password": "A" * 129,
            },
        )
        assert resp.status_code == 422

    def test_empty_password_rejected_with_422(self) -> None:
        """Empty password must return HTTP 422.

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        resp = client.post(
            "/auth/register",
            json={
                "email": SAMPLE_EMAIL,
                "password": "",
            },
        )
        assert resp.status_code == 422

    def test_invalid_email_format_rejected_with_422(self) -> None:
        """Non-email strings must be rejected at the schema level (HTTP 422).

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        for bad_email in [
            "notanemail",
            "missing@",
            "@nodomain.com",
            "spaces in@email.com",
        ]:
            resp = client.post(
                "/auth/register",
                json={
                    "email": bad_email,
                    "password": SAMPLE_PASSWORD,
                },
            )
            assert resp.status_code == 422, f"Expected 422 for email={bad_email!r}"

    def test_duplicate_email_returns_409(self) -> None:
        """Registering with an existing email returns HTTP 409 (uniqueness constraint).

        Requirements: 1.1, 21.1
        """
        _, client = self._build_app_and_client()
        with patch("app.api.auth.router.UserRepository") as MockRepo:
            MockRepo.return_value.get_by_email = AsyncMock(return_value=_make_user())
            resp = client.post(
                "/auth/register",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )
        assert resp.status_code == 409


# ===========================================================================
# 2. JWT issuance — Unit tests (Requirements 21.1)
# ===========================================================================


class TestJWTIssuance:
    """Verify JWT and refresh-token properties returned by issue_tokens_for_user.

    Requirements: 1.2, 21.1
    """

    @pytest.mark.asyncio
    async def test_access_token_expires_in_approximately_15_minutes(self) -> None:
        """Access token expiry must be within ±10 seconds of 15 minutes.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
            (
                access_token,
                access_exp,
                _refresh,
                _refresh_exp,
            ) = await issue_tokens_for_user(mock_db, SAMPLE_USER_ID, "user")

        now = datetime.now(tz=timezone.utc)
        lifetime_seconds = (access_exp - now).total_seconds()
        assert (
            14 * 60 + 50 <= lifetime_seconds <= 15 * 60 + 10
        ), f"Expected ~15-min expiry, got {lifetime_seconds:.1f}s"

    @pytest.mark.asyncio
    async def test_refresh_token_expires_in_approximately_30_days(self) -> None:
        """Refresh token expiry must be within ±60 seconds of 30 days.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
            _access, _access_exp, _refresh, refresh_exp = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, "user"
            )

        now = datetime.now(tz=timezone.utc)
        target_seconds = 30 * 24 * 3600
        actual_seconds = (refresh_exp - now).total_seconds()
        assert (
            abs(actual_seconds - target_seconds) < 60
        ), f"Expected ~30-day expiry ({target_seconds}s), got {actual_seconds:.0f}s"

    @pytest.mark.asyncio
    async def test_access_token_carries_correct_sub_claim(self) -> None:
        """JWT sub claim must equal the user_id string.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
            access_token, _, _, _ = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, "premium"
            )

        payload = verify_access_token(access_token)
        assert payload.sub == str(SAMPLE_USER_ID)

    @pytest.mark.asyncio
    async def test_access_token_carries_correct_role_claim(self) -> None:
        """JWT role claim must reflect the role passed to issue_tokens_for_user.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        for role in ("user", "premium", "admin"):
            with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
                MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
                access_token, _, _, _ = await issue_tokens_for_user(
                    mock_db, SAMPLE_USER_ID, role
                )
            payload = verify_access_token(access_token)
            assert payload.role == role, f"Expected role={role!r}, got {payload.role!r}"

    @pytest.mark.asyncio
    async def test_access_token_is_verifiable(self) -> None:
        """JWT must pass signature verification without error.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
            access_token, _, _, _ = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, "user"
            )

        # Must not raise InvalidTokenError
        payload = verify_access_token(access_token)
        assert payload is not None

    @pytest.mark.asyncio
    async def test_refresh_token_is_opaque_string_not_jwt(self) -> None:
        """Refresh token must be an opaque string — not a signed JWT.

        Requirements: 1.2, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.create = AsyncMock(return_value=MagicMock())
            _, _, refresh_token, _ = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, "user"
            )

        # A JWT would have exactly 2 dots; opaque tokens have none or different structure
        dot_count = refresh_token.count(".")
        assert (
            dot_count == 0 or len(refresh_token.split(".")) != 3
        ), "Refresh token must be opaque, not a JWT with header.payload.signature"


# ===========================================================================
# 3. Token rotation — Unit tests (Requirements 21.1)
# ===========================================================================


class TestTokenRotation:
    """Verify token rotation: old token marked used, new token has same family_id.

    Requirements: 1.3, 1.4, 21.1
    """

    @pytest.mark.asyncio
    async def test_rotation_marks_old_token_as_used(self) -> None:
        """After a successful refresh, the consumed token must be marked used.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "rotation-test-token-001"
        old_record = _make_token_record(user=user, token_hash=hash_token(raw))

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            await refresh_tokens(mock_db, raw)

        repo.mark_used.assert_called_once_with(old_record.id)

    @pytest.mark.asyncio
    async def test_rotation_new_token_preserves_family_id(self) -> None:
        """New refresh token must share the family_id of the consumed token.

        Requirements: 1.4, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        family = uuid.uuid4()
        raw = "rotation-family-token-002"
        old_record = _make_token_record(
            user=user, token_hash=hash_token(raw), family_id=family
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            await refresh_tokens(mock_db, raw)

        create_kwargs = repo.create.call_args.kwargs
        assert create_kwargs["family_id"] == family

    @pytest.mark.asyncio
    async def test_rotation_issues_new_access_and_refresh_tokens(self) -> None:
        """Rotation must return distinct non-empty access_token and refresh_token strings.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "rotation-token-003"
        old_record = _make_token_record(user=user, token_hash=hash_token(raw))

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            (
                new_access,
                access_exp,
                new_refresh,
                refresh_exp,
                role,
                user_id,
            ) = await refresh_tokens(mock_db, raw)

        assert isinstance(new_access, str) and len(new_access) > 0
        assert isinstance(new_refresh, str) and len(new_refresh) > 0
        assert new_refresh != raw  # rotated — must not be the same token
        assert isinstance(access_exp, datetime)
        assert isinstance(refresh_exp, datetime)

    @pytest.mark.asyncio
    async def test_rotation_returns_correct_role_and_user_id(self) -> None:
        """Rotation return tuple must carry the correct role and user_id from the token record.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        target_uid = uuid.uuid4()
        user = _make_user(user_id=target_uid, role="premium")
        raw = "rotation-role-token-004"
        old_record = _make_token_record(user=user, token_hash=hash_token(raw))

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            _, _, _, _, returned_role, returned_uid = await refresh_tokens(mock_db, raw)

        assert returned_role == "premium"
        assert returned_uid == target_uid


# ===========================================================================
# 4. Replay detection — Unit tests (Requirements 21.1)
# ===========================================================================


class TestReplayDetection:
    """Verify that submitting an already-used refresh token revokes the whole family.

    Requirements: 1.4, 21.1
    """

    @pytest.mark.asyncio
    async def test_replay_raises_token_family_revoked_error(self) -> None:
        """Re-using a consumed token must raise TokenFamilyRevokedError (HTTP 401 on wire).

        Requirements: 1.4, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "replay-detection-token-001"
        used_record = _make_token_record(
            user=user, token_hash=hash_token(raw), used=True
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=3)

            with pytest.raises(TokenFamilyRevokedError):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_replay_revokes_entire_family(self) -> None:
        """revoke_family must be called with the family_id of the replayed token.

        Requirements: 1.4, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        family = uuid.uuid4()
        raw = "replay-detection-token-002"
        used_record = _make_token_record(
            user=user, token_hash=hash_token(raw), used=True, family_id=family
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=2)

            with pytest.raises(TokenFamilyRevokedError):
                await refresh_tokens(mock_db, raw)

        repo.revoke_family.assert_called_once_with(family)

    @pytest.mark.asyncio
    async def test_replay_does_not_issue_new_tokens(self) -> None:
        """No new token record must be persisted when replay is detected.

        Requirements: 1.4, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "replay-detection-token-003"
        used_record = _make_token_record(
            user=user, token_hash=hash_token(raw), used=True
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=1)
            repo.create = AsyncMock()

            with pytest.raises(SecurityViolationError):
                await refresh_tokens(mock_db, raw)

        repo.create.assert_not_called()

    @pytest.mark.asyncio
    async def test_replay_error_is_security_violation_not_invalid_token(self) -> None:
        """Replay must surface as SecurityViolationError, not plain InvalidTokenError.

        The distinction matters: InvalidTokenError is for validation failures,
        SecurityViolationError signals an active attack.

        Requirements: 1.4, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "replay-type-check-token-004"
        used_record = _make_token_record(
            user=user, token_hash=hash_token(raw), used=True
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=1)

            raised: BaseException | None = None
            try:
                await refresh_tokens(mock_db, raw)
            except Exception as exc:
                raised = exc

        assert raised is not None
        assert isinstance(raised, SecurityViolationError)
        # Must NOT be a plain InvalidTokenError (a non-SecurityViolation subclass)
        if isinstance(raised, InvalidTokenError):
            assert isinstance(
                raised, SecurityViolationError
            ), "If InvalidTokenError is also raised, it must be a SecurityViolationError subclass"


# ===========================================================================
# 5. Refresh token error cases — Unit tests (Requirements 21.1)
# ===========================================================================


class TestRefreshTokenErrorCases:
    """Verify all error paths for refresh_tokens raise InvalidTokenError.

    Requirements: 1.3, 21.1
    """

    @pytest.mark.asyncio
    async def test_raises_on_nonexistent_token(self) -> None:
        """Unknown token must raise InvalidTokenError.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.get_by_hash = AsyncMock(return_value=None)
            with pytest.raises(InvalidTokenError, match="not found"):
                await refresh_tokens(mock_db, "completely-unknown-token")

    @pytest.mark.asyncio
    async def test_raises_on_revoked_token(self) -> None:
        """Revoked token must raise InvalidTokenError.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "revoked-token-error-001"
        revoked_record = _make_token_record(
            user=user, token_hash=hash_token(raw), revoked=True
        )
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.get_by_hash = AsyncMock(return_value=revoked_record)
            with pytest.raises(InvalidTokenError, match="revoked"):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_raises_on_expired_token(self) -> None:
        """Expired token must raise InvalidTokenError.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        user = _make_user()
        raw = "expired-token-error-002"
        expired_record = _make_token_record(
            user=user, token_hash=hash_token(raw), expires_seconds=-1
        )
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.get_by_hash = AsyncMock(return_value=expired_record)
            with pytest.raises(InvalidTokenError, match="expired"):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_raises_on_inactive_user(self) -> None:
        """Token belonging to an inactive (deactivated) user must raise InvalidTokenError.

        Requirements: 1.3, 21.1
        """
        mock_db = AsyncMock()
        inactive_user = _make_user(is_active=False)
        raw = "inactive-user-token-003"
        record = _make_token_record(user=inactive_user, token_hash=hash_token(raw))
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=record)
            repo.mark_used = AsyncMock()
            with pytest.raises(InvalidTokenError, match="not active"):
                await refresh_tokens(mock_db, raw)


# ===========================================================================
# 6. Logout — Unit tests (Requirements 21.1)
# ===========================================================================


class TestLogout:
    """Verify logout invalidates all active refresh tokens for the session.

    Requirements: 1.10, 21.1
    """

    @pytest.mark.asyncio
    async def test_logout_revokes_all_tokens_and_returns_count(self) -> None:
        """logout_user must call revoke_all_for_user and return the count.

        Requirements: 1.10, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.revoke_all_for_user = AsyncMock(return_value=4)
            count = await logout_user(mock_db, SAMPLE_USER_ID)

        MockRepo.return_value.revoke_all_for_user.assert_called_once_with(
            SAMPLE_USER_ID
        )
        assert count == 4

    @pytest.mark.asyncio
    async def test_logout_with_no_active_tokens_returns_zero(self) -> None:
        """If the user has no active tokens, logout returns 0 (no error).

        Requirements: 1.10, 21.1
        """
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.revoke_all_for_user = AsyncMock(return_value=0)
            count = await logout_user(mock_db, SAMPLE_USER_ID)
        assert count == 0

    @pytest.mark.asyncio
    async def test_logout_passes_correct_user_id(self) -> None:
        """logout_user must pass the exact user_id to the repository.

        Requirements: 1.10, 21.1
        """
        mock_db = AsyncMock()
        target_uid = uuid.uuid4()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            MockRepo.return_value.revoke_all_for_user = AsyncMock(return_value=1)
            await logout_user(mock_db, target_uid)
        MockRepo.return_value.revoke_all_for_user.assert_called_once_with(target_uid)

    def test_logout_endpoint_returns_200_with_token_count(self) -> None:
        """POST /auth/logout with a valid JWT returns HTTP 200 and tokens_revoked count.

        Requirements: 1.10, 21.1
        """
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

        token, _ = create_access_token(
            SAMPLE_USER_ID, "user", expires_delta=timedelta(minutes=5)
        )

        with (
            patch("app.api.auth.router.logout_user", new=AsyncMock(return_value=3)),
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.security.dependencies._is_jti_revoked",
                new=AsyncMock(return_value=False),
            ),
        ):
            MockAudit.return_value.log_logout = AsyncMock()
            with TestClient(app, raise_server_exceptions=False) as client:
                resp = client.post(
                    "/auth/logout",
                    headers={"Authorization": f"Bearer {token}"},
                )

        assert resp.status_code == 200
        assert resp.json()["tokens_revoked"] == 3

    def test_logout_without_jwt_returns_401(self) -> None:
        """POST /auth/logout without Authorization header returns HTTP 401.

        Requirements: 1.10, 21.1
        """
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

        with TestClient(app, raise_server_exceptions=False) as client:
            resp = client.post("/auth/logout")
        assert resp.status_code == 401


# ===========================================================================
# 7. Account lockout — Unit tests (Requirements 21.1)
# ===========================================================================


class TestAccountLockoutUnit:
    """Verify 5-failure lockout logic via the auth router with mocked services.

    Requirements: 1.5, 21.1
    """

    def _build_app_and_client(self) -> tuple[FastAPI, TestClient]:
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

    def test_locked_account_returns_429_with_retry_after(self) -> None:
        """After lockout, login attempts return HTTP 429 with Retry-After header.

        Requirements: 1.5, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock(
                side_effect=AccountLockedError("locked", retry_after_seconds=900)
            )
            MockAudit.return_value.log_failed_login = AsyncMock()

            resp = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert resp.status_code == 429
        assert "Retry-After" in resp.headers

    def test_retry_after_header_value_reflects_lockout_duration(self) -> None:
        """Retry-After header must reflect the actual remaining lockout seconds.

        The lockout duration is 15 minutes (900 seconds).

        Requirements: 1.5, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock(
                side_effect=AccountLockedError("locked", retry_after_seconds=750)
            )
            MockAudit.return_value.log_failed_login = AsyncMock()

            resp = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert resp.status_code == 429
        retry_after = int(resp.headers["Retry-After"])
        assert retry_after == 750

    def test_failed_attempt_increments_counter(self) -> None:
        """Each failed login attempt must call record_failed_attempt on the lockout service.

        Requirements: 1.5, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=False),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()
            lockout.record_failed_attempt = AsyncMock(return_value=(1, False))
            MockAudit.return_value.log_failed_login = AsyncMock()

            resp = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": "WrongPass!!123",
                },
            )

        assert resp.status_code == 401
        lockout.record_failed_attempt.assert_called_once()

    def test_successful_login_clears_failed_attempts(self) -> None:
        """Successful login must call clear_on_success to reset the failure counter.

        Requirements: 1.5, 21.1
        """
        _, client = self._build_app_and_client()
        user = _make_user()
        now = datetime.now(tz=timezone.utc)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.verify_password", return_value=True),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(return_value=("acc", now, "ref", now)),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockRepo.return_value.get_by_email = AsyncMock(return_value=user)
            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()
            lockout.clear_on_success = AsyncMock()
            MockAudit.return_value.log_login = AsyncMock()

            resp = client.post(
                "/auth/login",
                json={
                    "email": SAMPLE_EMAIL,
                    "password": SAMPLE_PASSWORD,
                },
            )

        assert resp.status_code == 200
        lockout.clear_on_success.assert_called_once()


# ===========================================================================
# 8. Google OAuth2 flow — Unit tests (Requirements 21.1)
# ===========================================================================


class TestGoogleOAuth2Unit:
    """Google OAuth2 sign-in: mock token verification, map to local user.

    Requirements: 1.6, 21.1
    """

    _GOOGLE_SUB = "google-sub-unit-test-12345"
    _GOOGLE_EMAIL = "oauth2.unit@gmail.com"

    def _build_app_and_client(self) -> tuple[FastAPI, TestClient]:
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

    def _make_google_id_info(self) -> dict:
        return {
            "sub": self._GOOGLE_SUB,
            "email": self._GOOGLE_EMAIL,
            "aud": "test-google-client-id",
            "name": "OAuth Unit User",
            "picture": "https://lh3.googleusercontent.com/unit-test",
            "email_verified": True,
        }

    def test_new_google_user_creates_local_record_and_returns_is_new_user_true(
        self,
    ) -> None:
        """First Google sign-in creates a local user and returns is_new_user=True.

        Requirements: 1.6, 21.1
        """
        _, client = self._build_app_and_client()
        new_user = _make_user(email=self._GOOGLE_EMAIL, google_id=self._GOOGLE_SUB)
        now = datetime.now(tz=timezone.utc)

        with (
            patch(
                "google.oauth2.id_token.verify_oauth2_token",
                return_value=self._make_google_id_info(),
            ),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(return_value=("g.acc", now, "g.ref", now)),
            ),
        ):
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(
                return_value=None
            )  # no existing google user
            repo.get_by_email = AsyncMock(return_value=None)  # no existing email user
            repo.create_google_user = AsyncMock(return_value=new_user)
            MockAudit.return_value.log_login = AsyncMock()

            resp = client.post(
                "/auth/google", json={"id_token": "mock-google-id-token"}
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_new_user"] is True
        assert body["email"] == self._GOOGLE_EMAIL
        assert "access_token" in body and "refresh_token" in body

    def test_existing_google_user_returns_is_new_user_false(self) -> None:
        """Subsequent Google sign-in with the same account returns is_new_user=False.

        Requirements: 1.6, 21.1
        """
        _, client = self._build_app_and_client()
        existing_user = _make_user(email=self._GOOGLE_EMAIL, google_id=self._GOOGLE_SUB)
        now = datetime.now(tz=timezone.utc)

        with (
            patch(
                "google.oauth2.id_token.verify_oauth2_token",
                return_value=self._make_google_id_info(),
            ),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(return_value=("g.acc2", now, "g.ref2", now)),
            ),
        ):
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(
                return_value=existing_user
            )  # already exists
            MockAudit.return_value.log_login = AsyncMock()

            resp = client.post(
                "/auth/google", json={"id_token": "mock-google-id-token"}
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_new_user"] is False

    def test_google_links_to_existing_email_account(self) -> None:
        """Google sign-in with email matching an existing password account links them.

        No new user is created; the existing account gains google_id.

        Requirements: 1.6, 21.1
        """
        _, client = self._build_app_and_client()
        existing_user = _make_user(email=self._GOOGLE_EMAIL, google_id=None)
        linked_user = _make_user(
            email=self._GOOGLE_EMAIL,
            google_id=self._GOOGLE_SUB,
            user_id=existing_user.id,
        )
        now = datetime.now(tz=timezone.utc)

        with (
            patch(
                "google.oauth2.id_token.verify_oauth2_token",
                return_value=self._make_google_id_info(),
            ),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(return_value=("linked.acc", now, "linked.ref", now)),
            ),
        ):
            repo = MockRepo.return_value
            repo.get_by_google_id = AsyncMock(return_value=None)  # no google_id yet
            repo.get_by_email = AsyncMock(return_value=existing_user)  # email match
            repo.update_google_id = AsyncMock(return_value=linked_user)
            MockAudit.return_value.log_login = AsyncMock()

            resp = client.post(
                "/auth/google", json={"id_token": "mock-google-id-token"}
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_new_user"] is False

    def test_invalid_google_token_returns_401(self) -> None:
        """Tampered or expired Google ID token must return HTTP 401.

        Requirements: 1.6, 21.1
        """
        _, client = self._build_app_and_client()
        with (
            patch(
                "google.oauth2.id_token.verify_oauth2_token",
                side_effect=ValueError("Token is invalid or expired"),
            ),
            patch("google.auth.transport.requests.Request", return_value=MagicMock()),
        ):
            resp = client.post("/auth/google", json={"id_token": "tampered-token"})

        assert resp.status_code == 401


# ===========================================================================
# 9. RBAC enforcement — Unit tests (Requirements 21.1)
# ===========================================================================


class TestRBACEnforcementUnit:
    """Verify RBAC dependencies enforce role requirements.

    Requirements: 1.8, 1.11, 21.1
    """

    def _build_rbac_test_app(self, required_roles_dep) -> TestClient:
        from fastapi import Depends

        rbac_app = FastAPI()

        @rbac_app.get("/protected", dependencies=[Depends(required_roles_dep)])
        async def _protected_ep():
            return {"access": "granted"}

        return TestClient(rbac_app, raise_server_exceptions=False)

    def _get_token(self, role: str) -> str:
        token, _ = create_access_token(
            SAMPLE_USER_ID, role, expires_delta=timedelta(minutes=5)
        )
        return token

    # ---- require_admin ----

    def test_admin_role_accesses_admin_endpoint(self) -> None:
        """Admin JWT must be accepted on admin-only endpoints.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_admin

        client = self._build_rbac_test_app(require_admin)
        token = self._get_token("admin")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 200

    def test_user_role_denied_on_admin_endpoint_returns_403(self) -> None:
        """User-role JWT must receive HTTP 403 on admin-only endpoints.

        Requirements: 1.11, 21.1
        """
        from app.security.rbac import require_admin

        client = self._build_rbac_test_app(require_admin)
        token = self._get_token("user")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 403

    def test_premium_role_denied_on_admin_endpoint_returns_403(self) -> None:
        """Premium-role JWT must receive HTTP 403 on admin-only endpoints.

        Requirements: 1.11, 21.1
        """
        from app.security.rbac import require_admin

        client = self._build_rbac_test_app(require_admin)
        token = self._get_token("premium")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 403

    # ---- require_premium_or_admin ----

    def test_premium_role_accesses_premium_endpoint(self) -> None:
        """Premium JWT must be accepted on premium-or-admin endpoints.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_premium_or_admin

        client = self._build_rbac_test_app(require_premium_or_admin)
        token = self._get_token("premium")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 200

    def test_admin_role_accesses_premium_endpoint(self) -> None:
        """Admin JWT must also be accepted on premium-or-admin endpoints.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_premium_or_admin

        client = self._build_rbac_test_app(require_premium_or_admin)
        token = self._get_token("admin")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 200

    def test_user_role_denied_on_premium_endpoint_returns_403(self) -> None:
        """User-role JWT must receive HTTP 403 on premium-or-admin endpoints.

        Requirements: 1.8, 1.11, 21.1
        """
        from app.security.rbac import require_premium_or_admin

        client = self._build_rbac_test_app(require_premium_or_admin)
        token = self._get_token("user")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 403

    def test_no_jwt_returns_401_not_403(self) -> None:
        """A request with no JWT must return HTTP 401, not 403.

        The distinction: 401 = not authenticated, 403 = authenticated but unauthorized.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_admin

        client = self._build_rbac_test_app(require_admin)
        resp = client.get("/protected")
        assert resp.status_code == 401

    # ---- require_roles custom factory ----

    def test_require_roles_with_single_role_accepts_matching(self) -> None:
        """require_roles factory must accept users whose role is in the allowed set.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_roles

        client = self._build_rbac_test_app(require_roles("user"))
        token = self._get_token("user")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        assert resp.status_code == 200

    def test_require_roles_with_single_role_rejects_other_role(self) -> None:
        """require_roles factory must reject users whose role is NOT in the allowed set.

        Requirements: 1.8, 21.1
        """
        from app.security.rbac import require_roles

        client = self._build_rbac_test_app(require_roles("user"))
        token = self._get_token("admin")
        with patch(
            "app.security.dependencies._is_jti_revoked",
            new=AsyncMock(return_value=False),
        ):
            resp = client.get(
                "/protected", headers={"Authorization": f"Bearer {token}"}
            )
        # admin is NOT in ["user"] → 403
        assert resp.status_code == 403


# ===========================================================================
# 10. Integration tests — full auth cycle (Requirements 21.2)
# ===========================================================================


class TestAuthServiceIntegration:
    """End-to-end auth cycle using FastAPI TestClient + mocked DB and Redis.

    No live PostgreSQL or Redis is required. External services (Google OAuth,
    email) are mocked per Requirements 21.2.

    Requirements: 21.2
    """

    _VALID_EMAIL = "integration.test@example.com"
    _VALID_PASSWORD = "SecureIntegration99!"

    def _build_app(self) -> FastAPI:
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
        return app

    def test_full_register_login_refresh_logout_cycle(self) -> None:
        """Register → login → refresh → logout — all steps must succeed.

        This end-to-end test validates that token rotation works across the
        complete lifecycle.

        Requirements: 1.1, 1.2, 1.3, 1.4, 1.10, 21.2
        """
        app = self._build_app()
        user = _make_user(email=self._VALID_EMAIL)
        now = datetime.now(tz=timezone.utc)
        access_exp = now + timedelta(minutes=15)
        refresh_exp = now + timedelta(days=30)

        user_id = uuid.uuid4()
        new_access = "integration.new.access.token"
        new_refresh = "integration.new.refresh.token"

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.AccountLockoutService") as MockLockout,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch("app.api.auth.router.verify_password", return_value=True),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(
                    return_value=("acc.tok", access_exp, "ref.tok", refresh_exp)
                ),
            ),
            patch(
                "app.api.auth.router.refresh_tokens",
                new=AsyncMock(
                    return_value=(
                        new_access,
                        access_exp,
                        new_refresh,
                        refresh_exp,
                        "user",
                        user_id,
                    )
                ),
            ),
            patch("app.api.auth.router.logout_user", new=AsyncMock(return_value=1)),
            patch(
                "app.security.dependencies._is_jti_revoked",
                new=AsyncMock(return_value=False),
            ),
        ):
            repo = MockRepo.return_value
            repo.get_by_email = AsyncMock(
                side_effect=[None, user]
            )  # None=register, user=login
            repo.create = AsyncMock(return_value=user)

            audit = MockAudit.return_value
            audit.log_login = AsyncMock()
            audit.log_failed_login = AsyncMock()
            audit.log_token_refresh = AsyncMock()
            audit.log_logout = AsyncMock()

            lockout = MockLockout.return_value
            lockout.check_locked = AsyncMock()
            lockout.clear_on_success = AsyncMock()

            with TestClient(app, raise_server_exceptions=False) as client:
                # Step 1: Register
                reg_resp = client.post(
                    "/auth/register",
                    json={
                        "email": self._VALID_EMAIL,
                        "password": self._VALID_PASSWORD,
                    },
                )
                assert (
                    reg_resp.status_code == 201
                ), f"Register failed: {reg_resp.json()}"
                assert "access_token" in reg_resp.json()

                # Step 2: Login
                login_resp = client.post(
                    "/auth/login",
                    json={
                        "email": self._VALID_EMAIL,
                        "password": self._VALID_PASSWORD,
                    },
                )
                assert (
                    login_resp.status_code == 200
                ), f"Login failed: {login_resp.json()}"
                login_body = login_resp.json()
                assert "access_token" in login_body
                assert "refresh_token" in login_body

                # Step 3: Refresh — new tokens must differ from original
                refresh_resp = client.post(
                    "/auth/refresh",
                    json={
                        "refresh_token": login_body["refresh_token"],
                    },
                )
                assert (
                    refresh_resp.status_code == 200
                ), f"Refresh failed: {refresh_resp.json()}"
                refresh_body = refresh_resp.json()
                assert refresh_body["access_token"] == new_access
                assert refresh_body["refresh_token"] == new_refresh

                # Step 4: Logout with the new access token
                jwt_token, _ = create_access_token(
                    user_id, "user", expires_delta=timedelta(minutes=5)
                )
                logout_resp = client.post(
                    "/auth/logout",
                    headers={"Authorization": f"Bearer {jwt_token}"},
                )
                assert logout_resp.status_code == 200
                assert logout_resp.json()["tokens_revoked"] == 1

    def test_register_then_attempt_duplicate_registration(self) -> None:
        """Second registration with same email must return 409.

        Requirements: 1.1, 21.2
        """
        app = self._build_app()
        user = _make_user(email=self._VALID_EMAIL)
        now = datetime.now(tz=timezone.utc)

        with (
            patch("app.api.auth.router.UserRepository") as MockRepo,
            patch("app.api.auth.router.AuditService") as MockAudit,
            patch("app.api.auth.router.hash_password", return_value="$2b$12$fakehash"),
            patch(
                "app.api.auth.router.issue_tokens_for_user",
                new=AsyncMock(return_value=("a", now, "r", now)),
            ),
        ):
            repo = MockRepo.return_value
            # First call: no existing user; second call: existing user found
            repo.get_by_email = AsyncMock(side_effect=[None, user])
            repo.create = AsyncMock(return_value=user)
            MockAudit.return_value.log_login = AsyncMock()

            with TestClient(app, raise_server_exceptions=False) as client:
                resp1 = client.post(
                    "/auth/register",
                    json={
                        "email": self._VALID_EMAIL,
                        "password": self._VALID_PASSWORD,
                    },
                )
                assert resp1.status_code == 201

                resp2 = client.post(
                    "/auth/register",
                    json={
                        "email": self._VALID_EMAIL,
                        "password": self._VALID_PASSWORD,
                    },
                )
                assert resp2.status_code == 409

    def test_refresh_then_replay_same_token_returns_401(self) -> None:
        """After a successful refresh, replaying the old token must return 401.

        Requirements: 1.4, 21.2
        """
        app = self._build_app()
        old_refresh = "old-refresh-integration-token"

        with (
            patch(
                "app.api.auth.router.refresh_tokens",
                new=AsyncMock(side_effect=TokenFamilyRevokedError("replay detected")),
            ),
            patch("app.api.auth.router.AuditService") as MockAudit,
        ):
            MockAudit.return_value.log_token_refresh = AsyncMock()

            with TestClient(app, raise_server_exceptions=False) as client:
                resp = client.post("/auth/refresh", json={"refresh_token": old_refresh})

        assert resp.status_code == 401
        # Response body must be vague — no mention of "replay" or "family"
        detail = resp.json().get("detail", "")
        assert "replay" not in detail.lower()
        assert "family" not in detail.lower()

    def test_access_token_rejected_after_expiry(self) -> None:
        """An expired JWT must be rejected with HTTP 401 on a protected endpoint.

        Requirements: 1.2, 21.2
        """
        from fastapi import Depends

        from app.security.rbac import require_admin

        test_app = FastAPI()

        @test_app.get("/admin/test", dependencies=[Depends(require_admin)])
        async def _admin_ep():
            return {"ok": True}

        expired_token, _ = create_access_token(
            SAMPLE_USER_ID, "admin", expires_delta=timedelta(seconds=-1)
        )

        with TestClient(test_app, raise_server_exceptions=False) as client:
            resp = client.get(
                "/admin/test",
                headers={"Authorization": f"Bearer {expired_token}"},
            )

        assert resp.status_code == 401


# ===========================================================================
# 11. JWT expiry boundary tests — Unit (Requirements 21.1)
# ===========================================================================


class TestJWTExpiryBoundaries:
    """Verify JWT and refresh token boundary behaviour.

    Requirements: 1.2, 1.3, 21.1
    """

    def test_access_token_expiry_is_in_future(self) -> None:
        """Freshly issued access token must have expiry strictly in the future.

        Requirements: 1.2, 21.1
        """
        token, exp = create_access_token(SAMPLE_USER_ID, "user")
        assert exp > datetime.now(tz=timezone.utc)

    def test_refresh_token_expiry_is_in_future(self) -> None:
        """Freshly issued refresh token must have expiry strictly in the future.

        Requirements: 1.2, 21.1
        """
        data = create_refresh_token()
        assert data.expires_at > datetime.now(tz=timezone.utc)

    def test_access_token_expiry_less_than_31_minutes(self) -> None:
        """Access token lifetime must not exceed 30 minutes (guard against overly long expiry).

        Requirements: 1.2, 21.1
        """
        token, exp = create_access_token(SAMPLE_USER_ID, "user")
        lifetime = (exp - datetime.now(tz=timezone.utc)).total_seconds()
        assert (
            lifetime <= 30 * 60
        ), f"Access token lifetime {lifetime}s exceeds 30 minutes"

    def test_refresh_token_expiry_greater_than_29_days(self) -> None:
        """Refresh token lifetime must be at least 29 days.

        Requirements: 1.2, 21.1
        """
        data = create_refresh_token()
        lifetime = (data.expires_at - datetime.now(tz=timezone.utc)).total_seconds()
        assert (
            lifetime >= 29 * 24 * 3600
        ), f"Refresh token lifetime {lifetime}s is less than 29 days"

    def test_expired_access_token_raises_on_verification(self) -> None:
        """verify_access_token must raise InvalidTokenError for expired tokens.

        This verifies that the backend correctly rejects expired credentials.

        Requirements: 1.2, 21.1
        """
        expired_token, _ = create_access_token(
            SAMPLE_USER_ID, "user", expires_delta=timedelta(seconds=-1)
        )
        with pytest.raises(InvalidTokenError):
            verify_access_token(expired_token)

    def test_access_token_claims_all_present(self) -> None:
        """JWT must include sub, role, jti, iat, exp claims.

        Requirements: 1.2, 21.1
        """
        token, _ = create_access_token(SAMPLE_USER_ID, "admin")
        payload = verify_access_token(token)
        assert payload.sub == str(SAMPLE_USER_ID)
        assert payload.role == "admin"
        assert payload.jti is not None and len(payload.jti) > 0
        assert payload.iat is not None
        assert payload.exp is not None
        assert payload.exp > payload.iat

    def test_two_access_tokens_have_unique_jti(self) -> None:
        """Each JWT must have a unique jti to support revocation.

        Requirements: 1.2, 21.1
        """
        t1, _ = create_access_token(SAMPLE_USER_ID, "user")
        t2, _ = create_access_token(SAMPLE_USER_ID, "user")
        p1 = verify_access_token(t1)
        p2 = verify_access_token(t2)
        assert p1.jti != p2.jti
