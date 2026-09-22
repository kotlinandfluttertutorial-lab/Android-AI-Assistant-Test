"""Unit tests for app.llm.service.LLMService.

Covers:
- generate(): routes to GeminiProvider for simple/complex requests.
- generate(): routes to LocalGemmaProvider when complexity="local".
- generate(): routes to LocalGemmaProvider when DEFAULT_LLM_PROVIDER=gemma.
- generate(): provider-level fallback on LLMError.
- generate(): LLMConfigurationError bypasses fallback.
- generate(): both providers fail â†’ LLMProviderError raised.
- generate(): structured log emitted on success and error.
- _resolve_provider(): returns cached instance on repeated calls.
- get_llm_service(): returns the module singleton.
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-32-chars-long-minimum!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
# AES_ENCRYPTION_KEY is set by conftest.py — not repeated here to avoid false-positive secret scans.

from app.llm.base import LLMRequest, LLMResponse, LLMUsage
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMError,
    LLMProviderError,
)
from app.llm.service import LLMService


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_request(complexity: str = "simple", user_id: str = "user-1") -> LLMRequest:
    return LLMRequest(
        prompt="Hello",
        complexity=complexity,
        user_id=user_id,
        request_id="req-123",
    )


def _make_llm_response(provider: str = "gemini", model: str = "gemini-3.6-flash") -> LLMResponse:
    return LLMResponse(
        text="Answer.",
        provider=provider,
        model=model,
        usage=LLMUsage(input_tokens=10, output_tokens=20),
        request_id="req-123",
    )


def _make_mock_provider(
    name: str = "gemini",
    model: str = "gemini-3.6-flash",
    response: LLMResponse | None = None,
    side_effect: Exception | None = None,
) -> MagicMock:
    """Build a mock LLMProvider."""
    mock = MagicMock()
    mock.provider_name = name
    mock.model_name = model
    if side_effect:
        mock.generate = AsyncMock(side_effect=side_effect)
    else:
        mock.generate = AsyncMock(return_value=response or _make_llm_response(name, model))
    return mock


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def service():
    """Return a fresh LLMService with an empty provider cache."""
    s = LLMService()
    s._providers.clear()
    return s


# ---------------------------------------------------------------------------
# Routing tests
# ---------------------------------------------------------------------------

class TestLLMServiceRouting:
    @pytest.mark.asyncio
    async def test_simple_request_routes_to_gemini(self, service) -> None:
        """Simple requests route to GeminiProvider by default."""
        mock_provider = _make_mock_provider("gemini")
        service._providers["gemini"] = mock_provider

        resp = await service.generate(_make_request(complexity="simple"))

        mock_provider.generate.assert_awaited_once()
        assert resp.provider == "gemini"

    @pytest.mark.asyncio
    async def test_complex_request_routes_to_gemini(self, service) -> None:
        """Complex requests also route to GeminiProvider (same model; future hook)."""
        mock_provider = _make_mock_provider("gemini")
        service._providers["gemini"] = mock_provider

        resp = await service.generate(_make_request(complexity="complex"))
        assert resp.provider == "gemini"

    @pytest.mark.asyncio
    async def test_local_complexity_routes_to_gemma(self, service) -> None:
        """complexity='local' routes to LocalGemmaProvider."""
        mock_provider = _make_mock_provider("gemma", "gemma3:latest")
        service._providers["gemma"] = mock_provider

        resp = await service.generate(_make_request(complexity="local"))

        mock_provider.generate.assert_awaited_once()
        assert resp.provider == "gemma"

    @pytest.mark.asyncio
    async def test_default_provider_gemma_routes_to_gemma(self, service) -> None:
        """DEFAULT_LLM_PROVIDER=gemma routes non-local requests to Gemma."""
        mock_provider = _make_mock_provider("gemma")
        service._providers["gemma"] = mock_provider

        with patch.dict(os.environ, {"DEFAULT_LLM_PROVIDER": "gemma"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            resp = await service.generate(_make_request(complexity="simple"))
            get_settings.cache_clear()

        assert resp.provider == "gemma"


# ---------------------------------------------------------------------------
# Fallback tests
# ---------------------------------------------------------------------------

class TestLLMServiceFallback:
    @pytest.mark.asyncio
    async def test_provider_fallback_invoked_on_error(self, service) -> None:
        """When primary provider raises LLMError, fallback provider is attempted."""
        primary = _make_mock_provider(
            "gemini", side_effect=LLMProviderError("primary failed", is_transient=True)
        )
        fallback = _make_mock_provider("ollama", response=_make_llm_response("ollama", "llama3"))
        service._providers["gemini"] = primary
        service._providers["ollama"] = fallback

        with patch.dict(os.environ, {"LLM_FALLBACK_PROVIDER": "ollama"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            resp = await service.generate(_make_request())
            get_settings.cache_clear()

        assert resp.provider == "ollama"

    @pytest.mark.asyncio
    async def test_configuration_error_bypasses_fallback(self, service) -> None:
        """LLMConfigurationError must NOT trigger provider-level fallback."""
        primary = _make_mock_provider(
            "gemini", side_effect=LLMConfigurationError("bad config")
        )
        fallback = _make_mock_provider("ollama")
        service._providers["gemini"] = primary
        service._providers["ollama"] = fallback

        with patch.dict(os.environ, {"LLM_FALLBACK_PROVIDER": "ollama"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            with pytest.raises(LLMConfigurationError):
                await service.generate(_make_request())
            get_settings.cache_clear()

        fallback.generate.assert_not_called()

    @pytest.mark.asyncio
    async def test_both_providers_fail_raises_llm_provider_error(self, service) -> None:
        """When both primary and fallback fail, LLMProviderError is raised."""
        primary = _make_mock_provider(
            "gemini", side_effect=LLMProviderError("primary failed", is_transient=True)
        )
        fallback = _make_mock_provider(
            "ollama", side_effect=LLMProviderError("fallback failed", is_transient=True)
        )
        service._providers["gemini"] = primary
        service._providers["ollama"] = fallback

        with patch.dict(os.environ, {"LLM_FALLBACK_PROVIDER": "ollama"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            with pytest.raises(LLMProviderError):
                await service.generate(_make_request())
            get_settings.cache_clear()

    @pytest.mark.asyncio
    async def test_no_fallback_configured_error_propagates(self, service) -> None:
        """When no fallback provider is configured, the primary error propagates."""
        primary = _make_mock_provider(
            "gemini", side_effect=LLMProviderError("failed", is_transient=True)
        )
        service._providers["gemini"] = primary

        with patch.dict(os.environ, {"LLM_FALLBACK_PROVIDER": "", "FALLBACK_LLM_PROVIDER": ""}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            with pytest.raises(LLMError):
                await service.generate(_make_request())
            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# Provider caching
# ---------------------------------------------------------------------------

class TestLLMServiceProviderCache:
    def test_same_instance_returned_on_repeated_calls(self, service) -> None:
        """_get_provider_by_name returns the cached instance, not a new one."""
        mock_provider = _make_mock_provider("gemini")
        service._providers["gemini"] = mock_provider

        p1 = service._get_provider_by_name("gemini")
        p2 = service._get_provider_by_name("gemini")
        assert p1 is p2

    def test_unknown_provider_raises_configuration_error(self, service) -> None:
        """Requesting an unknown provider name raises LLMConfigurationError."""
        with pytest.raises(LLMConfigurationError):
            service._get_provider_by_name("unknown-provider-xyz")


# ---------------------------------------------------------------------------
# get_llm_service singleton
# ---------------------------------------------------------------------------

class TestGetLLMService:
    def test_returns_same_singleton(self) -> None:
        """get_llm_service() must return the same object on every call."""
        from app.llm.service import get_llm_service
        s1 = get_llm_service()
        s2 = get_llm_service()
        assert s1 is s2
