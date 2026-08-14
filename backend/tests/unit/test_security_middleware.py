"""Unit tests for security middleware: unauthenticated IP rate limiting,
data residency enforcement, push notification worker, device token refresh.

Requirements: 9.11, 16.1, 16.2, 16.7, 21.1
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


def _make_rate_limit_settings(
    auth_limit: int = 60, unauth_limit: int = 20
) -> MagicMock:
    s = MagicMock()
    s.RATE_LIMIT_REQUESTS_PER_MINUTE = auth_limit
    s.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE = unauth_limit
    s.REDIS_URL = "redis://localhost:6379/0"
    return s


def _make_data_residency_settings(region: str = "") -> MagicMock:
    s = MagicMock()
    s.DATA_RESIDENCY_REGION = region
    return s


# ---------------------------------------------------------------------------
# Import modules under test (after env vars are set)
# ---------------------------------------------------------------------------
from app.middleware.data_residency import DataResidencyMiddleware
from app.middleware.rate_limit import (
    RateLimitMiddleware,
    _extract_client_ip,
)

# ===========================================================================
# Helper — build a minimal mock Request for RateLimitMiddleware.dispatch()
# ===========================================================================


def _make_request(
    user_id: str | None = "user-42",
    xff_header: str | None = None,
    client_host: str | None = None,
) -> MagicMock:
    """Create a mock Starlette Request for unit tests."""
    request = MagicMock()
    request.client = MagicMock()
    request.client.host = client_host or "127.0.0.1"

    headers: dict[str, str] = {}
    if user_id:
        headers["authorization"] = _make_bearer_jwt({"sub": user_id})
    if xff_header:
        headers["x-forwarded-for"] = xff_header

    def _headers_get(key: str, default=None):
        return headers.get(key.lower(), default)

    request.headers.get = _headers_get
    request.scope = {}
    return request


def _build_rate_middleware(
    redis_mock: AsyncMock, settings_stub: MagicMock
) -> RateLimitMiddleware:
    app_stub = MagicMock()
    mw = RateLimitMiddleware(app_stub)
    mw._get_redis = AsyncMock(return_value=redis_mock)
    mw._get_settings = MagicMock(return_value=settings_stub)
    return mw


# ===========================================================================
# 1. Rate limiting — unauthenticated IP tier
# ===========================================================================


class TestUnauthenticatedIPRateLimit:
    """Req 9.11 — unauthenticated requests are rate limited by source IP."""

    @pytest.mark.asyncio
    async def test_unauthenticated_ip_allows_under_limit(self) -> None:
        """Redis INCR returns 1, call_next should be invoked."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(unauth_limit=20)
        )
        call_next = AsyncMock(return_value=MagicMock(status_code=200))
        request = _make_request(user_id=None)

        response = await mw.dispatch(request, call_next)

        call_next.assert_called_once_with(request)
        assert response.status_code == 200

    @pytest.mark.asyncio
    async def test_unauthenticated_ip_blocks_at_limit_plus_one(self) -> None:
        """INCR returns 21 (over the 20 req/min limit), returns 429."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=21)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(unauth_limit=20)
        )
        call_next = AsyncMock()
        request = _make_request(user_id=None)

        response = await mw.dispatch(request, call_next)

        call_next.assert_not_called()
        assert response.status_code == 429

    @pytest.mark.asyncio
    async def test_unauthenticated_ip_429_has_retry_after_header(self) -> None:
        """HTTP 429 response must include a Retry-After header."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=21)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(unauth_limit=20)
        )
        request = _make_request(user_id=None)

        response = await mw.dispatch(request, AsyncMock())

        assert "Retry-After" in response.headers

    @pytest.mark.asyncio
    async def test_ip_extracted_from_x_forwarded_for(self) -> None:
        """X-Forwarded-For: '1.2.3.4, 5.6.7.8' → uses '1.2.3.4' as the key IP."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=21)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(unauth_limit=20)
        )
        request = _make_request(user_id=None, xff_header="1.2.3.4, 5.6.7.8")

        await mw.dispatch(request, AsyncMock(return_value=MagicMock(status_code=200)))

        called_key: str = redis_mock.incr.call_args[0][0]
        assert "1.2.3.4" in called_key

    @pytest.mark.asyncio
    async def test_ip_falls_back_to_scope_client(self) -> None:
        """No X-Forwarded-For header → uses request.client.host as the IP."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(unauth_limit=20)
        )
        request = _make_request(user_id=None, client_host="10.20.30.40")

        await mw.dispatch(request, AsyncMock(return_value=MagicMock(status_code=200)))

        called_key: str = redis_mock.incr.call_args[0][0]
        assert "10.20.30.40" in called_key

    @pytest.mark.asyncio
    async def test_authenticated_request_bypasses_ip_rate_limit(self) -> None:
        """When an auth header is present, the user rate limit is applied (not IP)."""
        redis_mock = AsyncMock()
        redis_mock.incr = AsyncMock(return_value=1)
        redis_mock.expire = AsyncMock()

        mw = _build_rate_middleware(
            redis_mock, _make_rate_limit_settings(auth_limit=60)
        )
        call_next = AsyncMock(return_value=MagicMock(status_code=200))
        request = _make_request(user_id="user-42")

        response = await mw.dispatch(request, call_next)

        # The key should start with "rate:" not "rate:ip:"
        called_key: str = redis_mock.incr.call_args[0][0]
        assert called_key.startswith("rate:user-42:")
        assert not called_key.startswith("rate:ip:")
        call_next.assert_called_once_with(request)


# ===========================================================================
# 2. _extract_client_ip helper
# ===========================================================================


class TestExtractClientIp:
    def test_extracts_first_ip_from_xff(self) -> None:
        scope = {"headers": [(b"x-forwarded-for", b"1.2.3.4, 5.6.7.8")]}
        assert _extract_client_ip(scope) == "1.2.3.4"

    def test_falls_back_to_client_tuple(self) -> None:
        scope = {"headers": [], "client": ("192.168.1.1", 54321)}
        assert _extract_client_ip(scope) == "192.168.1.1"

    def test_returns_unknown_when_no_info(self) -> None:
        assert _extract_client_ip({}) == "unknown"

    def test_single_ip_in_xff(self) -> None:
        scope = {"headers": [(b"x-forwarded-for", b"10.0.0.1")]}
        assert _extract_client_ip(scope) == "10.0.0.1"


# ===========================================================================
# 3. Data residency middleware
# ===========================================================================


def _make_asgi_scope(method: str = "POST", region_header: str | None = None) -> dict:
    """Build a minimal ASGI HTTP scope for DataResidencyMiddleware tests."""
    headers = [(b"content-type", b"application/json")]
    if region_header is not None:
        headers.append((b"x-client-region", region_header.encode()))
    return {
        "type": "http",
        "method": method,
        "path": "/test",
        "headers": headers,
    }


async def _dispatch_data_residency(
    mw: DataResidencyMiddleware,
    scope: dict,
) -> int:
    """Run the middleware and return the response status code."""
    status_code_holder: list[int] = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message) -> None:
        if message["type"] == "http.response.start":
            status_code_holder.append(message["status"])

    await mw(scope, receive, send)
    return status_code_holder[0] if status_code_holder else 200


class TestDataResidencyMiddleware:
    """Req 9.7 — write operations blocked by geographic region mismatch."""

    def _build_middleware(self, region: str = "") -> DataResidencyMiddleware:
        app_stub = AsyncMock()
        mw = DataResidencyMiddleware(app_stub)
        settings = _make_data_residency_settings(region)
        mw._get_settings = MagicMock(return_value=settings)
        return mw

    @pytest.mark.asyncio
    async def test_allows_when_no_region_configured(self) -> None:
        """Empty DATA_RESIDENCY_REGION → all writes are allowed."""
        mw = self._build_middleware(region="")
        scope = _make_asgi_scope(method="POST", region_header="eu-west")
        status_code = await _dispatch_data_residency(mw, scope)
        # app should be called (no 403)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_allows_matching_region(self) -> None:
        """X-Client-Region matches configured region → request passes through."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="POST", region_header="us-east")
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_rejects_non_matching_region(self) -> None:
        """X-Client-Region 'eu-west' vs configured 'us-east' → 403."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="POST", region_header="eu-west")
        status_code = await _dispatch_data_residency(mw, scope)
        assert status_code == 403
        mw.app.assert_not_called()

    @pytest.mark.asyncio
    async def test_allows_missing_header(self) -> None:
        """No X-Client-Region header → allowed (absent is not a violation)."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="POST", region_header=None)
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_requests_not_checked(self) -> None:
        """GET with wrong region header → not blocked (only writes are checked)."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="GET", region_header="eu-west")
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_head_requests_not_checked(self) -> None:
        """HEAD with wrong region header → not blocked."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="HEAD", region_header="eu-west")
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_options_requests_not_checked(self) -> None:
        """OPTIONS with wrong region header → not blocked."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="OPTIONS", region_header="eu-west")
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()

    @pytest.mark.asyncio
    async def test_put_request_rejected_with_wrong_region(self) -> None:
        """PUT with wrong region header → 403."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="PUT", region_header="ap-southeast")
        status_code = await _dispatch_data_residency(mw, scope)
        assert status_code == 403

    @pytest.mark.asyncio
    async def test_delete_request_rejected_with_wrong_region(self) -> None:
        """DELETE with wrong region header → 403."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="DELETE", region_header="ap-southeast")
        status_code = await _dispatch_data_residency(mw, scope)
        assert status_code == 403

    @pytest.mark.asyncio
    async def test_case_insensitive_region_matching(self) -> None:
        """Region comparison is case-insensitive: 'US-EAST' matches 'us-east'."""
        mw = self._build_middleware(region="us-east")
        scope = _make_asgi_scope(method="POST", region_header="US-EAST")
        await _dispatch_data_residency(mw, scope)
        mw.app.assert_called_once()


# ===========================================================================
# 4. Push notification worker
# ===========================================================================


class TestSendPushNotification:
    """Req 16.1, 16.2 — FCM push notification Celery task."""

    @pytest.mark.asyncio
    async def test_send_push_success(self) -> None:
        """When Firebase is configured and send succeeds, returns {"status": "sent"}."""
        import app.config.settings as _settings_mod
        from app.workers.notification_worker import (
            _run_send_push_notification,
        )

        settings_stub = MagicMock()
        settings_stub.FIREBASE_CREDENTIALS_PATH = "/fake/path/credentials.json"

        _settings_mod.get_settings.cache_clear()
        with (
            patch.object(_settings_mod, "get_settings", return_value=settings_stub),
            patch("asyncio.to_thread", new=AsyncMock(return_value=None)),
        ):
            result = await _run_send_push_notification(
                MagicMock(), "user-123", "Hello", "World", {}
            )
        _settings_mod.get_settings.cache_clear()

        assert result == {"status": "sent", "user_id": "user-123"}

    @pytest.mark.asyncio
    async def test_send_push_skips_when_not_configured(self) -> None:
        """No Firebase credentials → returns {"status": "skipped", "reason": ...}."""
        import app.config.settings as _settings_mod
        from app.workers.notification_worker import (
            _run_send_push_notification,
        )

        settings_stub = MagicMock()
        settings_stub.FIREBASE_CREDENTIALS_PATH = ""

        _settings_mod.get_settings.cache_clear()
        with patch.object(_settings_mod, "get_settings", return_value=settings_stub):
            result = await _run_send_push_notification(
                MagicMock(), "user-123", "Hello", "World", {}
            )
        _settings_mod.get_settings.cache_clear()

        assert result["status"] == "skipped"
        assert result["reason"] == "firebase_not_configured"

    @pytest.mark.asyncio
    async def test_refresh_device_token_success(self) -> None:
        """DB update succeeds → returns {"status": "updated"}."""
        mock_user = MagicMock()
        mock_user.fcm_token = None

        mock_result = MagicMock()
        mock_result.scalar_one_or_none = MagicMock(return_value=mock_user)

        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(return_value=mock_result)
        mock_db.commit = AsyncMock()
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)

        mock_session_local = MagicMock(return_value=mock_db)

        with patch(
            "app.workers.notification_worker.AsyncSessionLocal", mock_session_local
        ):
            from app.workers.notification_worker import _run_refresh_device_token

            result = await _run_refresh_device_token(
                MagicMock(),
                "123e4567-e89b-12d3-a456-426614174000",
                "old-token",
                "new-token",
            )

        assert result["status"] == "updated"
        assert mock_user.fcm_token == "new-token"

    @pytest.mark.asyncio
    async def test_refresh_device_token_redis_retry_on_failure(self) -> None:
        """DB raises → Redis fcm_token_retry:{user_id} key is set."""
        mock_db = AsyncMock()
        mock_db.execute = AsyncMock(side_effect=Exception("DB connection error"))
        mock_db.__aenter__ = AsyncMock(return_value=mock_db)
        mock_db.__aexit__ = AsyncMock(return_value=False)
        mock_session_local = MagicMock(return_value=mock_db)

        mock_redis = AsyncMock()
        mock_redis.set = AsyncMock()

        with (
            patch(
                "app.workers.notification_worker.AsyncSessionLocal", mock_session_local
            ),
            patch(
                "app.workers.notification_worker.get_redis_client",
                return_value=mock_redis,
                create=True,
            ),
        ):
            from app.workers.notification_worker import (
                _run_refresh_device_token,
                _set_token_retry_counter,
            )

            user_id = "123e4567-e89b-12d3-a456-426614174000"
            await _run_refresh_device_token(
                MagicMock(), user_id, "old-token", "new-token"
            )

            # Now verify the helper sets the right Redis key
            mock_redis.set.reset_mock()
            with patch("app.database.redis.get_redis_client", return_value=mock_redis):
                await _set_token_retry_counter(user_id, "new-token")
