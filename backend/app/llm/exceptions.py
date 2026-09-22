# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : llm
# File    : exceptions.py
# Purpose : Typed exceptions for every LLM failure mode.
# ============================================================

"""Typed exceptions for the LLM abstraction layer.

Every error that can originate from an LLM provider call maps to a distinct
exception class.  This allows callers and ``LLMService`` to implement precise
retry and fallback policies without inspecting raw HTTP status codes or
provider-specific exception hierarchies.

Retry policy summary
--------------------
- ``LLMRateLimitError``  — back off and retry; honour ``retry_after_seconds``.
- ``LLMQuotaError``      — retry with fallback model/provider; do NOT retry
                           the same model that reported quota exhaustion.
- ``LLMTimeoutError``    — retry with exponential back-off.
- ``LLMProviderError``   — retry only if ``is_transient=True``.
- ``LLMConfigurationError`` — permanent; never retry.
"""

from __future__ import annotations


class LLMError(Exception):
    """Base class for all LLM layer exceptions."""

    def __init__(
        self,
        message: str,
        provider: str = "",
        model: str = "",
        request_id: str = "",
    ) -> None:
        super().__init__(message)
        self.provider = provider
        self.model = model
        self.request_id = request_id


class LLMConfigurationError(LLMError):
    """Raised when a provider cannot be initialised due to missing/invalid config.

    This is a **permanent** error — no retry should be attempted.

    Typical causes:
    - ``GEMINI_API_KEY`` is blank or missing.
    - ``GEMINI_MODEL`` refers to a model name that is not recognised.
    - Required environment variables are absent at startup.
    """


class LLMRateLimitError(LLMError):
    """Raised when the application-level per-provider rate limit is exceeded.

    IMPORTANT: This is the **application** rate limit set by
    ``LLM_RATE_LIMIT_GEMINI`` — it is **not** the same as Google's official
    API quota.  The application limit is a protective cap that fires before
    the API is even called.

    Attributes:
        retry_after_seconds: Seconds until the rate-limit window resets.
                             Callers should wait at least this long before
                             retrying.
    """

    def __init__(
        self,
        message: str,
        provider: str = "",
        model: str = "",
        request_id: str = "",
        retry_after_seconds: int = 60,
    ) -> None:
        super().__init__(message, provider=provider, model=model, request_id=request_id)
        self.retry_after_seconds = retry_after_seconds


class LLMQuotaError(LLMError):
    """Raised when the provider's API quota is exhausted (HTTP 429 from Google).

    This differs from ``LLMRateLimitError`` which is the *application-level*
    cap.  ``LLMQuotaError`` means the Google API itself returned a quota or
    rate-limit response.

    ``LLMService`` uses this to trigger a model-level fallback (primary model
    → fallback model) rather than a simple retry.

    Attributes:
        retry_after_seconds: Suggested wait before retrying, when provided by
                             the API response.
    """

    def __init__(
        self,
        message: str,
        provider: str = "",
        model: str = "",
        request_id: str = "",
        retry_after_seconds: int = 0,
    ) -> None:
        super().__init__(message, provider=provider, model=model, request_id=request_id)
        self.retry_after_seconds = retry_after_seconds


class LLMTimeoutError(LLMError):
    """Raised when a provider API call exceeds the configured timeout.

    This is a **transient** error — ``LLMService`` will retry with exponential
    back-off up to ``LLM_MAX_RETRY_ATTEMPTS``.

    Attributes:
        timeout_seconds: The timeout value that was exceeded.
    """

    def __init__(
        self,
        message: str,
        provider: str = "",
        model: str = "",
        request_id: str = "",
        timeout_seconds: float = 0.0,
    ) -> None:
        super().__init__(message, provider=provider, model=model, request_id=request_id)
        self.timeout_seconds = timeout_seconds


class LLMProviderError(LLMError):
    """Raised for all other provider-specific errors.

    Attributes:
        status_code:  HTTP status code from the provider, when available.
        is_transient: ``True`` when the error is likely temporary (5xx server
                      errors, network failures).  ``LLMService`` only retries
                      when this flag is ``True``.
                      ``False`` for permanent errors (4xx bad request, invalid
                      model, authentication failure, malformed request).
    """

    def __init__(
        self,
        message: str,
        provider: str = "",
        model: str = "",
        request_id: str = "",
        status_code: int | None = None,
        is_transient: bool = False,
    ) -> None:
        super().__init__(message, provider=provider, model=model, request_id=request_id)
        self.status_code = status_code
        self.is_transient = is_transient


class LLMPromptTooLargeError(LLMError):
    """Raised when the assembled prompt exceeds the configured size limit.

    ``PromptBuilder`` raises this before the provider is ever called, so no
    API cost is incurred.

    Attributes:
        prompt_chars:  Actual character count of the assembled prompt.
        limit_chars:   Configured limit (``LLM_PROMPT_MAX_CHARS``).
    """

    def __init__(
        self,
        message: str,
        prompt_chars: int = 0,
        limit_chars: int = 0,
        request_id: str = "",
    ) -> None:
        super().__init__(message, request_id=request_id)
        self.prompt_chars = prompt_chars
        self.limit_chars = limit_chars
