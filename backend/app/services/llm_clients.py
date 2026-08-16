# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : llm_clients.py
# Purpose : llm_clients — services module
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

"""LLM provider adapters implementing the BaseLLMClient abstract interface.

This module defines the abstract base class `BaseLLMClient` and six concrete
provider implementations:

- OpenAIClient  — OpenAI GPT-4o via the OpenAI Python SDK
- GeminiClient  — Google Gemini 1.5 Pro via google-generativeai
- ClaudeClient  — Anthropic Claude 3.5 Sonnet via the Anthropic SDK
- OllamaClient  — Local Ollama endpoint; NO external network calls
- LlamaClient   — Llama 3.x via local Ollama endpoint
- MistralClient — Mistral via local Ollama endpoint

Each client implements:
- `stream(context)`            → AsyncIterator[str]  — stream completion tokens
- `complete(context)`          → str                 — non-streaming completion
- `get_provider_name()`        → str                 — provider identifier
- `get_max_context_tokens()`   → int                 — context window size
- `get_cost_per_token()`       → dict[str, Decimal]  — input/output pricing
- `max_context_tokens`         → int  (property)
- `cost_per_input_token`       → Decimal (property)
- `cost_per_output_token`      → Decimal (property)

Per-provider rate limiting is enforced inside each client using a Redis
sliding-window counter.  When a limit is exceeded a `RateLimitError` is
raised immediately (before calling the provider API).

Automatic fallback and user notification are handled by `AIOrchestrator`
(see `ai_orchestrator.py`).  Clients raise exceptions; the orchestrator
catches them and retries with the fallback provider.

OllamaClient routes requests to `OLLAMA_BASE_URL` only — no external calls.

Requirements: 3.1, 3.3, 3.4, 3.5, 3.6
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from decimal import Decimal
from typing import Any, cast

import google.generativeai as genai
import httpx
from anthropic import AsyncAnthropic
from anthropic.types import TextBlock
from openai import AsyncOpenAI
from openai.types.chat import ChatCompletionMessageParam

from app.config.settings import get_settings

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class RateLimitError(Exception):
    """Raised when a per-provider rate limit is exceeded.

    Attributes:
        provider: The provider name that hit its limit.
        retry_after: Seconds until the next rate-limit window opens.
    """

    def __init__(self, provider: str, retry_after: int) -> None:
        self.provider = provider
        self.retry_after = retry_after
        super().__init__(
            f"Rate limit exceeded for provider '{provider}'. "
            f"Retry after {retry_after} second(s)."
        )


# ---------------------------------------------------------------------------
# Shared data structures
# ---------------------------------------------------------------------------


class PromptContext:
    """Context object passed to every LLM client.

    Attributes:
        system_prompt: System instructions for the LLM.
        messages: List of (role, content) tuples representing conversation history.
        max_tokens: Maximum tokens to generate in the response.
        temperature: Sampling temperature (0.0 = deterministic, 1.0 = creative).
        user_id: User ID for logging and rate limiting.
    """

    def __init__(
        self,
        system_prompt: str,
        messages: list[tuple[str, str]],
        max_tokens: int = 2048,
        temperature: float = 0.7,
        user_id: str | None = None,
    ) -> None:
        self.system_prompt = system_prompt
        self.messages = messages
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.user_id = user_id


# ---------------------------------------------------------------------------
# Per-provider Redis rate limiter
# ---------------------------------------------------------------------------

_RATE_KEY_PREFIX = "llm_rate:"
_RATE_KEY_TTL_SECONDS = 120  # 2 windows for safe expiry


class _ProviderRateLimiter:
    """Enforces per-provider, per-user sliding-window rate limits via Redis.

    Uses the same fixed-window approach as the HTTP middleware:
    key = ``llm_rate:{provider}:{user_id}:{window}`` where ``window``
    is ``int(time.time() // 60)``.

    If Redis is unavailable the check is skipped (fail-open) with a warning.

    Requirements: 3.4
    """

    def __init__(self, provider_name: str, limit_per_minute: int) -> None:
        self._provider = provider_name
        self._limit = limit_per_minute

    async def check(self, user_id: str | None) -> None:
        """Raise ``RateLimitError`` if the per-provider limit is exceeded.

        Args:
            user_id: The requesting user identifier.  When ``None`` no limit
                is applied (internal / background calls).

        Raises:
            RateLimitError: When the provider rate limit is exceeded.

        Requirements: 3.4
        """
        if self._limit == 0 or user_id is None:
            return  # unlimited or anonymous — skip

        now = time.time()
        window = int(now // 60)
        key = f"{_RATE_KEY_PREFIX}{self._provider}:{user_id}:{window}"

        try:
            redis_client = await self._get_redis()
            count: int = await redis_client.incr(key)  # type: ignore[assignment]
            if count == 1:
                await redis_client.expire(key, _RATE_KEY_TTL_SECONDS)

            if count > self._limit:
                retry_after = math.ceil(60 - (now % 60))
                logger.warning(
                    "LLM provider rate limit exceeded",
                    extra={
                        "provider": self._provider,
                        "user_id": user_id,
                        "count": count,
                        "limit": self._limit,
                        "retry_after": retry_after,
                    },
                )
                raise RateLimitError(self._provider, retry_after)
        except RateLimitError:
            raise
        except Exception as exc:  # Redis unavailable; fail-open
            logger.warning(
                "LLM provider rate-limit Redis check failed (fail-open) "
                "for provider '%s': %s",
                self._provider,
                exc,
            )

    @staticmethod
    async def _get_redis() -> Any:
        import redis.asyncio as aioredis

        settings = get_settings()
        return aioredis.from_url(settings.REDIS_URL, decode_responses=True)  # type: ignore[no-untyped-call]


# ---------------------------------------------------------------------------
# Abstract Base Class
# ---------------------------------------------------------------------------


class BaseLLMClient(ABC):
    """Abstract base class for all LLM provider clients.

    Every concrete provider must implement:
    - ``stream()``            — async generator yielding token strings
    - ``complete()``          — async method returning full response string
    - ``max_context_tokens``  — property returning context window size
    - ``cost_per_input_token``  — property returning input token cost (USD)
    - ``cost_per_output_token`` — property returning output token cost (USD)

    Convenience methods (implemented on the base class):
    - ``get_provider_name()``      — returns the provider identifier string
    - ``get_max_context_tokens()`` — delegates to ``max_context_tokens``
    - ``get_cost_per_token()``     — returns ``{"input": ..., "output": ...}``

    Subclasses are responsible for:
    - API authentication (read keys from settings)
    - Request formatting (convert PromptContext to provider-specific format)
    - Response parsing (extract tokens from provider-specific format)
    - Error handling (raise exceptions with descriptive messages)
    - Rate limit check via ``self._rate_limiter.check(context.user_id)``

    Subclasses should NOT implement:
    - Fallback logic (handled by AIOrchestrator)
    - Prompt injection detection (handled by AIOrchestrator)
    - Safety filtering (handled by AIOrchestrator)

    Requirements: 3.1, 3.6
    """

    # Subclasses must assign a ``_ProviderRateLimiter`` instance.
    _rate_limiter: _ProviderRateLimiter

    # ------------------------------------------------------------------
    # Abstract interface
    # ------------------------------------------------------------------

    @abstractmethod
    def stream(self, context: PromptContext) -> AsyncIterator[str]:
        """Stream completion tokens incrementally.

        Implementations MUST call ``await self._rate_limiter.check(context.user_id)``
        before making any network request.

        Args:
            context: Prompt context with system prompt, history, and params.

        Yields:
            Token strings as generated by the provider.

        Raises:
            RateLimitError: When the per-provider limit is exceeded.
            Exception: Other provider-specific errors (network, auth, quota).

        Requirements: 3.1, 3.4
        """
        ...

    @abstractmethod
    async def complete(self, context: PromptContext) -> str:
        """Generate a full completion (non-streaming).

        Implementations MUST call ``await self._rate_limiter.check(context.user_id)``
        before making any network request.

        Args:
            context: Prompt context with system prompt, history, and params.

        Returns:
            The complete response string.

        Raises:
            RateLimitError: When the per-provider limit is exceeded.
            Exception: Other provider-specific errors (network, auth, quota).

        Requirements: 3.1, 3.4
        """
        ...

    @property
    @abstractmethod
    def max_context_tokens(self) -> int:
        """Maximum context window size (input + output tokens).

        Requirements: 3.1
        """
        ...

    @property
    @abstractmethod
    def cost_per_input_token(self) -> Decimal:
        """Cost per input token in USD.

        Used by the Admin Dashboard to track per-provider spending.

        Requirements: 3.6
        """
        ...

    @property
    @abstractmethod
    def cost_per_output_token(self) -> Decimal:
        """Cost per output token in USD.

        Used by the Admin Dashboard to track per-provider spending.

        Requirements: 3.6
        """
        ...

    @property
    @abstractmethod
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for this provider.

        Returns the per-provider configurable cap from settings.  No single
        response may generate more tokens than this value.  The orchestrator
        clamps ``PromptContext.max_tokens`` to this limit before every LLM
        API call.

        Requirements: 25.5
        """
        ...

    # ------------------------------------------------------------------
    # Convenience methods (used by orchestrator & admin service)
    # ------------------------------------------------------------------

    @abstractmethod
    def get_provider_name(self) -> str:
        """Return the canonical provider identifier string.

        Returns one of: 'openai', 'gemini', 'claude', 'ollama', 'llama', 'mistral'.

        Requirements: 3.1
        """
        ...

    def get_max_context_tokens(self) -> int:
        """Delegate to the ``max_context_tokens`` property.

        Requirements: 3.1
        """
        return self.max_context_tokens

    def get_cost_per_token(self) -> dict[str, Decimal]:
        """Return a dict with input and output token costs in USD.

        Returns:
            ``{"input": Decimal, "output": Decimal}``

        Requirements: 3.6
        """
        return {
            "input": self.cost_per_input_token,
            "output": self.cost_per_output_token,
        }


# ---------------------------------------------------------------------------
# OpenAI Client
# ---------------------------------------------------------------------------


class OpenAIClient(BaseLLMClient):
    """OpenAI GPT-4o client using the OpenAI Python SDK.

    Pricing (as of 2024-01):
    - Input:  $0.005 / 1K tokens = $0.000005 per token
    - Output: $0.015 / 1K tokens = $0.000015 per token

    Context window: 128,000 tokens

    Requirements: 3.1, 3.4, 3.6
    """

    def __init__(self) -> None:
        settings = get_settings()
        if not settings.OPENAI_API_KEY:
            raise ValueError("OPENAI_API_KEY not configured")
        self.client = AsyncOpenAI(api_key=settings.OPENAI_API_KEY)
        self.model = "gpt-4o"
        self._rate_limiter = _ProviderRateLimiter(
            "openai", settings.LLM_RATE_LIMIT_OPENAI
        )

    def get_provider_name(self) -> str:
        return "openai"

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        """Stream tokens from OpenAI GPT-4o.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        messages: list[ChatCompletionMessageParam] = [
            {"role": "system", "content": context.system_prompt}  # type: ignore[misc]
        ]
        for role, content in context.messages:
            messages.append({"role": role, "content": content})  # type: ignore[misc]

        stream = await self.client.chat.completions.create(
            model=self.model,
            messages=messages,
            max_tokens=context.max_tokens,
            temperature=context.temperature,
            stream=True,
        )

        async for chunk in stream:
            if chunk.choices and chunk.choices[0].delta.content:
                yield chunk.choices[0].delta.content

    async def complete(self, context: PromptContext) -> str:
        """Generate full completion from OpenAI GPT-4o.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        messages2: list[ChatCompletionMessageParam] = [
            {"role": "system", "content": context.system_prompt}  # type: ignore[misc]
        ]
        for role, content in context.messages:
            messages2.append({"role": role, "content": content})  # type: ignore[misc]

        response = await self.client.chat.completions.create(
            model=self.model,
            messages=messages2,
            max_tokens=context.max_tokens,
            temperature=context.temperature,
            stream=False,
        )

        return cast(str, response.choices[0].message.content or "")

    @property
    def max_context_tokens(self) -> int:
        """GPT-4o supports 128K tokens."""
        return 128_000

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for OpenAI (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_OPENAI

    @property
    def cost_per_input_token(self) -> Decimal:
        """GPT-4o input: $0.005 / 1K tokens."""
        return Decimal("0.000005")

    @property
    def cost_per_output_token(self) -> Decimal:
        """GPT-4o output: $0.015 / 1K tokens."""
        return Decimal("0.000015")


# ---------------------------------------------------------------------------
# Gemini Client
# ---------------------------------------------------------------------------


class GeminiClient(BaseLLMClient):
    """Google Gemini 1.5 Pro client using google-generativeai SDK.

    Pricing (as of 2024-01):
    - Input:  $0.00125 / 1K tokens = $0.00000125 per token
    - Output: $0.00375 / 1K tokens = $0.00000375 per token

    Context window: 1,000,000 tokens (Pro model)

    Requirements: 3.1, 3.4, 3.6
    """

    def __init__(self) -> None:
        settings = get_settings()
        if not settings.GEMINI_API_KEY:
            raise ValueError("GEMINI_API_KEY not configured")
        genai.configure(api_key=settings.GEMINI_API_KEY)
        self.model = genai.GenerativeModel("gemini-flash-latest")
        self._rate_limiter = _ProviderRateLimiter(
            "gemini", settings.LLM_RATE_LIMIT_GEMINI
        )

    def get_provider_name(self) -> str:
        return "gemini"

    def _build_prompt(self, context: PromptContext) -> str:
        parts = [context.system_prompt]
        for role, content in context.messages:
            parts.append(f"{role}: {content}")
        return "\n\n".join(parts)

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        """Stream tokens from Gemini 1.5 Pro.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        full_prompt = self._build_prompt(context)
        generation_config = genai.types.GenerationConfig(
            max_output_tokens=context.max_tokens,
            temperature=context.temperature,
        )

        response = await asyncio.to_thread(
            self.model.generate_content,
            full_prompt,
            generation_config=generation_config,
            stream=True,
        )

        for chunk in response:
            if chunk.text:
                yield chunk.text

    async def complete(self, context: PromptContext) -> str:
        """Generate full completion from Gemini 1.5 Pro.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        full_prompt = self._build_prompt(context)
        generation_config = genai.types.GenerationConfig(
            max_output_tokens=context.max_tokens,
            temperature=context.temperature,
        )

        response = await asyncio.to_thread(
            self.model.generate_content,
            full_prompt,
            generation_config=generation_config,
            stream=False,
        )

        return response.text

    @property
    def max_context_tokens(self) -> int:
        """Gemini 1.5 Pro supports 1M tokens."""
        return 1_000_000

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for Gemini (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_GEMINI

    @property
    def cost_per_input_token(self) -> Decimal:
        """Gemini 1.5 Pro input: $0.00125 / 1K tokens."""
        return Decimal("0.00000125")

    @property
    def cost_per_output_token(self) -> Decimal:
        """Gemini 1.5 Pro output: $0.00375 / 1K tokens."""
        return Decimal("0.00000375")


# ---------------------------------------------------------------------------
# Claude Client
# ---------------------------------------------------------------------------


class ClaudeClient(BaseLLMClient):
    """Anthropic Claude 3.5 Sonnet client using the Anthropic SDK.

    Pricing (as of 2024-01):
    - Input:  $0.003 / 1K tokens = $0.000003 per token
    - Output: $0.015 / 1K tokens = $0.000015 per token

    Context window: 200,000 tokens

    Requirements: 3.1, 3.4, 3.6
    """

    def __init__(self) -> None:
        settings = get_settings()
        if not settings.ANTHROPIC_API_KEY:
            raise ValueError("ANTHROPIC_API_KEY not configured")
        self.client = AsyncAnthropic(api_key=settings.ANTHROPIC_API_KEY)
        self.model = "claude-3-5-sonnet-20241022"
        self._rate_limiter = _ProviderRateLimiter(
            "claude", settings.LLM_RATE_LIMIT_CLAUDE
        )

    def get_provider_name(self) -> str:
        return "claude"

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        """Stream tokens from Claude 3.5 Sonnet.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        from anthropic.types import MessageParam

        claude_messages: list[MessageParam] = [
            {"role": role, "content": content}  # type: ignore[misc]
            for role, content in context.messages
        ]

        async with self.client.messages.stream(
            model=self.model,
            max_tokens=context.max_tokens,
            temperature=context.temperature,
            system=context.system_prompt,
            messages=claude_messages,
        ) as stream:
            async for text in stream.text_stream:
                yield text

    async def complete(self, context: PromptContext) -> str:
        """Generate full completion from Claude 3.5 Sonnet.

        Requirements: 3.1, 3.4
        """
        await self._rate_limiter.check(context.user_id)

        from anthropic.types import MessageParam

        claude_messages2: list[MessageParam] = [
            {"role": role, "content": content}  # type: ignore[misc]
            for role, content in context.messages
        ]

        response = await self.client.messages.create(
            model=self.model,
            max_tokens=context.max_tokens,
            temperature=context.temperature,
            system=context.system_prompt,
            messages=claude_messages2,
        )

        first_block = response.content[0]
        return cast(str, first_block.text if isinstance(first_block, TextBlock) else "")

    @property
    def max_context_tokens(self) -> int:
        """Claude 3.5 Sonnet supports 200K tokens."""
        return 200_000

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for Claude (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_CLAUDE

    @property
    def cost_per_input_token(self) -> Decimal:
        """Claude 3.5 Sonnet input: $0.003 / 1K tokens."""
        return Decimal("0.000003")

    @property
    def cost_per_output_token(self) -> Decimal:
        """Claude 3.5 Sonnet output: $0.015 / 1K tokens."""
        return Decimal("0.000015")


# ---------------------------------------------------------------------------
# Ollama Client (base for Llama and Mistral)
# ---------------------------------------------------------------------------


class OllamaClient(BaseLLMClient):
    """Local Ollama client for self-hosted models.

    Routes ALL requests to the locally configured Ollama endpoint defined by
    ``OLLAMA_BASE_URL`` environment variable.  NO external network calls are
    ever made by this client.

    Supports any model installed in the local Ollama instance.

    Pricing: $0.00 (self-hosted)
    Context window: varies by model (default 4096 for safety)

    Requirements: 3.1, 3.4, 3.5, 3.6
    """

    def __init__(self, model: str = "llama3.2:latest") -> None:
        settings = get_settings()
        # Enforce local-only by reading from OLLAMA_BASE_URL.
        # The httpx client is bound to this base URL; no other URLs are used.
        self.base_url: str = settings.OLLAMA_BASE_URL
        self.model = model
        # Use a dedicated httpx client per instance; no proxy or external routing.
        self.client = httpx.AsyncClient(
            base_url=self.base_url,
            timeout=120.0,
        )
        self._rate_limiter = _ProviderRateLimiter(
            "ollama", settings.LLM_RATE_LIMIT_OLLAMA
        )

    def get_provider_name(self) -> str:
        return "ollama"

    def _build_prompt(self, context: PromptContext) -> str:
        """Build a single prompt string from context (Ollama /api/generate format)."""
        parts = [context.system_prompt]
        for role, content in context.messages:
            parts.append(f"{role}: {content}")
        return "\n\n".join(parts)

    async def stream(self, context: PromptContext) -> AsyncIterator[str]:
        """Stream tokens from local Ollama endpoint.

        Only the ``self.base_url`` endpoint is contacted — no external calls.

        Requirements: 3.1, 3.4, 3.5
        """
        await self._rate_limiter.check(context.user_id)

        prompt = self._build_prompt(context)
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": True,
            "options": {
                "temperature": context.temperature,
                "num_predict": context.max_tokens,
            },
        }

        async with self.client.stream(
            "POST", "/api/generate", json=payload
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if line.strip():
                    chunk = json.loads(line)
                    if "response" in chunk:
                        yield chunk["response"]

    async def complete(self, context: PromptContext) -> str:
        """Generate full completion from local Ollama endpoint.

        Only the ``self.base_url`` endpoint is contacted — no external calls.

        Requirements: 3.1, 3.4, 3.5
        """
        await self._rate_limiter.check(context.user_id)

        prompt = self._build_prompt(context)
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {
                "temperature": context.temperature,
                "num_predict": context.max_tokens,
            },
        }

        response = await self.client.post("/api/generate", json=payload)
        response.raise_for_status()
        data = response.json()
        return str(data.get("response", ""))

    @property
    def max_context_tokens(self) -> int:
        """Default 4096 tokens (varies by installed model)."""
        return 4096

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for Ollama (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_OLLAMA

    @property
    def cost_per_input_token(self) -> Decimal:
        """Self-hosted: zero API cost."""
        return Decimal("0.0")

    @property
    def cost_per_output_token(self) -> Decimal:
        """Self-hosted: zero API cost."""
        return Decimal("0.0")


# ---------------------------------------------------------------------------
# Llama Client
# ---------------------------------------------------------------------------


class LlamaClient(OllamaClient):
    """Llama 3.x client via local Ollama endpoint.

    Inherits all behavior from ``OllamaClient``.  Only the default model and
    rate-limiter key differ.

    Requirements: 3.1, 3.4, 3.5, 3.6
    """

    def __init__(self) -> None:
        super().__init__(model="llama3.2:latest")
        settings = get_settings()
        # Override the rate limiter with the Llama-specific limit.
        self._rate_limiter = _ProviderRateLimiter(
            "llama", settings.LLM_RATE_LIMIT_LLAMA
        )

    def get_provider_name(self) -> str:
        return "llama"

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for Llama (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_LLAMA


# ---------------------------------------------------------------------------
# Mistral Client
# ---------------------------------------------------------------------------


class MistralClient(OllamaClient):
    """Mistral client via local Ollama endpoint.

    Inherits all behavior from ``OllamaClient``.  Only the default model and
    rate-limiter key differ.

    Requirements: 3.1, 3.4, 3.5, 3.6
    """

    def __init__(self) -> None:
        super().__init__(model="mistral:latest")
        settings = get_settings()
        # Override the rate limiter with the Mistral-specific limit.
        self._rate_limiter = _ProviderRateLimiter(
            "mistral", settings.LLM_RATE_LIMIT_MISTRAL
        )

    def get_provider_name(self) -> str:
        return "mistral"

    @property
    def max_output_tokens(self) -> int:
        """Configured maximum output tokens for Mistral (from settings).

        Requirements: 25.5
        """
        return get_settings().LLM_MAX_OUTPUT_TOKENS_MISTRAL
