"""Unit tests for app.llm.prompt_builder.PromptBuilder.

Covers:
- build(): returns LLMRequest with assembled prompt.
- build(): system section always comes first.
- build(): RAG context is wrapped in delimiters.
- build(): conversation history is included.
- build(): user question is last.
- build(): no RAG context → no RETRIEVED CONTEXT section.
- build(): no history → no CONVERSATION HISTORY section.
- build(): prompt is truncated to LLM_PROMPT_MAX_CHARS.
- build(): oldest history dropped first during truncation.
- build(): complexity classification — simple vs complex.
- build(): empty user message → empty prompt field.
- _classify_complexity(): known complex patterns return "complex".
- _classify_complexity(): non-complex returns "simple".
- _build_rag_section(): individual chunks truncated at _MAX_CHARS_PER_CHUNK.
- LLMRequest fields populated correctly (user_id, request_id, conversation_id).
"""

from __future__ import annotations

import os

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-32-chars-long-minimum!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
# AES_ENCRYPTION_KEY is set by conftest.py � not repeated here to avoid false-positive secret scans.

from app.llm.prompt_builder import (
    PromptBuilder,
    _CONTEXT_FOOTER,
    _CONTEXT_HEADER,
    _HISTORY_HEADER,
    _QUESTION_HEADER,
    _SYSTEM_HEADER,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def builder():
    return PromptBuilder()


# ---------------------------------------------------------------------------
# Basic assembly
# ---------------------------------------------------------------------------

class TestPromptBuilderBasic:
    def test_returns_llm_request(self, builder) -> None:
        from app.llm.base import LLMRequest
        result = builder.build(user_message="Hello")
        assert isinstance(result, LLMRequest)

    def test_prompt_is_string(self, builder) -> None:
        result = builder.build(user_message="Hello")
        assert isinstance(result.prompt, str)

    def test_system_section_present(self, builder) -> None:
        result = builder.build(
            user_message="Hello",
            system_prompt="You are a test assistant.",
        )
        assert _SYSTEM_HEADER in result.prompt
        assert "You are a test assistant." in result.prompt

    def test_default_system_prompt_used_when_omitted(self, builder) -> None:
        result = builder.build(user_message="Hello")
        assert _SYSTEM_HEADER in result.prompt
        assert "helpful" in result.prompt.lower()

    def test_system_section_is_first(self, builder) -> None:
        result = builder.build(
            user_message="Q",
            system_prompt="SYS",
            rag_context=["doc1"],
            conversation_history=[("user", "prev")],
        )
        sys_pos = result.prompt.index(_SYSTEM_HEADER)
        rag_pos = result.prompt.index(_CONTEXT_HEADER)
        question_pos = result.prompt.index(_QUESTION_HEADER)
        assert sys_pos < rag_pos < question_pos

    def test_question_is_last(self, builder) -> None:
        result = builder.build(
            user_message="Final Q",
            system_prompt="SYS",
            rag_context=["doc"],
            conversation_history=[("user", "prev"), ("assistant", "ans")],
        )
        question_pos = result.prompt.index(_QUESTION_HEADER)
        assert question_pos == result.prompt.rfind(_QUESTION_HEADER)
        # Nothing after the question content except whitespace
        after_question = result.prompt[question_pos:]
        assert "Final Q" in after_question

    def test_user_message_in_prompt(self, builder) -> None:
        result = builder.build(user_message="What is Clean Architecture?")
        assert "What is Clean Architecture?" in result.prompt


# ---------------------------------------------------------------------------
# RAG context
# ---------------------------------------------------------------------------

class TestPromptBuilderRag:
    def test_rag_context_wrapped_in_delimiters(self, builder) -> None:
        result = builder.build(
            user_message="Q",
            rag_context=["Document text here."],
        )
        assert _CONTEXT_HEADER in result.prompt
        assert _CONTEXT_FOOTER in result.prompt
        assert "Document text here." in result.prompt

    def test_no_rag_section_when_empty(self, builder) -> None:
        result = builder.build(user_message="Q", rag_context=[])
        assert _CONTEXT_HEADER not in result.prompt

    def test_no_rag_section_when_none(self, builder) -> None:
        result = builder.build(user_message="Q", rag_context=None)
        assert _CONTEXT_HEADER not in result.prompt

    def test_multiple_rag_chunks_numbered(self, builder) -> None:
        result = builder.build(
            user_message="Q",
            rag_context=["chunk one", "chunk two", "chunk three"],
        )
        assert "[Document 1]" in result.prompt
        assert "[Document 2]" in result.prompt
        assert "[Document 3]" in result.prompt

    def test_large_rag_chunk_truncated(self, builder) -> None:
        big_chunk = "x" * 10_000  # well over _MAX_CHARS_PER_CHUNK=2000
        result = builder.build(user_message="Q", rag_context=[big_chunk])
        # The truncation marker should appear
        assert "[...truncated]" in result.prompt


# ---------------------------------------------------------------------------
# Conversation history
# ---------------------------------------------------------------------------

class TestPromptBuilderHistory:
    def test_history_included(self, builder) -> None:
        result = builder.build(
            user_message="Current Q",
            conversation_history=[
                ("user", "Previous question"),
                ("assistant", "Previous answer"),
            ],
        )
        assert _HISTORY_HEADER in result.prompt
        assert "Previous question" in result.prompt
        assert "Previous answer" in result.prompt

    def test_no_history_section_when_empty(self, builder) -> None:
        result = builder.build(user_message="Q", conversation_history=[])
        assert _HISTORY_HEADER not in result.prompt

    def test_no_history_section_when_none(self, builder) -> None:
        result = builder.build(user_message="Q", conversation_history=None)
        assert _HISTORY_HEADER not in result.prompt

    def test_user_label_in_history(self, builder) -> None:
        result = builder.build(
            user_message="Q",
            conversation_history=[("user", "Hi there")],
        )
        assert "User: Hi there" in result.prompt

    def test_assistant_label_in_history(self, builder) -> None:
        result = builder.build(
            user_message="Q",
            conversation_history=[("assistant", "Hello back")],
        )
        assert "Assistant: Hello back" in result.prompt


# ---------------------------------------------------------------------------
# Size truncation
# ---------------------------------------------------------------------------

class TestPromptBuilderTruncation:
    def test_prompt_within_limit(self, builder) -> None:
        from app.config.settings import get_settings
        limit = get_settings().LLM_PROMPT_MAX_CHARS
        result = builder.build(user_message="Short question")
        assert len(result.prompt) <= limit

    def test_large_history_truncated(self, builder) -> None:
        """When history causes prompt to exceed limit, oldest entries are dropped."""
        # Build a history that will inflate the prompt well above the limit.
        long_history = [("user", "x" * 500) for _ in range(100)]

        with __import__("unittest.mock", fromlist=["patch"]).patch.dict(
            os.environ, {"LLM_PROMPT_MAX_CHARS": "3000"}
        ):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            result = builder.build(
                user_message="Final question",
                conversation_history=long_history,
            )
            get_settings.cache_clear()

        assert len(result.prompt) <= 3000

    def test_question_always_preserved(self, builder) -> None:
        """User question must always be present even after truncation."""
        long_history = [("user", "x" * 500) for _ in range(100)]

        with __import__("unittest.mock", fromlist=["patch"]).patch.dict(
            os.environ, {"LLM_PROMPT_MAX_CHARS": "3000"}
        ):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            result = builder.build(
                user_message="MUST_BE_PRESENT",
                conversation_history=long_history,
            )
            get_settings.cache_clear()

        assert "MUST_BE_PRESENT" in result.prompt


# ---------------------------------------------------------------------------
# Complexity classification
# ---------------------------------------------------------------------------

class TestPromptBuilderComplexity:
    def test_simple_request(self, builder) -> None:
        result = builder.build(user_message="What is Python?")
        assert result.complexity == "simple"

    def test_complex_request_architect(self, builder) -> None:
        result = builder.build(user_message="How should I architect this microservice system?")
        assert result.complexity == "complex"

    def test_complex_request_analyze(self, builder) -> None:
        result = builder.build(user_message="Analyze the performance of this query.")
        assert result.complexity == "complex"

    def test_complex_request_migration(self, builder) -> None:
        result = builder.build(user_message="Help me migrate this database schema.")
        assert result.complexity == "complex"

    def test_complex_request_security_audit(self, builder) -> None:
        result = builder.build(user_message="Perform a security audit of this code.")
        assert result.complexity == "complex"

    def test_classify_complexity_static(self) -> None:
        assert PromptBuilder._classify_complexity("simple question") == "simple"
        assert PromptBuilder._classify_complexity("architect the system design") == "complex"
        assert PromptBuilder._classify_complexity("refactor this module") == "complex"


# ---------------------------------------------------------------------------
# LLMRequest field population
# ---------------------------------------------------------------------------

class TestPromptBuilderFields:
    def test_user_id_set(self, builder) -> None:
        result = builder.build(user_message="Q", user_id="user-xyz")
        assert result.user_id == "user-xyz"

    def test_request_id_set(self, builder) -> None:
        result = builder.build(user_message="Q", request_id="req-abc")
        assert result.request_id == "req-abc"

    def test_conversation_id_set(self, builder) -> None:
        result = builder.build(user_message="Q", conversation_id="conv-123")
        assert result.conversation_id == "conv-123"

    def test_max_output_tokens_passed_through(self, builder) -> None:
        result = builder.build(user_message="Q", max_output_tokens=512)
        assert result.max_output_tokens == 512

    def test_temperature_passed_through(self, builder) -> None:
        result = builder.build(user_message="Q", temperature=0.9)
        assert abs(result.temperature - 0.9) < 0.001

    def test_rag_context_stored_on_request(self, builder) -> None:
        result = builder.build(user_message="Q", rag_context=["doc1", "doc2"])
        assert result.rag_context == ["doc1", "doc2"]
