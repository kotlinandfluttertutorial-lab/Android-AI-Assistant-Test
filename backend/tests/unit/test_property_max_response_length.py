"""Property-based tests for maximum response length enforcement.

Property 15: Maximum Response Length Enforcement
**Validates: Requirements 25.5**

Uses Hypothesis to verify that per-provider ``max_output_tokens`` settings are
correctly enforced by ``AIOrchestrator._to_llm_prompt_context`` and that the
``output_tokens`` recorded in ``TokenUsage`` never exceeds the configured cap.

Properties tested:
- 15a: For any provider with a configured ``max_output_tokens``, the LLM is
       asked to produce at most ``min(2048, max_output_tokens)`` tokens, and
       the recorded ``output_tokens`` stays within the configured cap.
- 15b: ``_to_llm_prompt_context`` produces a ``LLMPromptContext`` whose
       ``max_tokens`` equals ``min(2048, max_output_tokens)`` for any positive
       cap value.
- 15c: When ``max_output_tokens == 0`` (no cap), ``max_tokens`` falls back to
       the default 2048.

Deterministic edge cases:
- Smoke test across all six providers with fixed inputs.
- Boundary: response producing exactly ``max_output_tokens`` tokens.
- No-cap: ``max_output_tokens = 0`` → ``max_tokens = 2048``.
"""

from __future__ import annotations

import os

# Set env vars BEFORE importing any app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

import uuid
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.services.ai_orchestrator import (
    AIOrchestrator,
    LLMProvider,
    PromptContext,
    PromptMessage,
)
from app.services.llm_clients import (
    ClaudeClient,
    GeminiClient,
    LlamaClient,
    MistralClient,
    OllamaClient,
    OpenAIClient,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Default max_tokens used inside _to_llm_prompt_context when no cap is applied
DEFAULT_MAX_TOKENS = 2048

# Provider default max_output_tokens from settings.py
PROVIDER_DEFAULT_MAX_OUTPUT_TOKENS: dict[LLMProvider, int] = {
    LLMProvider.openai: 4096,
    LLMProvider.gemini: 8192,
    LLMProvider.claude: 8192,
    LLMProvider.ollama: 2048,
    LLMProvider.llama: 2048,
    LLMProvider.mistral: 2048,
}

ALL_PROVIDERS = list(LLMProvider)

# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

# Providers as a sampled strategy
st_provider = st.sampled_from(ALL_PROVIDERS)

# Random max_output_tokens from 1 to 4096 (finite cap)
st_max_output_tokens_positive = st.integers(min_value=1, max_value=4096)

# Random max_output_tokens in the range used by Property 15a (64–2048)
st_max_output_tokens_15a = st.integers(min_value=64, max_value=2048)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_mock_client(
    provider: LLMProvider,
    max_output_tokens: int,
    stream_tokens: int,
) -> MagicMock:
    """Build a mock LLM client for *provider* that:
    - reports ``max_output_tokens`` as configured
    - streams a response whose estimated token count == ``stream_tokens``
      (content of length ``4 * stream_tokens`` chars)

    Token estimation: ``_estimate_tokens(text) = max(1, len(text) // 4)``
    So to get N tokens from a single yielded chunk, yield ``4 * N`` characters.
    """
    client_classes = {
        LLMProvider.openai: OpenAIClient,
        LLMProvider.gemini: GeminiClient,
        LLMProvider.claude: ClaudeClient,
        LLMProvider.ollama: OllamaClient,
        LLMProvider.llama: LlamaClient,
        LLMProvider.mistral: MistralClient,
    }
    mock_client = MagicMock(spec=client_classes[provider])
    mock_client.max_output_tokens = max_output_tokens
    mock_client.max_context_tokens = 128_000
    mock_client.cost_per_input_token = Decimal("0.000005")
    mock_client.cost_per_output_token = Decimal("0.000015")
    mock_client.get_provider_name.return_value = provider.value

    # Stream exactly ``stream_tokens`` estimated tokens
    output_text = "x" * (4 * stream_tokens)

    async def _fake_stream(ctx):
        yield output_text

    mock_client.stream = _fake_stream
    return mock_client


def _make_orchestrator(
    provider: LLMProvider,
    mock_client: MagicMock,
    input_tokens: int = 100,
) -> tuple[AIOrchestrator, list[dict]]:
    """Build an AIOrchestrator with all dependencies stubbed.

    Returns:
        (orchestrator, captured_calls) where captured_calls is a list that
        will be populated with the kwargs passed to
        ``TokenUsageRepository.create`` during ``stream_chat``.
    """
    captured_calls: list[dict] = []

    db = AsyncMock()
    db.commit = AsyncMock()
    orch = AIOrchestrator(db=db)

    # Stub memory service
    orch._memory_service = AsyncMock()
    orch._memory_service.get_relevant_memories = AsyncMock(return_value=[])

    # Stub message repo
    mock_assistant_msg = MagicMock()
    mock_assistant_msg.id = uuid.uuid4()
    orch._message_repo = AsyncMock()
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=[])
    orch._message_repo.create = AsyncMock(return_value=mock_assistant_msg)

    # Stub token usage repo — capture all create() calls
    async def _fake_create(
        *,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        provider: str,
        input_tokens: int,
        output_tokens: int,
        cost_usd: Decimal = Decimal(0),
    ):
        captured_calls.append(
            {
                "user_id": user_id,
                "message_id": message_id,
                "provider": provider,
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cost_usd": cost_usd,
            }
        )
        row = MagicMock()
        row.id = uuid.uuid4()
        return row

    orch._token_usage_repo = AsyncMock()
    orch._token_usage_repo.create = _fake_create

    # Inject mock client into the provider cache
    orch._provider_cache[provider] = mock_client

    # Stub _build_prompt to return a context with controlled estimated_tokens
    async def _fake_build_prompt(conversation_id, user_id, message):
        return PromptContext(
            messages=[PromptMessage(role="system", content="sys")],
            estimated_tokens=input_tokens,
            provider=provider,
            user_id=user_id,
        )

    orch._build_prompt = _fake_build_prompt

    return orch, captured_calls


async def _run_stream_chat(
    orch: AIOrchestrator,
    provider: LLMProvider,
) -> None:
    """Execute stream_chat with standard patches applied."""
    mock_ws = AsyncMock()
    mock_ws.send_json = AsyncMock()

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
        patch(
            "app.services.ai_orchestrator._detect_prompt_injection_static",
            return_value=False,
        ),
        patch("app.workers.metrics.record_token_usage"),
    ):
        await orch.stream_chat(
            conversation_id=str(uuid.uuid4()),
            user_message="Hello",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )


# ---------------------------------------------------------------------------
# Property 15a: output_tokens ≤ configured max_output_tokens
#
# **Validates: Requirements 25.5**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    max_output_tokens=st_max_output_tokens_15a,
)
@settings(max_examples=60, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_15a_output_tokens_within_configured_cap(
    provider: LLMProvider,
    max_output_tokens: int,
) -> None:
    """**Validates: Requirements 25.5**

    Property 15a: For any provider with ``max_output_tokens`` in [64, 2048],
    when the LLM streams a response that fills the capped token budget,
    the recorded ``output_tokens`` must be ≤ ``max_output_tokens``.

    The clamped max is ``min(DEFAULT_MAX_TOKENS=2048, max_output_tokens)``.
    The mock LLM streams exactly that many estimated tokens, so the invariant
    ``output_tokens ≤ max_output_tokens`` should always hold.
    """
    # The orchestrator clamps: capped = min(2048, max_output_tokens)
    capped = min(DEFAULT_MAX_TOKENS, max_output_tokens)

    # Mock LLM streams exactly `capped` estimated tokens (simulates LLM respecting cap)
    mock_client = _make_mock_client(provider, max_output_tokens, stream_tokens=capped)
    orch, captured = _make_orchestrator(provider, mock_client)

    await _run_stream_chat(orch, provider)

    assert len(captured) == 1, (
        f"Expected exactly one TokenUsage.create call, got {len(captured)}"
    )

    actual_output = captured[0]["output_tokens"]
    assert actual_output <= max_output_tokens, (
        f"[Property 15a] output_tokens={actual_output} must be ≤ "
        f"max_output_tokens={max_output_tokens} for provider '{provider.value}'"
    )


# ---------------------------------------------------------------------------
# Property 15b: _to_llm_prompt_context clamps max_tokens to min(2048, cap)
#
# **Validates: Requirements 25.5**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    max_output_tokens=st_max_output_tokens_positive,
)
@settings(max_examples=60, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_15b_to_llm_prompt_context_clamps_max_tokens(
    provider: LLMProvider,
    max_output_tokens: int,
) -> None:
    """**Validates: Requirements 25.5**

    Property 15b: For any positive ``max_output_tokens`` (1–4096),
    ``_to_llm_prompt_context`` must produce an ``LLMPromptContext`` whose
    ``max_tokens`` equals ``min(2048, max_output_tokens)``.

    This verifies the clamping logic directly, independent of stream_chat.
    """
    client_classes = {
        LLMProvider.openai: OpenAIClient,
        LLMProvider.gemini: GeminiClient,
        LLMProvider.claude: ClaudeClient,
        LLMProvider.ollama: OllamaClient,
        LLMProvider.llama: LlamaClient,
        LLMProvider.mistral: MistralClient,
    }
    mock_client = MagicMock(spec=client_classes[provider])
    mock_client.max_output_tokens = max_output_tokens

    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="sys")],
        estimated_tokens=10,
        provider=provider,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=mock_client)

    expected_max_tokens = min(DEFAULT_MAX_TOKENS, max_output_tokens)
    assert llm_ctx.max_tokens == expected_max_tokens, (
        f"[Property 15b] max_tokens must be min(2048, {max_output_tokens}) = "
        f"{expected_max_tokens}, got {llm_ctx.max_tokens} for provider '{provider.value}'"
    )


# ---------------------------------------------------------------------------
# Property 15c: max_output_tokens=0 → max_tokens=2048 (no cap)
#
# **Validates: Requirements 25.5**
# ---------------------------------------------------------------------------


@given(provider=st_provider)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_15c_no_cap_when_max_output_tokens_zero(
    provider: LLMProvider,
) -> None:
    """**Validates: Requirements 25.5**

    Property 15c: When ``max_output_tokens == 0`` (disabled / no cap),
    ``_to_llm_prompt_context`` must use the default 2048 for ``max_tokens``.

    This verifies that operators can disable the per-response cap by setting
    ``LLM_MAX_OUTPUT_TOKENS_*=0`` in their environment.
    """
    client_classes = {
        LLMProvider.openai: OpenAIClient,
        LLMProvider.gemini: GeminiClient,
        LLMProvider.claude: ClaudeClient,
        LLMProvider.ollama: OllamaClient,
        LLMProvider.llama: LlamaClient,
        LLMProvider.mistral: MistralClient,
    }
    mock_client = MagicMock(spec=client_classes[provider])
    mock_client.max_output_tokens = 0  # disabled

    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="sys")],
        estimated_tokens=10,
        provider=provider,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=mock_client)

    assert llm_ctx.max_tokens == DEFAULT_MAX_TOKENS, (
        f"[Property 15c] max_tokens must equal {DEFAULT_MAX_TOKENS} when "
        f"max_output_tokens=0 (no cap) for provider '{provider.value}', "
        f"got {llm_ctx.max_tokens}"
    )


# ---------------------------------------------------------------------------
# Deterministic edge cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_15_all_six_providers_deterministic() -> None:
    """**Validates: Requirements 25.5**

    Smoke test: run stream_chat across all six providers with fixed
    ``max_output_tokens=100``.  The mock LLM streams exactly 100 estimated
    tokens.  Assert ``output_tokens ≤ 100`` for every provider.
    """
    for provider in ALL_PROVIDERS:
        fixed_max = 100
        mock_client = _make_mock_client(provider, fixed_max, stream_tokens=fixed_max)
        orch, captured = _make_orchestrator(provider, mock_client)

        await _run_stream_chat(orch, provider)

        assert len(captured) == 1, (
            f"Expected 1 TokenUsage.create call for {provider.value}, got {len(captured)}"
        )
        actual_output = captured[0]["output_tokens"]
        assert actual_output <= fixed_max, (
            f"output_tokens={actual_output} must be ≤ max_output_tokens={fixed_max} "
            f"for provider '{provider.value}'"
        )


@pytest.mark.asyncio
async def test_15_boundary_exact_max_tokens() -> None:
    """**Validates: Requirements 25.5**

    Boundary: when the mock LLM streams a response that produces exactly
    ``max_output_tokens`` estimated tokens, the recorded ``output_tokens``
    must equal ``max_output_tokens`` (no over-counting, no under-counting).

    Uses OpenAI with ``max_output_tokens=200``. The capped value is
    ``min(2048, 200) = 200``, and the mock streams 200 tokens.
    """
    provider = LLMProvider.openai
    exact_max = 200

    mock_client = _make_mock_client(provider, exact_max, stream_tokens=exact_max)
    orch, captured = _make_orchestrator(provider, mock_client)

    await _run_stream_chat(orch, provider)

    assert len(captured) == 1
    actual_output = captured[0]["output_tokens"]
    assert actual_output == exact_max, (
        f"Boundary: output_tokens must equal {exact_max} when the LLM streams "
        f"exactly that many tokens, got {actual_output}"
    )


@pytest.mark.asyncio
async def test_15_no_cap_when_zero() -> None:
    """**Validates: Requirements 25.5**

    When ``max_output_tokens=0``, the orchestrator must NOT apply a cap and
    ``_to_llm_prompt_context`` must use the default 2048 for ``max_tokens``.

    Uses Claude with no cap (0). Verify ``max_tokens == 2048`` is passed to
    the LLM context.
    """
    provider = LLMProvider.claude
    mock_client = MagicMock(spec=ClaudeClient)
    mock_client.max_output_tokens = 0

    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="system prompt")],
        estimated_tokens=50,
        provider=provider,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=mock_client)

    assert llm_ctx.max_tokens == DEFAULT_MAX_TOKENS, (
        f"max_tokens must be {DEFAULT_MAX_TOKENS} when max_output_tokens=0 "
        f"(no cap), got {llm_ctx.max_tokens}"
    )


@pytest.mark.asyncio
async def test_15_clamping_when_provider_cap_below_2048() -> None:
    """**Validates: Requirements 25.5**

    When a provider's ``max_output_tokens`` is below 2048 (e.g. 512),
    the clamp must reduce ``max_tokens`` to the provider cap, not 2048.
    """
    provider = LLMProvider.ollama
    provider_cap = 512  # less than the default 2048

    mock_client = MagicMock(spec=OllamaClient)
    mock_client.max_output_tokens = provider_cap

    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="sys")],
        estimated_tokens=20,
        provider=provider,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=mock_client)

    assert llm_ctx.max_tokens == provider_cap, (
        f"max_tokens must be clamped to provider cap {provider_cap}, "
        f"got {llm_ctx.max_tokens}"
    )


@pytest.mark.asyncio
async def test_15_clamping_when_provider_cap_above_2048() -> None:
    """**Validates: Requirements 25.5**

    When a provider's ``max_output_tokens`` exceeds 2048 (e.g. Gemini at
    8192), the clamp must cap ``max_tokens`` at 2048 (the orchestrator default),
    not the higher provider value.
    """
    provider = LLMProvider.gemini
    provider_cap = 8192  # default for Gemini, above 2048

    mock_client = MagicMock(spec=GeminiClient)
    mock_client.max_output_tokens = provider_cap

    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="sys")],
        estimated_tokens=10,
        provider=provider,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=mock_client)

    assert llm_ctx.max_tokens == DEFAULT_MAX_TOKENS, (
        f"max_tokens must be capped at {DEFAULT_MAX_TOKENS} when provider cap "
        f"({provider_cap}) > default, got {llm_ctx.max_tokens}"
    )


@pytest.mark.asyncio
async def test_15_no_client_uses_default() -> None:
    """**Validates: Requirements 25.5**

    When no client is passed to ``_to_llm_prompt_context``, the max_tokens
    must fall back to the default 2048.
    """
    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    context = PromptContext(
        messages=[PromptMessage(role="system", content="sys")],
        estimated_tokens=5,
        provider=LLMProvider.openai,
        user_id="test-user",
    )

    llm_ctx = orch._to_llm_prompt_context(context, "test-user", client=None)

    assert llm_ctx.max_tokens == DEFAULT_MAX_TOKENS, (
        f"max_tokens must be {DEFAULT_MAX_TOKENS} when no client is passed, "
        f"got {llm_ctx.max_tokens}"
    )
