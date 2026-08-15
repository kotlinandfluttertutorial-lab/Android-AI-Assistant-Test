# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : ai_orchestrator.py
# Purpose : ai_orchestrator — services module
#
# Architecture Layer : Service
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""AI Orchestrator — provider-agnostic LLM interaction hub.

The ``AIOrchestrator`` is the single entry point for all LLM calls in the
system. It:

1. Resolves the active LLM provider to a concrete ``BaseLLMClient`` adapter.
2. Builds a structured prompt from:
   - A versioned system prompt (persona + scope + safety rules).
   - Top-3 semantically relevant user memories from ``MemoryService``.
   - Conversation history (summarized when > 80 % of the context window is used).
   - The current user message.
3. Detects and rejects prompt injection attempts before any LLM call.
4. Applies safety filters to user input and assistant output.
5. Streams tokens incrementally over a WebSocket (``stream_chat``).
6. Records input/output token usage in the ``token_usage`` table (``TokenUsage``).

Safety enforcement
------------------
- ``_apply_safety_filters`` delegates to ``SafetyService.filter_response``.
  If ``SafetyFilterError`` is raised the entire response is blocked (Property 14).
- ``_detect_prompt_injection`` delegates to the module-level static helper.
  The ``InjectionDetector`` class (``safety_service.py``) handles audit-log
  writing and HTTP-layer blocking for REST endpoints (Property 13).

Provider clients
----------------
Six concrete clients are defined in ``app.services.llm_clients``:
``OpenAIClient``, ``GeminiClient``, ``ClaudeClient``, ``OllamaClient``,
``LlamaClient``, ``MistralClient``.  They are instantiated lazily and cached
on first use.

Graceful degradation
--------------------
- If ``MemoryService`` raises or returns no results, prompt construction
  continues without memory injection (Requirement 7.2).
- If the active provider fails, a fallback provider (if configured) is tried
  automatically and the user is notified (Requirement 3.3).

Requirements: 2.1, 2.2, 2.3, 2.4, 2.9, 3.3, 7.2, 25.3, 25.4, 25.6
"""

from __future__ import annotations

import enum
import logging
import re
import uuid
from dataclasses import dataclass, field
from decimal import Decimal

from fastapi import WebSocket
from sqlalchemy.ext.asyncio import AsyncSession

from app.config.settings import get_settings
from app.models.message import Message, MessageRole
from app.models.token_usage import TokenUsage, UsageFeature
from app.prompts.system_prompts import (
    build_base_system_prompt,
    build_summarization_prompt,
)
from app.repositories.message_repository import MessageRepository
from app.repositories.token_usage_repository import TokenUsageRepository
from app.services.llm_clients import (
    BaseLLMClient,
    ClaudeClient,
    GeminiClient,
    LlamaClient,
    MistralClient,
    OllamaClient,
    OpenAIClient,
)
from app.services.llm_clients import (
    PromptContext as LLMPromptContext,
)
from app.services.memory_service import MemoryService
from app.services.safety_service import SafetyFilterError, SafetyService

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Token estimation helpers
# ---------------------------------------------------------------------------

_CHARS_PER_TOKEN = 4  # conservative estimate: 1 token ≈ 4 characters


def _estimate_tokens(text: str) -> int:
    """Estimate the token count of *text* using a 4 chars/token heuristic.

    This is a fast approximation used for context window budget calculations.
    Exact counts are taken from the provider's response metadata when available.

    Args:
        text: The text to estimate.

    Returns:
        Estimated token count.

    Requirements: 2.3, 2.4
    """
    return max(1, len(text) // _CHARS_PER_TOKEN)


# ---------------------------------------------------------------------------
# Enums and shared data structures
# ---------------------------------------------------------------------------


class LLMProvider(str, enum.Enum):
    """Supported LLM providers.

    Requirements: 3.1
    """

    openai = "openai"
    gemini = "gemini"
    claude = "claude"
    ollama = "ollama"
    llama = "llama"
    mistral = "mistral"


@dataclass
class PromptMessage:
    """A single message in the assembled prompt context.

    Attributes:
        role: Message role ("system", "user", "assistant").
        content: Message text content.
    """

    role: str
    content: str


@dataclass
class PromptContext:
    """The fully assembled prompt context passed to LLM clients.

    Attributes:
        messages: Ordered list of messages (system → memories → history → user).
        estimated_tokens: Rough token budget estimate for the entire context.
        provider: The resolved LLM provider for this request.
        user_id: User identifier for logging and rate limiting.
    """

    messages: list[PromptMessage] = field(default_factory=list)
    estimated_tokens: int = 0
    provider: LLMProvider = LLMProvider.openai
    user_id: str | None = None


@dataclass
class CompletionResult:
    """Result of a non-streaming LLM completion.

    Attributes:
        text: The generated text response.
        input_tokens: Number of tokens in the prompt.
        output_tokens: Number of tokens in the response.
    """

    text: str
    input_tokens: int = 0
    output_tokens: int = 0


# ---------------------------------------------------------------------------
# Prompt injection detection
# ---------------------------------------------------------------------------

# Patterns that indicate a User is attempting to override the system prompt.
# Case-insensitive matching.
_INJECTION_PATTERNS: list[re.Pattern[str]] = [
    re.compile(pattern, re.IGNORECASE)
    for pattern in [
        r"ignore\s+(all\s+)?previous\s+instructions?",
        r"disregard\s+(all\s+)?previous",
        r"forget\s+(all\s+)?previous\s+instructions?",
        r"forget\s+your\s+(instructions?|training|rules?)",
        r"system\s*:\s*",  # "system: ..." directly in user message
        r"you\s+are\s+now\s+",  # "You are now a..."
        r"new\s+system\s+prompt",
        r"override\s+(the\s+)?(system|prompt|instructions?)",
        r"your\s+(new\s+)?(true|real|actual)\s+(identity|self|persona|role)",
        r"pretend\s+(to\s+be|you\s+are)\s+",
        r"act\s+as\s+(if\s+you\s+(are|were)\s+)?(?:a\s+)?(?!helpful|an?\s+AI)",
        r"\[SYSTEM\]",
        r"<system>",
        r"</?(inst|s|INST)>",  # LLaMA instruction tokens in user input
    ]
]


def _detect_prompt_injection_static(text: str) -> bool:
    """Detect prompt injection patterns in *text* using static regex rules.

    Returns ``True`` if an injection attempt is detected, ``False`` otherwise.

    Args:
        text: The user-provided message to inspect.

    Returns:
        ``True`` when any injection pattern matches.

    Requirements: 25.6, 9.6
    """
    for pattern in _INJECTION_PATTERNS:
        if pattern.search(text):
            logger.warning(
                "Prompt injection pattern detected: %r matched in message excerpt: %.100r",
                pattern.pattern,
                text,
            )
            return True
    return False


# ---------------------------------------------------------------------------
# Safety filter
# ---------------------------------------------------------------------------

# Known harmful pattern fragments to strip from output.
# This is a lightweight placeholder; a production system would integrate a
# dedicated content-moderation API (e.g. OpenAI Moderation API).
_HARMFUL_OUTPUT_PATTERNS: list[re.Pattern[str]] = [
    # Placeholder patterns — extend as needed.
    # Match <script ...>...</script> including variants like </script > or
    # </script\n> where the closing tag may contain whitespace/attributes.
    re.compile(r"<\s*script[\s\S]*?>[\s\S]*?<\s*/\s*script[\s\S]*?>", re.IGNORECASE),
    re.compile(r"javascript\s*:", re.IGNORECASE),
]


def _apply_safety_filters_static(text: str) -> str:
    """Apply lightweight safety filtering to *text*.

    Strips known harmful patterns from LLM output before it is delivered to
    the client. This is a best-effort defence; a production deployment should
    integrate a dedicated content-moderation service.

    Args:
        text: The raw LLM output.

    Returns:
        Sanitized text.

    # TODO: Integrate OpenAI Moderation API or equivalent for production use.

    Requirements: 9.6
    """
    for pattern in _HARMFUL_OUTPUT_PATTERNS:
        text = pattern.sub("[content removed]", text)
    return text


# ---------------------------------------------------------------------------
# Main orchestrator class
# ---------------------------------------------------------------------------


class AIOrchestrator:
    """Provider-agnostic LLM orchestration service.

    Accepts a ``WebSocket`` and ``AsyncSession`` on construction so that a
    single orchestrator instance can handle one streaming request. It may be
    instantiated per-request by FastAPI dependency injection.

    Args:
        db: SQLAlchemy async session (scoped to the current request).
        memory_service: Optional memory service instance. If ``None``, a new
            instance will be created from *db*.

    Requirements: 2.1, 2.2, 2.3, 2.4, 2.9, 7.2, 25.6
    """

    # Context threshold — summarize when history exceeds this fraction of the
    # provider's context window (Requirement 2.4).
    SUMMARIZE_THRESHOLD = 0.80

    # Fraction of messages to summarize when the threshold is hit.
    # The oldest half of the messages (excluding system) are replaced by a
    # summary.
    SUMMARIZE_OLDEST_FRACTION = 0.5

    # Summary placeholder prefix in conversation history.
    SUMMARY_ROLE = "system"
    SUMMARY_PREFIX = "[Conversation Summary] "

    def __init__(
        self,
        db: AsyncSession,
        memory_service: MemoryService | None = None,
    ) -> None:
        self._db = db
        self._memory_service = memory_service or MemoryService(db)
        self._message_repo = MessageRepository(db)
        self._token_usage_repo = TokenUsageRepository(db)
        # Cache of provider instances (lazy-initialised on first use per provider)
        self._provider_cache: dict[LLMProvider, BaseLLMClient] = {}
        # Safety service — applied to every LLM response (Requirement 25.3, Property 14)
        self._safety_service = SafetyService()

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    async def stream_chat(
        self,
        conversation_id: str,
        user_message: str,
        provider: LLMProvider,
        user_id: str,
        ws: WebSocket,
        persona_id: str | None = None,
        feature: UsageFeature = UsageFeature.chat,
    ) -> TokenUsage:
        """Stream an AI response over a WebSocket connection.

        Workflow:
        1. Detect prompt injection in ``user_message``; raise on detection.
        2. Build the prompt context (system + memories + history + user msg).
        3. Resolve the provider client.
        4. Stream tokens from the provider, emitting each as a JSON message.
        5. Emit the ``done`` event with usage statistics.
        6. Persist the user message, assistant message, and ``TokenUsage`` row.
        7. Return the ``TokenUsage`` ORM instance.

        Args:
            conversation_id: UUID string of the target conversation.
            user_message: The raw user input message.
            provider: The LLM provider to use for this request.
            user_id: UUID string of the authenticated user.
            ws: FastAPI WebSocket connection for token streaming.
            persona_id: Optional UUID string of the persona to inject into the
                system prompt. When provided, the persona's system_prompt, tone,
                and scope_description replace the default base system prompt.
                For non-admin users, platform safety rules are appended after
                the persona content (Requirement 32.4).
            feature: The AI feature context generating this usage record
                (chat/rag/code/voice/comparison/suggestions). Defaults to
                ``chat``. Callers should pass the appropriate feature value so
                the Cost Dashboard can break down costs by feature (Req 34.1).

        Returns:
            The persisted :class:`~app.models.token_usage.TokenUsage` row.

        Raises:
            ValueError: If prompt injection is detected in the user message.

        Requirements: 2.1, 2.2, 2.9, 25.6, 32.2, 32.4
        """
        # Step 1 — Prompt injection guard (Requirement 25.6, 9.6)
        if await self._detect_prompt_injection(user_message):
            error_payload = {
                "type": "error",
                "message": (
                    "Your message was blocked because it appears to contain a "
                    "prompt injection attempt. Please rephrase your request."
                ),
            }
            await ws.send_json(error_payload)
            raise ValueError(
                f"Prompt injection detected in message from user {user_id}"
            )

        conv_uuid = uuid.UUID(conversation_id)
        user_uuid = uuid.UUID(user_id)

        # Step 2 — Persist the user message first (so it's included in history
        # on the NEXT turn; for this turn it's appended to context directly)
        await self._message_repo.create(
            conversation_id=conv_uuid,
            role=MessageRole.user,
            content=user_message,
            provider=provider.value,
        )

        # Step 3 — Build prompt context (Requirement 2.3, 7.2)
        context = await self._build_prompt(
            conversation_id, user_id, user_message, persona_id=persona_id
        )

        # Step 4 — Resolve provider client (Requirement 3.1)
        client = await self._resolve_provider(provider)

        # Step 5 — Stream tokens from the provider (Requirement 2.2, 3.3)
        output_tokens = 0
        collected_tokens: list[str] = []

        llm_context = self._to_llm_prompt_context(context, user_id, client=client)
        active_provider = provider  # may be updated to fallback

        try:
            async for token in client.stream(llm_context):
                safe_token = await self._apply_safety_filters(token)
                collected_tokens.append(safe_token)
                output_tokens += _estimate_tokens(safe_token)
                await ws.send_json({"type": "token", "data": safe_token})

        except SafetyFilterError as safety_exc:
            # Property 14 / Requirement 25.3: safety filter could not redact
            # harmful content — block the entire response.
            logger.error(
                "Safety filter blocked entire response for user %s: %s",
                user_id,
                safety_exc,
            )
            await ws.send_json(
                {
                    "type": "error",
                    "message": "The response was blocked by the content safety filter.",
                }
            )
            raise

        except Exception as primary_exc:
            # Requirement 3.3: attempt fallback provider on any error.
            fallback_provider = self._get_fallback_provider(provider)

            if fallback_provider is None:
                # No fallback configured — re-raise so the caller can handle
                logger.error(
                    "Provider '%s' failed and no fallback is configured: %s",
                    provider.value,
                    primary_exc,
                )
                raise

            logger.warning(
                "Provider '%s' failed; falling back to '%s'. Error: %s",
                provider.value,
                fallback_provider.value,
                primary_exc,
            )

            # Notify the user of the substitution (Requirement 3.3)
            await ws.send_json(
                {
                    "type": "notice",
                    "message": (
                        f"The '{provider.value}' provider encountered an error. "
                        f"Your request is being handled by the fallback provider "
                        f"'{fallback_provider.value}'."
                    ),
                }
            )

            fallback_client = await self._resolve_provider(fallback_provider)
            active_provider = fallback_provider
            collected_tokens.clear()
            output_tokens = 0

            try:
                async for token in fallback_client.stream(llm_context):
                    safe_token = await self._apply_safety_filters(token)
                    collected_tokens.append(safe_token)
                    output_tokens += _estimate_tokens(safe_token)
                    await ws.send_json({"type": "token", "data": safe_token})
            except SafetyFilterError as safety_exc:
                # Property 14 / Requirement 25.3: block entire fallback response.
                logger.error(
                    "Safety filter blocked fallback response for user %s: %s",
                    user_id,
                    safety_exc,
                )
                await ws.send_json(
                    {
                        "type": "error",
                        "message": "The response was blocked by the content safety filter.",
                    }
                )
                raise

        assistant_response = "".join(collected_tokens)
        input_tokens = context.estimated_tokens

        # Step 6 — Emit the done event (Requirement 2.2)
        usage_payload = {
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "provider": active_provider.value,
        }
        await ws.send_json({"type": "done", "usage": usage_payload})

        # Step 7 — Persist the assistant message (Requirement 2.9)
        assistant_msg = await self._message_repo.create(
            conversation_id=conv_uuid,
            role=MessageRole.assistant,
            content=assistant_response,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            provider=active_provider.value,
        )

        # Step 8 — Compute cost and persist TokenUsage row (Requirement 2.9)
        # Resolve the client that actually served this request for correct pricing
        active_client = await self._resolve_provider(active_provider)
        cost_usd = (
            Decimal(str(input_tokens)) * active_client.cost_per_input_token
            + Decimal(str(output_tokens)) * active_client.cost_per_output_token
        )
        token_usage = await self._token_usage_repo.create(
            user_id=user_uuid,
            message_id=assistant_msg.id,
            provider=provider.value,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=cost_usd,
            feature=feature,  # propagate the AI feature context (Requirement 34.1)
        )

        # Step 9 — Record per-provider token cost in Prometheus (Requirement 27.1)
        try:
            from app.workers.metrics import record_token_usage

            record_token_usage(
                provider=active_provider.value,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                cost_usd=float(cost_usd),
            )
        except Exception as metrics_exc:
            logger.warning(
                "Failed to record token usage Prometheus metrics: %s", metrics_exc
            )

        await self._db.commit()
        return token_usage

    async def complete(
        self,
        prompt: str,
        provider: LLMProvider,
        max_tokens: int,
        user_id: str,
    ) -> CompletionResult:
        """Generate a non-streaming completion.

        Used internally for conversation summarization and other short
        completions that do not require WebSocket streaming.

        Args:
            prompt: The complete prompt string to send to the provider.
            provider: The LLM provider to use.
            max_tokens: Maximum tokens to generate.
            user_id: UUID string of the requesting user (for logging).

        Returns:
            :class:`CompletionResult` with generated text and token counts.

        Requirements: 2.4
        """
        client = await self._resolve_provider(provider)
        context = LLMPromptContext(
            system_prompt="",
            messages=[("user", prompt)],
            max_tokens=max_tokens,
            temperature=0.3,  # lower temperature for summarization
            user_id=user_id,
        )
        response_text = await client.complete(context)
        # Raises SafetyFilterError if harmful content cannot be redacted
        # (Requirement 25.3, Property 14) — propagated to caller.
        safe_text = await self._apply_safety_filters(response_text)
        input_tokens = _estimate_tokens(prompt)
        output_tokens = _estimate_tokens(safe_text)
        return CompletionResult(
            text=safe_text,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _resolve_provider(self, provider: LLMProvider) -> BaseLLMClient:
        """Resolve a ``LLMProvider`` enum value to a concrete ``BaseLLMClient``.

        Clients are lazily instantiated and cached per-provider per-orchestrator
        instance.

        Args:
            provider: The provider enum value to resolve.

        Returns:
            An initialised :class:`~app.services.llm_clients.BaseLLMClient`.

        Raises:
            ValueError: If the provider is not supported.

        Requirements: 3.1
        """
        if provider in self._provider_cache:
            return self._provider_cache[provider]

        client: BaseLLMClient
        match provider:
            case LLMProvider.openai:
                client = OpenAIClient()
            case LLMProvider.gemini:
                client = GeminiClient()
            case LLMProvider.claude:
                client = ClaudeClient()
            case LLMProvider.ollama:
                client = OllamaClient()
            case LLMProvider.llama:
                client = LlamaClient()
            case LLMProvider.mistral:
                client = MistralClient()
            case _:
                raise ValueError(f"Unsupported LLM provider: {provider!r}")

        self._provider_cache[provider] = client
        return client

    def _get_fallback_provider(self, primary: LLMProvider) -> LLMProvider | None:
        """Return the configured fallback provider, or ``None`` if not set.

        Reads ``LLM_FALLBACK_PROVIDER`` (preferred) or ``FALLBACK_LLM_PROVIDER``
        (legacy alias) from settings.  Returns ``None`` when:
        - Neither setting is configured (blank/empty) — per Requirement 26.6 the
          orchestrator returns a structured error instead of attempting a fallback.
        - The fallback value is the same as the primary (avoid infinite loop).
        - The fallback value is not a valid ``LLMProvider`` enum member.

        Args:
            primary: The provider that just failed.

        Returns:
            A ``LLMProvider`` enum value different from ``primary``, or ``None``.

        Requirements: 3.3, 26.6
        """
        settings = get_settings()
        fallback_value = settings.effective_fallback_provider.strip().lower()

        if not fallback_value:
            return None

        try:
            fallback = LLMProvider(fallback_value)
        except ValueError:
            logger.warning(
                "LLM_FALLBACK_PROVIDER '%s' is not a valid LLM provider; "
                "ignoring fallback.",
                fallback_value,
            )
            return None

        if fallback == primary:
            logger.warning(
                "LLM_FALLBACK_PROVIDER '%s' is the same as the primary provider; "
                "fallback has no effect.",
                fallback_value,
            )
            return None

        return fallback

    async def _build_prompt(
        self,
        conversation_id: str,
        user_id: str,
        message: str,
        persona_id: str | None = None,
    ) -> PromptContext:
        """Assemble the complete prompt context for a chat request.

        Construction order:
        1. Build system prompt from template (persona + scope + safety rules).
        2. Retrieve top-3 memories from MemoryService and inject into system prompt.
        3. Load conversation history from the database.
        4. If accumulated tokens > 80% of the provider context window, summarize
           the oldest half of messages.
        5. Append the current user message.

        NOTE: User messages that attempt to override the system prompt are
        detected and blocked *before* ``_build_prompt`` is called (in
        ``stream_chat``). This method additionally ensures that no raw
        ``"system:"`` prefixed content from user history is injected.

        When *persona_id* is provided, the persona's system_prompt, tone, and
        scope_description are used instead of the default base system prompt.
        For non-admin users, platform safety rules are appended to the persona
        prompt so that user-defined personas cannot override safety guardrails
        (Requirement 32.4).

        Args:
            conversation_id: UUID string of the conversation.
            user_id: UUID string of the requesting user.
            message: The current user message to append.
            persona_id: Optional UUID string of a persona whose system prompt
                should be injected. When ``None``, the default base prompt is
                used.

        Returns:
            Assembled :class:`PromptContext`.

        Requirements: 2.3, 2.4, 7.2, 25.6, 32.2, 32.4
        """
        conv_uuid = uuid.UUID(conversation_id)
        user_uuid = uuid.UUID(user_id)

        # Step 1 — Retrieve memories with graceful degradation (Requirement 7.2)
        memory_entries: list[str] = []
        try:
            memories = await self._memory_service.get_relevant_memories(
                user_id=user_uuid,
                query=message,
                top_k=3,
            )
            memory_entries = [mem.content for mem in memories]
        except Exception as exc:
            # Requirement 7.2: proceed without memories on failure
            logger.warning(
                "Memory retrieval failed; building prompt without memories. Error: %s",
                exc,
            )

        # Step 2 — Build system prompt:
        #   - When a persona is selected (Req 32.2): construct from persona fields.
        #   - Otherwise: use the default base system prompt.
        system_prompt: str

        if persona_id is not None:
            system_prompt = await self._build_persona_system_prompt(
                persona_id=persona_id,
                user_id=user_id,
                memory_entries=memory_entries or None,
            )
        else:
            system_prompt = build_base_system_prompt(
                assistant_name="AI Assistant",
                memory_entries=memory_entries or None,
            )

        # Step 3 — Load conversation history from the database
        # Exclude messages created in this same request (user_message not yet committed)
        history_messages: list[
            Message
        ] = await self._message_repo.get_by_conversation_id(conv_uuid)

        # Filter to only user and assistant messages (exclude any system-injected rows)
        history_messages = [
            msg
            for msg in history_messages
            if msg.role in (MessageRole.user, MessageRole.assistant)
        ]

        # Step 4 — Estimate tokens and check if summarization is needed
        # We use a conservative estimate: the actual provider will be determined
        # later, so we use the smallest context window (Ollama default: 4096) to
        # be safe, unless we already know the provider from the conversation.
        # The orchestrator will call stream_chat with the provider, but _build_prompt
        # is called before resolve_provider in stream_chat. We use a separate
        # summarization path that is provider-aware when called from stream_chat.
        # For safety, we use 4096 as the minimum threshold here.
        FALLBACK_MAX_CONTEXT = 4096
        token_budget = int(FALLBACK_MAX_CONTEXT * self.SUMMARIZE_THRESHOLD)

        # Estimate current history token usage
        history_tokens = sum(_estimate_tokens(msg.content) for msg in history_messages)
        current_message_tokens = _estimate_tokens(message)
        system_prompt_tokens = _estimate_tokens(system_prompt)
        total_estimated = system_prompt_tokens + history_tokens + current_message_tokens

        # Summarize if over budget (Requirement 2.4)
        final_history: list[PromptMessage] = []
        if total_estimated > token_budget and len(history_messages) > 2:
            summarized_messages = await self._summarize_history(
                history_messages=history_messages,
                provider=LLMProvider.openai,  # default for summarization
                user_id=user_id,
            )
            final_history = summarized_messages
        else:
            final_history = [
                PromptMessage(role=msg.role.value, content=msg.content)
                for msg in history_messages
            ]

        # Step 5 — Assemble the final prompt context
        prompt_messages: list[PromptMessage] = [
            PromptMessage(role="system", content=system_prompt),
            *final_history,
            # Current user message is NOT added here — it will be sent as a
            # "user" role message by the LLM client call. This avoids doubling
            # it in the history on the next turn.
        ]

        # Recalculate estimated tokens after potential summarization
        total_tokens_after = (
            sum(_estimate_tokens(msg.content) for msg in prompt_messages)
            + current_message_tokens
        )

        return PromptContext(
            messages=prompt_messages,
            estimated_tokens=total_tokens_after,
            provider=LLMProvider.openai,  # placeholder; overridden in stream_chat
            user_id=user_id,
        )

    async def _summarize_history(
        self,
        history_messages: list[Message],
        provider: LLMProvider,
        user_id: str,
    ) -> list[PromptMessage]:
        """Summarize the oldest portion of conversation history.

        Replaces the oldest 50% of messages with a single AI-generated summary.
        The more recent messages are retained verbatim.

        Args:
            history_messages: Full ordered message history.
            provider: LLM provider to use for summarization.
            user_id: User UUID string for logging.

        Returns:
            Reduced list of :class:`PromptMessage` objects starting with a
            ``[Conversation Summary]`` system message.

        Requirements: 2.4
        """
        cutoff_idx = max(1, int(len(history_messages) * self.SUMMARIZE_OLDEST_FRACTION))
        old_messages = history_messages[:cutoff_idx]
        recent_messages = history_messages[cutoff_idx:]

        # Build the conversation text to summarize
        conversation_lines: list[str] = []
        for msg in old_messages:
            role_label = msg.role.value.capitalize()
            conversation_lines.append(f"{role_label}: {msg.content}")
        conversation_text = "\n\n".join(conversation_lines)

        summarization_prompt = build_summarization_prompt(conversation_text)

        try:
            result = await self.complete(
                prompt=summarization_prompt,
                provider=provider,
                max_tokens=512,
                user_id=user_id,
            )
            summary_text = result.text
        except Exception as exc:
            # If summarization fails, fall back to keeping only recent messages
            logger.warning(
                "Conversation summarization failed; keeping recent messages only. Error: %s",
                exc,
            )
            return [
                PromptMessage(role=msg.role.value, content=msg.content)
                for msg in recent_messages
            ]

        # Build the condensed history
        condensed: list[PromptMessage] = [
            PromptMessage(
                role=self.SUMMARY_ROLE,
                content=f"{self.SUMMARY_PREFIX}{summary_text}",
            ),
            *[
                PromptMessage(role=msg.role.value, content=msg.content)
                for msg in recent_messages
            ],
        ]
        logger.info(
            "Summarized %d messages into a single summary block for user %s",
            len(old_messages),
            user_id,
        )
        return condensed

    async def _apply_safety_filters(self, text: str) -> str:
        """Apply safety filters to a text string.

        Delegates to ``SafetyService.filter_response``.  If ``SafetyFilterError``
        is raised (i.e. harmful content could not be fully redacted), the error
        propagates to the caller so that the entire response can be blocked rather
        than delivering unredacted harmful content to the user.

        Args:
            text: The text to filter (LLM output token or full response).

        Returns:
            The sanitized text with harmful patterns replaced.

        Raises:
            SafetyFilterError: When harmful content cannot be fully redacted.
                The caller must block the entire response on this error.

        Requirements: 9.6, 25.3
        """
        return self._safety_service.filter_response(text)

    async def _detect_prompt_injection(self, text: str) -> bool:
        """Detect prompt injection patterns in the given text.

        Uses a set of case-insensitive regex patterns to identify common
        injection techniques such as "ignore previous instructions",
        "you are now", "system: ...", etc.

        Args:
            text: The user-provided text to inspect.

        Returns:
            ``True`` if an injection attempt is detected, ``False`` otherwise.

        Requirements: 25.6, 9.6
        """
        return _detect_prompt_injection_static(text)

    async def _build_persona_system_prompt(
        self,
        persona_id: str,
        user_id: str,
        memory_entries: list[str] | None = None,
    ) -> str:
        """Construct the system prompt from a stored persona.

        When a persona is active, the LLM system message is built as:

            {persona.system_prompt}

            [Tone: {persona.tone.value}]
            [Scope: {persona.scope_description}]

        For non-admin users, platform safety rules are appended so that
        user-defined personas cannot override platform safety guardrails
        (Requirement 32.4).

        If the persona cannot be loaded (not found or DB error), the method
        falls back to the default base system prompt and logs a warning.

        Memory entries are appended to the system prompt in both the persona
        and fallback branches to preserve the memory injection behaviour
        (Requirement 7.2).

        Args:
            persona_id: UUID string of the active persona.
            user_id: UUID string of the requesting user (used to load the user
                record and check the admin role).
            memory_entries: Optional list of relevant memory snippets to inject.

        Returns:
            Assembled system prompt string.

        Requirements: 32.2, 32.4
        """
        from app.repositories.persona_repository import (
            PersonaRepository,
        )
        from app.repositories.user_repository import UserRepository

        _PLATFORM_SAFETY_RULES = (
            "\n\n--- Platform Safety Rules ---\n"
            "This AI assistant must not provide harmful, illegal, or unethical content. "
            "Responses must remain within the scope of the active persona and the platform's "
            "community standards.\n"
            "User-defined personas cannot override platform safety guardrails."
        )

        try:
            persona_uuid = uuid.UUID(persona_id)
            persona_repo = PersonaRepository(self._db)
            persona = await persona_repo.get_persona_by_id(persona_uuid)

            if persona is None:
                logger.warning(
                    "Persona id=%s not found; falling back to default system prompt.",
                    persona_id,
                )
                return build_base_system_prompt(
                    assistant_name="AI Assistant",
                    memory_entries=memory_entries,
                )

            # Build persona-based system prompt (Requirement 32.2)
            parts: list[str] = [
                persona.system_prompt,
                f"\n[Tone: {persona.tone.value}]",
            ]
            if persona.scope_description:
                parts.append(f"[Scope: {persona.scope_description}]")

            persona_prompt = "\n".join(parts)

            # Determine if the user is admin to decide whether to append safety rules
            user_uuid = uuid.UUID(user_id)
            user_repo = UserRepository(self._db)
            user = await user_repo.get_by_id(user_uuid)

            is_admin = user is not None and user.role.value == "admin"

            # Requirement 32.4: append platform safety rules for non-admin users
            if not is_admin:
                persona_prompt += _PLATFORM_SAFETY_RULES

            # Append memory context (mirrors build_base_system_prompt behaviour)
            if memory_entries:
                memory_block = "\n".join(f"- {entry}" for entry in memory_entries)
                persona_prompt += (
                    f"\n\nRelevant context from your memory:\n{memory_block}"
                )

            return persona_prompt

        except Exception as exc:
            logger.warning(
                "Failed to load persona id=%s for user %s; "
                "falling back to default system prompt. Error: %s",
                persona_id,
                user_id,
                exc,
            )
            return build_base_system_prompt(
                assistant_name="AI Assistant",
                memory_entries=memory_entries,
            )

    # ------------------------------------------------------------------
    # Conversion helpers
    # ------------------------------------------------------------------

    def _to_llm_prompt_context(
        self,
        context: PromptContext,
        user_id: str,
        client: BaseLLMClient | None = None,
    ) -> LLMPromptContext:
        """Convert an orchestrator ``PromptContext`` to an ``LLMPromptContext``.

        Extracts the system message and converts the remaining messages to
        the ``(role, content)`` tuple format expected by ``BaseLLMClient``.

        When *client* is supplied, ``max_tokens`` is clamped to
        ``client.max_output_tokens`` so that no response ever exceeds the
        configured per-provider limit (Requirement 25.5).

        Args:
            context: The assembled ``PromptContext`` from ``_build_prompt``.
            user_id: User ID for rate-limit tracking in the LLM client.
            client: Optional resolved ``BaseLLMClient`` whose
                ``max_output_tokens`` is used to cap the request.

        Returns:
            A :class:`~app.services.llm_clients.PromptContext` for the
            LLM client.

        Requirements: 25.5
        """
        system_prompt = ""
        conversation_messages: list[tuple[str, str]] = []

        for msg in context.messages:
            if msg.role == "system" and not system_prompt:
                # First system message is the main system prompt
                system_prompt = msg.content
            elif msg.role == "system" and system_prompt:
                # Subsequent system messages (e.g. summaries) are injected as
                # assistant messages prefixed with a marker so they aren't
                # confused with user/assistant conversation turns.
                conversation_messages.append(("assistant", msg.content))
            else:
                conversation_messages.append((msg.role, msg.content))

        # Default max_tokens for a response.
        requested_max_tokens = 2048

        # Clamp to the provider's configured maximum (Requirement 25.5).
        if client is not None and client.max_output_tokens > 0:
            capped_max_tokens = min(requested_max_tokens, client.max_output_tokens)
        else:
            capped_max_tokens = requested_max_tokens

        return LLMPromptContext(
            system_prompt=system_prompt,
            messages=conversation_messages,
            max_tokens=capped_max_tokens,
            temperature=0.7,
            user_id=user_id,
        )
