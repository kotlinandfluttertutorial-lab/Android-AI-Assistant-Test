"""Property-based tests for safety filter on LLM output.

Property 14: Safety Filter on LLM Output
Validates: Requirements 25.3

Approach
--------
Uses Hypothesis to generate harmful LLM output strings and verify that:

- 14a: Harmful content never appears in the user-facing response after filtering.
- 14b: Redaction replaces harmful content with "[content removed]" placeholder.
- 14c: Clean LLM output passes through unchanged (no false positives).
- 14d: When redaction fails, SafetyFilterError is raised and harmful text is not
       delivered to the caller.
- 14e: SafetyFilterError during streaming blocks the entire response via WebSocket.

Requirements: 25.3
"""

from __future__ import annotations

import os

# ---------------------------------------------------------------------------
# Set required env vars BEFORE any app imports.
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import asyncio
import re
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.services.safety_service import (
    _HARMFUL_OUTPUT_PATTERNS,
    SafetyFilterError,
    SafetyService,
)

# ---------------------------------------------------------------------------
# Concrete harmful strings — each is guaranteed to trigger at least one
# pattern in _HARMFUL_OUTPUT_PATTERNS.
# ---------------------------------------------------------------------------

HARMFUL_STRINGS: list[str] = [
    '<script>alert("xss")</script>',
    '<script type="text/javascript">evil()</script>',
    "javascript:void(0)",
    "javascript:alert(1)",
    "JAVASCRIPT:malicious()",
    "<SCRIPT>document.cookie</SCRIPT>",
    '<script\ntype="text/javascript">bad()</script>',
]

# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

harmful_string_strategy = st.sampled_from(HARMFUL_STRINGS)

# Clean text — contains no characters that could form harmful patterns.
_SAFE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,!?"
clean_text_strategy = st.text(min_size=0, max_size=100, alphabet=_SAFE_ALPHABET)


# ---------------------------------------------------------------------------
# Property 14a: Harmful content never appears in user-facing response
#
# Validates: Requirements 25.3
# ---------------------------------------------------------------------------


@given(
    harmful=harmful_string_strategy,
    prefix=clean_text_strategy,
    suffix=clean_text_strategy,
)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
def test_harmful_content_never_appears_in_filtered_response(
    harmful: str, prefix: str, suffix: str
) -> None:
    """**Validates: Requirements 25.3**

    For every known harmful string (optionally embedded in clean text),
    SafetyService.filter_response() must ensure the harmful pattern does
    NOT appear in the returned output.  "[content removed]" must be present
    to indicate redaction occurred.
    """
    service = SafetyService()
    text = f"{prefix}{harmful}{suffix}"

    result = service.filter_response(text)

    # Verify no harmful pattern remains in the output.
    for pattern in _HARMFUL_OUTPUT_PATTERNS:
        assert not pattern.search(result), (
            f"Harmful pattern {pattern.pattern!r} still found in filtered output. "
            f"Input: {text!r}, Output: {result!r}"
        )

    # Redaction marker must be present.
    assert "[content removed]" in result, (
        f"Expected '[content removed]' placeholder in output for input {text!r}. "
        f"Got: {result!r}"
    )


# ---------------------------------------------------------------------------
# Property 14b: Redaction replaces harmful content with placeholder
#
# Validates: Requirements 25.3
# ---------------------------------------------------------------------------


@given(harmful=harmful_string_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
def test_redaction_replaces_harmful_content_with_placeholder(harmful: str) -> None:
    """**Validates: Requirements 25.3**

    For every known harmful string, filter_response() must:
    - Remove every match of every harmful pattern from the output.
    - Replace it with the "[content removed]" marker.
    """
    service = SafetyService()

    result = service.filter_response(harmful)

    # All harmful patterns must be absent from the output.
    for pattern in _HARMFUL_OUTPUT_PATTERNS:
        assert not pattern.search(result), (
            f"Pattern {pattern.pattern!r} found in output after filtering {harmful!r}. "
            f"Output: {result!r}"
        )

    # The replacement marker must be present (at least one substitution occurred).
    assert "[content removed]" in result, (
        f"Expected '[content removed]' in result for harmful input {harmful!r}. "
        f"Got: {result!r}"
    )


# ---------------------------------------------------------------------------
# Property 14c: Clean LLM output passes through unchanged
#
# Validates: Requirements 25.3
# ---------------------------------------------------------------------------


@given(text=clean_text_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
def test_clean_output_passes_through_unchanged(text: str) -> None:
    """**Validates: Requirements 25.3**

    For any clean text string (containing no harmful patterns),
    filter_response() must:
    - Return the text unchanged.
    - Not raise SafetyFilterError.
    - Not insert any "[content removed]" placeholder.
    """
    service = SafetyService()

    # Must not raise.
    result = service.filter_response(text)

    # Must be returned verbatim — no modifications to clean content.
    assert result == text, (
        f"Clean text was modified by filter_response. "
        f"Input: {text!r}, Output: {result!r}"
    )

    # No spurious redaction markers.
    assert (
        "[content removed]" not in result or "[content removed]" in text
    ), f"Unexpected '[content removed]' in output for clean input {text!r}."


# ---------------------------------------------------------------------------
# Property 14d: When redaction fails, SafetyFilterError is raised and
#               harmful text is NOT delivered to the caller.
#
# Validates: Requirements 25.3
# ---------------------------------------------------------------------------


@given(harmful=harmful_string_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
def test_redaction_failure_raises_safety_filter_error(harmful: str) -> None:
    """**Validates: Requirements 25.3**

    When a harmful pattern survives the post-redaction rescan (simulating a
    redaction failure), SafetyFilterError must be raised and the harmful text
    must NOT be returned to the caller.
    """
    service = SafetyService()

    # Patch _HARMFUL_OUTPUT_PATTERNS with a pattern that matches both the
    # input AND the replacement text ("[content removed]"), so the rescan
    # will always find a match and trigger SafetyFilterError.
    simulated_persistent_pattern = re.compile(r"BADWORD|content removed", re.IGNORECASE)

    with patch(
        "app.services.safety_service._HARMFUL_OUTPUT_PATTERNS",
        [simulated_persistent_pattern],
    ):
        captured_exception: SafetyFilterError | None = None
        try:
            result = service.filter_response("BADWORD " + harmful)
            # If we reach here, the error was not raised — fail.
            pytest.fail(f"Expected SafetyFilterError but got result: {result!r}")
        except SafetyFilterError as exc:
            captured_exception = exc

    # SafetyFilterError must have been raised.
    assert captured_exception is not None, (
        "Expected SafetyFilterError to be raised when redaction fails, "
        "but no exception was raised."
    )

    # The harmful text is not accessible — the exception confirms the content
    # was blocked, not returned.  The exception message itself must not
    # contain the raw harmful input.
    assert harmful not in str(captured_exception), (
        f"Harmful text {harmful!r} found in SafetyFilterError message: "
        f"{captured_exception!r}. The harmful content must be blocked, "
        "not leaked in the exception message."
    )


# ---------------------------------------------------------------------------
# Property 14e (orchestrator-level): SafetyFilterError during streaming
#                                     blocks entire response via WebSocket.
#
# Validates: Requirements 25.3
# ---------------------------------------------------------------------------


@given(harmful=harmful_string_strategy)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow])
def test_safety_filter_error_blocks_entire_streaming_response(harmful: str) -> None:
    """**Validates: Requirements 25.3**

    When SafetyService.filter_response raises SafetyFilterError during token
    streaming, the AIOrchestrator must:
    - Send {"type": "error", ...} to the WebSocket (blocked response).
    - NOT send the harmful token to the WebSocket.
    - Re-raise SafetyFilterError so the caller can take further action.
    """
    from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

    # Build mock WebSocket that records sent messages.
    mock_ws = AsyncMock()
    sent_messages: list[dict] = []

    async def capture_send_json(payload: dict) -> None:
        sent_messages.append(payload)

    mock_ws.send_json = capture_send_json

    # Mock AsyncSession — AIOrchestrator stores it but we won't call real DB ops.
    mock_db = MagicMock()
    mock_db.add = MagicMock()
    mock_db.flush = AsyncMock()
    mock_db.commit = AsyncMock()
    mock_db.rollback = AsyncMock()

    # Mock MemoryService to avoid DB calls.
    mock_memory_service = AsyncMock()
    mock_memory_service.get_relevant_memories = AsyncMock(return_value=[])

    # Build orchestrator instance.
    orchestrator = AIOrchestrator(db=mock_db, memory_service=mock_memory_service)

    async def run_stream_chat() -> None:
        # Patch _apply_safety_filters to raise SafetyFilterError for any input.
        async def mock_apply_safety_filters(token: str) -> str:
            raise SafetyFilterError(
                "Safety filter failed to redact harmful content; blocking entire response."
            )

        # Patch _build_prompt to avoid DB/LLM calls.
        from app.services.ai_orchestrator import PromptContext, PromptMessage

        async def mock_build_prompt(
            conversation_id: str, user_id: str, message: str
        ) -> PromptContext:
            return PromptContext(
                messages=[PromptMessage(role="system", content="Test system prompt")],
                estimated_tokens=10,
                provider=LLMProvider.openai,
                user_id=user_id,
            )

        # Patch _detect_prompt_injection to always return False (clean input).
        async def mock_detect_injection(text: str) -> bool:
            return False

        # Patch _resolve_provider to return a mock LLM client that yields the harmful token.
        async def mock_resolve_provider(provider: LLMProvider):
            mock_client = MagicMock()

            async def mock_stream(context):
                yield harmful

            mock_client.stream = mock_stream
            mock_client.max_output_tokens = 2048
            mock_client.cost_per_input_token = __import__("decimal").Decimal("0")
            mock_client.cost_per_output_token = __import__("decimal").Decimal("0")
            return mock_client

        with (
            patch.object(
                orchestrator, "_apply_safety_filters", mock_apply_safety_filters
            ),
            patch.object(orchestrator, "_build_prompt", mock_build_prompt),
            patch.object(
                orchestrator, "_detect_prompt_injection", mock_detect_injection
            ),
            patch.object(orchestrator, "_resolve_provider", mock_resolve_provider),
        ):
            await orchestrator.stream_chat(
                conversation_id="00000000-0000-0000-0000-000000000001",
                user_message="Hello",
                provider=LLMProvider.openai,
                user_id="00000000-0000-0000-0000-000000000002",
                ws=mock_ws,
            )

    # stream_chat must re-raise SafetyFilterError.
    with pytest.raises(SafetyFilterError):
        asyncio.run(run_stream_chat())

    # Verify the WebSocket received an error message (response blocked).
    error_messages = [m for m in sent_messages if m.get("type") == "error"]
    assert error_messages, (
        f"Expected a WebSocket error message when SafetyFilterError is raised for "
        f"harmful token {harmful!r}. Sent messages: {sent_messages}"
    )

    # The error message must reference the safety filter.
    error_text = error_messages[0].get("message", "")
    assert (
        "safety" in error_text.lower() or "blocked" in error_text.lower()
    ), f"Error message does not mention safety/blocked. Got: {error_text!r}"

    # The harmful token must NOT have been sent as a "token" message.
    token_messages = [m for m in sent_messages if m.get("type") == "token"]
    for msg in token_messages:
        assert harmful not in msg.get("data", ""), (
            f"Harmful token {harmful!r} was sent to WebSocket before being blocked. "
            f"Token messages: {token_messages}"
        )
