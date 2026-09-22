# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm/providers
# File    : gemini_provider.py
# Purpose : GeminiProvider — concrete LLMProvider implementation using the
#           official Google Gen AI SDK (google-genai >= 1.x).
#
# SDK docs : https://googleapis.github.io/python-genai/
# Migration: https://ai.google.dev/gemini-api/docs/migrate
#
# SECURITY RULES enforced here:
#   - GEMINI_API_KEY is read once at init; never logged, never returned.
#   - Full prompt text is logged only when LLM_LOG_PROMPTS=true (default: false).
#   - Token usage is tracked on every response.
#   - API key is stripped of leading/trailing whitespace to handle Cloud Run
#     secrets that may include a trailing newline.
# ============================================================

"""GeminiProvider — implements ``LLMProvider`` using the Google Gen AI SDK.

SDK package : ``google-genai`` (``from google import genai``)
NOT         : ``google-generativeai`` (EOL November 30, 2025)

Responsibilities
----------------
1. Validate API configuration on construction.
2. Initialise a single ``genai.Client`` per provider instance.
3. Apply the application-level Redis rate limiter before every API call.
4. Execute requests against the configured primary model.
5. Retry transient errors with exponential back-off.
6. Fall back to ``GEMINI_FALLBACK_MODEL`` on quota/availability errors when
   ``LLM_ENABLE_FALLBACK=true``.
7. Extract token usage from ``response.usage_metadata``.
8. Emit structured log records for every request (no sensitive content).
9. Raise typed exceptions from ``app.llm.exceptions`` — never raw SDK errors.

Rate limiting note
------------------
``LLM_RATE_LIMIT_GEMINI`` is the **application-level** cap (requests/minute
per user).  This is NOT Google's official quota.  The purpose of this limit is
to protect against accidental runaway usage or abuse of the API key.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator

from google import genai
from google.genai import errors as genai_errors
from google.genai import types as genai_types

from app.config.settings import get_settings
from app.llm.base import LLMProvider, LLMRequest, LLMResponse, LLMUsage
from app.llm.exceptions import (
    LLMConfigurationError,
    LLMProviderError,
    LLMQuotaError,
    LLMRateLimitError,
    LLMTimeoutError,
)
from app.services.llm_clients import _ProviderRateLimiter

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# HTTP status codes that indicate a transient (retryable) server error.
# ---------------------------------------------------------------------------
_TRANSIENT_STATUS_CODES: frozenset[int] = frozenset({500, 502, 503, 504})

# HTTP status codes that indicate a permanent error — never retry these.
_PERMANENT_STATUS_CODES: frozenset[int] = frozenset({400, 401, 403, 404})

# HTTP 429 signals API-level quota exhaustion (triggers model fallback).
_QUOTA_STATUS_CODE = 429


def _make_request_id() -> str:
    """Generate a short unique request ID for log correlation."""
    return uuid.uuid4().hex[:12]


def _build_genai_contents(request: LLMRequest) -> list[genai_types.Content]:
    """Convert ``LLMRequest`` to the ``contents`` list expected by the SDK.

    The SDK accepts a ``list[types.Content]`` where each item has a ``role``
    (``"user"`` or ``"model"``) and a list of ``parts``.  We map the flat
    ``LLMRequest.prompt`` into a single user turn.

    Args:
        request: The incoming LLM request.

    Returns:
        A list containing one ``Content`` object with the user's prompt.
    """
    return [
        genai_types.Content(
            role="user",
            parts=[genai_types.Part.from_text(text=request.prompt)],
        )
    ]


class GeminiProvider(LLMProvider):
    """Concrete ``LLMProvider`` backed by the Google Gemini API.

    Uses the new ``google-genai`` SDK (``from google import genai``).
    The old ``google-generativeai`` SDK reached EOL on November 30, 2025 and
    must not be used.

    Configuration (all from ``app.config.settings.Settings``)
    ----------------------------------------------------------
    - ``GEMINI_API_KEY``            — required; never logged.
    - ``GEMINI_MODEL``              — primary model (default: ``gemini-3.6-flash``).
    - ``GEMINI_FALLBACK_MODEL``     — fallback model (default: ``gemini-3.1-flash-lite``).
    - ``LLM_RATE_LIMIT_GEMINI``     — requests/min per user (application limit).
    - ``LLM_MAX_OUTPUT_TOKENS_GEMINI`` — per-response output token cap.
    - ``LLM_TEMPERATURE``           — default sampling temperature.
    - ``LLM_MAX_RETRY_ATTEMPTS``    — max retries on transient errors.
    - ``LLM_RETRY_BASE_DELAY_SECONDS`` — base delay for exponential back-off.
    - ``LLM_ENABLE_FALLBACK``       — enable model-level fallback on quota errors.
    - ``LLM_LOG_PROMPTS``           — log full prompt (SECURITY: default false).

    Args:
        model_override: When provided, overrides ``GEMINI_MODEL`` from settings.
                        Used by ``LLMService`` for complexity-based routing
                        (e.g. routing complex requests to a more capable model).
    """

    def __init__(self, model_override: str | None = None) -> None:
        settings = get_settings()

        api_key = settings.GEMINI_API_KEY.strip()
        if not api_key:
            raise LLMConfigurationError(
                "GEMINI_API_KEY is not configured. "
                "Set it in backend/.env (local) or Cloud Run Secret Manager (production). "
                "See docs/gemini-setup.md.",
                provider="gemini",
            )

        # Initialise the SDK client.  The api_key is stored inside the client
        # object — we do not retain a reference to it in any instance variable.
        self._client = genai.Client(api_key=api_key)

        # Model selection.
        self._primary_model: str = model_override or settings.GEMINI_MODEL
        self._fallback_model: str = settings.GEMINI_FALLBACK_MODEL

        # Provider configuration.
        self._max_output_tokens: int = settings.LLM_MAX_OUTPUT_TOKENS_GEMINI
        self._default_temperature: float = settings.LLM_TEMPERATURE
        self._max_retry_attempts: int = settings.LLM_MAX_RETRY_ATTEMPTS
        self._retry_base_delay: float = settings.LLM_RETRY_BASE_DELAY_SECONDS
        self._enable_fallback: bool = settings.LLM_ENABLE_FALLBACK
        self._log_prompts: bool = settings.LLM_LOG_PROMPTS

        # Application-level rate limiter (NOT the Google API quota).
        # See class docstring for the distinction.
        self._rate_limiter = _ProviderRateLimiter(
            "gemini", settings.LLM_RATE_LIMIT_GEMINI
        )

        logger.info(
            "GeminiProvider initialised",
            extra={
                "primary_model": self._primary_model,
                "fallback_model": self._fallback_model,
                "max_output_tokens": self._max_output_tokens,
                "fallback_enabled": self._enable_fallback,
                "rate_limit_rpm": settings.LLM_RATE_LIMIT_GEMINI,
            },
        )

    # ------------------------------------------------------------------
    # LLMProvider interface
    # ------------------------------------------------------------------

    @property
    def provider_name(self) -> str:
        return "gemini"

    @property
    def model_name(self) -> str:
        return self._primary_model

    async def generate(self, request: LLMRequest) -> LLMResponse:
        """Generate a complete (non-streaming) response from Gemini.

        Workflow:
        1. Apply application-level rate limit.
        2. Attempt the primary model with retry/back-off on transient errors.
        3. On quota error (HTTP 429) and ``LLM_ENABLE_FALLBACK=true``, attempt
           the fallback model once (no further retry on fallback failure).
        4. Extract token usage from ``response.usage_metadata``.
        5. Return ``LLMResponse`` with usage, model name, and latency.

        Args:
            request: Fully assembled LLM request.

        Returns:
            ``LLMResponse`` with generated text and usage statistics.

        Raises:
            LLMRateLimitError:     Application-level rate limit exceeded.
            LLMQuotaError:         Gemini API quota exhausted (fallback disabled
                                   or fallback also failed).
            LLMTimeoutError:       Request timed out.
            LLMConfigurationError: Invalid API key or model.
            LLMProviderError:      Other non-retryable provider error.
        """
        request_id = request.request_id or _make_request_id()

        # Step 1: Application rate limit (fails fast before any API call).
        await self._rate_limiter.check(request.user_id)

        # Step 2: Try primary model with retry.
        start_ms = time.monotonic() * 1000.0
        try:
            response = await self._generate_with_retry(
                model=self._primary_model,
                request=request,
                request_id=request_id,
            )
            latency_ms = time.monotonic() * 1000.0 - start_ms
            return self._build_response(
                response=response,
                model=self._primary_model,
                request_id=request_id,
                latency_ms=latency_ms,
                fallback_used=False,
            )

        except LLMQuotaError as quota_exc:
            # Step 3: Model-level fallback on quota exhaustion.
            if not self._enable_fallback:
                raise

            if self._fallback_model == self._primary_model:
                logger.warning(
                    "Gemini quota error on primary model; fallback model is same as primary — not retrying",
                    extra={"request_id": request_id, "model": self._primary_model},
                )
                raise

            logger.warning(
                "Gemini quota error on primary model; attempting fallback model",
                extra={
                    "request_id": request_id,
                    "primary_model": self._primary_model,
                    "fallback_model": self._fallback_model,
                    "failure_reason": str(quota_exc),
                },
            )

            fallback_response = await self._generate_with_retry(
                model=self._fallback_model,
                request=request,
                request_id=request_id,
            )
            latency_ms = time.monotonic() * 1000.0 - start_ms
            return self._build_response(
                response=fallback_response,
                model=self._fallback_model,
                request_id=request_id,
                latency_ms=latency_ms,
                fallback_used=True,
            )

    async def stream(self, request: LLMRequest) -> AsyncIterator[str]:
        """Stream response tokens from Gemini.

        Applies rate limiting and attempts the primary model.  Falls back to
        ``GEMINI_FALLBACK_MODEL`` on quota errors when fallback is enabled.

        Args:
            request: Fully assembled LLM request.

        Yields:
            Token strings as generated by the model.

        Raises:
            Same exceptions as ``generate()``.
        """
        request_id = request.request_id or _make_request_id()

        # Application rate limit.
        await self._rate_limiter.check(request.user_id)

        model = self._primary_model
        try:
            async for token in self._stream_model(
                model=model, request=request, request_id=request_id
            ):
                yield token
        except LLMQuotaError:
            if not self._enable_fallback or self._fallback_model == self._primary_model:
                raise

            logger.warning(
                "Gemini quota error on stream; switching to fallback model",
                extra={
                    "request_id": request_id,
                    "primary_model": self._primary_model,
                    "fallback_model": self._fallback_model,
                },
            )
            async for token in self._stream_model(
                model=self._fallback_model, request=request, request_id=request_id
            ):
                yield token

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    def _build_config(
        self, request: LLMRequest, model: str
    ) -> genai_types.GenerateContentConfig:
        """Build a ``GenerateContentConfig`` from an ``LLMRequest``.

        Args:
            request: The incoming request.
            model:   Model name (used only for log context here).

        Returns:
            SDK config object with system_instruction, max_output_tokens,
            and temperature.
        """
        max_tokens = request.max_output_tokens or self._max_output_tokens
        temperature = request.temperature if request.temperature is not None else self._default_temperature

        return genai_types.GenerateContentConfig(
            system_instruction=request.system_prompt or None,
            max_output_tokens=max_tokens,
            temperature=temperature,
        )

    def _build_response(
        self,
        response: genai_types.GenerateContentResponse,
        model: str,
        request_id: str,
        latency_ms: float,
        fallback_used: bool,
    ) -> LLMResponse:
        """Convert a raw SDK response into an ``LLMResponse``.

        Extracts ``usage_metadata`` safely — fields default to 0 when the
        provider does not include them.

        Args:
            response:     Raw ``GenerateContentResponse`` from the SDK.
            model:        Model that served this request.
            request_id:   Correlation ID.
            latency_ms:   Wall-clock request latency in milliseconds.
            fallback_used: Whether the fallback model was used.

        Returns:
            Populated ``LLMResponse``.
        """
        text = response.text or ""

        usage = LLMUsage()
        meta = getattr(response, "usage_metadata", None)
        if meta is not None:
            usage = LLMUsage(
                input_tokens=getattr(meta, "prompt_token_count", 0) or 0,
                output_tokens=getattr(meta, "candidates_token_count", 0) or 0,
                total_tokens=getattr(meta, "total_token_count", 0) or 0,
            )

        logger.info(
            "Gemini generate complete",
            extra={
                "request_id": request_id,
                "provider": "gemini",
                "model": model,
                "latency_ms": round(latency_ms, 1),
                "status": "success",
                "input_tokens": usage.input_tokens,
                "output_tokens": usage.output_tokens,
                "total_tokens": usage.total_tokens,
                "fallback_used": fallback_used,
            },
        )

        return LLMResponse(
            text=text,
            provider="gemini",
            model=model,
            usage=usage,
            request_id=request_id,
            fallback_used=fallback_used,
            latency_ms=latency_ms,
        )

    async def _generate_with_retry(
        self,
        model: str,
        request: LLMRequest,
        request_id: str,
    ) -> genai_types.GenerateContentResponse:
        """Call ``client.aio.models.generate_content`` with retry/back-off.

        Retries only on transient errors (network failures, 5xx responses,
        timeouts).  Permanent errors (4xx, invalid key, bad model) are raised
        immediately without retry.

        Args:
            model:      Gemini model ID to use.
            request:    LLM request data.
            request_id: Correlation ID for logs.

        Returns:
            Raw SDK ``GenerateContentResponse``.

        Raises:
            LLMConfigurationError, LLMQuotaError, LLMTimeoutError,
            LLMProviderError — depending on the failure type.
        """
        contents = _build_genai_contents(request)
        config = self._build_config(request, model)

        if self._log_prompts:
            logger.debug(
                "Gemini prompt (LLM_LOG_PROMPTS=true)",
                extra={
                    "request_id": request_id,
                    "model": model,
                    "prompt_chars": len(request.prompt),
                    # Truncate to avoid massive log lines; never log in production.
                    "prompt_preview": request.prompt[:200],
                },
            )

        last_exc: Exception | None = None

        for attempt in range(self._max_retry_attempts + 1):
            try:
                response = await self._client.aio.models.generate_content(
                    model=model,
                    contents=contents,
                    config=config,
                )
                return response

            except asyncio.TimeoutError as exc:
                last_exc = exc
                self._log_retry(
                    request_id=request_id,
                    model=model,
                    attempt=attempt,
                    reason="timeout",
                )
                await self._maybe_sleep(attempt)

            except genai_errors.APIError as exc:
                last_exc = exc
                status_code: int = getattr(exc, "code", 0) or 0

                if status_code == _QUOTA_STATUS_CODE:
                    raise LLMQuotaError(
                        f"Gemini API quota/rate-limit exceeded (HTTP 429): {exc}",
                        provider="gemini",
                        model=model,
                        request_id=request_id,
                    ) from exc

                if status_code in _PERMANENT_STATUS_CODES:
                    # 400/401/403/404 — bad request, invalid key, unknown model.
                    raise LLMConfigurationError(
                        f"Gemini API permanent error (HTTP {status_code}): {exc}",
                        provider="gemini",
                        model=model,
                        request_id=request_id,
                    ) from exc

                if status_code in _TRANSIENT_STATUS_CODES or status_code == 0:
                    # 5xx or unknown — transient; retry.
                    self._log_retry(
                        request_id=request_id,
                        model=model,
                        attempt=attempt,
                        reason=f"HTTP {status_code}: {exc}",
                    )
                    await self._maybe_sleep(attempt)
                    continue

                # Unexpected status code — raise immediately.
                raise LLMProviderError(
                    f"Gemini API error (HTTP {status_code}): {exc}",
                    provider="gemini",
                    model=model,
                    request_id=request_id,
                    status_code=status_code,
                    is_transient=False,
                ) from exc

            except (LLMQuotaError, LLMConfigurationError, LLMRateLimitError, LLMTimeoutError):
                # Our own typed exceptions must propagate unchanged — do not retry them.
                raise

            except Exception as exc:
                # Unexpected exception type (network, DNS, etc.) — retry.
                last_exc = exc
                self._log_retry(
                    request_id=request_id,
                    model=model,
                    attempt=attempt,
                    reason=str(exc),
                )
                await self._maybe_sleep(attempt)

        # All attempts exhausted.
        if isinstance(last_exc, asyncio.TimeoutError):
            raise LLMTimeoutError(
                f"Gemini request timed out after {self._max_retry_attempts + 1} attempts",
                provider="gemini",
                model=model,
                request_id=request_id,
            ) from last_exc

        raise LLMProviderError(
            f"Gemini request failed after {self._max_retry_attempts + 1} attempts: {last_exc}",
            provider="gemini",
            model=model,
            request_id=request_id,
            is_transient=True,
        ) from last_exc

    async def _stream_model(
        self,
        model: str,
        request: LLMRequest,
        request_id: str,
    ) -> AsyncIterator[str]:
        """Yield tokens from ``client.aio.models.generate_content_stream``.

        Args:
            model:      Gemini model ID.
            request:    LLM request.
            request_id: Correlation ID.

        Yields:
            Token strings.

        Raises:
            LLMQuotaError, LLMConfigurationError, LLMTimeoutError,
            LLMProviderError — mapped from SDK exceptions.
        """
        contents = _build_genai_contents(request)
        config = self._build_config(request, model)

        try:
            async for chunk in await self._client.aio.models.generate_content_stream(
                model=model,
                contents=contents,
                config=config,
            ):
                token_text = chunk.text
                if token_text:
                    yield token_text

        except asyncio.TimeoutError as exc:
            raise LLMTimeoutError(
                f"Gemini stream timed out for model '{model}'",
                provider="gemini",
                model=model,
                request_id=request_id,
            ) from exc

        except genai_errors.APIError as exc:
            status_code = getattr(exc, "code", 0) or 0

            if status_code == _QUOTA_STATUS_CODE:
                raise LLMQuotaError(
                    f"Gemini stream quota exceeded (HTTP 429): {exc}",
                    provider="gemini",
                    model=model,
                    request_id=request_id,
                ) from exc

            if status_code in _PERMANENT_STATUS_CODES:
                raise LLMConfigurationError(
                    f"Gemini stream permanent error (HTTP {status_code}): {exc}",
                    provider="gemini",
                    model=model,
                    request_id=request_id,
                ) from exc

            raise LLMProviderError(
                f"Gemini stream error (HTTP {status_code}): {exc}",
                provider="gemini",
                model=model,
                request_id=request_id,
                status_code=status_code,
                is_transient=status_code in _TRANSIENT_STATUS_CODES,
            ) from exc

        except (LLMQuotaError, LLMConfigurationError, LLMRateLimitError, LLMTimeoutError):
            # Our own typed exceptions must propagate unchanged so callers
            # (e.g. stream() → fallback logic) can catch them specifically.
            raise

        except Exception as exc:
            raise LLMProviderError(
                f"Gemini stream unexpected error: {exc}",
                provider="gemini",
                model=model,
                request_id=request_id,
                is_transient=True,
            ) from exc

    def _log_retry(
        self, request_id: str, model: str, attempt: int, reason: str
    ) -> None:
        """Emit a structured log for a retry attempt."""
        logger.warning(
            "Gemini request failed — retrying",
            extra={
                "request_id": request_id,
                "provider": "gemini",
                "model": model,
                "attempt": attempt + 1,
                "max_attempts": self._max_retry_attempts + 1,
                "reason": reason,
            },
        )

    async def _maybe_sleep(self, attempt: int) -> None:
        """Sleep with exponential back-off if more retries remain.

        Delay formula: ``base_delay * (2 ** attempt)``
        Example (base=1.0s): attempt 0 → 1s, attempt 1 → 2s, attempt 2 → 4s.

        Does NOT sleep after the final attempt.
        """
        if attempt < self._max_retry_attempts:
            delay = self._retry_base_delay * (2**attempt)
            await asyncio.sleep(delay)
