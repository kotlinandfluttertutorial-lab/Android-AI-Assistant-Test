"""Unit tests for the auth service layer functions.

Tests cover the three core auth service functions as pure functions — no HTTP,
no router, no TestClient. All database I/O is mocked.

  issue_tokens_for_user:
    - Returns (access_token, access_exp, refresh_token, refresh_exp) 4-tuple
    - Persists the refresh token via the repository
    - Access token is a valid JWT signed with the correct algorithm
    - Access token carries the correct user_id and role claims

  refresh_tokens:
    - Happy path: rotates token, marks old as used, issues new JWT + refresh
    - Token not found → InvalidTokenError
    - Token already revoked → InvalidTokenError
    - Token expired → InvalidTokenError
    - Token already used (replay) → revokes entire family → TokenFamilyRevokedError
    - Inactive user → InvalidTokenError
    - Returns 6-tuple with correct types

  logout_user:
    - Calls revoke_all_for_user on the repository
    - Returns the count of revoked tokens

Teaching notes (Phase 3 — FastAPI Backend):
  Service Layer Pattern:
    - Service functions contain ONLY business logic — no SQL, no HTTP.
    - They call repositories for data access and return domain objects.
    - This makes them trivially testable with mocks.
    - The route handler translates their return values into HTTP responses.

  Constructor injection vs direct import:
    - Repositories are created INSIDE service functions (not class attributes)
    - This is a simpler form of DI — the session is the only dependency injected,
      and the repository is constructed from it.
    - For testing, mock the repository class itself so constructor calls return
      a pre-configured mock.

  Token replay detection (RFC 6819 §5.2.2.3):
    If a client submits a refresh token that has already been used (token.used=True),
    that means either:
      a) An attacker stole the old token and is replaying it, OR
      b) A network issue caused the client to retry.
    The safe response in BOTH cases: revoke the entire token family so the
    legitimate user is forced to re-authenticate. This limits the blast radius.

Requirements: 21.1, 1.2, 1.3, 1.4, 1.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")

from app.security.exceptions import (
    InvalidTokenError,
    TokenFamilyRevokedError,
)
from app.services.auth_service import (
    issue_tokens_for_user,
    logout_user,
    refresh_tokens,
)

# ---------------------------------------------------------------------------
# Shared fixtures
# ---------------------------------------------------------------------------

_USER_ID = uuid.UUID("aaaabbbb-cccc-dddd-eeee-ffffffffffff")
_ROLE = "user"
_NOW = datetime(2025, 6, 15, 12, 0, 0, tzinfo=timezone.utc)
_FUTURE = _NOW + timedelta(days=30)


def _make_user(*, role: str = "user", is_active: bool = True) -> MagicMock:
    user = MagicMock()
    user.id = _USER_ID
    user.is_active = is_active
    role_mock = MagicMock()
    role_mock.value = role
    user.role = role_mock
    return user


def _make_token_record(
    *,
    user: MagicMock,
    used: bool = False,
    revoked: bool = False,
    expired: bool = False,
) -> MagicMock:
    record = MagicMock()
    record.id = uuid.uuid4()
    record.user = user
    record.user_id = user.id
    record.family_id = uuid.uuid4()
    record.used = used
    record.revoked = revoked
    record.expires_at = _NOW - timedelta(hours=1) if expired else _NOW + timedelta(days=30)
    return record


# ---------------------------------------------------------------------------
# issue_tokens_for_user
# ---------------------------------------------------------------------------


class TestIssueTokensForUser:
    """issue_tokens_for_user issues JWT + refresh tokens and persists them."""

    @pytest.fixture()
    def mock_repo(self):
        repo = AsyncMock()
        repo.create = AsyncMock()
        return repo

    async def test_returns_four_tuple(self, mock_repo: AsyncMock) -> None:
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            result = await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        assert len(result) == 4
        access_token, access_exp, refresh_token, refresh_exp = result
        assert isinstance(access_token, str)
        assert isinstance(refresh_token, str)
        assert isinstance(access_exp, datetime)
        assert isinstance(refresh_exp, datetime)

    async def test_access_token_is_valid_jwt(self, mock_repo: AsyncMock) -> None:
        """The issued access token must be verifiable with verify_access_token."""
        from app.security.jwt_handler import verify_access_token

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            access_token, _, _, _ = await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        payload = verify_access_token(access_token)
        assert payload.sub == str(_USER_ID)
        assert payload.role == _ROLE

    async def test_access_token_expires_in_future(self, mock_repo: AsyncMock) -> None:
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            _, access_exp, _, _ = await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        assert access_exp > datetime.now(tz=timezone.utc)

    async def test_refresh_token_is_persisted(self, mock_repo: AsyncMock) -> None:
        """The refresh token must be stored in the database."""
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        mock_repo.create.assert_awaited_once()

    async def test_repository_create_called_with_user_id(self, mock_repo: AsyncMock) -> None:
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        create_kwargs = mock_repo.create.call_args.kwargs
        assert create_kwargs["user_id"] == _USER_ID

    async def test_parent_token_id_is_none_for_first_token(self, mock_repo: AsyncMock) -> None:
        """First token in a chain has no parent (new family)."""
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await issue_tokens_for_user(
                db=AsyncMock(),
                user_id=_USER_ID,
                role=_ROLE,
            )

        create_kwargs = mock_repo.create.call_args.kwargs
        assert create_kwargs["parent_token_id"] is None

    async def test_different_users_get_different_tokens(self, mock_repo: AsyncMock) -> None:
        other_id = uuid.uuid4()
        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            tok_a, _, _, _ = await issue_tokens_for_user(
                db=AsyncMock(), user_id=_USER_ID, role="user"
            )
            tok_b, _, _, _ = await issue_tokens_for_user(
                db=AsyncMock(), user_id=other_id, role="admin"
            )

        assert tok_a != tok_b


# ---------------------------------------------------------------------------
# refresh_tokens
# ---------------------------------------------------------------------------


class TestRefreshTokens:
    """refresh_tokens handles all token rotation scenarios."""

    @pytest.fixture()
    def mock_repo(self):
        repo = AsyncMock()
        repo.create = AsyncMock()
        repo.mark_used = AsyncMock()
        repo.revoke_family = AsyncMock(return_value=3)
        return repo

    async def test_happy_path_returns_six_tuple(self, mock_repo: AsyncMock) -> None:
        user = _make_user()
        record = _make_token_record(user=user)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        raw_token = "raw-valid-refresh-token-string"
        # Make get_by_hash return our record for any hash
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            result = await refresh_tokens(db=AsyncMock(), raw_refresh_token=raw_token)

        assert len(result) == 6
        new_access, access_exp, new_refresh, refresh_exp, role, user_id = result
        assert isinstance(new_access, str)
        assert isinstance(new_refresh, str)
        assert isinstance(access_exp, datetime)
        assert role == _ROLE
        assert user_id == _USER_ID

    async def test_happy_path_marks_old_token_as_used(self, mock_repo: AsyncMock) -> None:
        user = _make_user()
        record = _make_token_record(user=user)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await refresh_tokens(db=AsyncMock(), raw_refresh_token="any-token")

        mock_repo.mark_used.assert_awaited_once_with(record.id)

    async def test_happy_path_issues_new_token_with_same_family(self, mock_repo: AsyncMock) -> None:
        user = _make_user()
        record = _make_token_record(user=user)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await refresh_tokens(db=AsyncMock(), raw_refresh_token="any-token")

        create_kwargs = mock_repo.create.call_args.kwargs
        assert create_kwargs["parent_token_id"] == record.id

    async def test_not_found_raises_invalid_token_error(self, mock_repo: AsyncMock) -> None:
        mock_repo.get_by_hash = AsyncMock(return_value=None)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            with pytest.raises(InvalidTokenError):
                await refresh_tokens(db=AsyncMock(), raw_refresh_token="bad-token")

    async def test_revoked_token_raises_invalid_token_error(self, mock_repo: AsyncMock) -> None:
        user = _make_user()
        record = _make_token_record(user=user, revoked=True)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            with pytest.raises(InvalidTokenError):
                await refresh_tokens(db=AsyncMock(), raw_refresh_token="any")

    async def test_expired_token_raises_invalid_token_error(self, mock_repo: AsyncMock) -> None:
        user = _make_user()
        record = _make_token_record(user=user, expired=True)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            with pytest.raises(InvalidTokenError):
                await refresh_tokens(db=AsyncMock(), raw_refresh_token="any")

    async def test_replay_revokes_family_and_raises(self, mock_repo: AsyncMock) -> None:
        """Replay detection: used=True → revoke family → TokenFamilyRevokedError.

        Teaching note: This is RFC 6819 §5.2.2.3. Revoking the ENTIRE family
        ensures that even if an attacker already used the stolen token to get
        a new one, all their new tokens are also invalidated. The legitimate
        user must re-authenticate.
        """
        user = _make_user()
        record = _make_token_record(user=user, used=True)
        mock_repo.get_by_hash = AsyncMock(return_value=record)
        mock_repo.revoke_family = AsyncMock(return_value=3)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            with pytest.raises(TokenFamilyRevokedError):
                await refresh_tokens(db=AsyncMock(), raw_refresh_token="any")

        mock_repo.revoke_family.assert_awaited_once_with(record.family_id)

    async def test_inactive_user_raises_invalid_token_error(self, mock_repo: AsyncMock) -> None:
        user = _make_user(is_active=False)
        record = _make_token_record(user=user)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            with pytest.raises(InvalidTokenError):
                await refresh_tokens(db=AsyncMock(), raw_refresh_token="any")

    async def test_new_access_token_is_valid_jwt(self, mock_repo: AsyncMock) -> None:
        from app.security.jwt_handler import verify_access_token

        user = _make_user(role="premium")
        record = _make_token_record(user=user)
        mock_repo.get_by_hash = AsyncMock(return_value=record)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            new_access, _, _, _, _, _ = await refresh_tokens(
                db=AsyncMock(), raw_refresh_token="any"
            )

        payload = verify_access_token(new_access)
        assert payload.sub == str(_USER_ID)
        assert payload.role == "premium"


# ---------------------------------------------------------------------------
# logout_user
# ---------------------------------------------------------------------------


class TestLogoutUser:
    """logout_user revokes all active refresh tokens for the user."""

    async def test_returns_count_of_revoked_tokens(self) -> None:
        mock_repo = AsyncMock()
        mock_repo.revoke_all_for_user = AsyncMock(return_value=4)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            count = await logout_user(db=AsyncMock(), user_id=_USER_ID)

        assert count == 4

    async def test_calls_revoke_all_for_user_with_correct_id(self) -> None:
        mock_repo = AsyncMock()
        mock_repo.revoke_all_for_user = AsyncMock(return_value=2)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            await logout_user(db=AsyncMock(), user_id=_USER_ID)

        mock_repo.revoke_all_for_user.assert_awaited_once_with(_USER_ID)

    async def test_returns_zero_when_no_active_tokens(self) -> None:
        mock_repo = AsyncMock()
        mock_repo.revoke_all_for_user = AsyncMock(return_value=0)

        with patch(
            "app.services.auth_service.RefreshTokenRepository",
            return_value=mock_repo,
        ):
            count = await logout_user(db=AsyncMock(), user_id=_USER_ID)

        assert count == 0

    async def test_does_not_call_issue_tokens(self) -> None:
        """logout must not issue any new tokens."""
        mock_repo = AsyncMock()
        mock_repo.revoke_all_for_user = AsyncMock(return_value=1)

        with (
            patch(
                "app.services.auth_service.RefreshTokenRepository",
                return_value=mock_repo,
            ),
            patch("app.services.auth_service.create_access_token") as mock_create,
        ):
            await logout_user(db=AsyncMock(), user_id=_USER_ID)

        mock_create.assert_not_called()
