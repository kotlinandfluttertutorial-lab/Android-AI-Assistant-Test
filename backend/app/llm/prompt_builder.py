# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm
# File    : prompt_builder.py
# Purpose : Assembles LLMRequest prompts from structured inputs.
#           Enforces size limits and injection protection.
# ============================================================

"""PromptBuilder — assembles a safe, cost-controlled LLM prompt.

Responsibilities
----------------
1. Combine system instructions, RAG context, conversation history, and the
   current user question into a single structured prompt string.
2. Enforce the ``LLM_PROMPT_MAX_CHARS`` size limit — truncate gracefully
   rather than fail hard.
3. Prevent retrieved RAG documents from overriding system instructions
   (prompt injection via untrusted documents).
4. Classify request complexity to help ``LLMService`` route to the right model.

Security
--------
- System instructions are ALWAYS placed before user content and RAG context.
- RAG documents are wrapped in a clear delimiter so the model sees them as
  external data, not instructions.
- User input is never interpolated into the system prompt.
- The assembled prompt is length-limited before any API call.

Cost optimisation
-----------------
- History is truncated from the oldest end when the total size exceeds the
  limit, so recent context is always preserved.
- RAG context is truncated per-document rather than dropped entirely, keeping
  the most relevant content.
"""

from __future__ import annotations

import logging
import re

from app.config.settings import get_settings
from app.llm.base import LLMRequest

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Section delimiters — visible to the model to signal data boundaries.
# ---------------------------------------------------------------------------
_SYSTEM_HEADER = "## SYSTEM INSTRUCTIONS"
_CONTEXT_HEADER = "## RETRIEVED CONTEXT"
_CONTEXT_FOOTER = "## END OF RETRIEVED CONTEXT"
_HISTORY_HEADER = "## CONVERSATION HISTORY"
_QUESTION_HEADER = "## USER QUESTION"

# Max characters per individual RAG document chunk before truncation.
_MAX_CHARS_PER_CHUNK = 2_000

# Complexity classification: patterns that indicate a complex request.
# Deterministic — no extra LLM call.
_COMPLEX_PATTERNS: list[re.Pattern[str]] = [
    re.compile(p, re.IGNORECASE)
    for p in [
        r"\barchitect\b",
        r"\bdesign\b.*\bsystem\b",
        r"\boptimis[ez]\b",
        r"\brefactor\b",
        r"\banalyse\b|\banalyze\b",
        r"\bcompare\b.*\bapproach\b",
        r"\bperformance\b.*\bimprove\b",
        r"\bsecurity\b.*\baudit\b",
        r"\bmigrat",
        r"\bstrategy\b",
    ]
]


class PromptBuilder:
    """Assembles ``LLMRequest`` objects from structured inputs.

    Typical usage inside a route handler or service::

        builder = PromptBuilder()
        llm_request = builder.build(
            user_message="What is Clean Architecture?",
            system_prompt="You are an expert software engineer.",
            rag_context=["Clean Architecture separates concerns into layers..."],
            conversation_history=[("user", "Hi"), ("assistant", "Hello!")],
            conversation_id="conv-123",
            user_id="user-456",
            request_id="req-789",
        )

    The returned ``LLMRequest.prompt`` is already assembled and size-limited.
    """

    def build(
        self,
        user_message: str,
        system_prompt: str = "",
        rag_context: list[str] | None = None,
        conversation_history: list[tuple[str, str]] | None = None,
        conversation_id: str | None = None,
        user_id: str | None = None,
        request_id: str = "",
        max_output_tokens: int | None = None,
        temperature: float | None = None,
    ) -> LLMRequest:
        """Build a complete ``LLMRequest`` ready for ``LLMService.generate()``.

        The prompt is assembled in this order:
        1. ``## SYSTEM INSTRUCTIONS`` — static, never influenced by user input.
        2. ``## RETRIEVED CONTEXT``   — RAG documents (untrusted; delimited).
        3. ``## CONVERSATION HISTORY``— previous turns (oldest → newest).
        4. ``## USER QUESTION``       — current user message.

        The total prompt is then truncated to ``LLM_PROMPT_MAX_CHARS`` by
        removing the oldest history entries first, then truncating individual
        RAG chunks.

        Args:
            user_message:         The current user message.
            system_prompt:        System instructions.  Defaults to a generic
                                  helpful-assistant prompt.
            rag_context:          Retrieved document snippets.
            conversation_history: List of ``(role, content)`` tuples, oldest first.
            conversation_id:      Conversation UUID for logging.
            user_id:              User UUID for rate limiting.
            request_id:           Correlation ID.
            max_output_tokens:    Override the provider's default output cap.
            temperature:          Override the provider's default temperature.

        Returns:
            Assembled ``LLMRequest``.
        """
        settings = get_settings()
        max_chars = settings.LLM_PROMPT_MAX_CHARS

        rag_context = rag_context or []
        conversation_history = conversation_history or []

        # Build each section independently so we can measure sizes.
        system_section = self._build_system_section(system_prompt)
        rag_section = self._build_rag_section(rag_context)
        history_section = self._build_history_section(conversation_history)
        question_section = self._build_question_section(user_message)

        # Assemble and apply size limit.
        prompt = self._assemble_and_truncate(
            system_section=system_section,
            rag_section=rag_section,
            history_section=history_section,
            question_section=question_section,
            max_chars=max_chars,
            user_message=user_message,
            conversation_history=conversation_history,
            rag_context=rag_context,
        )

        complexity = self._classify_complexity(user_message)

        if settings.LLM_LOG_PROMPTS:
            logger.debug(
                "PromptBuilder assembled prompt (LLM_LOG_PROMPTS=true)",
                extra={
                    "request_id": request_id,
                    "prompt_chars": len(prompt),
                    "rag_chunks": len(rag_context),
                    "history_turns": len(conversation_history),
                    "complexity": complexity,
                    "prompt_preview": prompt[:300],
                },
            )
        else:
            logger.debug(
                "PromptBuilder assembled prompt",
                extra={
                    "request_id": request_id,
                    "prompt_chars": len(prompt),
                    "rag_chunks": len(rag_context),
                    "history_turns": len(conversation_history),
                    "complexity": complexity,
                },
            )

        return LLMRequest(
            prompt=prompt,
            system_prompt=system_prompt or self._default_system_prompt(),
            conversation_id=conversation_id,
            user_id=user_id,
            request_id=request_id,
            max_output_tokens=max_output_tokens,
            temperature=temperature,
            rag_context=rag_context,
            complexity=complexity,
        )

    # ------------------------------------------------------------------
    # Section builders
    # ------------------------------------------------------------------

    @staticmethod
    def _default_system_prompt() -> str:
        return (
            "You are a helpful, accurate, and concise AI assistant. "
            "Answer the user's question based on the provided context. "
            "If you are unsure, say so rather than guessing."
        )

    def _build_system_section(self, system_prompt: str) -> str:
        content = system_prompt.strip() or self._default_system_prompt()
        return f"{_SYSTEM_HEADER}\n{content}\n"

    @staticmethod
    def _build_rag_section(rag_context: list[str]) -> str:
        if not rag_context:
            return ""
        chunks: list[str] = []
        for i, chunk in enumerate(rag_context, start=1):
            # Truncate individual chunks to keep prompt balanced.
            safe_chunk = chunk[:_MAX_CHARS_PER_CHUNK]
            if len(chunk) > _MAX_CHARS_PER_CHUNK:
                safe_chunk += "\n[...truncated]"
            chunks.append(f"[Document {i}]\n{safe_chunk}")
        body = "\n\n".join(chunks)
        return f"{_CONTEXT_HEADER}\n{body}\n{_CONTEXT_FOOTER}\n"

    @staticmethod
    def _build_history_section(history: list[tuple[str, str]]) -> str:
        if not history:
            return ""
        lines: list[str] = []
        for role, content in history:
            label = "User" if role == "user" else "Assistant"
            lines.append(f"{label}: {content.strip()}")
        body = "\n".join(lines)
        return f"{_HISTORY_HEADER}\n{body}\n"

    @staticmethod
    def _build_question_section(user_message: str) -> str:
        return f"{_QUESTION_HEADER}\n{user_message.strip()}\n"

    # ------------------------------------------------------------------
    # Assembly and truncation
    # ------------------------------------------------------------------

    def _assemble_and_truncate(
        self,
        system_section: str,
        rag_section: str,
        history_section: str,
        question_section: str,
        max_chars: int,
        user_message: str,
        conversation_history: list[tuple[str, str]],
        rag_context: list[str],
    ) -> str:
        """Assemble sections and truncate to ``max_chars``.

        Truncation order (oldest/cheapest first):
        1. Drop oldest history entries one by one.
        2. Shorten individual RAG chunks.
        3. Truncate the system prompt suffix (last resort).

        Args:
            system_section:      Pre-built system section string.
            rag_section:         Pre-built RAG section string.
            history_section:     Pre-built history section string.
            question_section:    Pre-built question section string.
            max_chars:           Maximum total character count.
            user_message:        Original user message (for rebuild).
            conversation_history: Original history (for rebuild on truncation).
            rag_context:         Original RAG chunks (for rebuild on truncation).

        Returns:
            Assembled prompt string within ``max_chars``.
        """
        prompt = system_section + rag_section + history_section + question_section

        if len(prompt) <= max_chars:
            return prompt

        # Step 1: drop oldest history entries.
        trimmed_history = list(conversation_history)
        while len(prompt) > max_chars and len(trimmed_history) > 0:
            trimmed_history.pop(0)
            history_section = self._build_history_section(trimmed_history)
            prompt = system_section + rag_section + history_section + question_section

        if len(prompt) <= max_chars:
            if len(trimmed_history) < len(conversation_history):
                logger.debug(
                    "PromptBuilder truncated %d history entries to fit within limit",
                    len(conversation_history) - len(trimmed_history),
                )
            return prompt

        # Step 2: shorten RAG chunks.
        if rag_context:
            chunk_limit = _MAX_CHARS_PER_CHUNK
            while len(prompt) > max_chars and chunk_limit > 200:
                chunk_limit = max(200, chunk_limit // 2)
                truncated_rag = [c[:chunk_limit] for c in rag_context]
                rag_section = self._build_rag_section(truncated_rag)
                prompt = system_section + rag_section + history_section + question_section

        if len(prompt) <= max_chars:
            return prompt

        # Step 3: hard truncate (should be extremely rare).
        logger.warning(
            "PromptBuilder hard-truncating prompt from %d to %d chars",
            len(prompt),
            max_chars,
        )
        return prompt[:max_chars]

    # ------------------------------------------------------------------
    # Complexity classification
    # ------------------------------------------------------------------

    @staticmethod
    def _classify_complexity(user_message: str) -> str:
        """Classify message complexity using deterministic regex rules.

        Returns ``"complex"`` when the message matches known complex-task
        patterns (architecture, security audit, migration, etc.), otherwise
        returns ``"simple"``.

        No additional LLM call is made.

        Args:
            user_message: The user's raw message.

        Returns:
            ``"complex"`` or ``"simple"``.
        """
        for pattern in _COMPLEX_PATTERNS:
            if pattern.search(user_message):
                return "complex"
        return "simple"
