"""LLM abstraction layer for the Android AI Assistant backend.

This package provides a provider-agnostic interface for LLM operations:

- ``base.py``          — LLMProvider abstract interface, LLMRequest/LLMResponse, LLMUsage
- ``exceptions.py``    — Typed exceptions for every failure mode
- ``service.py``       — LLMService: routing, retry, model-level fallback, observability
- ``prompt_builder.py``— PromptBuilder: RAG context, history, injection protection
- ``providers/``       — Concrete provider implementations
  - ``gemini_provider.py``      — GeminiProvider (google-genai SDK)
  - ``local_gemma_provider.py`` — LocalGemmaProvider (on-device Gemma via Ollama)

Public re-exports for convenience:
"""

from app.llm.base import LLMProvider, LLMRequest, LLMResponse, LLMUsage
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMProviderError,
    LLMQuotaError,
    LLMRateLimitError,
    LLMTimeoutError,
)
from app.llm.service import LLMService

__all__ = [
    "LLMProvider",
    "LLMRequest",
    "LLMResponse",
    "LLMUsage",
    "LLMConfigurationError",
    "LLMProviderError",
    "LLMQuotaError",
    "LLMRateLimitError",
    "LLMTimeoutError",
    "LLMService",
]
