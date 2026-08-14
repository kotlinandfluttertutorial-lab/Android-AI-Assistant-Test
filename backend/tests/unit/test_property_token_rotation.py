"""Property-based tests for token rotation and replay detection.

Validates: Requirements 1.4

Uses Hypothesis to generate arbitrary token strings and verify that the
token rotation invariants hold across a wide variety of inputs:

- Property 1: Second use of any refresh token raises a SecurityViolationError
- Property 2: On replay, revoke_family is called with the correct family_id
- Property 3: On replay, no new tokens are issued (repo.create not called)
- Property 4: Newly-issued tokens share the original family_id
- Property 5: Error on replay is always SecurityViolationError or subclass,
              never just InvalidTokenError
"""

from __future__ import annotations

import os

# Set env vars before importing any app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

import uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.security.exceptions import (
    InvalidTokenError,
    SecurityViolationError,
)
from app.security.jwt_handler import hash_token
from app.services.auth_service import refresh_tokens

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_user(
    user_id: uuid.UUID | None = None,
    role_value: str = "user",
    is_active: bool = True,
) -> MagicMock:
    """Build a mock User ORM object."""
    user = MagicMock()
    user.id = user_id or uuid.uuid4()
    role_mock = MagicMock()
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
# Strategy: generate raw token strings that can be SHA-256 hashed
# ---------------------------------------------------------------------------

# Generate printable ASCII strings of a reasonable length — similar to what
# secrets.token_urlsafe() would produce, but Hypothesis-controlled.
token_strategy = st.text(
    alphabet=st.characters(
        whitelist_categories=("Lu", "Ll", "Nd"),
        whitelist_characters="-_",
    ),
    min_size=10,
    max_size=100,
)


# ---------------------------------------------------------------------------
# Property 1: Second use of any refresh token raises a SecurityViolationError
#
# Validates: Requirements 1.4
# ---------------------------------------------------------------------------


@given(raw_token=token_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
@pytest.mark.asyncio
async def test_replay_always_raises_security_violation(raw_token: str) -> None:
    """**Validates: Requirements 1.4**

    For any arbitrary token string, submitting a token whose ``used`` flag is
    True must raise a SecurityViolationError (or subclass). This must hold
    regardless of the specific token value.
    """
    mock_db = AsyncMock()
    user = _make_user()
    token_hash = hash_token(raw_token)
    used_record = _make_refresh_token_record(
        user=user,
        token_hash=token_hash,
        used=True,  # replay scenario: already consumed
        revoked=False,
    )

    with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
        repo = MockRepo.return_value
        repo.get_by_hash = AsyncMock(return_value=used_record)
        repo.revoke_family = AsyncMock(return_value=2)
        repo.create = AsyncMock()

        with pytest.raises(SecurityViolationError):
            await refresh_tokens(mock_db, raw_token)


# ---------------------------------------------------------------------------
# Property 2: On replay, revoke_family is called with the correct family_id
#
# Validates: Requirements 1.4
# ---------------------------------------------------------------------------


@given(raw_token=token_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
@pytest.mark.asyncio
async def test_replay_revokes_correct_family(raw_token: str) -> None:
    """**Validates: Requirements 1.4**

    When a used token is replayed, ``repo.revoke_family`` must be invoked with
    exactly the ``family_id`` associated with that token record. This ensures
    the entire rotation chain is revoked, not an unrelated family.
    """
    mock_db = AsyncMock()
    user = _make_user()
    family_id = uuid.uuid4()
    token_hash = hash_token(raw_token)
    used_record = _make_refresh_token_record(
        user=user,
        token_hash=token_hash,
        used=True,
        revoked=False,
        family_id=family_id,
    )

    with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
        repo = MockRepo.return_value
        repo.get_by_hash = AsyncMock(return_value=used_record)
        repo.revoke_family = AsyncMock(return_value=1)
        repo.create = AsyncMock()

        try:
            await refresh_tokens(mock_db, raw_token)
        except SecurityViolationError:
            pass

    repo.revoke_family.assert_called_once_with(family_id)


# ---------------------------------------------------------------------------
# Property 3: On replay, no new tokens are issued (repo.create not called)
#
# Validates: Requirements 1.4
# ---------------------------------------------------------------------------


@given(raw_token=token_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
@pytest.mark.asyncio
async def test_replay_does_not_issue_new_tokens(raw_token: str) -> None:
    """**Validates: Requirements 1.4**

    When a replay is detected, the service must NOT persist any new token
    record. ``repo.create`` must never be called during a replay attempt.
    """
    mock_db = AsyncMock()
    user = _make_user()
    token_hash = hash_token(raw_token)
    used_record = _make_refresh_token_record(
        user=user,
        token_hash=token_hash,
        used=True,
        revoked=False,
    )

    with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
        repo = MockRepo.return_value
        repo.get_by_hash = AsyncMock(return_value=used_record)
        repo.revoke_family = AsyncMock(return_value=1)
        repo.create = AsyncMock()

        try:
            await refresh_tokens(mock_db, raw_token)
        except SecurityViolationError:
            pass

    assert repo.create.call_count == 0, (
        f"repo.create was called {repo.create.call_count} time(s) during replay "
        f"but must never be called when replaying a used token."
    )


# ---------------------------------------------------------------------------
# Property 4: Newly-issued tokens share the original family_id
#
# Validates: Requirements 1.4
# ---------------------------------------------------------------------------


@given(raw_token=token_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
@pytest.mark.asyncio
async def test_new_token_inherits_family_id(raw_token: str) -> None:
    """**Validates: Requirements 1.4**

    For any valid (unused, unrevoked, unexpired) token, the new refresh token
    created by ``repo.create`` must carry the same ``family_id`` as the
    original token. This maintains the rotation chain.
    """
    mock_db = AsyncMock()
    user = _make_user()
    family_id = uuid.uuid4()
    token_hash = hash_token(raw_token)
    valid_record = _make_refresh_token_record(
        user=user,
        token_hash=token_hash,
        used=False,
        revoked=False,
        family_id=family_id,
    )

    with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
        repo = MockRepo.return_value
        repo.get_by_hash = AsyncMock(return_value=valid_record)
        repo.mark_used = AsyncMock()
        repo.create = AsyncMock(return_value=MagicMock())

        await refresh_tokens(mock_db, raw_token)

    repo.create.assert_called_once()
    create_kwargs = repo.create.call_args.kwargs
    assert create_kwargs["family_id"] == family_id, (
        f"New token was persisted with family_id={create_kwargs['family_id']} "
        f"but expected {family_id}. Token rotation must preserve family_id."
    )


# ---------------------------------------------------------------------------
# Property 5: Error on replay is SecurityViolationError, never InvalidTokenError alone
#
# Validates: Requirements 1.4
# ---------------------------------------------------------------------------


@given(raw_token=token_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
@pytest.mark.asyncio
async def test_replay_error_is_security_violation_not_invalid_token(
    raw_token: str,
) -> None:
    """**Validates: Requirements 1.4**

    The exception raised on replay detection must be a SecurityViolationError
    (or a subclass such as TokenFamilyRevokedError). It must NOT be a plain
    InvalidTokenError — that type is reserved for token lookup/validation
    failures, not security-relevant replay attacks.
    """
    mock_db = AsyncMock()
    user = _make_user()
    token_hash = hash_token(raw_token)
    used_record = _make_refresh_token_record(
        user=user,
        token_hash=token_hash,
        used=True,
        revoked=False,
    )

    with patch("app.services.auth_service.RefreshTokenRepository") as MockRepo:
        repo = MockRepo.return_value
        repo.get_by_hash = AsyncMock(return_value=used_record)
        repo.revoke_family = AsyncMock(return_value=1)
        repo.create = AsyncMock()

        raised_exc: BaseException | None = None
        try:
            await refresh_tokens(mock_db, raw_token)
        except Exception as exc:
            raised_exc = exc

    assert raised_exc is not None, "Expected an exception to be raised on replay"

    # Must be a SecurityViolationError or subclass
    assert isinstance(raised_exc, SecurityViolationError), (
        f"Expected SecurityViolationError (or subclass), got {type(raised_exc).__name__}: "
        f"{raised_exc}"
    )

    # Must NOT be a plain InvalidTokenError (only those that are NOT also a
    # SecurityViolationError subclass — InvalidTokenError extends AuthError,
    # not SecurityViolationError, so any InvalidTokenError here is wrong).
    assert not (
        isinstance(raised_exc, InvalidTokenError)
        and not isinstance(raised_exc, SecurityViolationError)
    ), (
        "Replay exception must not be a plain InvalidTokenError. "
        "Use TokenFamilyRevokedError or SecurityViolationError."
    )
