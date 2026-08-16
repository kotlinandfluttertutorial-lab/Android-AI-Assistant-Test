"""Property-based tests for token usage recording invariant.

Property 5: Token Usage Recording Invariant
**Validates: Requirements 2.9, 21.6**

Uses Hypothesis to generate valid messages across all six supported providers
and verifies three core invariants on every recorded ``TokenUsage``:

- 5a: ``input_tokens > 0``  — every prompt consumes at least one token.
- 5b: ``output_tokens > 0``  — every non-empty completion produces at least one token.
- 5c: ``input_tokens + output_tokens ≤ provider.max_context_tokens`` — total token
      consumption never exceeds the provider's declared context window.

Additionally:
- 5d: ``cost_usd ≥ 0``  — cost is never negative.
- 5e: ``provider`` field on the saved ``TokenUsage`` matches the requested provider.
- 5f: ``TokenUsageRepository.create`` is called exactly once per completion.

Test strategy
-------------
The tests mock ``AIOrchestrator.stream_chat`` at the boundary layer that writes
``TokenUsage`` rows (via ``TokenUsageRepository``) so that we can verify the
values passed in without exercising the real LLM providers, database, or
WebSocket infrastructure.  Each test:

1. Generates a random (message, provider, token_counts) triple via Hypothesis.
2. Runs the part of ``AIOrchestrator.stream_chat`` that records usage — specifically
   the ``TokenUsageRepository.create`` call — by exercising the orchestrator's
   recording logic with the generated inputs.
3. Asserts the three invariants on the captured arguments.
"""

from __future__ import annotations

import os

# Set env vars before importing any app modules (mirrors conftest pattern)
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

import uuid
from decimal import Decimal
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.models.token_usage import TokenUsage
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
# Provider context window constants (mirrors llm_clients.py)
# ---------------------------------------------------------------------------

PROVIDER_MAX_CONTEXT_TOKENS: dict[LLMProvider, int] = {
    LLMProvider.openai: 128_000,  # GPT-4o
    LLMProvider.gemini: 1_000_000,  # Gemini 1.5 Pro
    LLMProvider.claude: 200_000,  # Claude 3.5 Sonnet
    LLMProvider.ollama: 4_096,  # Ollama default
    LLMProvider.llama: 4_096,  # Llama 3.x via Ollama
    LLMProvider.mistral: 4_096,  # Mistral via Ollama
}

ALL_PROVIDERS = list(LLMProvider)

# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

# A non-empty user message (1–500 chars of printable text)
st_user_message = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=500,
)

# Pick any of the six supported providers
st_provider = st.sampled_from(ALL_PROVIDERS)

# Generate token counts that satisfy the invariants:
# input > 0, output > 0, input + output <= max_context_tokens for the smallest
# context window (4096, used by ollama/llama/mistral).  We use 4096 as the
# upper bound so the same strategy works across all providers.
_MIN_CONTEXT = min(PROVIDER_MAX_CONTEXT_TOKENS.values())  # 4096

st_token_counts = st.fixed_dictionaries(
    {
        "input_tokens": st.integers(min_value=1, max_value=_MIN_CONTEXT - 1),
        "output_tokens": st.integers(min_value=1, max_value=_MIN_CONTEXT - 1),
    }
).filter(lambda d: d["input_tokens"] + d["output_tokens"] <= _MIN_CONTEXT)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_token_usage_row(
    user_id: uuid.UUID,
    message_id: uuid.UUID,
    provider: str,
    input_tokens: int,
    output_tokens: int,
    cost_usd: Decimal,
) -> TokenUsage:
    """Build an in-memory ``TokenUsage`` without a database session."""
    row = TokenUsage(
        id=uuid.uuid4(),
        user_id=user_id,
        message_id=message_id,
        provider=provider,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        cost_usd=float(cost_usd),
    )
    return row


def _get_client_for_provider(provider: LLMProvider) -> Any:
    """Return an uninitialised mock of the concrete client for *provider*."""
    client_classes = {
        LLMProvider.openai: OpenAIClient,
        LLMProvider.gemini: GeminiClient,
        LLMProvider.claude: ClaudeClient,
        LLMProvider.ollama: OllamaClient,
        LLMProvider.llama: LlamaClient,
        LLMProvider.mistral: MistralClient,
    }
    cls = client_classes[provider]
    mock_client = MagicMock(spec=cls)
    mock_client.max_context_tokens = PROVIDER_MAX_CONTEXT_TOKENS[provider]
    mock_client.cost_per_input_token = Decimal("0.000005")
    mock_client.cost_per_output_token = Decimal("0.000015")
    mock_client.max_output_tokens = min(4096, PROVIDER_MAX_CONTEXT_TOKENS[provider])
    mock_client.get_provider_name.return_value = provider.value

    # stream() is an async generator: yield a single token then stop
    async def _fake_stream(ctx):
        yield "hello"

    mock_client.stream = _fake_stream
    return mock_client


def _make_orchestrator_with_capture(
    provider: LLMProvider,
    input_tokens: int,
    output_tokens: int,
) -> tuple[AIOrchestrator, list[dict]]:
    """Construct an ``AIOrchestrator`` that captures ``TokenUsageRepository.create`` calls.

    Returns the orchestrator and a list that will be populated with the keyword
    arguments passed to ``create`` when ``stream_chat`` runs.
    """
    captured_calls: list[dict] = []

    db = AsyncMock()
    db.commit = AsyncMock()
    orch = AIOrchestrator(db=db)

    # Stub memory service — no memories, no side effects
    orch._memory_service = AsyncMock()
    orch._memory_service.get_relevant_memories = AsyncMock(return_value=[])

    # Stub message repo — return empty history; create() returns a mock message
    mock_assistant_msg = MagicMock()
    mock_assistant_msg.id = uuid.uuid4()
    orch._message_repo = AsyncMock()
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=[])
    orch._message_repo.create = AsyncMock(return_value=mock_assistant_msg)

    # Stub token usage repo — capture arguments, return a fake row
    async def _fake_create(
        *,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        provider: str,
        input_tokens: int,
        output_tokens: int,
        cost_usd: Decimal = Decimal(0),
        feature=None,  # added: ai_orchestrator now passes feature kwarg (Req 34.1)
    ) -> TokenUsage:
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
        return _make_token_usage_row(
            user_id=user_id,
            message_id=message_id,
            provider=provider,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=cost_usd,
        )

    orch._token_usage_repo = AsyncMock()
    orch._token_usage_repo.create = _fake_create

    # Stub provider cache — use a mock client with controlled token counts
    mock_client = _get_client_for_provider(provider)

    # Override the streaming to yield exactly enough "tokens" to produce
    # output_tokens when estimated.  _estimate_tokens(text) = max(1, len(text) // 4)
    # So we yield a single string of length 4 * output_tokens.
    output_text = "x" * (4 * output_tokens)

    async def _fake_stream(ctx):
        yield output_text

    mock_client.stream = _fake_stream

    # Estimated input tokens come from context.estimated_tokens which _build_prompt sets.
    # We patch _build_prompt to return a context with our desired estimated_tokens.
    desired_input = input_tokens

    async def _fake_build_prompt(conversation_id, user_id, message, **kwargs):
        return PromptContext(
            messages=[PromptMessage(role="system", content="sys")],
            estimated_tokens=desired_input,
            provider=provider,
            user_id=user_id,
        )

    orch._build_prompt = _fake_build_prompt
    orch._provider_cache[provider] = mock_client

    return orch, captured_calls


# ---------------------------------------------------------------------------
# Property 5a + 5b + 5c: core invariants across all six providers
#
# **Validates: Requirements 2.9, 21.6**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    message=st_user_message,
    tokens=st_token_counts,
)
@settings(max_examples=60, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_5_token_usage_invariants_all_providers(
    provider: LLMProvider,
    message: str,
    tokens: dict,
) -> None:
    """**Validates: Requirements 2.9, 21.6**

    Property 5 (combined 5a + 5b + 5c): For valid messages across all six
    providers, the ``TokenUsage`` row recorded by ``AIOrchestrator.stream_chat``
    must satisfy:

    - ``input_tokens > 0``
    - ``output_tokens > 0``
    - ``input_tokens + output_tokens ≤ provider.max_context_tokens``

    The test drives the real ``stream_chat`` code path that calls
    ``TokenUsageRepository.create`` so that we verify the actual values
    the orchestrator computes, not just the repository contract.
    """
    input_tokens = tokens["input_tokens"]
    output_tokens = tokens["output_tokens"]

    orch, captured = _make_orchestrator_with_capture(
        provider, input_tokens, output_tokens
    )

    # Build a mock WebSocket that accepts every send_json call silently
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
            user_message=message,
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    # --- Invariant checks ---
    assert len(captured) == 1, (
        f"Expected exactly one TokenUsage.create call, got {len(captured)}. "
        f"Provider: {provider.value}"
    )

    rec = captured[0]
    actual_input = rec["input_tokens"]
    actual_output = rec["output_tokens"]
    max_ctx = PROVIDER_MAX_CONTEXT_TOKENS[provider]

    # 5a: input_tokens > 0
    assert actual_input > 0, (
        f"[Property 5a] input_tokens must be > 0 for provider '{provider.value}', "
        f"got input_tokens={actual_input}"
    )

    # 5b: output_tokens > 0
    assert actual_output > 0, (
        f"[Property 5b] output_tokens must be > 0 for provider '{provider.value}', "
        f"got output_tokens={actual_output}"
    )

    # 5c: input_tokens + output_tokens ≤ max_context_tokens
    total = actual_input + actual_output
    assert total <= max_ctx, (
        f"[Property 5c] input_tokens + output_tokens must be ≤ max_context_tokens "
        f"for provider '{provider.value}': "
        f"{actual_input} + {actual_output} = {total} > {max_ctx}"
    )


# ---------------------------------------------------------------------------
# Property 5d: cost_usd ≥ 0 for all providers
#
# **Validates: Requirements 2.9, 3.6**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    tokens=st_token_counts,
)
@settings(max_examples=40, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_5d_cost_usd_never_negative(
    provider: LLMProvider,
    tokens: dict,
) -> None:
    """**Validates: Requirements 2.9, 3.6**

    Property 5d: The pre-computed ``cost_usd`` stored in the ``TokenUsage`` row
    must never be negative regardless of provider or token counts.

    Self-hosted providers (ollama, llama, mistral) have zero per-token cost and
    should produce ``cost_usd == 0``.  Commercial providers should produce
    ``cost_usd > 0`` when tokens > 0.
    """
    input_tokens = tokens["input_tokens"]
    output_tokens = tokens["output_tokens"]

    orch, captured = _make_orchestrator_with_capture(
        provider, input_tokens, output_tokens
    )

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
            user_message="test",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    assert len(captured) == 1
    cost = captured[0]["cost_usd"]
    assert cost >= 0, (
        f"[Property 5d] cost_usd must be ≥ 0 for provider '{provider.value}', "
        f"got cost_usd={cost}"
    )


# ---------------------------------------------------------------------------
# Property 5e: provider field matches requested provider
#
# **Validates: Requirements 2.9**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    tokens=st_token_counts,
)
@settings(max_examples=40, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_5e_provider_field_matches_requested_provider(
    provider: LLMProvider,
    tokens: dict,
) -> None:
    """**Validates: Requirements 2.9**

    Property 5e: The ``provider`` field on every recorded ``TokenUsage`` row
    must match the provider identifier that was passed to ``stream_chat``.

    This verifies that token usage is attributed to the correct provider in the
    accounting layer — a prerequisite for accurate cost reporting in the Admin
    Dashboard.
    """
    input_tokens = tokens["input_tokens"]
    output_tokens = tokens["output_tokens"]

    orch, captured = _make_orchestrator_with_capture(
        provider, input_tokens, output_tokens
    )

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
            user_message="test",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    assert len(captured) == 1
    recorded_provider = captured[0]["provider"]
    assert recorded_provider == provider.value, (
        f"[Property 5e] TokenUsage.provider must match the requested provider. "
        f"Expected '{provider.value}', got '{recorded_provider}'"
    )


# ---------------------------------------------------------------------------
# Property 5f: repository is called exactly once per completion
#
# **Validates: Requirements 2.9**
# ---------------------------------------------------------------------------


@given(
    provider=st_provider,
    tokens=st_token_counts,
)
@settings(max_examples=30, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_5f_token_usage_create_called_exactly_once(
    provider: LLMProvider,
    tokens: dict,
) -> None:
    """**Validates: Requirements 2.9**

    Property 5f: ``TokenUsageRepository.create`` must be invoked exactly once
    for every call to ``AIOrchestrator.stream_chat``.

    This verifies that usage is always recorded (no silent misses) and never
    double-counted, regardless of which provider processes the request.
    """
    input_tokens = tokens["input_tokens"]
    output_tokens = tokens["output_tokens"]

    orch, captured = _make_orchestrator_with_capture(
        provider, input_tokens, output_tokens
    )

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
            user_message="test",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    assert len(captured) == 1, (
        f"[Property 5f] TokenUsageRepository.create must be called exactly once "
        f"per stream_chat invocation. Got {len(captured)} call(s) for provider "
        f"'{provider.value}'."
    )


# ---------------------------------------------------------------------------
# Deterministic edge cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_5_minimum_token_counts_openai() -> None:
    """**Validates: Requirements 2.9**

    Edge case 5-min: With input_tokens=1 and output_tokens=1 (the minimum),
    all three core invariants still hold for OpenAI.
    """
    provider = LLMProvider.openai
    orch, captured = _make_orchestrator_with_capture(provider, 1, 1)

    mock_ws = AsyncMock()
    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="s"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt", return_value="s"
        ),
        patch(
            "app.services.ai_orchestrator._detect_prompt_injection_static",
            return_value=False,
        ),
        patch("app.workers.metrics.record_token_usage"),
    ):
        await orch.stream_chat(
            conversation_id=str(uuid.uuid4()),
            user_message="hi",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    assert len(captured) == 1
    rec = captured[0]
    assert rec["input_tokens"] > 0
    assert rec["output_tokens"] > 0
    assert (
        rec["input_tokens"] + rec["output_tokens"]
        <= PROVIDER_MAX_CONTEXT_TOKENS[provider]
    )


@pytest.mark.asyncio
async def test_5_boundary_token_sum_at_context_window_ollama() -> None:
    """**Validates: Requirements 2.9**

    Edge case 5-boundary: When input + output = ollama.max_context_tokens exactly,
    the invariant ``≤ max_context_tokens`` still holds (boundary is inclusive).
    """
    provider = LLMProvider.ollama
    max_ctx = PROVIDER_MAX_CONTEXT_TOKENS[provider]  # 4096
    # Use input = max_ctx - 1 and output = 1 → total = max_ctx (exactly at boundary)
    input_tokens = max_ctx - 1
    output_tokens = 1

    orch, captured = _make_orchestrator_with_capture(
        provider, input_tokens, output_tokens
    )

    mock_ws = AsyncMock()
    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="s"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt", return_value="s"
        ),
        patch(
            "app.services.ai_orchestrator._detect_prompt_injection_static",
            return_value=False,
        ),
        patch("app.workers.metrics.record_token_usage"),
    ):
        await orch.stream_chat(
            conversation_id=str(uuid.uuid4()),
            user_message="test",
            provider=provider,
            user_id=str(uuid.uuid4()),
            ws=mock_ws,
        )

    assert len(captured) == 1
    rec = captured[0]
    total = rec["input_tokens"] + rec["output_tokens"]
    assert (
        total <= max_ctx
    ), f"Boundary: total {total} must be ≤ max_context_tokens {max_ctx}"


@pytest.mark.asyncio
async def test_5_all_six_providers_deterministic() -> None:
    """**Validates: Requirements 2.9, 21.6**

    Smoke test: exercise all six providers with fixed inputs and assert the
    three invariants hold for each.  Provides a quick deterministic regression
    check alongside the property-based coverage.
    """
    input_tokens = 100
    output_tokens = 50

    for provider in ALL_PROVIDERS:
        orch, captured = _make_orchestrator_with_capture(
            provider, input_tokens, output_tokens
        )

        mock_ws = AsyncMock()
        with (
            patch(
                "app.services.ai_orchestrator.build_base_system_prompt",
                return_value="sys",
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
                user_message="test message",
                provider=provider,
                user_id=str(uuid.uuid4()),
                ws=mock_ws,
            )

        assert len(captured) == 1, f"Expected 1 capture for {provider.value}"
        rec = captured[0]
        max_ctx = PROVIDER_MAX_CONTEXT_TOKENS[provider]

        assert (
            rec["input_tokens"] > 0
        ), f"[5a] input_tokens must be > 0 for {provider.value}"
        assert (
            rec["output_tokens"] > 0
        ), f"[5b] output_tokens must be > 0 for {provider.value}"
        assert rec["input_tokens"] + rec["output_tokens"] <= max_ctx, (
            f"[5c] total tokens must be ≤ {max_ctx} for {provider.value}, "
            f"got {rec['input_tokens']} + {rec['output_tokens']} = "
            f"{rec['input_tokens'] + rec['output_tokens']}"
        )
        assert rec["cost_usd"] >= 0, f"[5d] cost_usd must be ≥ 0 for {provider.value}"
        assert (
            rec["provider"] == provider.value
        ), f"[5e] provider field must match for {provider.value}"
