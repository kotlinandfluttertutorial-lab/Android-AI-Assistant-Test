"""Unit tests for rate limiting middleware and account lockout logic.

Requirements: 9.9, 1.5, 21.1
"""

from __future__ import annotations

import base64
import json
import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Environment setup — must happen before any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_bearer_jwt(payload: dict) -> str:
    """Build a structurally valid JWT Bearer header value (no real signature)."""
    header_b64 = (
        base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        .rstrip(b"=")
        .decode()
    )
    payload_b64 = (
        base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()
    )
    return f"Bearer {header_b64}.{payload_b64}.fakesignature"


def _make_settings_stub(rate_limit: int = 60) -> MagicMock:
    s = MagicMock()
    s.RATE_LIMIT_REQUESTS_PER_MINUTE = rate_limit
    s.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE = 20
    s.REDIS_URL = "redis://localhost:6379/0"
    return s


def _make_lockout_settings_stub() -> MagicMock:
    s = MagicMock()
    s.ACCOUNT_LOCKOUT_MAX_ATTEMPTS = 5
    s.ACCOUNT_LOCKOUT_WINDOW_MINUTES = 10
    s.ACCOUNT_LOCKOUT_DURATION_MINUTES = 15
    return s


# ---------------------------------------------------------------------------
# Import the modules under test (after env vars are set)
# ---------------------------------------------------------------------------
from app.middleware.rate_limit import (
    RateLimitMiddleware,
    _extract_user_id_from_header,
)
from app.security.exceptions import AccountLockedError
from app.security.lockout import (
    AccountLockoutService,
    clear_failed_attempts,
    get_lockout_ttl,
    is_locked,
    record_failed_attempt,
)

# ===========================================================================
# 1. _extract_user_id_from_header
# ===========================================================================


class TestExtractUserIdFromHeader:
    def test_valid_bearer_jwt_returns_sub(self) -> None:
        header = _make_bearer_jwt({"sub": "user-123", "email": "a@b.com"})
        result = _extract_user_id_from_header(header)
        assert result == "user-123"

    def test_none_header_returns_none(self) -> None:
        assert _extract_user_id_from_header(None) is None

    def test_non_bearer_prefix_returns_none(self) -> None:
        header = _make_bearer_jwt({"sub": "user-123"}).replace("Bearer ", "Basic ")
        assert _extract_user_id_from_header(header) is None

    def test_jwt_with_only_two_parts_returns_none(self) -> None:
        # Only header.payload — missing signature segment
        header_b64 = base64.urlsafe_b64encode(b'{"alg":"HS256"}').rstrip(b"=").decode()
        payload_b64 = base64.urlsafe_b64encode(b'{"sub":"x"}').rstrip(b"=").decode()
        assert (
            _extract_user_id_from_header(f"Bearer {header_b64}.{payload_b64}") is None
        )

    def test_jwt_with_no_sub_claim_returns_none(self) -> None:
        header = _make_bearer_jwt({"email": "no-sub@example.com"})
        assert _extract_user_id_from_header(header) is None

    def test_malformed_base64_payload_returns_none(self) -> None:
        assert _extract_user_id_from_header("Bearer aaa.!!!.bbb") is None

    def test_empty_string_returns_none(self) -> None:
        assert _extract_user_id_from_header("") is None

    def test_bearer_without_token_returns_none(self) -> None:
        assert _extract_user_id_from_header("Bearer ") is None


# ===========================================================================
# 2. RateLimitMiddleware
# ===========================================================================


class TestRateLimitMiddleware:
    """Tests for RateLimitMiddleware.dispatch."""

    def _build_middleware(
        self, redis_mock: AsyncMock, settings_stub: MagicMock
    ) -> RateLimitMiddleware:
        app_stub = MagicMock()
        mw = RateLimitMiddleware(app_stub)
        mw._get_redis = AsyncMock(return_value=redis_mock)
        mw._get_settings = MagicMock(return_value=settings_stub)
        return mw

    def _make_request(self, user_id: str | None = "user-42") -> MagicMock:
        request = MagicMock()
        if user_id:
            bearer = _make_bearer_jwt({"sub": user_id})
            request.headers.get = lambda key, default=None: (
                bearer if key.lower() == "authorization" else default
            )
        else:
            request.headers.get = lambda key, default=None: default
        return request

    @pytest.mark.asyncio
    async def test_allows_request_under_limit(self) -> None:
        """When Redis INCR returns 1 (first request), call_next is invoked."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub(rate_limit=60))
        call_next = AsyncMock(return_value=MagicMock(status_code=200))
        request = self._make_request("user-42")

        response = await mw.dispatch(request, call_next)

        call_next.assert_called_once_with(request)
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_returns_429_when_limit_exceeded(self) -> None:
        """When Redis INCR returns 61 (over the 60 req/min limit), return 429."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=61)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub(rate_limit=60))
        call_next = AsyncMock()
        request = self._make_request("user-42")

        response = await mw.dispatch(request, call_next)

        call_next.assert_not_called()
        assert response.status_code == 429

    @pytest.mark.asyncio
    async def test_429_response_has_retry_after_header(self) -> None:
        """HTTP 429 must include a Retry-After header."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=61)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub(rate_limit=60))
        request = self._make_request("user-99")

        response = await mw.dispatch(request, AsyncMock())

        assert "Retry-After" in response.headers

    @pytest.mark.asyncio
    async def test_429_body_contains_detail_message(self) -> None:
        """429 body must contain 'Rate limit exceeded' in the detail field."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=100)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub(rate_limit=60))
        request = self._make_request("user-99")

        response = await mw.dispatch(request, AsyncMock())

        body = json.loads(response.body)
        assert "Rate limit exceeded" in body["detail"]

    @pytest.mark.asyncio
    async def test_skips_rate_limiting_for_unauthenticated_request(self) -> None:
        """Requests without a valid JWT are rate-limited by IP (not by user ID).

        With the addition of Req 9.11, unauthenticated requests now go through
        the IP-based rate limit tier.  call_next is still invoked when the IP
        limit has not been exceeded (INCR returns 1).
        """
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub())
        call_next = AsyncMock(return_value=MagicMock(status_code=200))
        request = self._make_request(user_id=None)

        response = await mw.dispatch(request, call_next)

        # IP tier calls Redis (incr) for unauthenticated requests
        redis_mock.incr.assert_called_once()
        # call_next is still invoked (not over the IP limit)
        call_next.assert_called_once_with(request)
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_fails_open_when_redis_raises(self) -> None:
        """When Redis raises an exception, call_next is still called (fail-open)."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(side_effect=ConnectionError("Redis down"))

        mw = self._build_middleware(redis_mock, _make_settings_stub())
        call_next = AsyncMock(return_value=MagicMock(status_code=200))
        request = self._make_request("user-42")

        response = await mw.dispatch(request, call_next)

        call_next.assert_called_once_with(request)
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_uses_correct_redis_key_format(self) -> None:
        """The Redis key must follow the pattern rate:{user_id}:{window}."""

        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub())
        request = self._make_request("user-42")

        with patch("app.middleware.rate_limit.time") as mock_time:
            mock_time.time.return_value = 1700000000.0
            expected_window = int(1700000000.0 // 60)
            await mw.dispatch(
                request, AsyncMock(return_value=MagicMock(status_code=200))
            )

        called_key = redis_mock.incr.call_args[0][0]
        assert called_key == f"rate:user-42:{expected_window}"

    @pytest.mark.asyncio
    async def test_sets_ttl_to_120_on_first_request(self) -> None:
        """When INCR returns 1, expire() must be called with TTL=120."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub())
        request = self._make_request("user-42")

        await mw.dispatch(request, AsyncMock(return_value=MagicMock(status_code=200)))

        redis_mock.expire.assert_called_once()
        _key, ttl = redis_mock.expire.call_args[0]
        assert ttl == 120

    @pytest.mark.asyncio
    async def test_does_not_set_ttl_on_subsequent_requests(self) -> None:
        """When INCR returns >1, expire() must NOT be called again."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=30)
        redis_mock.expire = AsyncMock()

        mw = self._build_middleware(redis_mock, _make_settings_stub())
        request = self._make_request("user-42")

        await mw.dispatch(request, AsyncMock(return_value=MagicMock(status_code=200)))

        redis_mock.expire.assert_not_called()


# ===========================================================================
# 3. is_locked
# ===========================================================================


class TestIsLocked:
    @pytest.mark.asyncio
    async def test_returns_true_when_lock_key_exists(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.exists = AsyncMock(return_value=1)
        result = await is_locked(redis_mock, "test@example.com")
        assert result is True

    @pytest.mark.asyncio
    async def test_returns_false_when_lock_key_absent(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.exists = AsyncMock(return_value=0)
        result = await is_locked(redis_mock, "test@example.com")
        assert result is False

    @pytest.mark.asyncio
    async def test_uses_lowercase_email_in_key(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.exists = AsyncMock(return_value=0)
        await is_locked(redis_mock, "User@Example.COM")
        key_used = redis_mock.exists.call_args[0][0]
        assert "user@example.com" in key_used
        assert "auth:locked:" in key_used


# ===========================================================================
# 4. get_lockout_ttl
# ===========================================================================


class TestGetLockoutTtl:
    @pytest.mark.asyncio
    async def test_returns_ttl_when_key_exists(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.ttl = AsyncMock(return_value=750)
        result = await get_lockout_ttl(redis_mock, "locked@example.com")
        assert result == 750

    @pytest.mark.asyncio
    async def test_returns_zero_when_key_absent(self) -> None:
        """Redis returns -2 when a key does not exist; we must normalise to 0."""
        redis_mock = AsyncMock()
        redis_mock.ttl = AsyncMock(return_value=-2)
        result = await get_lockout_ttl(redis_mock, "unlocked@example.com")
        assert result == 0

    @pytest.mark.asyncio
    async def test_returns_zero_for_negative_ttl(self) -> None:
        """Redis returns -1 for keys without a TTL; we must normalise to 0."""
        redis_mock = AsyncMock()
        redis_mock.ttl = AsyncMock(return_value=-1)
        result = await get_lockout_ttl(redis_mock, "no-ttl@example.com")
        assert result == 0


# ===========================================================================
# 5. record_failed_attempt
# ===========================================================================


class TestRecordFailedAttempt:
    """Tests for the module-level record_failed_attempt function."""

    def _make_redis(
        self, attempt_count: int, already_locked: bool = False
    ) -> AsyncMock:
        redis_mock = AsyncMock()
        redis_mock.eval = AsyncMock(return_value=attempt_count)
        redis_mock.exists = AsyncMock(return_value=1 if already_locked else 0)
        redis_mock.setex = AsyncMock()
        redis_mock.ttl = AsyncMock(return_value=900 if already_locked else -2)
        return redis_mock

    @pytest.mark.asyncio
    async def test_lockout_triggers_at_fifth_failure(self) -> None:
        """Exactly at attempt 5, setex must be called to apply the lock."""
        redis_mock = self._make_redis(attempt_count=5, already_locked=False)
        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ):
            count, locked = await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )

        assert count == 5
        assert locked is True
        redis_mock.setex.assert_called_once()

    @pytest.mark.asyncio
    async def test_lock_duration_is_15_minutes(self) -> None:
        """The lock TTL must be 15 * 60 = 900 seconds."""
        redis_mock = self._make_redis(attempt_count=5, already_locked=False)
        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ):
            await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )

        _key, ttl, _val = redis_mock.setex.call_args[0]
        assert ttl == 900

    @pytest.mark.asyncio
    async def test_account_not_locked_before_fifth_failure(self) -> None:
        """Attempts 1–4 must NOT call setex (no lock applied yet)."""
        for attempt in range(1, 5):
            redis_mock = self._make_redis(attempt_count=attempt, already_locked=False)
            with patch(
                "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
            ):
                count, locked = await record_failed_attempt(
                    redis_mock,
                    "user@test.com",
                    max_attempts=5,
                    window_minutes=10,
                    lockout_duration_minutes=15,
                )

            assert locked is False, f"Should not be locked after attempt {attempt}"
            redis_mock.setex.assert_not_called()

    @pytest.mark.asyncio
    async def test_email_sent_when_lock_is_applied(self) -> None:
        """send_failed_login_email must be called exactly when the lock is applied."""
        redis_mock = self._make_redis(attempt_count=5, already_locked=False)
        redis_mock.ttl = AsyncMock(return_value=900)

        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ) as mock_email:
            await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )

        mock_email.assert_called_once()

    @pytest.mark.asyncio
    async def test_email_sent_on_each_failure_while_already_locked(self) -> None:
        """Every attempt after the lock is set must also trigger an email (Req 1.5)."""
        for extra_attempt in range(1, 4):
            redis_mock = self._make_redis(
                attempt_count=5 + extra_attempt, already_locked=True
            )
            with patch(
                "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
            ) as mock_email:
                count, locked = await record_failed_attempt(
                    redis_mock,
                    "user@test.com",
                    max_attempts=5,
                    window_minutes=10,
                    lockout_duration_minutes=15,
                )

            assert locked is True
            (
                mock_email.assert_called_once(),
                (f"Email not sent for post-lock attempt {extra_attempt}"),
            )

    @pytest.mark.asyncio
    async def test_email_not_sent_for_attempts_below_threshold(self) -> None:
        """No email for attempts 1–4 (below threshold, not yet locked)."""
        for attempt in range(1, 5):
            redis_mock = self._make_redis(attempt_count=attempt, already_locked=False)
            with patch(
                "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
            ) as mock_email:
                await record_failed_attempt(
                    redis_mock,
                    "user@test.com",
                    max_attempts=5,
                    window_minutes=10,
                    lockout_duration_minutes=15,
                )

            (
                mock_email.assert_not_called(),
                (f"Email must not be sent for attempt {attempt}"),
            )

    @pytest.mark.asyncio
    async def test_returns_correct_tuple_not_locked(self) -> None:
        """Before threshold: returns (attempt_count, False)."""
        redis_mock = self._make_redis(attempt_count=3, already_locked=False)
        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ):
            count, locked = await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )
        assert count == 3
        assert locked is False

    @pytest.mark.asyncio
    async def test_returns_correct_tuple_just_locked(self) -> None:
        """At threshold: returns (attempt_count, True)."""
        redis_mock = self._make_redis(attempt_count=5, already_locked=False)
        redis_mock.ttl = AsyncMock(return_value=900)
        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ):
            count, locked = await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )
        assert count == 5
        assert locked is True

    @pytest.mark.asyncio
    async def test_returns_correct_tuple_already_locked(self) -> None:
        """While already locked: returns (attempt_count, True)."""
        redis_mock = self._make_redis(attempt_count=7, already_locked=True)
        with patch(
            "app.security.lockout.send_failed_login_email", new_callable=AsyncMock
        ):
            count, locked = await record_failed_attempt(
                redis_mock,
                "user@test.com",
                max_attempts=5,
                window_minutes=10,
                lockout_duration_minutes=15,
            )
        assert count == 7
        assert locked is True


# ===========================================================================
# 6. clear_failed_attempts
# ===========================================================================


class TestClearFailedAttempts:
    @pytest.mark.asyncio
    async def test_deletes_attempts_key(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.delete = AsyncMock()
        await clear_failed_attempts(redis_mock, "user@test.com")
        redis_mock.delete.assert_called_once()

    @pytest.mark.asyncio
    async def test_deletes_correct_key(self) -> None:
        redis_mock = AsyncMock()
        redis_mock.delete = AsyncMock()
        await clear_failed_attempts(redis_mock, "User@Test.COM")
        key = redis_mock.delete.call_args[0][0]
        assert "auth:attempts:" in key
        assert "user@test.com" in key


# ===========================================================================
# 7. AccountLockoutService
# ===========================================================================


class TestAccountLockoutService:
    def _make_service(self) -> AccountLockoutService:
        redis_mock = AsyncMock()
        service = AccountLockoutService(redis_mock)
        service._get_settings = MagicMock(return_value=_make_lockout_settings_stub())
        return service

    @pytest.mark.asyncio
    async def test_check_locked_raises_when_locked(self) -> None:
        """check_locked must raise AccountLockedError when the account is locked."""
        service = self._make_service()
        with (
            patch("app.security.lockout.is_locked", AsyncMock(return_value=True)),
            patch("app.security.lockout.get_lockout_ttl", AsyncMock(return_value=720)),
        ):
            with pytest.raises(AccountLockedError) as exc_info:
                await service.check_locked("locked@example.com")

        assert exc_info.value.retry_after_seconds == 720

    @pytest.mark.asyncio
    async def test_check_locked_does_not_raise_when_unlocked(self) -> None:
        """check_locked must NOT raise when the account is not locked."""
        service = self._make_service()
        with patch("app.security.lockout.is_locked", AsyncMock(return_value=False)):
            # Should not raise
            await service.check_locked("unlocked@example.com")

    @pytest.mark.asyncio
    async def test_record_failed_attempt_delegates_to_module_function(self) -> None:
        """AccountLockoutService.record_failed_attempt delegates with settings params."""
        service = self._make_service()
        with patch(
            "app.security.lockout.record_failed_attempt",
            new_callable=AsyncMock,
            return_value=(3, False),
        ) as mock_fn:
            count, locked = await service.record_failed_attempt(
                "user@example.com", "Test User"
            )

        mock_fn.assert_called_once()
        call_kwargs = mock_fn.call_args[1]
        assert call_kwargs["max_attempts"] == 5
        assert call_kwargs["window_minutes"] == 10
        assert call_kwargs["lockout_duration_minutes"] == 15
        assert count == 3
        assert locked is False

    @pytest.mark.asyncio
    async def test_clear_on_success_clears_failed_attempts(self) -> None:
        """clear_on_success must call clear_failed_attempts for the given email."""
        service = self._make_service()
        with patch(
            "app.security.lockout.clear_failed_attempts", new_callable=AsyncMock
        ) as mock_clear:
            await service.clear_on_success("user@example.com")

        mock_clear.assert_called_once()
        assert "user@example.com" in mock_clear.call_args[0]
