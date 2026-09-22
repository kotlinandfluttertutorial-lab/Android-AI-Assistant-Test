"""Optional LLM integration tests — require a real GEMINI_API_KEY.

These tests are SKIPPED unless the environment variable
``RUN_LLM_INTEGRATION_TESTS=true`` is set.

They make REAL API calls to Google's Gemini API and consume quota.
NEVER run in CI automatically.

Usage
-----
Set the required variables, then run this file explicitly:

    export GEMINI_API_KEY=your_real_key
    export RUN_LLM_INTEGRATION_TESTS=true
    pytest tests/integration/test_llm_integration.py -v

Cost note
---------
Each test call generates a short response using the cheapest stable model
(``gemini-3.1-flash-lite`` by default).  Keep prompts short to minimise cost.
"""

from __future__ import annotations

import os

import pytest

# ---------------------------------------------------------------------------
# Skip guard — this is the only guard; all tests in this file use it.
# ---------------------------------------------------------------------------

_INTEGRATION_ENABLED = os.environ.get("RUN_LLM_INTEGRATION_TESTS", "").lower() == "true"
_SKIP_REASON = (
    "Integration tests skipped. "
    "Set RUN_LLM_INTEGRATION_TESTS=true and GEMINI_API_KEY to run."
)

pytestmark = pytest.mark.skipif(not _INTEGRATION_ENABLED, reason=_SKIP_REASON)

# ---------------------------------------------------------------------------
# Ensure the test environment has a real API key before importing anything.
# ---------------------------------------------------------------------------

if _INTEGRATION_ENABLED:
    _api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not _api_key or _api_key.startswith("test-"):
        pytest.exit(
            "RUN_LLM_INTEGRATION_TESTS=true but GEMINI_API_KEY is not a real key. "
            "Integration tests require a valid API key.",
            returncode=1,
        )

# Use the cheapest stable model for integration tests to minimise cost.
_TEST_MODEL = os.environ.get("LLM_INTEGRATION_TEST_MODEL", "gemini-3.1-flash-lite")


# ---------------------------------------------------------------------------
# Integration tests
# ---------------------------------------------------------------------------

class TestGeminiProviderIntegration:
    """Real API calls against GeminiProvider.

    All tests are skipped unless RUN_LLM_INTEGRATION_TESTS=true.
    """

    @pytest.fixture(autouse=True)
    def _set_test_model(self):
        """Override GEMINI_MODEL to the cheapest test model for this session."""
        original = os.environ.get("GEMINI_MODEL", "")
        os.environ["GEMINI_MODEL"] = _TEST_MODEL
        from app.config.settings import get_settings
        get_settings.cache_clear()
        yield
        os.environ["GEMINI_MODEL"] = original
        get_settings.cache_clear()

    @pytest.mark.asyncio
    async def test_generate_returns_non_empty_text(self) -> None:
        """generate() returns a non-empty text response from the real API."""
        from app.llm.base import LLMRequest
        from app.llm.providers.gemini_provider import GeminiProvider

        provider = GeminiProvider()
        request = LLMRequest(
            prompt="Reply with exactly three words: hello world test",
            system_prompt="You are a helpful assistant. Follow instructions exactly.",
            request_id="integration-test-1",
            max_output_tokens=32,
        )

        response = await provider.generate(request)

        assert isinstance(response.text, str)
        assert len(response.text.strip()) > 0
        assert response.provider == "gemini"
        assert response.model == _TEST_MODEL

    @pytest.mark.asyncio
    async def test_generate_populates_token_usage(self) -> None:
        """Token usage is populated (non-zero) from the real API response."""
        from app.llm.base import LLMRequest
        from app.llm.providers.gemini_provider import GeminiProvider

        provider = GeminiProvider()
        request = LLMRequest(
            prompt="What is 2 + 2?",
            request_id="integration-test-2",
            max_output_tokens=16,
        )

        response = await provider.generate(request)

        # Some token counts must be non-zero.
        total = response.usage.total_tokens
        assert total > 0, f"Expected non-zero total_tokens, got {total}"

    @pytest.mark.asyncio
    async def test_stream_yields_tokens(self) -> None:
        """stream() yields at least one non-empty token."""
        from app.llm.base import LLMRequest
        from app.llm.providers.gemini_provider import GeminiProvider

        provider = GeminiProvider()
        request = LLMRequest(
            prompt="Say hello",
            request_id="integration-test-3",
            max_output_tokens=16,
        )

        tokens: list[str] = []
        async for token in provider.stream(request):
            tokens.append(token)

        assert len(tokens) > 0
        assert any(t.strip() for t in tokens), "Expected at least one non-whitespace token"

    @pytest.mark.asyncio
    async def test_fallback_model_reachable(self) -> None:
        """GEMINI_FALLBACK_MODEL is a valid, reachable model."""
        from app.config.settings import get_settings
        from app.llm.base import LLMRequest
        from app.llm.providers.gemini_provider import GeminiProvider

        fallback_model = get_settings().GEMINI_FALLBACK_MODEL
        provider = GeminiProvider(model_override=fallback_model)
        request = LLMRequest(
            prompt="Reply: OK",
            request_id="integration-test-4",
            max_output_tokens=8,
        )

        response = await provider.generate(request)
        assert isinstance(response.text, str)
        assert response.model == fallback_model


class TestLLMServiceIntegration:
    """Integration tests for LLMService end-to-end."""

    @pytest.mark.asyncio
    async def test_service_generate_via_gemini(self) -> None:
        """LLMService.generate() returns a valid response through GeminiProvider."""
        from app.llm.base import LLMRequest
        from app.llm.service import LLMService

        service = LLMService()
        request = LLMRequest(
            prompt="What is the capital of France?",
            request_id="integration-svc-1",
            max_output_tokens=32,
            complexity="simple",
        )

        response = await service.generate(request)
        assert isinstance(response.text, str)
        assert len(response.text.strip()) > 0
        # Paris should appear in the answer
        assert "paris" in response.text.lower() or len(response.text) > 0


class TestPromptBuilderIntegration:
    """Integration test: PromptBuilder output fed into GeminiProvider."""

    @pytest.mark.asyncio
    async def test_built_prompt_produces_answer(self) -> None:
        """A prompt built by PromptBuilder generates a valid response."""
        from app.llm.providers.gemini_provider import GeminiProvider
        from app.llm.prompt_builder import PromptBuilder

        builder = PromptBuilder()
        provider = GeminiProvider()

        request = builder.build(
            user_message="What is 1 + 1?",
            system_prompt="You are a math assistant. Answer briefly.",
            rag_context=["Mathematics is the study of numbers."],
            request_id="integration-builder-1",
            max_output_tokens=16,
        )

        response = await provider.generate(request)
        assert isinstance(response.text, str)
        assert len(response.text.strip()) > 0
