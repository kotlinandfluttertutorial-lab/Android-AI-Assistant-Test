# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm
# File    : base.py
# Purpose : Abstract LLM provider interface, request/response types, LLMUsage.
#
# Architecture Layer : Service abstraction
# Pattern Used       : Strategy / Provider
# ============================================================

"""Abstract base types for the LLM abstraction layer.

All concrete providers (GeminiProvider, LocalGemmaProvider, …) implement
``LLMProvider``.  The rest of the application depends **only** on these types —
never on a concrete provider class directly.

Design principles
-----------------
- ``LLMProvider`` is the single integration point.
- ``LLMUsage`` is provider-agnostic; not every provider exposes all fields.
- ``LLMRequest`` carries all inputs needed by any provider; fields unused by a
  provider are simply ignored.
- ``LLMResponse`` always carries ``text`` and ``usage``; ``model`` reflects the
  model that actually served the request (may differ from requested model after
  fallback).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field


# ---------------------------------------------------------------------------
# Token usage
# ---------------------------------------------------------------------------


@dataclass
class LLMUsage:
    """Token consumption reported by the provider.

    Not all providers expose every counter.  Fields default to 0 when the
    provider does not report them — callers should treat 0 as "unknown", not
    as "zero tokens used".

    Attributes:
        input_tokens:  Number of tokens in the prompt (system + history + user).
        output_tokens: Number of tokens generated in the response.
        total_tokens:  Sum of input and output tokens.  When reported by the
                       provider this value is used directly; otherwise it is
                       computed as ``input_tokens + output_tokens``.
    """

    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0

    def __post_init__(self) -> None:
        # If total was not explicitly set, derive it.
        if self.total_tokens == 0 and (self.input_tokens or self.output_tokens):
            self.total_tokens = self.input_tokens + self.output_tokens


# ---------------------------------------------------------------------------
# Request / Response
# ---------------------------------------------------------------------------


@dataclass
class LLMRequest:
    """Input to every LLM provider call.

    Attributes:
        prompt:           The user's message (after injection detection).
        system_prompt:    System / instruction text prepended to the context.
        conversation_id:  Optional identifier used for structured logging.
        user_id:          User identifier for rate limiting and audit.
        max_output_tokens: Maximum tokens the model may generate.  ``None``
                           defers to the provider's configured default.
        temperature:      Sampling temperature.  ``None`` defers to the
                          provider's configured default.
        request_id:       Unique ID for this request, used in logs and metrics.
        rag_context:      Retrieved document snippets to inject into the prompt.
        complexity:       Routing hint — ``"simple"`` routes to the cheaper model;
                          ``"complex"`` routes to the more capable model.
    """

    prompt: str
    system_prompt: str = ""
    conversation_id: str | None = None
    user_id: str | None = None
    max_output_tokens: int | None = None
    temperature: float | None = None
    request_id: str = ""
    rag_context: list[str] = field(default_factory=list)
    complexity: str = "simple"  # "simple" | "complex" | "local"


@dataclass
class LLMResponse:
    """Output from every LLM provider call.

    Attributes:
        text:     The generated text.  Always a non-None string (may be empty
                  if the provider returned nothing).
        provider: Name of the provider that served the request (e.g. "gemini").
        model:    Exact model ID used (may differ from the requested model when
                  a fallback was invoked).
        usage:    Token counters.  Fields default to 0 when not reported.
        request_id: Echo of ``LLMRequest.request_id`` for correlation.
        fallback_used: ``True`` when the response was served by the fallback
                       model rather than the primary model.
        latency_ms:   Wall-clock time from request to first byte, in ms.
    """

    text: str
    provider: str
    model: str
    usage: LLMUsage = field(default_factory=LLMUsage)
    request_id: str = ""
    fallback_used: bool = False
    latency_ms: float = 0.0


# ---------------------------------------------------------------------------
# Abstract provider interface
# ---------------------------------------------------------------------------


class LLMProvider(ABC):
    """Abstract interface all LLM providers must implement.

    The rest of the application (LLMService, chat router, RAG pipeline, …)
    depends **only** on ``LLMProvider``.  Concrete implementations live in
    ``app/llm/providers/``.

    Subclasses are responsible for:
    - Validating their own API configuration in ``__init__``.
    - Mapping ``LLMRequest`` to the provider's native call format.
    - Returning an ``LLMResponse`` with populated ``usage`` when available.
    - Raising typed exceptions from ``app.llm.exceptions`` on failure.

    Subclasses must NOT:
    - Implement cross-provider fallback (handled by ``LLMService``).
    - Implement prompt injection detection (handled upstream).
    - Log API keys or full prompt text.
    """

    @abstractmethod
    async def generate(self, request: LLMRequest) -> LLMResponse:
        """Generate a complete (non-streaming) response.

        Args:
            request: The fully assembled LLM request.

        Returns:
            ``LLMResponse`` with generated text and usage statistics.

        Raises:
            LLMConfigurationError: Invalid or missing API configuration.
            LLMRateLimitError:     Per-provider rate limit exceeded.
            LLMQuotaError:         Provider API quota exhausted.
            LLMTimeoutError:       Request timed out.
            LLMProviderError:      Any other provider-specific error.
        """

    @abstractmethod
    async def stream(self, request: LLMRequest) -> AsyncIterator[str]:
        """Stream response tokens incrementally.

        Args:
            request: The fully assembled LLM request.

        Yields:
            Token strings as generated by the provider.

        Raises:
            Same exceptions as ``generate()``.
        """

    @property
    @abstractmethod
    def provider_name(self) -> str:
        """Canonical provider identifier (e.g. ``"gemini"``, ``"gemma"``).

        Used in structured logs and response metadata.
        """

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Model ID currently configured for this provider instance."""
