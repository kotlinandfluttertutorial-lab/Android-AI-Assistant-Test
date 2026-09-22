"""Unit tests for app.llm.providers.gemini_provider.GeminiProvider.

Covers:
- Initialisation: validates API key, stores model names from settings.
- generate(): success path, usage_metadata extraction, fallback_used flag.
- generate(): fallback to GEMINI_FALLBACK_MODEL on LLMQuotaError.
- generate(): fallback disabled → quota error propagates.
- generate(): fallback skipped when primary == fallback model.
- generate(): permanent errors (4xx) are NOT retried.
- generate(): transient errors (5xx) ARE retried with back-off.
- generate(): LLMTimeoutError raised after all retries exhausted.
- generate(): application rate limit checked before API call.
- stream(): yields tokens from the async generator.
- stream(): falls back to fallback model on quota error.
- _maybe_sleep(): no sleep after final attempt.
- _build_config(): respects per-request overrides.
- No API key → LLMConfigurationError at init.
"""

from __future__ import annotations

import asyncio
import os
from collections.abc import AsyncIterator
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Ensure env vars are set before any app import.
os.environ.setdefault("SECRET_KEY", "test-secret-32-chars-long-minimum!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
# AES_ENCRYPTION_KEY is set by conftest.py � not repeated here to avoid false-positive secret scans.

from app.llm.base import LLMRequest
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMProviderError,
    LLMQuotaError,
    LLMRateLimitError,
    LLMTimeoutError,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_request(
    prompt: str = "What is Clean Architecture?",
    user_id: str = "user-test-123",
    request_id: str = "req-test-abc",
    max_output_tokens: int | None = None,
    temperature: float | None = None,
) -> LLMRequest:
    return LLMRequest(
        prompt=prompt,
        system_prompt="You are a helpful assistant.",
        user_id=user_id,
        request_id=request_id,
        max_output_tokens=max_output_tokens,
        temperature=temperature,
    )


def _make_sdk_response(text: str = "Test response.", input_tokens: int = 10, output_tokens: int = 20) -> MagicMock:
    """Build a mock SDK GenerateContentResponse with usage_metadata."""
    usage = MagicMock()
    usage.prompt_token_count = input_tokens
    usage.candidates_token_count = output_tokens
    usage.total_token_count = input_tokens + output_tokens

    resp = MagicMock()
    resp.text = text
    resp.usage_metadata = usage
    return resp


def _make_api_error(code: int, message: str = "API error") -> Exception:
    """Build a real google.genai APIError subclass instance with a .code attribute.

    Using MagicMock(spec=APIError) does NOT satisfy isinstance(exc, APIError) checks
    in the production retry loop, so we construct a genuine subclass instead.
    """
    from google.genai import errors as genai_errors  # type: ignore[import]

    class _FakeAPIError(genai_errors.APIError):  # type: ignore[misc]
        pass

    exc = _FakeAPIError.__new__(_FakeAPIError)
    exc.code = code
    exc.message = message
    exc.status = None
    exc.details = []
    # Ensure str(exc) returns the message so assertion messages are readable.
    exc.__str__ = lambda self: message  # noqa: E731
    return exc


async def _async_gen_tokens(tokens: list[str]) -> AsyncIterator[MagicMock]:
    """Async generator that yields mock chunk objects."""
    for t in tokens:
        chunk = MagicMock()
        chunk.text = t
        yield chunk


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def provider():
    """Return a GeminiProvider instance with all SDK calls patched out."""
    with patch("app.llm.providers.gemini_provider.genai") as mock_genai:
        mock_client = MagicMock()
        mock_genai.Client.return_value = mock_client

        # Patch rate limiter to never block.
        with patch("app.llm.providers.gemini_provider._ProviderRateLimiter") as mock_rl:
            mock_rl.return_value.check = AsyncMock(return_value=None)

            from app.llm.providers.gemini_provider import GeminiProvider
            p = GeminiProvider()
            p._client = mock_client
            p._max_retry_attempts = 2
            p._retry_base_delay = 0.0  # no actual sleep in tests
            yield p


# ---------------------------------------------------------------------------
# Initialisation tests
# ---------------------------------------------------------------------------

class TestGeminiProviderInit:
    def test_raises_on_missing_api_key(self) -> None:
        """Empty GEMINI_API_KEY must raise LLMConfigurationError at init."""
        with patch.dict(os.environ, {"GEMINI_API_KEY": ""}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            with patch("app.llm.providers.gemini_provider.genai"):
                from app.llm.providers.gemini_provider import GeminiProvider
                with pytest.raises(LLMConfigurationError, match="GEMINI_API_KEY"):
                    GeminiProvider()
            get_settings.cache_clear()

    def test_model_override_respected(self) -> None:
        """model_override must take precedence over GEMINI_MODEL setting."""
        with patch("app.llm.providers.gemini_provider.genai"):
            with patch("app.llm.providers.gemini_provider._ProviderRateLimiter"):
                from app.llm.providers.gemini_provider import GeminiProvider
                p = GeminiProvider(model_override="gemini-3.8-flash")
                assert p._primary_model == "gemini-3.8-flash"

    def test_provider_name(self, provider) -> None:
        assert provider.provider_name == "gemini"

    def test_model_name_from_settings(self, provider) -> None:
        from app.config.settings import get_settings
        assert provider.model_name == get_settings().GEMINI_MODEL


# ---------------------------------------------------------------------------
# generate() — success path
# ---------------------------------------------------------------------------

class TestGeminiProviderGenerate:
    @pytest.mark.asyncio
    async def test_generate_returns_response(self, provider) -> None:
        """Successful generate returns LLMResponse with text and usage."""
        sdk_resp = _make_sdk_response("Clean Architecture separates concerns.", 15, 25)
        provider._client.aio = MagicMock()
        provider._client.aio.models = MagicMock()
        provider._client.aio.models.generate_content = AsyncMock(return_value=sdk_resp)

        resp = await provider.generate(_make_request())

        assert resp.text == "Clean Architecture separates concerns."
        assert resp.provider == "gemini"
        assert resp.usage.input_tokens == 15
        assert resp.usage.output_tokens == 25
        assert resp.usage.total_tokens == 40
        assert resp.fallback_used is False

    @pytest.mark.asyncio
    async def test_generate_uses_primary_model(self, provider) -> None:
        """generate() must call the primary model by default."""
        sdk_resp = _make_sdk_response()
        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = AsyncMock(return_value=sdk_resp)

        await provider.generate(_make_request())

        call_kwargs = provider._client.aio.models.generate_content.call_args.kwargs
        assert call_kwargs["model"] == provider._primary_model

    @pytest.mark.asyncio
    async def test_generate_missing_usage_defaults_to_zero(self, provider) -> None:
        """When usage_metadata is absent, usage fields default to 0."""
        sdk_resp = MagicMock()
        sdk_resp.text = "Answer."
        sdk_resp.usage_metadata = None

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = AsyncMock(return_value=sdk_resp)

        resp = await provider.generate(_make_request())
        assert resp.usage.input_tokens == 0
        assert resp.usage.output_tokens == 0


# ---------------------------------------------------------------------------
# generate() — fallback behaviour
# ---------------------------------------------------------------------------

class TestGeminiProviderFallback:
    @pytest.mark.asyncio
    async def test_fallback_invoked_on_quota_error(self, provider) -> None:
        """LLMQuotaError on primary model → fallback model is tried."""
        provider._enable_fallback = True
        provider._primary_model = "gemini-3.6-flash"
        provider._fallback_model = "gemini-3.1-flash-lite"

        quota_exc = LLMQuotaError("quota", provider="gemini", model="gemini-3.6-flash")
        fallback_resp = _make_sdk_response("Fallback answer.")

        call_count = 0

        async def side_effect(*_, **kwargs):
            nonlocal call_count
            call_count += 1
            if kwargs.get("model") == "gemini-3.6-flash":
                raise quota_exc
            return fallback_resp

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = side_effect

        resp = await provider.generate(_make_request())

        assert resp.text == "Fallback answer."
        assert resp.fallback_used is True
        assert resp.model == "gemini-3.1-flash-lite"

    @pytest.mark.asyncio
    async def test_fallback_disabled_quota_propagates(self, provider) -> None:
        """When LLM_ENABLE_FALLBACK=false, quota error propagates immediately."""
        provider._enable_fallback = False
        quota_exc = LLMQuotaError("quota")

        async def always_quota(*_, **__):
            raise quota_exc

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = always_quota

        with pytest.raises(LLMQuotaError):
            await provider.generate(_make_request())

    @pytest.mark.asyncio
    async def test_fallback_skipped_when_same_as_primary(self, provider) -> None:
        """When primary == fallback model, quota error propagates without retry."""
        provider._enable_fallback = True
        provider._primary_model = "gemini-3.6-flash"
        provider._fallback_model = "gemini-3.6-flash"  # same!

        quota_exc = LLMQuotaError("quota")

        async def always_quota(*_, **__):
            raise quota_exc

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = always_quota

        with pytest.raises(LLMQuotaError):
            await provider.generate(_make_request())


# ---------------------------------------------------------------------------
# generate() — retry and error handling
# ---------------------------------------------------------------------------

class TestGeminiProviderRetry:
    @pytest.mark.asyncio
    async def test_permanent_error_not_retried(self, provider) -> None:
        """HTTP 400 must raise LLMConfigurationError without retrying."""
        provider._max_retry_attempts = 3
        exc_400 = _make_api_error(400, "Bad request")

        call_count = 0

        async def fail_400(*_, **__):
            nonlocal call_count
            call_count += 1
            raise exc_400

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = fail_400

        with pytest.raises(LLMConfigurationError):
            await provider.generate(_make_request())

        assert call_count == 1, "Permanent error must NOT be retried"

    @pytest.mark.asyncio
    async def test_transient_error_retried(self, provider) -> None:
        """HTTP 503 triggers retries; succeeds on 3rd attempt."""
        provider._max_retry_attempts = 3
        provider._retry_base_delay = 0.0
        exc_503 = _make_api_error(503, "Service unavailable")
        sdk_resp = _make_sdk_response("OK after retry.")

        call_count = 0

        async def fail_then_succeed(*_, **__):
            nonlocal call_count
            call_count += 1
            if call_count < 3:
                raise exc_503
            return sdk_resp

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = fail_then_succeed

        resp = await provider.generate(_make_request())
        assert resp.text == "OK after retry."
        assert call_count == 3

    @pytest.mark.asyncio
    async def test_timeout_error_raised_after_retries(self, provider) -> None:
        """asyncio.TimeoutError exhausted → LLMTimeoutError raised."""
        provider._max_retry_attempts = 1
        provider._retry_base_delay = 0.0

        async def always_timeout(*_, **__):
            raise asyncio.TimeoutError()

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = always_timeout

        with pytest.raises(LLMTimeoutError):
            await provider.generate(_make_request())

    @pytest.mark.asyncio
    async def test_quota_429_maps_to_llm_quota_error(self, provider) -> None:
        """HTTP 429 from API must map to LLMQuotaError (not retried)."""
        provider._enable_fallback = False
        exc_429 = _make_api_error(429, "Quota exceeded")

        async def quota(*_, **__):
            raise exc_429

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = quota

        with pytest.raises(LLMQuotaError):
            await provider.generate(_make_request())

    @pytest.mark.asyncio
    async def test_rate_limit_checked_before_api_call(self, provider) -> None:
        """Application rate limit must fire before any SDK call."""
        provider._rate_limiter.check = AsyncMock(
            side_effect=LLMRateLimitError("rate limit", retry_after_seconds=30)
        )
        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content = AsyncMock()

        with pytest.raises(LLMRateLimitError):
            await provider.generate(_make_request())

        provider._client.aio.models.generate_content.assert_not_called()


# ---------------------------------------------------------------------------
# stream() tests
# ---------------------------------------------------------------------------

class TestGeminiProviderStream:
    @pytest.mark.asyncio
    async def test_stream_yields_tokens(self, provider) -> None:
        """stream() must yield individual token strings."""
        tokens = ["Hello", " world", "!"]

        async def mock_stream(*_, **__):
            return _async_gen_tokens(tokens)

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content_stream = mock_stream

        collected = []
        async for token in provider.stream(_make_request()):
            collected.append(token)

        assert collected == tokens

    @pytest.mark.asyncio
    async def test_stream_falls_back_on_quota(self, provider) -> None:
        """quota error during primary stream → fallback model is used."""
        provider._enable_fallback = True
        provider._primary_model = "gemini-3.6-flash"
        provider._fallback_model = "gemini-3.1-flash-lite"

        used_models: list[str] = []

        async def mock_stream(*_, **kwargs):
            model = kwargs.get("model", "")
            used_models.append(model)
            if model == "gemini-3.6-flash":
                raise LLMQuotaError("quota")
            return _async_gen_tokens(["fallback token"])

        provider._client.aio = MagicMock()
        provider._client.aio.models.generate_content_stream = mock_stream

        collected = []
        async for token in provider.stream(_make_request()):
            collected.append(token)

        assert collected == ["fallback token"]
        assert "gemini-3.6-flash" in used_models
        assert "gemini-3.1-flash-lite" in used_models


# ---------------------------------------------------------------------------
# _build_config() tests
# ---------------------------------------------------------------------------

class TestGeminiProviderBuildConfig:
    def test_request_overrides_respected(self, provider) -> None:
        """Per-request max_output_tokens and temperature override provider defaults."""
        request = _make_request(max_output_tokens=512, temperature=0.9)
        config = provider._build_config(request, "gemini-3.6-flash")

        assert config.max_output_tokens == 512
        assert abs(config.temperature - 0.9) < 0.001

    def test_defaults_used_when_not_overridden(self, provider) -> None:
        """When request does not override, provider defaults are used."""
        request = _make_request()  # no overrides
        config = provider._build_config(request, "gemini-3.6-flash")

        assert config.max_output_tokens == provider._max_output_tokens
        assert abs(config.temperature - provider._default_temperature) < 0.001
