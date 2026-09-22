"""Unit tests for app.llm.exceptions.

Covers:
- All exception classes have correct attributes.
- Inheritance chain: subclasses → LLMError → Exception.
- LLMRateLimitError.retry_after_seconds stored.
- LLMQuotaError.retry_after_seconds stored.
- LLMTimeoutError.timeout_seconds stored.
- LLMProviderError.status_code and is_transient stored.
- LLMPromptTooLargeError.prompt_chars and limit_chars stored.
- str(exc) includes the original message.
"""

from __future__ import annotations

import pytest

from app.llm.exceptions import (
    LLMConfigurationError,
    LLMError,
    LLMPromptTooLargeError,
    LLMProviderError,
    LLMQuotaError,
    LLMRateLimitError,
    LLMTimeoutError,
)


class TestLLMExceptionHierarchy:
    def test_all_are_llm_error_subclasses(self) -> None:
        for cls in [
            LLMConfigurationError,
            LLMRateLimitError,
            LLMQuotaError,
            LLMTimeoutError,
            LLMProviderError,
            LLMPromptTooLargeError,
        ]:
            exc = cls("test message")
            assert isinstance(exc, LLMError)
            assert isinstance(exc, Exception)

    def test_base_llm_error_message(self) -> None:
        exc = LLMError("base message", provider="gemini", model="gemini-3.6-flash", request_id="r1")
        assert str(exc) == "base message"
        assert exc.provider == "gemini"
        assert exc.model == "gemini-3.6-flash"
        assert exc.request_id == "r1"


class TestLLMConfigurationError:
    def test_message_and_provider(self) -> None:
        exc = LLMConfigurationError("Bad key", provider="gemini", model="gemini-3.6-flash")
        assert "Bad key" in str(exc)
        assert exc.provider == "gemini"
        assert exc.model == "gemini-3.6-flash"


class TestLLMRateLimitError:
    def test_retry_after_seconds(self) -> None:
        exc = LLMRateLimitError("Rate limited", provider="gemini", retry_after_seconds=45)
        assert exc.retry_after_seconds == 45
        assert exc.provider == "gemini"

    def test_default_retry_after(self) -> None:
        exc = LLMRateLimitError("Rate limited")
        assert exc.retry_after_seconds == 60

    def test_message_preserved(self) -> None:
        exc = LLMRateLimitError("Too fast")
        assert "Too fast" in str(exc)


class TestLLMQuotaError:
    def test_retry_after_seconds(self) -> None:
        exc = LLMQuotaError("Quota", provider="gemini", retry_after_seconds=120)
        assert exc.retry_after_seconds == 120

    def test_default_retry_after_is_zero(self) -> None:
        exc = LLMQuotaError("Quota")
        assert exc.retry_after_seconds == 0


class TestLLMTimeoutError:
    def test_timeout_seconds(self) -> None:
        exc = LLMTimeoutError("Timed out", provider="gemini", timeout_seconds=55.0)
        assert abs(exc.timeout_seconds - 55.0) < 0.001

    def test_default_timeout_is_zero(self) -> None:
        exc = LLMTimeoutError("Timed out")
        assert exc.timeout_seconds == 0.0


class TestLLMProviderError:
    def test_status_code_and_transient(self) -> None:
        exc = LLMProviderError("Error", status_code=503, is_transient=True)
        assert exc.status_code == 503
        assert exc.is_transient is True

    def test_permanent_error(self) -> None:
        exc = LLMProviderError("Permanent", status_code=400, is_transient=False)
        assert exc.is_transient is False

    def test_defaults(self) -> None:
        exc = LLMProviderError("Error")
        assert exc.status_code is None
        assert exc.is_transient is False


class TestLLMPromptTooLargeError:
    def test_prompt_chars_and_limit(self) -> None:
        exc = LLMPromptTooLargeError("Too large", prompt_chars=50_000, limit_chars=32_000)
        assert exc.prompt_chars == 50_000
        assert exc.limit_chars == 32_000

    def test_message_preserved(self) -> None:
        exc = LLMPromptTooLargeError("Prompt exceeded limit")
        assert "Prompt exceeded limit" in str(exc)
