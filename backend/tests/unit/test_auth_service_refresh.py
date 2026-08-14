"""Unit tests for app.services.auth_service refresh token flows.

Uses AsyncMock to test the service logic without a live database. All
repository calls are mocked so these tests run with no external dependencies.

Covers:
- issue_tokens_for_user: returns (JWT, refresh_token), calls repo.create
- refresh_tokens happy path: old token marked used, new JWT+refresh issued, family_id preserved
- refresh_tokens replay detection: revokes entire family, raises TokenFamilyRevokedError
- refresh_tokens with revoked token: raises InvalidTokenError without issuing new tokens
- refresh_tokens with expired token: raises InvalidTokenError
- logout_user: delegates to repo.revoke_all_for_user

Requirements: 1.2, 1.3, 1.4, 1.10
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Set env vars before importing app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.models.user import UserRole
from app.security.exceptions import (
    InvalidTokenError,
    TokenFamilyRevokedError,
)
from app.security.jwt_handler import hash_token, verify_access_token
from app.services.auth_service import (
    issue_tokens_for_user,
    logout_user,
    refresh_tokens,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SAMPLE_USER_ID = uuid.UUID("12345678-1234-5678-1234-567812345678")
SAMPLE_ROLE = "user"


def _make_user(
    user_id: uuid.UUID = SAMPLE_USER_ID,
    role: UserRole = UserRole.user,
    is_active: bool = True,
) -> MagicMock:
    """Build a mock User ORM object."""
    user = MagicMock()
    user.id = user_id
    # Create a MagicMock for role with .value set to the role's string value
    role_mock = MagicMock()
    role_value = role.value if isinstance(role, UserRole) else role
    role_mock.value = role_value
    user.role = role_mock
    user.is_active = is_active
    return user


def _make_refresh_token_record(
    *,
    user: MagicMock,
    token_hash: str,
    used: bool = False,
    revoked: bool = False,
    family_id: uuid.UUID | None = None,
    expires_delta_seconds: int = 30 * 24 * 3600,
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
        seconds=expires_delta_seconds
    )
    return record


# ---------------------------------------------------------------------------
# issue_tokens_for_user
# ---------------------------------------------------------------------------


class TestIssueTokensForUser:
    """Tests for initial token issuance after login."""

    @pytest.mark.asyncio
    async def test_returns_expected_types(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.create = AsyncMock(return_value=MagicMock())

            (
                access_token,
                access_exp,
                refresh_token,
                refresh_exp,
            ) = await issue_tokens_for_user(mock_db, SAMPLE_USER_ID, SAMPLE_ROLE)

        assert isinstance(access_token, str)
        assert isinstance(access_exp, datetime)
        assert isinstance(refresh_token, str)
        assert isinstance(refresh_exp, datetime)
        assert len(access_token) > 0
        assert len(refresh_token) > 0

    @pytest.mark.asyncio
    async def test_access_token_contains_correct_claims(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.create = AsyncMock(return_value=MagicMock())

            access_token, _, _, _ = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, "admin"
            )

        payload = verify_access_token(access_token)
        assert payload.sub == str(SAMPLE_USER_ID)
        assert payload.role == "admin"

    @pytest.mark.asyncio
    async def test_repo_create_called_once(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.create = AsyncMock(return_value=MagicMock())

            await issue_tokens_for_user(mock_db, SAMPLE_USER_ID, SAMPLE_ROLE)

        repo.create.assert_called_once()

    @pytest.mark.asyncio
    async def test_repo_create_called_with_no_parent(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.create = AsyncMock(return_value=MagicMock())

            _, _, raw_refresh, _ = await issue_tokens_for_user(
                mock_db, SAMPLE_USER_ID, SAMPLE_ROLE
            )

        call_kwargs = repo.create.call_args.kwargs
        assert call_kwargs["user_id"] == SAMPLE_USER_ID
        assert call_kwargs["parent_token_id"] is None


# ---------------------------------------------------------------------------
# refresh_tokens — happy path
# ---------------------------------------------------------------------------


class TestRefreshTokensHappyPath:
    """Tests for successful token rotation."""

    @pytest.mark.asyncio
    async def test_returns_new_tokens_with_expiries(self) -> None:
        """Happy path: valid, unused, unexpired token."""
        mock_db = AsyncMock()
        user = _make_user()
        raw = "my-raw-refresh-token"
        token_hash = hash_token(raw)
        old_record = _make_refresh_token_record(user=user, token_hash=token_hash)

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            (
                new_jwt,
                access_exp,
                new_refresh,
                refresh_exp,
                role,
                user_id,
            ) = await refresh_tokens(mock_db, raw)

        assert isinstance(new_jwt, str)
        assert isinstance(access_exp, datetime)
        assert isinstance(new_refresh, str)
        assert isinstance(refresh_exp, datetime)
        assert role == SAMPLE_ROLE
        assert user_id == SAMPLE_USER_ID

    @pytest.mark.asyncio
    async def test_old_token_marked_used(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "my-raw-refresh-token"
        token_hash = hash_token(raw)
        old_record = _make_refresh_token_record(user=user, token_hash=token_hash)

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            await refresh_tokens(mock_db, raw)

        repo.mark_used.assert_called_once_with(old_record.id)

    @pytest.mark.asyncio
    async def test_new_token_persisted_with_same_family(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "my-raw-refresh-token"
        token_hash = hash_token(raw)
        original_family_id = uuid.uuid4()
        old_record = _make_refresh_token_record(
            user=user, token_hash=token_hash, family_id=original_family_id
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            await refresh_tokens(mock_db, raw)

        # repo.create must be called with the same family_id
        create_kwargs = repo.create.call_args.kwargs
        assert create_kwargs["family_id"] == original_family_id
        assert create_kwargs["parent_token_id"] == old_record.id

    @pytest.mark.asyncio
    async def test_new_jwt_has_correct_claims(self) -> None:
        mock_db = AsyncMock()
        user = _make_user(role=UserRole.premium)
        raw = "my-raw-refresh-token"
        token_hash = hash_token(raw)
        old_record = _make_refresh_token_record(user=user, token_hash=token_hash)

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=old_record)
            repo.mark_used = AsyncMock()
            repo.create = AsyncMock(return_value=MagicMock())

            new_jwt, _, _, _, role, _ = await refresh_tokens(mock_db, raw)

        payload = verify_access_token(new_jwt)
        assert payload.sub == str(SAMPLE_USER_ID)
        assert payload.role == "premium"


# ---------------------------------------------------------------------------
# refresh_tokens — replay detection
# ---------------------------------------------------------------------------


class TestRefreshTokensReplayDetection:
    """Tests for replay detection (token already used)."""

    @pytest.mark.asyncio
    async def test_raises_token_family_revoked_on_replay(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "already-used-token"
        token_hash = hash_token(raw)
        used_record = _make_refresh_token_record(
            user=user,
            token_hash=token_hash,
            used=True,  # already used!
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=3)

            with pytest.raises(TokenFamilyRevokedError):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_revoke_family_called_on_replay(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "already-used-token"
        token_hash = hash_token(raw)
        family_id = uuid.uuid4()
        used_record = _make_refresh_token_record(
            user=user, token_hash=token_hash, used=True, family_id=family_id
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=2)

            try:
                await refresh_tokens(mock_db, raw)
            except TokenFamilyRevokedError:
                pass

        repo.revoke_family.assert_called_once_with(family_id)

    @pytest.mark.asyncio
    async def test_no_new_tokens_issued_on_replay(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "already-used-token"
        token_hash = hash_token(raw)
        used_record = _make_refresh_token_record(
            user=user, token_hash=token_hash, used=True
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=used_record)
            repo.revoke_family = AsyncMock(return_value=1)

            try:
                await refresh_tokens(mock_db, raw)
            except TokenFamilyRevokedError:
                pass

        # repo.create must NOT have been called
        assert not hasattr(repo, "create") or repo.create.call_count == 0


# ---------------------------------------------------------------------------
# refresh_tokens — error cases
# ---------------------------------------------------------------------------


class TestRefreshTokensErrorCases:
    """Tests for all error cases that raise InvalidTokenError."""

    @pytest.mark.asyncio
    async def test_raises_on_nonexistent_token(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=None)

            with pytest.raises(InvalidTokenError, match="not found"):
                await refresh_tokens(mock_db, "nonexistent-token")

    @pytest.mark.asyncio
    async def test_raises_on_revoked_token(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "revoked-token"
        token_hash = hash_token(raw)
        revoked_record = _make_refresh_token_record(
            user=user, token_hash=token_hash, revoked=True
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=revoked_record)

            with pytest.raises(InvalidTokenError, match="revoked"):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_raises_on_expired_token(self) -> None:
        mock_db = AsyncMock()
        user = _make_user()
        raw = "expired-token"
        token_hash = hash_token(raw)
        # expires_delta_seconds=-1 puts expires_at in the past
        expired_record = _make_refresh_token_record(
            user=user, token_hash=token_hash, expires_delta_seconds=-1
        )

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=expired_record)

            with pytest.raises(InvalidTokenError, match="expired"):
                await refresh_tokens(mock_db, raw)

    @pytest.mark.asyncio
    async def test_raises_on_inactive_user(self) -> None:
        mock_db = AsyncMock()
        inactive_user = _make_user(is_active=False)
        raw = "valid-looking-token"
        token_hash = hash_token(raw)
        record = _make_refresh_token_record(user=inactive_user, token_hash=token_hash)

        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.get_by_hash = AsyncMock(return_value=record)
            repo.mark_used = AsyncMock()

            with pytest.raises(InvalidTokenError, match="not active"):
                await refresh_tokens(mock_db, raw)


# ---------------------------------------------------------------------------
# logout_user
# ---------------------------------------------------------------------------


class TestLogoutUser:
    """Tests for logout (revokes all active tokens)."""

    @pytest.mark.asyncio
    async def test_delegates_to_revoke_all_for_user(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.revoke_all_for_user = AsyncMock(return_value=5)

            count = await logout_user(mock_db, SAMPLE_USER_ID)

        repo.revoke_all_for_user.assert_called_once_with(SAMPLE_USER_ID)
        assert count == 5

    @pytest.mark.asyncio
    async def test_returns_zero_when_no_tokens(self) -> None:
        mock_db = AsyncMock()
        with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
            repo = MockRepo.return_value
            repo.revoke_all_for_user = AsyncMock(return_value=0)

            count = await logout_user(mock_db, SAMPLE_USER_ID)

        assert count == 0
