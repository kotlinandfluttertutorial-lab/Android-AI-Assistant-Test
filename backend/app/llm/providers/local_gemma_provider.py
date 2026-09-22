# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm/providers
# File    : local_gemma_provider.py
# Purpose : LocalGemmaProvider — on-device / local Gemma via Ollama.
#
# Privacy model: ALL data stays on-device. No external API calls.
# Cost model   : $0.00 per token (self-hosted).
# ============================================================

"""LocalGemmaProvider — routes requests to a local Gemma model via Ollama.

This provider is intended for:
- On-device inference (privacy-sensitive requests).
- Local development and testing without a Gemini API key.
- Low-latency, zero-cost completions on capable hardware.

The implementation delegates to the existing ``OllamaClient`` in
``app.services.llm_clients`` to avoid duplicating the HTTP/streaming logic.

Configuration (all from Settings)
-----------------------------------
- ``OLLAMA_BASE_URL``  — base URL of the local Ollama server
                         (default: ``http://localhost:11434``).
- ``LLM_RATE_LIMIT_OLLAMA`` — application rate limit (0 = unlimited).

Model
-----
The default model is ``gemma3:latest``.  Other Gemma variants can be used
by passing ``model_name`` to the constructor::

    provider = LocalGemmaProvider(model_name="gemma3:12b")

No API key is required or accepted by this provider.
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator

from app.config.settings import get_settings
from app.llm.base import LLMProvider, LLMRequest, LLMResponse, LLMUsage
from app.llm.exceptions import LLMProviderError, LLMTimeoutError
from app.services.llm_clients import OllamaClient, PromptContext

logger = logging.getLogger(__name__)

# Default Gemma model tag in Ollama.
_DEFAULT_GEMMA_MODEL = "gemma3:latest"


class LocalGemmaProvider(LLMProvider):
    """On-device LLM provider backed by a local Ollama Gemma model.

    All requests are routed exclusively to ``OLLAMA_BASE_URL`` — no external
    network calls are ever made.

    Args:
        model_name: Ollama model tag to use.
                    Defaults to ``gemma3:latest``.
                    Must be installed in the local Ollama instance::

                        ollama pull gemma3:latest
    """

    def __init__(self, model_name: str = _DEFAULT_GEMMA_MODEL) -> None:
        self._model_name_str = model_name
        settings = get_settings()

        # Delegate to the existing OllamaClient which handles HTTP + streaming.
        self._client = OllamaClient(model=model_name)

        self._default_temperature = settings.LLM_TEMPERATURE
        self._max_output_tokens = settings.LLM_MAX_OUTPUT_TOKENS_OLLAMA

        logger.info(
            "LocalGemmaProvider initialised",
            extra={
                "model": model_name,
                "ollama_base_url": settings.OLLAMA_BASE_URL,
            },
        )

    # ------------------------------------------------------------------
    # LLMProvider interface
    # ------------------------------------------------------------------

    @property
    def provider_name(self) -> str:
        return "gemma"

    @property
    def model_name(self) -> str:
        return self._model_name_str

    async def generate(self, request: LLMRequest) -> LLMResponse:
        """Generate a complete response from the local Gemma model.

        Args:
            request: The LLM request.

        Returns:
            ``LLMResponse`` with generated text.  Token counts are not
            available from Ollama and default to 0.

        Raises:
            LLMTimeoutError: Request exceeded Ollama's 120s timeout.
            LLMProviderError: Ollama server error or network failure.
        """
        prompt_ctx = self._to_prompt_context(request)
        start_ms = time.monotonic() * 1000.0

        try:
            text = await self._client.complete(prompt_ctx)
        except Exception as exc:
            err_str = str(exc).lower()
            if "timeout" in err_str or "timed out" in err_str:
                raise LLMTimeoutError(
                    f"LocalGemma request timed out: {exc}",
                    provider="gemma",
                    model=self._model_name_str,
                    request_id=request.request_id,
                ) from exc
            raise LLMProviderError(
                f"LocalGemma request failed: {exc}",
                provider="gemma",
                model=self._model_name_str,
                request_id=request.request_id,
                is_transient=True,
            ) from exc

        latency_ms = time.monotonic() * 1000.0 - start_ms

        logger.info(
            "LocalGemma generate complete",
            extra={
                "request_id": request.request_id,
                "provider": "gemma",
                "model": self._model_name_str,
                "latency_ms": round(latency_ms, 1),
                "status": "success",
            },
        )

        return LLMResponse(
            text=text,
            provider="gemma",
            model=self._model_name_str,
            usage=LLMUsage(),  # Ollama does not report token counts
            request_id=request.request_id,
            fallback_used=False,
            latency_ms=latency_ms,
        )

    async def stream(self, request: LLMRequest) -> AsyncIterator[str]:
        """Stream tokens from the local Gemma model.

        Args:
            request: The LLM request.

        Yields:
            Token strings.

        Raises:
            LLMTimeoutError:  Request timed out.
            LLMProviderError: Ollama server error.
        """
        prompt_ctx = self._to_prompt_context(request)

        try:
            async for token in self._client.stream(prompt_ctx):
                yield token
        except Exception as exc:
            err_str = str(exc).lower()
            if "timeout" in err_str or "timed out" in err_str:
                raise LLMTimeoutError(
                    f"LocalGemma stream timed out: {exc}",
                    provider="gemma",
                    model=self._model_name_str,
                    request_id=request.request_id,
                ) from exc
            raise LLMProviderError(
                f"LocalGemma stream failed: {exc}",
                provider="gemma",
                model=self._model_name_str,
                request_id=request.request_id,
                is_transient=True,
            ) from exc

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _to_prompt_context(self, request: LLMRequest) -> PromptContext:
        """Convert ``LLMRequest`` to the ``PromptContext`` used by OllamaClient.

        Args:
            request: LLM request to convert.

        Returns:
            ``PromptContext`` for the OllamaClient.
        """
        temperature = (
            request.temperature
            if request.temperature is not None
            else self._default_temperature
        )
        max_tokens = request.max_output_tokens or self._max_output_tokens

        return PromptContext(
            system_prompt=request.system_prompt or "",
            messages=[("user", request.prompt)],
            max_tokens=max_tokens,
            temperature=temperature,
            user_id=request.user_id,
        )
