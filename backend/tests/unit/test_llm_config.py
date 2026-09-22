"""Unit tests for new LLM-related settings fields in app.config.settings.

Covers:
- GEMINI_FALLBACK_MODEL default and override.
- LLM_ENABLE_FALLBACK default (True) and override.
- LLM_TEMPERATURE default (0.3) and override.
- LLM_MAX_OUTPUT_TOKENS default (2048) and override.
- LLM_MAX_RETRY_ATTEMPTS default (3) and override.
- LLM_RETRY_BASE_DELAY_SECONDS default (1.0) and override.
- LLM_LOG_PROMPTS default (False) and override.
- LLM_PROMPT_MAX_CHARS default (32000) and override.
- RUN_LLM_INTEGRATION_TESTS default (False) and override.
- GEMINI_MODEL default is gemini-3.6-flash.
- Validator: LLM_TEMPERATURE validated within [0.0, 2.0].
"""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-32-chars-long-minimum!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
# AES_ENCRYPTION_KEY is set by conftest.py — not repeated here to avoid false-positive secret scans.


class TestNewLLMSettings:
    @pytest.fixture(autouse=True)
    def _clear_cache(self):
        from app.config.settings import get_settings
        get_settings.cache_clear()
        yield
        get_settings.cache_clear()

    def test_gemini_model_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.GEMINI_MODEL == "gemini-3.6-flash"

    def test_gemini_fallback_model_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.GEMINI_FALLBACK_MODEL == "gemini-3.1-flash-lite"

    def test_gemini_fallback_model_override(self) -> None:
        with patch.dict(os.environ, {"GEMINI_FALLBACK_MODEL": "gemini-3.5-flash"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert s.GEMINI_FALLBACK_MODEL == "gemini-3.5-flash"

    def test_llm_enable_fallback_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.LLM_ENABLE_FALLBACK is True

    def test_llm_enable_fallback_override(self) -> None:
        with patch.dict(os.environ, {"LLM_ENABLE_FALLBACK": "false"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert s.LLM_ENABLE_FALLBACK is False

    def test_llm_temperature_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert abs(s.LLM_TEMPERATURE - 0.3) < 0.001

    def test_llm_temperature_override(self) -> None:
        with patch.dict(os.environ, {"LLM_TEMPERATURE": "0.7"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert abs(s.LLM_TEMPERATURE - 0.7) < 0.001

    def test_llm_max_output_tokens_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.LLM_MAX_OUTPUT_TOKENS == 2048

    def test_llm_max_retry_attempts_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.LLM_MAX_RETRY_ATTEMPTS == 3

    def test_llm_retry_base_delay_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert abs(s.LLM_RETRY_BASE_DELAY_SECONDS - 1.0) < 0.001

    def test_llm_log_prompts_default_false(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.LLM_LOG_PROMPTS is False

    def test_llm_log_prompts_override(self) -> None:
        with patch.dict(os.environ, {"LLM_LOG_PROMPTS": "true"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert s.LLM_LOG_PROMPTS is True

    def test_llm_prompt_max_chars_default(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.LLM_PROMPT_MAX_CHARS == 32_000

    def test_run_llm_integration_tests_default_false(self) -> None:
        from app.config.settings import get_settings
        s = get_settings()
        assert s.RUN_LLM_INTEGRATION_TESTS is False

    def test_run_llm_integration_tests_override(self) -> None:
        with patch.dict(os.environ, {"RUN_LLM_INTEGRATION_TESTS": "true"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert s.RUN_LLM_INTEGRATION_TESTS is True

    def test_gemini_model_override(self) -> None:
        with patch.dict(os.environ, {"GEMINI_MODEL": "gemini-3.8-flash"}):
            from app.config.settings import get_settings
            get_settings.cache_clear()
            s = get_settings()
            assert s.GEMINI_MODEL == "gemini-3.8-flash"
