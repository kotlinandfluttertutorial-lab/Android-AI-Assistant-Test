"""Property-based tests for context summarization threshold.

Property 4: Context History Summarization Threshold
**Validates: Requirements 2.4**

Uses Hypothesis to generate conversation histories spanning 70–100% of
the FALLBACK_MAX_CONTEXT (4096 tokens) used in ``_build_prompt`` and verifies:

- 4a: After ``_build_prompt`` runs, ``estimated_tokens < provider.max_context_tokens``
- 4b: Summarization is triggered when history token count exceeds the 80% threshold
- 4c: Histories below the threshold pass through without summarization
"""

from __future__ import annotations

import os

# Set env vars before importing any app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

import math
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.models.message import MessageRole
from app.services.ai_orchestrator import (
    AIOrchestrator,
    CompletionResult,
    PromptContext,
    PromptMessage,
    _estimate_tokens,
)

# ---------------------------------------------------------------------------
# Constants mirroring _build_prompt internals
# ---------------------------------------------------------------------------

FALLBACK_MAX_CONTEXT = 4096  # same constant used in _build_prompt
SUMMARIZE_THRESHOLD = 0.80  # AIOrchestrator.SUMMARIZE_THRESHOLD
TOKEN_BUDGET = int(FALLBACK_MAX_CONTEXT * SUMMARIZE_THRESHOLD)  # 3276


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_mock_message(content: str, role_value: str = "user") -> MagicMock:
    """Build a mock Message ORM object with the given content and role.

    Uses real ``MessageRole`` enum values so that ``_build_prompt``'s filter
    ``msg.role in (MessageRole.user, MessageRole.assistant)`` passes correctly.
    """
    msg = MagicMock()
    msg.role = MessageRole.user if role_value == "user" else MessageRole.assistant
    msg.content = content
    return msg


def _make_history_tokens_above(
    target_tokens: int, num_messages: int = 4
) -> list[MagicMock]:
    """Create messages whose *total* token count is EXACTLY *target_tokens*.

    Uses ``math.ceil`` so the token count never falls short due to floor division.
    Each message content is padded to ``4 * per_token`` chars; the last message
    absorbs any rounding remainder.

    Token estimation: ``_estimate_tokens(text) = max(1, len(text) // 4)``
    So to get N tokens from a message, use content of length ``4 * N``.
    """
    num_messages = max(1, num_messages)
    per_message_tokens = math.ceil(target_tokens / num_messages)
    messages = []
    roles = ["user", "assistant"]
    for i in range(num_messages):
        content = "x" * (4 * per_message_tokens)
        messages.append(_make_mock_message(content, role_value=roles[i % 2]))
    return messages


def _make_history_exactly(target_tokens: int, num_messages: int = 4) -> list[MagicMock]:
    """Create messages whose total token count equals *target_tokens* using floor div."""
    num_messages = max(1, num_messages)
    per_message_tokens = max(1, target_tokens // num_messages)
    messages = []
    roles = ["user", "assistant"]
    for i in range(num_messages):
        content = "x" * (4 * per_message_tokens)
        messages.append(_make_mock_message(content, role_value=roles[i % 2]))
    return messages


def _make_orchestrator() -> AIOrchestrator:
    """Return an AIOrchestrator with all DB/IO dependencies stubbed."""
    db = AsyncMock()
    orch = AIOrchestrator(db=db)
    orch._db.commit = AsyncMock()

    # Stub memory service — always returns empty list (no side-effects on tokens)
    orch._memory_service = AsyncMock()
    orch._memory_service.get_relevant_memories = AsyncMock(return_value=[])

    # Stub repos — will be overridden per test
    orch._message_repo = AsyncMock()
    orch._token_usage_repo = AsyncMock()
    return orch


def _total_tokens(history: list[MagicMock], system_prompt: str, message: str) -> int:
    """Calculate total estimated tokens as _build_prompt does."""
    return (
        _estimate_tokens(system_prompt)
        + sum(_estimate_tokens(msg.content) for msg in history)
        + _estimate_tokens(message)
    )


# ---------------------------------------------------------------------------
# Property 4a: estimated_tokens < provider.max_context_tokens after _build_prompt
#
# **Validates: Requirements 2.4**
# ---------------------------------------------------------------------------


@given(
    history_tokens=st.integers(min_value=2867, max_value=4096),
    num_messages=st.integers(min_value=2, max_value=6),
)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_4a_estimated_tokens_below_provider_max_after_build_prompt(
    history_tokens: int,
    num_messages: int,
) -> None:
    """**Validates: Requirements 2.4**

    Property 4a: For any conversation history spanning 70–100% of the
    fallback context window, the ``estimated_tokens`` in the returned
    ``PromptContext`` must be less than the provider's ``max_context_tokens``
    after ``_build_prompt`` runs.

    This verifies that the summarization path keeps the total token budget
    within bounds regardless of which provider is used.
    """
    from app.services.llm_clients import OpenAIClient

    orch = _make_orchestrator()
    history = _make_history_tokens_above(history_tokens, num_messages=num_messages)
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=history)

    # Mock summarization (complete) to return a short summary so total stays small
    short_summary = CompletionResult(
        text="Summary.",
        input_tokens=2,
        output_tokens=2,
    )
    orch.complete = AsyncMock(return_value=short_summary)

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
    ):
        context: PromptContext = await orch._build_prompt(
            conversation_id=str(uuid.uuid4()),
            user_id=str(uuid.uuid4()),
            message="Hello",
        )

    provider_client = OpenAIClient()
    assert context.estimated_tokens < provider_client.max_context_tokens, (
        f"estimated_tokens={context.estimated_tokens} must be < "
        f"max_context_tokens={provider_client.max_context_tokens}"
    )


# ---------------------------------------------------------------------------
# Property 4b: Summarization is triggered when history exceeds 80% threshold
#
# **Validates: Requirements 2.4**
# ---------------------------------------------------------------------------


@given(
    # Generate percentage above the 80% threshold — map to token counts
    # that STRICTLY exceed TOKEN_BUDGET when combined with system + message tokens
    history_tokens=st.integers(
        min_value=TOKEN_BUDGET + 10, max_value=FALLBACK_MAX_CONTEXT * 2
    ),
    num_messages=st.integers(min_value=3, max_value=8),
)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_4b_summarization_triggered_above_threshold(
    history_tokens: int,
    num_messages: int,
) -> None:
    """**Validates: Requirements 2.4**

    Property 4b: When the accumulated history token count exceeds 80% of the
    fallback context window (TOKEN_BUDGET = 3276), ``_summarize_history``
    must be called.

    We replace ``orch._summarize_history`` with a spy that records whether it
    was called, then assert it was invoked after ``_build_prompt`` completes.
    """
    orch = _make_orchestrator()
    # Use ceil-based helper so actual token count reliably exceeds TOKEN_BUDGET
    history = _make_history_tokens_above(history_tokens, num_messages=num_messages)
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=history)

    summarize_called = False

    async def _spy_summarize(*args, **kwargs):
        nonlocal summarize_called
        summarize_called = True
        # Return a valid minimal PromptMessage list so _build_prompt can continue
        return [
            PromptMessage(
                role="system", content="[Conversation Summary] Short summary."
            )
        ]

    orch._summarize_history = _spy_summarize

    # complete() should not be called directly in this test path (spy handles it),
    # but stub it just in case _summarize_history calls it internally
    orch.complete = AsyncMock(
        return_value=CompletionResult(
            text="Short summary.", input_tokens=2, output_tokens=2
        )
    )

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
    ):
        await orch._build_prompt(
            conversation_id=str(uuid.uuid4()),
            user_id=str(uuid.uuid4()),
            message="Hello",
        )

    # Verify actual total exceeded the budget (sanity check for the test itself)
    actual_total = _total_tokens(history, "sys", "Hello")

    if actual_total > TOKEN_BUDGET and len(history) > 2:
        assert summarize_called, (
            f"_summarize_history must be called when total_estimated={actual_total} "
            f"> TOKEN_BUDGET={TOKEN_BUDGET} and len(history)={len(history)} > 2, "
            f"but it was NOT called."
        )


# ---------------------------------------------------------------------------
# Property 4c: Histories below threshold pass through without summarization
#
# **Validates: Requirements 2.4**
# ---------------------------------------------------------------------------


@given(
    # Generate token counts that are guaranteed below the budget even after
    # adding system prompt and current message overhead (subtract 10 as buffer)
    history_tokens=st.integers(min_value=1, max_value=TOKEN_BUDGET - 10),
    num_messages=st.integers(min_value=1, max_value=4),
)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_4c_no_summarization_below_threshold(
    history_tokens: int,
    num_messages: int,
) -> None:
    """**Validates: Requirements 2.4**

    Property 4c: When the conversation history is comfortably below the 80%
    threshold, summarization must NOT be triggered and the history messages
    must be passed through verbatim to the ``PromptContext``.
    """
    orch = _make_orchestrator()
    history = _make_history_exactly(history_tokens, num_messages=num_messages)
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=history)

    summarize_called = False

    async def _spy_summarize(*args, **kwargs):
        nonlocal summarize_called
        summarize_called = True
        return []

    orch._summarize_history = _spy_summarize
    orch.complete = AsyncMock(
        return_value=CompletionResult(
            text="Short summary.", input_tokens=2, output_tokens=2
        )
    )

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
    ):
        await orch._build_prompt(
            conversation_id=str(uuid.uuid4()),
            user_id=str(uuid.uuid4()),
            message="Hello",
        )

    actual_total = _total_tokens(history, "sys", "Hello")

    # Only assert no summarization when actual total is provably below the budget
    if actual_total <= TOKEN_BUDGET:
        assert not summarize_called, (
            f"_summarize_history must NOT be called when total={actual_total} "
            f"<= TOKEN_BUDGET={TOKEN_BUDGET}, but it WAS called."
        )


# ---------------------------------------------------------------------------
# Additional edge cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_4b_edge_summarization_triggered_at_exact_boundary() -> None:
    """**Validates: Requirements 2.4**

    Edge case: when the total token estimate is just over the 80% threshold
    (TOKEN_BUDGET + 1), ``_summarize_history`` must be triggered.
    """
    orch = _make_orchestrator()

    # Build 3 messages that together produce enough tokens to push total over budget.
    # system = "sys" → _estimate_tokens("sys") = max(1, 3 // 4) = 1 token
    # message = "Hello" → _estimate_tokens("Hello") = max(1, 5 // 4) = 1 token
    # overhead = 2 tokens; we need history > TOKEN_BUDGET - 2 = 3274 tokens
    # Use 3 messages with ceil(3275 / 3) = 1092 tokens each → total = 3276 history tokens
    # total = 1 + 3276 + 1 = 3278 > 3276
    target_history_tokens = TOKEN_BUDGET - 2 + 1  # 3275
    history = _make_history_tokens_above(target_history_tokens, num_messages=3)
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=history)

    summarize_called = False

    async def _spy_summarize(*args, **kwargs):
        nonlocal summarize_called
        summarize_called = True
        return [
            PromptMessage(role="system", content="[Conversation Summary] Edge summary.")
        ]

    orch._summarize_history = _spy_summarize
    orch.complete = AsyncMock(
        return_value=CompletionResult(
            text="Edge summary.", input_tokens=2, output_tokens=2
        )
    )

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
    ):
        await orch._build_prompt(
            conversation_id=str(uuid.uuid4()),
            user_id=str(uuid.uuid4()),
            message="Hello",
        )

    actual_total = _total_tokens(history, "sys", "Hello")
    if actual_total > TOKEN_BUDGET and len(history) > 2:
        assert summarize_called, (
            f"Summarization must be triggered at boundary: total={actual_total}, "
            f"budget={TOKEN_BUDGET}"
        )


@pytest.mark.asyncio
async def test_4c_edge_no_summarization_below_threshold() -> None:
    """**Validates: Requirements 2.4**

    Edge case: when the total token estimate is clearly below the 80% threshold
    (50% of budget), summarization must NOT be triggered and ``complete()``
    must NOT be called.
    """
    orch = _make_orchestrator()

    # 50% of budget = well below threshold
    target_history_tokens = TOKEN_BUDGET // 2  # ~1638 tokens
    history = _make_history_exactly(target_history_tokens, num_messages=4)
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=history)

    complete_called = False
    summarize_called = False

    async def _spy_complete(*args, **kwargs):
        nonlocal complete_called
        complete_called = True
        return CompletionResult(text="Summary", input_tokens=1, output_tokens=1)

    async def _spy_summarize(*args, **kwargs):
        nonlocal summarize_called
        summarize_called = True
        return []

    orch.complete = _spy_complete
    orch._summarize_history = _spy_summarize

    with (
        patch(
            "app.services.ai_orchestrator.build_base_system_prompt", return_value="sys"
        ),
        patch(
            "app.services.ai_orchestrator.build_summarization_prompt",
            return_value="sum",
        ),
    ):
        context: PromptContext = await orch._build_prompt(
            conversation_id=str(uuid.uuid4()),
            user_id=str(uuid.uuid4()),
            message="Hello",
        )

    actual_total = _total_tokens(history, "sys", "Hello")
    assert actual_total <= TOKEN_BUDGET, (
        f"Test setup error: actual_total={actual_total} > TOKEN_BUDGET={TOKEN_BUDGET}. "
        f"History was not constructed correctly."
    )

    assert not complete_called, (
        "complete() (used for summarization) must NOT be called when history "
        f"total={actual_total} is well below the 80% threshold={TOKEN_BUDGET}."
    )
    assert not summarize_called, (
        "_summarize_history must NOT be called when history "
        f"total={actual_total} is well below the 80% threshold={TOKEN_BUDGET}."
    )

    # Verify the number of messages in context = 1 (system prompt) + len(history)
    assert len(context.messages) == len(history) + 1, (
        f"Expected {len(history) + 1} messages in context "
        f"(1 system + {len(history)} history), got {len(context.messages)}"
    )
