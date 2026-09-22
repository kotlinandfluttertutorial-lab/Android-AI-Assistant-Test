# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm
# File    : service.py
# Purpose : LLMService — request routing, provider selection, observability.
# ============================================================

"""LLMService — the single entry point for LLM calls in the application.

LLMService sits between the API layer and the concrete provider implementations.
It handles:

1. **Request routing** — deterministic rules map requests to providers:
   - ``complexity="local"``   → ``LocalGemmaProvider`` (no API cost, on-device)
   - ``complexity="complex"`` → ``GeminiProvider`` with the primary model
   - ``complexity="simple"``  → ``GeminiProvider`` (same model; routing is a
     hook for future lighter-weight model variants)

   Routing does NOT call an additional LLM to classify complexity — that would
   waste tokens.  The caller or ``PromptBuilder`` applies deterministic rules.

2. **Provider-level fallback** — if ``DEFAULT_LLM_PROVIDER=gemini`` fails, the
   service attempts ``LLM_FALLBACK_PROVIDER`` when configured.  This is
   separate from model-level fallback inside ``GeminiProvider``.

3. **Structured observability** — every request emits a structured log record
   containing ``request_id``, ``provider``, ``model``, ``latency_ms``,
   ``status``, ``input_tokens``, ``output_tokens``, and ``retry_count``.
   Full prompt text is never logged unless ``LLM_LOG_PROMPTS=true``.

4. **Provider caching** — provider instances are lazily created and cached
   per ``LLMService`` instance to avoid redundant ``__init__`` overhead.

Usage
-----
    service = LLMService()
    response = await service.generate(request)

FastAPI dependency
------------------
    def get_llm_service() -> LLMService:
        return _llm_service_singleton   # module-level singleton

    @router.post("/chat")
    async def chat(service: LLMService = Depends(get_llm_service)):
        ...
"""

from __future__ import annotations

import logging
import time

from app.config.settings import get_settings
from app.llm.base import LLMProvider, LLMRequest, LLMResponse
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMError,
    LLMProviderError,
    LLMQuotaError,
    LLMTimeoutError,
)

logger = logging.getLogger(__name__)


class LLMService:
    """Routing and orchestration layer for LLM provider calls.

    Thread-safe and async-safe.  A single instance may be shared across
    concurrent requests.  Provider instances are lazily created and cached.
    """

    def __init__(self) -> None:
        # Lazy provider cache: provider_key → LLMProvider instance.
        # Keys are strings like "gemini", "gemma", "gemini:complex".
        self._providers: dict[str, LLMProvider] = {}

    # ------------------------------------------------------------------
    # Public interface
    # ------------------------------------------------------------------

    async def generate(self, request: LLMRequest) -> LLMResponse:
        """Generate a complete response, routing to the appropriate provider.

        Implements provider-level fallback: if the primary provider raises a
        permanent or quota error and ``LLM_FALLBACK_PROVIDER`` is configured,
        the request is retried with the fallback provider.

        Args:
            request: Fully assembled ``LLMRequest`` (prompt already built,
                     injection detection already passed).

        Returns:
            ``LLMResponse`` from the provider that served the request.

        Raises:
            LLMConfigurationError: Permanent configuration problem.
            LLMError:              All providers exhausted or not configured.
        """
        settings = get_settings()
        start_ms = time.monotonic() * 1000.0

        primary_provider = self._resolve_provider(request)

        try:
            response = await primary_provider.generate(request)
            self._log_request(
                request=request,
                response=response,
                provider_name=primary_provider.provider_name,
                latency_ms=time.monotonic() * 1000.0 - start_ms,
                status="success",
            )
            return response

        except LLMConfigurationError:
            # Configuration errors are permanent — don't attempt fallback.
            raise

        except LLMError as primary_exc:
            # Attempt provider-level fallback if configured.
            fallback_name = settings.effective_fallback_provider.strip().lower()
            if not fallback_name or fallback_name == primary_provider.provider_name:
                self._log_request(
                    request=request,
                    response=None,
                    provider_name=primary_provider.provider_name,
                    latency_ms=time.monotonic() * 1000.0 - start_ms,
                    status="error",
                    error=str(primary_exc),
                )
                raise

            logger.warning(
                "Primary LLM provider failed; attempting fallback provider",
                extra={
                    "request_id": request.request_id,
                    "primary_provider": primary_provider.provider_name,
                    "fallback_provider": fallback_name,
                    "failure_reason": str(primary_exc),
                },
            )

            fallback_provider = self._get_provider_by_name(fallback_name)
            try:
                response = await fallback_provider.generate(request)
                self._log_request(
                    request=request,
                    response=response,
                    provider_name=fallback_provider.provider_name,
                    latency_ms=time.monotonic() * 1000.0 - start_ms,
                    status="success_fallback",
                )
                return response
            except LLMError as fallback_exc:
                self._log_request(
                    request=request,
                    response=None,
                    provider_name=fallback_provider.provider_name,
                    latency_ms=time.monotonic() * 1000.0 - start_ms,
                    status="error_fallback",
                    error=str(fallback_exc),
                )
                raise LLMProviderError(
                    f"Both primary provider '{primary_provider.provider_name}' and "
                    f"fallback provider '{fallback_provider.provider_name}' failed. "
                    f"Primary: {primary_exc}. Fallback: {fallback_exc}",
                    provider=fallback_provider.provider_name,
                    request_id=request.request_id,
                    is_transient=False,
                ) from fallback_exc

    # ------------------------------------------------------------------
    # Provider resolution and routing
    # ------------------------------------------------------------------

    def _resolve_provider(self, request: LLMRequest) -> LLMProvider:
        """Select and return the appropriate provider for this request.

        Routing rules (deterministic — no extra LLM call):
        - complexity="local"   → LocalGemmaProvider
        - complexity="complex" → GeminiProvider (primary model)
        - complexity="simple"  → GeminiProvider (primary model)
        - DEFAULT_LLM_PROVIDER=gemma → LocalGemmaProvider

        Args:
            request: The incoming request with ``complexity`` hint.

        Returns:
            Initialised ``LLMProvider`` instance.
        """
        settings = get_settings()
        default = settings.DEFAULT_LLM_PROVIDER.strip().lower()

        # Explicit local routing.
        if request.complexity == "local" or default == "gemma":
            return self._get_provider_by_name("gemma")

        # All other complexity levels → configured default (usually gemini).
        return self._get_provider_by_name(default or "gemini")

    def _get_provider_by_name(self, name: str) -> LLMProvider:
        """Return (or lazily create) a provider instance by name.

        Args:
            name: Provider name: ``"gemini"``, ``"gemma"``, ``"openai"``, etc.

        Returns:
            Initialised ``LLMProvider``.

        Raises:
            LLMConfigurationError: When the provider name is not recognised.
        """
        if name not in self._providers:
            self._providers[name] = self._create_provider(name)
        return self._providers[name]

    def _create_provider(self, name: str) -> LLMProvider:
        """Instantiate a new provider by name.

        Args:
            name: Provider identifier.

        Returns:
            New provider instance.

        Raises:
            LLMConfigurationError: Unknown provider name.
        """
        if name == "gemini":
            from app.llm.providers.gemini_provider import GeminiProvider
            return GeminiProvider()

        if name in ("gemma", "local"):
            from app.llm.providers.local_gemma_provider import LocalGemmaProvider
            return LocalGemmaProvider()

        # Delegate remaining providers to the existing service layer.
        # This keeps backward-compatibility with openai/claude/ollama/llama/mistral
        # which are already wired into AIOrchestrator.
        raise LLMConfigurationError(
            f"Unknown LLM provider '{name}'. "
            "Valid values for DEFAULT_LLM_PROVIDER: gemini, gemma. "
            "Other providers (openai, claude, ollama, llama, mistral) are "
            "routed through AIOrchestrator.",
        )

    # ------------------------------------------------------------------
    # Observability
    # ------------------------------------------------------------------

    @staticmethod
    def _log_request(
        request: LLMRequest,
        response: LLMResponse | None,
        provider_name: str,
        latency_ms: float,
        status: str,
        error: str = "",
    ) -> None:
        """Emit a structured log record for every LLM request.

        Never logs the full prompt or sensitive RAG context.

        Args:
            request:       The original request.
            response:      The provider response (None on error).
            provider_name: Provider that handled the request.
            latency_ms:    Total wall-clock time in milliseconds.
            status:        One of: success, success_fallback, error, error_fallback.
            error:         Error message (empty on success).
        """
        extra: dict = {
            "request_id": request.request_id,
            "provider": provider_name,
            "model": response.model if response else "unknown",
            "latency_ms": round(latency_ms, 1),
            "status": status,
            "input_tokens": response.usage.input_tokens if response else 0,
            "output_tokens": response.usage.output_tokens if response else 0,
            "total_tokens": response.usage.total_tokens if response else 0,
            "fallback_used": response.fallback_used if response else False,
        }
        if error:
            extra["error"] = error

        if status.startswith("error"):
            logger.error("LLM request failed", extra=extra)
        else:
            logger.info("LLM request complete", extra=extra)


# ---------------------------------------------------------------------------
# Module-level singleton — created once at import time.
# Providers are lazily initialised on first use.
# ---------------------------------------------------------------------------

_llm_service = LLMService()


def get_llm_service() -> LLMService:
    """FastAPI dependency that returns the shared ``LLMService`` singleton.

    Usage in a router::

        from app.llm.service import get_llm_service
        from fastapi import Depends

        @router.post("/chat")
        async def chat(service: LLMService = Depends(get_llm_service)):
            response = await service.generate(request)
    """
    return _llm_service
