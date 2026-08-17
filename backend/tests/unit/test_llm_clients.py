"""Unit tests for app.services.llm_clients.

Covers:
- BaseLLMClient interface (abstract contract and convenience methods)
- _ProviderRateLimiter: rate limiting via Redis, fail-open on Redis error
- RateLimitError attributes
- OpenAIClient / GeminiClient / ClaudeClient: provider name, pricing, context window
- OllamaClient: local-only URL enforcement, provider name, zero pricing
- LlamaClient / MistralClient: correct model and provider names
- get_provider_name(), get_max_context_tokens(), get_cost_per_token() on all six

Requirements: 3.1, 3.3, 3.4, 3.5, 3.6
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Set required env vars before any app import
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

from app.services.llm_clients import (
    BaseLLMClient,
    ClaudeClient,
    GeminiClient,
    LlamaClient,
    MistralClient,
    OllamaClient,
    OpenAIClient,
    PromptContext,
    RateLimitError,
    _ProviderRateLimiter,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

SAMPLE_CONTEXT = PromptContext(
    system_prompt="You are a helpful assistant.",
    messages=[("user", "Hello!")],
    max_tokens=256,
    temperature=0.7,
    user_id="test-user-123",
)


class _FakeClient(BaseLLMClient):
    """Minimal concrete subclass for testing the base-class helpers."""

    def __init__(self) -> None:
        from app.services.llm_clients import _ProviderRateLimiter

        self._rate_limiter = _ProviderRateLimiter("fake", 0)

    def get_provider_name(self) -> str:
        return "fake"

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        yield "token"  # pragma: no cover

    async def complete(self, context: PromptContext) -> str:
        return "response"  # pragma: no cover

    @property
    def max_context_tokens(self) -> int:
        return 8192

    @property
    def max_output_tokens(self) -> int:
        return 2048

    @property
    def cost_per_input_token(self) -> Decimal:
        return Decimal("0.000001")

    @property
    def cost_per_output_token(self) -> Decimal:
        return Decimal("0.000002")


# ---------------------------------------------------------------------------
# RateLimitError
# ---------------------------------------------------------------------------


class TestRateLimitError:
    def test_attributes(self) -> None:
        err = RateLimitError("openai", 42)
        assert err.provider == "openai"
        assert err.retry_after == 42

    def test_message_includes_provider(self) -> None:
        err = RateLimitError("claude", 5)
        assert "claude" in str(err)
        assert "5" in str(err)


# ---------------------------------------------------------------------------
# BaseLLMClient convenience methods
# ---------------------------------------------------------------------------


class TestBaseLLMClientHelpers:
    def setup_method(self) -> None:
        self.client = _FakeClient()

    def test_get_max_context_tokens_delegates_property(self) -> None:
        assert self.client.get_max_context_tokens() == 8192

    def test_get_cost_per_token_returns_dict(self) -> None:
        costs = self.client.get_cost_per_token()
        assert "input" in costs
        assert "output" in costs
        assert isinstance(costs["input"], Decimal)
        assert isinstance(costs["output"], Decimal)

    def test_get_cost_per_token_values_match_properties(self) -> None:
        costs = self.client.get_cost_per_token()
        assert costs["input"] == self.client.cost_per_input_token
        assert costs["output"] == self.client.cost_per_output_token

    def test_get_provider_name(self) -> None:
        assert self.client.get_provider_name() == "fake"


# ---------------------------------------------------------------------------
# _ProviderRateLimiter
# ---------------------------------------------------------------------------


class TestProviderRateLimiter:
    @pytest.mark.asyncio
    async def test_unlimited_when_limit_is_zero(self) -> None:
        """Limit=0 means unlimited; no Redis call should be made."""
        limiter = _ProviderRateLimiter("test_provider", 0)
        # Should not raise and should not contact Redis
        with patch(
            "app.services.llm_clients._ProviderRateLimiter._get_redis"
        ) as mock_redis:
            await limiter.check("user-abc")
        mock_redis.assert_not_called()

    @pytest.mark.asyncio
    async def test_unlimited_when_user_id_is_none(self) -> None:
        """user_id=None means internal/background call; rate limit is skipped."""
        limiter = _ProviderRateLimiter("test_provider", 10)
        with patch(
            "app.services.llm_clients._ProviderRateLimiter._get_redis"
        ) as mock_redis:
            await limiter.check(None)
        mock_redis.assert_not_called()

    @pytest.mark.asyncio
    async def test_allows_request_within_limit(self) -> None:
        """Counter below limit → no exception raised."""
        limiter = _ProviderRateLimiter("openai", 60)

        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=10)  # well below 60
        mock_redis.expire = AsyncMock()

        with patch.object(limiter, "_get_redis", AsyncMock(return_value=mock_redis)):
            await limiter.check("user-abc")  # should not raise

    @pytest.mark.asyncio
    async def test_raises_rate_limit_error_when_exceeded(self) -> None:
        """Counter exceeds limit → RateLimitError raised."""
        limiter = _ProviderRateLimiter("openai", 60)

        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=61)  # one over limit
        mock_redis.expire = AsyncMock()

        with patch.object(limiter, "_get_redis", AsyncMock(return_value=mock_redis)):
            with pytest.raises(RateLimitError) as exc_info:
                await limiter.check("user-abc")

        assert exc_info.value.provider == "openai"
        assert exc_info.value.retry_after > 0

    @pytest.mark.asyncio
    async def test_fail_open_when_redis_unavailable(self) -> None:
        """Redis connection error → request proceeds (fail-open)."""
        limiter = _ProviderRateLimiter("openai", 60)

        async def broken_redis():
            raise ConnectionError("Redis down")

        with patch.object(limiter, "_get_redis", broken_redis):
            # Must not raise — fail-open behaviour
            await limiter.check("user-abc")

    @pytest.mark.asyncio
    async def test_sets_ttl_on_first_request(self) -> None:
        """First request in a window (count==1) must set the TTL on the key."""
        limiter = _ProviderRateLimiter("gemini", 60)

        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=1)  # first request
        mock_redis.expire = AsyncMock()

        with patch.object(limiter, "_get_redis", AsyncMock(return_value=mock_redis)):
            await limiter.check("user-xyz")

        mock_redis.expire.assert_called_once()
        # TTL argument should be 120 seconds
        _, ttl_arg = mock_redis.expire.call_args.args
        assert ttl_arg == 120

    @pytest.mark.asyncio
    async def test_does_not_set_ttl_on_subsequent_requests(self) -> None:
        """Subsequent requests within the same window must NOT reset TTL."""
        limiter = _ProviderRateLimiter("gemini", 60)

        mock_redis = AsyncMock()
        mock_redis.incr = AsyncMock(return_value=5)  # not first request
        mock_redis.expire = AsyncMock()

        with patch.object(limiter, "_get_redis", AsyncMock(return_value=mock_redis)):
            await limiter.check("user-xyz")

        mock_redis.expire.assert_not_called()


# ---------------------------------------------------------------------------
# OpenAIClient
# ---------------------------------------------------------------------------


class TestOpenAIClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.AsyncOpenAI"):
            self.client = OpenAIClient()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "openai"

    def test_max_context_tokens(self) -> None:
        assert self.client.max_context_tokens == 128_000

    def test_get_max_context_tokens_method(self) -> None:
        assert self.client.get_max_context_tokens() == 128_000

    def test_cost_per_input_token(self) -> None:
        assert self.client.cost_per_input_token == Decimal("0.000005")

    def test_cost_per_output_token(self) -> None:
        assert self.client.cost_per_output_token == Decimal("0.000015")

    def test_get_cost_per_token(self) -> None:
        costs = self.client.get_cost_per_token()
        assert costs["input"] == Decimal("0.000005")
        assert costs["output"] == Decimal("0.000015")

    def test_output_more_expensive_than_input(self) -> None:
        assert self.client.cost_per_output_token > self.client.cost_per_input_token

    @pytest.mark.asyncio
    async def test_stream_enforces_rate_limit(self) -> None:
        """stream() must call rate_limiter.check() before the API call."""
        mock_check = AsyncMock(side_effect=RateLimitError("openai", 10))
        self.client._rate_limiter.check = mock_check

        with pytest.raises(RateLimitError):
            async for _ in self.client.stream(SAMPLE_CONTEXT):
                pass  # pragma: no cover

        mock_check.assert_awaited_once_with(SAMPLE_CONTEXT.user_id)

    @pytest.mark.asyncio
    async def test_complete_enforces_rate_limit(self) -> None:
        """complete() must call rate_limiter.check() before the API call."""
        mock_check = AsyncMock(side_effect=RateLimitError("openai", 10))
        self.client._rate_limiter.check = mock_check

        with pytest.raises(RateLimitError):
            await self.client.complete(SAMPLE_CONTEXT)

        mock_check.assert_awaited_once_with(SAMPLE_CONTEXT.user_id)

    def test_raises_on_missing_api_key(self) -> None:
        with patch.dict(os.environ, {"OPENAI_API_KEY": ""}):
            # Flush lru_cache so settings re-reads the env
            from app.config.settings import get_settings

            get_settings.cache_clear()
            with pytest.raises(ValueError, match="OPENAI_API_KEY"):
                with patch("app.services.llm_clients.AsyncOpenAI"):
                    OpenAIClient()
            get_settings.cache_clear()  # restore for subsequent tests


# ---------------------------------------------------------------------------
# GeminiClient
# ---------------------------------------------------------------------------


class TestGeminiClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.genai"):
            self.client = GeminiClient()
            self.client.model = MagicMock()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "gemini"

    def test_max_context_tokens(self) -> None:
        assert self.client.max_context_tokens == 1_000_000

    def test_cost_per_input_token(self) -> None:
        assert self.client.cost_per_input_token == Decimal("0.00000125")

    def test_cost_per_output_token(self) -> None:
        assert self.client.cost_per_output_token == Decimal("0.00000375")

    @pytest.mark.asyncio
    async def test_stream_enforces_rate_limit(self) -> None:
        mock_check = AsyncMock(side_effect=RateLimitError("gemini", 5))
        self.client._rate_limiter.check = mock_check
        with pytest.raises(RateLimitError):
            async for _ in self.client.stream(SAMPLE_CONTEXT):
                pass  # pragma: no cover


# ---------------------------------------------------------------------------
# ClaudeClient
# ---------------------------------------------------------------------------


class TestClaudeClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.AsyncAnthropic"):
            self.client = ClaudeClient()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "claude"

    def test_max_context_tokens(self) -> None:
        assert self.client.max_context_tokens == 200_000

    def test_cost_per_input_token(self) -> None:
        assert self.client.cost_per_input_token == Decimal("0.000003")

    def test_cost_per_output_token(self) -> None:
        assert self.client.cost_per_output_token == Decimal("0.000015")

    @pytest.mark.asyncio
    async def test_complete_enforces_rate_limit(self) -> None:
        mock_check = AsyncMock(side_effect=RateLimitError("claude", 3))
        self.client._rate_limiter.check = mock_check
        with pytest.raises(RateLimitError):
            await self.client.complete(SAMPLE_CONTEXT)


# ---------------------------------------------------------------------------
# OllamaClient — local-only enforcement
# ---------------------------------------------------------------------------


class TestOllamaClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.httpx.AsyncClient"):
            self.client = OllamaClient()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "ollama"

    def test_base_url_is_local_ollama_endpoint(self) -> None:
        """base_url must come from OLLAMA_BASE_URL — never an external URL.

        Requirement 3.5: OllamaClient must NOT transmit data to external services.
        """
        assert self.client.base_url == os.environ["OLLAMA_BASE_URL"]
        # Must NOT point to any external cloud service
        assert "openai" not in self.client.base_url
        assert "google" not in self.client.base_url
        assert "anthropic" not in self.client.base_url

    def test_httpx_client_bound_to_local_base_url(self) -> None:
        """The httpx.AsyncClient is constructed with the local base_url,
        ensuring all requests go to the local endpoint only."""
        local_url = "http://my-local-ollama:11434"
        with patch.dict(os.environ, {"OLLAMA_BASE_URL": local_url}):
            from app.config.settings import get_settings

            get_settings.cache_clear()
            with patch("app.services.llm_clients.httpx.AsyncClient") as mock_client_cls:
                OllamaClient()
            # The AsyncClient must have been constructed with the local URL
            call_kwargs = mock_client_cls.call_args.kwargs
            assert call_kwargs.get("base_url") == local_url
            get_settings.cache_clear()

    def test_max_context_tokens(self) -> None:
        assert self.client.max_context_tokens == 4096

    def test_zero_cost(self) -> None:
        """Self-hosted models have zero API cost."""
        assert self.client.cost_per_input_token == Decimal("0.0")
        assert self.client.cost_per_output_token == Decimal("0.0")

    def test_get_cost_per_token_both_zero(self) -> None:
        costs = self.client.get_cost_per_token()
        assert costs["input"] == Decimal("0.0")
        assert costs["output"] == Decimal("0.0")

    @pytest.mark.asyncio
    async def test_stream_enforces_rate_limit(self) -> None:
        mock_check = AsyncMock(side_effect=RateLimitError("ollama", 1))
        self.client._rate_limiter.check = mock_check
        with pytest.raises(RateLimitError):
            async for _ in self.client.stream(SAMPLE_CONTEXT):
                pass  # pragma: no cover

    @pytest.mark.asyncio
    async def test_complete_enforces_rate_limit(self) -> None:
        mock_check = AsyncMock(side_effect=RateLimitError("ollama", 1))
        self.client._rate_limiter.check = mock_check
        with pytest.raises(RateLimitError):
            await self.client.complete(SAMPLE_CONTEXT)


# ---------------------------------------------------------------------------
# LlamaClient
# ---------------------------------------------------------------------------


class TestLlamaClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.httpx.AsyncClient"):
            self.client = LlamaClient()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "llama"

    def test_model_is_llama(self) -> None:
        assert "llama" in self.client.model.lower()

    def test_inherits_zero_cost(self) -> None:
        assert self.client.cost_per_input_token == Decimal("0.0")

    def test_base_url_is_local(self) -> None:
        """Llama must also route to the local Ollama endpoint."""
        assert self.client.base_url == os.environ["OLLAMA_BASE_URL"]

    def test_rate_limiter_provider_key_is_llama(self) -> None:
        """Rate limiter must be keyed to 'llama', not 'ollama'."""
        assert self.client._rate_limiter._provider == "llama"


# ---------------------------------------------------------------------------
# MistralClient
# ---------------------------------------------------------------------------


class TestMistralClient:
    def setup_method(self) -> None:
        with patch("app.services.llm_clients.httpx.AsyncClient"):
            self.client = MistralClient()

    def test_provider_name(self) -> None:
        assert self.client.get_provider_name() == "mistral"

    def test_model_is_mistral(self) -> None:
        assert "mistral" in self.client.model.lower()

    def test_inherits_zero_cost(self) -> None:
        assert self.client.cost_per_input_token == Decimal("0.0")

    def test_base_url_is_local(self) -> None:
        """Mistral must also route to the local Ollama endpoint."""
        assert self.client.base_url == os.environ["OLLAMA_BASE_URL"]

    def test_rate_limiter_provider_key_is_mistral(self) -> None:
        """Rate limiter must be keyed to 'mistral', not 'ollama'."""
        assert self.client._rate_limiter._provider == "mistral"


# ---------------------------------------------------------------------------
# Cross-provider: all six clients implement the required interface
# ---------------------------------------------------------------------------


class TestAllProviderInterface:
    """Verify every provider exposes all required interface methods."""

    @pytest.fixture(autouse=True)
    def _all_clients(self) -> None:
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            self.clients: list[BaseLLMClient] = [
                OpenAIClient(),
                GeminiClient(),
                ClaudeClient(),
                OllamaClient(),
                LlamaClient(),
                MistralClient(),
            ]

    def test_all_have_get_provider_name(self) -> None:
        names = {c.get_provider_name() for c in self.clients}
        assert names == {"openai", "gemini", "claude", "ollama", "llama", "mistral"}

    def test_all_have_positive_or_zero_max_context_tokens(self) -> None:
        for client in self.clients:
            assert (
                client.get_max_context_tokens() > 0
            ), f"{client.get_provider_name()} max_context_tokens must be > 0"

    def test_all_have_non_negative_costs(self) -> None:
        for client in self.clients:
            costs = client.get_cost_per_token()
            assert costs["input"] >= Decimal(
                0
            ), f"{client.get_provider_name()} input cost must be >= 0"
            assert costs["output"] >= Decimal(
                0
            ), f"{client.get_provider_name()} output cost must be >= 0"

    def test_all_have_rate_limiter(self) -> None:
        for client in self.clients:
            assert hasattr(
                client, "_rate_limiter"
            ), f"{client.get_provider_name()} must have _rate_limiter"
            assert isinstance(client._rate_limiter, _ProviderRateLimiter)

    def test_cloud_providers_have_positive_costs(self) -> None:
        """OpenAI, Gemini, Claude must have non-zero token costs."""
        cloud_providers = {"openai", "gemini", "claude"}
        for client in self.clients:
            if client.get_provider_name() in cloud_providers:
                costs = client.get_cost_per_token()
                assert costs["input"] > Decimal(
                    0
                ), f"{client.get_provider_name()} should have positive input cost"
                assert costs["output"] > Decimal(
                    0
                ), f"{client.get_provider_name()} should have positive output cost"

    def test_self_hosted_providers_have_zero_costs(self) -> None:
        """Ollama, Llama, Mistral are self-hosted and must have zero costs."""
        local_providers = {"ollama", "llama", "mistral"}
        for client in self.clients:
            if client.get_provider_name() in local_providers:
                costs = client.get_cost_per_token()
                assert costs["input"] == Decimal(
                    0
                ), f"{client.get_provider_name()} should have zero input cost"
                assert costs["output"] == Decimal(
                    0
                ), f"{client.get_provider_name()} should have zero output cost"


# ---------------------------------------------------------------------------
# max_output_tokens — per-provider configurable limit (Requirement 25.5)
# ---------------------------------------------------------------------------


class TestMaxOutputTokens:
    """Verify each provider's max_output_tokens returns a positive integer
    from settings, and that providers have distinct default values.

    Requirements: 25.5
    """

    @pytest.fixture(autouse=True)
    def _all_clients(self) -> None:
        from app.config.settings import get_settings

        get_settings.cache_clear()
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            self.openai_client = OpenAIClient()
            self.gemini_client = GeminiClient()
            self.claude_client = ClaudeClient()
            self.ollama_client = OllamaClient()
            self.llama_client = LlamaClient()
            self.mistral_client = MistralClient()

        self.all_clients: list[BaseLLMClient] = [
            self.openai_client,
            self.gemini_client,
            self.claude_client,
            self.ollama_client,
            self.llama_client,
            self.mistral_client,
        ]

    def test_all_providers_have_positive_max_output_tokens(self) -> None:
        """Every provider's max_output_tokens must be a positive integer."""
        for client in self.all_clients:
            assert isinstance(
                client.max_output_tokens, int
            ), f"{client.get_provider_name()}.max_output_tokens must be int"
            assert client.max_output_tokens > 0, (
                f"{client.get_provider_name()}.max_output_tokens must be > 0 "
                f"(got {client.max_output_tokens})"
            )

    def test_max_output_tokens_matches_settings(self) -> None:
        """Each provider reads its limit from the corresponding settings field."""
        from app.config.settings import get_settings

        settings = get_settings()

        assert (
            self.openai_client.max_output_tokens
            == settings.LLM_MAX_OUTPUT_TOKENS_OPENAI
        )
        assert (
            self.gemini_client.max_output_tokens
            == settings.LLM_MAX_OUTPUT_TOKENS_GEMINI
        )
        assert (
            self.claude_client.max_output_tokens
            == settings.LLM_MAX_OUTPUT_TOKENS_CLAUDE
        )
        assert (
            self.ollama_client.max_output_tokens
            == settings.LLM_MAX_OUTPUT_TOKENS_OLLAMA
        )
        assert (
            self.llama_client.max_output_tokens == settings.LLM_MAX_OUTPUT_TOKENS_LLAMA
        )
        assert (
            self.mistral_client.max_output_tokens
            == settings.LLM_MAX_OUTPUT_TOKENS_MISTRAL
        )

    def test_providers_have_distinct_default_values(self) -> None:
        """Not all providers should share the same default limit — at least two
        distinct values must exist (cloud providers differ from local ones)."""
        limits = {c.max_output_tokens for c in self.all_clients}
        # Default config has at least two distinct values (cloud vs local)
        assert len(limits) >= 2, (
            "Expected at least 2 distinct max_output_tokens defaults; "
            f"got {sorted(limits)}"
        )

    def test_max_output_tokens_respects_env_override(self) -> None:
        """Overriding LLM_MAX_OUTPUT_TOKENS_OPENAI via env must be reflected."""
        from app.config.settings import get_settings

        with patch.dict(os.environ, {"LLM_MAX_OUTPUT_TOKENS_OPENAI": "512"}):
            get_settings.cache_clear()
            with patch("app.services.llm_clients.AsyncOpenAI"):
                client = OpenAIClient()
            assert client.max_output_tokens == 512
        get_settings.cache_clear()  # restore for subsequent tests

    def test_llama_uses_llama_specific_setting(self) -> None:
        """LlamaClient must read LLM_MAX_OUTPUT_TOKENS_LLAMA, not OLLAMA."""
        from app.config.settings import get_settings

        with patch.dict(
            os.environ,
            {
                "LLM_MAX_OUTPUT_TOKENS_LLAMA": "1024",
                "LLM_MAX_OUTPUT_TOKENS_OLLAMA": "512",
            },
        ):
            get_settings.cache_clear()
            with patch("app.services.llm_clients.httpx.AsyncClient"):
                llama = LlamaClient()
                ollama = OllamaClient()
            assert llama.max_output_tokens == 1024
            assert ollama.max_output_tokens == 512
        get_settings.cache_clear()

    def test_mistral_uses_mistral_specific_setting(self) -> None:
        """MistralClient must read LLM_MAX_OUTPUT_TOKENS_MISTRAL, not OLLAMA."""
        from app.config.settings import get_settings

        with patch.dict(
            os.environ,
            {
                "LLM_MAX_OUTPUT_TOKENS_MISTRAL": "768",
                "LLM_MAX_OUTPUT_TOKENS_OLLAMA": "512",
            },
        ):
            get_settings.cache_clear()
            with patch("app.services.llm_clients.httpx.AsyncClient"):
                mistral = MistralClient()
                ollama = OllamaClient()
            assert mistral.max_output_tokens == 768
            assert ollama.max_output_tokens == 512
        get_settings.cache_clear()


# ---------------------------------------------------------------------------
# Orchestrator max_tokens clamping (Requirement 25.5)
# ---------------------------------------------------------------------------


class TestOrchestratorMaxTokensClamping:
    """Verify the orchestrator clamps max_tokens to the provider's configured limit.

    Requirements: 25.5
    """

    def _make_context(self) -> None:
        """Build a minimal orchestrator PromptContext for testing."""
        from app.services.ai_orchestrator import (
            LLMProvider,
            PromptMessage,
        )
        from app.services.ai_orchestrator import (
            PromptContext as OrchestratorContext,
        )

        return OrchestratorContext(
            messages=[
                PromptMessage(role="system", content="You are a helpful assistant."),
                PromptMessage(role="user", content="Hello"),
            ],
            estimated_tokens=50,
            provider=LLMProvider.openai,
            user_id="test-user",
        )

    def _make_orchestrator(self):
        from app.services.ai_orchestrator import AIOrchestrator

        orch = AIOrchestrator.__new__(AIOrchestrator)
        # Minimal attribute init required for _to_llm_prompt_context
        return orch

    def test_max_tokens_clamped_when_client_limit_is_lower(self) -> None:
        """When client.max_output_tokens < default 2048, result must be clamped."""

        orch = self._make_orchestrator()
        context = self._make_context()

        mock_client = MagicMock()
        mock_client.max_output_tokens = 512  # below 2048

        llm_ctx = orch._to_llm_prompt_context(context, "user-1", client=mock_client)
        assert (
            llm_ctx.max_tokens == 512
        ), f"Expected max_tokens=512 (clamped to client limit), got {llm_ctx.max_tokens}"

    def test_max_tokens_unchanged_when_client_limit_is_higher(self) -> None:
        """When client.max_output_tokens >= 2048, the default is kept as-is."""

        orch = self._make_orchestrator()
        context = self._make_context()

        mock_client = MagicMock()
        mock_client.max_output_tokens = 8192  # well above 2048

        llm_ctx = orch._to_llm_prompt_context(context, "user-1", client=mock_client)
        assert (
            llm_ctx.max_tokens == 2048
        ), f"Expected max_tokens=2048 (default), got {llm_ctx.max_tokens}"

    def test_max_tokens_unchanged_when_no_client_supplied(self) -> None:
        """When client is None, max_tokens uses the default 2048."""

        orch = self._make_orchestrator()
        context = self._make_context()

        llm_ctx = orch._to_llm_prompt_context(context, "user-1", client=None)
        assert llm_ctx.max_tokens == 2048

    def test_max_tokens_unchanged_when_client_limit_is_zero(self) -> None:
        """A max_output_tokens of 0 means no cap — default 2048 is kept."""

        orch = self._make_orchestrator()
        context = self._make_context()

        mock_client = MagicMock()
        mock_client.max_output_tokens = 0  # disabled cap

        llm_ctx = orch._to_llm_prompt_context(context, "user-1", client=mock_client)
        assert llm_ctx.max_tokens == 2048
