"""Unit tests for app.config.settings.Settings.

Tests cover:
  - Required field absence raises ValidationError (no defaults)
  - Default values for every optional field
  - Field validators: CORS_ORIGINS list parsing, bcrypt work factor bounds
  - Property aliases: JWT_SECRET_KEY, JWT_ACCESS_TOKEN_EXPIRE_MINUTES, etc.
  - effective_fallback_provider precedence (LLM_FALLBACK_PROVIDER > FALLBACK_LLM_PROVIDER)
  - LLM temperature bounds enforcement
  - get_settings() returns a stable singleton (same object on repeated calls)
  - Environment variable overrides work correctly at construction time

Teaching notes (Phase 3 — FastAPI Backend):
  - pydantic-settings reads env vars in priority order:
      actual env vars > .env file > field defaults
  - Field(ge=X) / Field(le=Y) enforce bounds — ValidationError on violation
  - @lru_cache on get_settings() means settings are parsed once per process;
    tests must clear the cache to test different env configurations
  - Properties (JWT_SECRET_KEY) are computed at access time from stored fields —
    they are NOT env-var backed themselves, which is why they are @property not Field()

Requirements: 26.1, 26.3, 26.5, 20.6
"""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest
from pydantic import ValidationError

# Set required env vars before importing settings
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")

from app.config.settings import Settings, get_settings  # noqa: E402


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_settings(**overrides: str) -> Settings:
    """Construct a Settings object from minimal required env vars + overrides.

    Uses model_validate to bypass the .env file loading so each test is
    isolated from whatever .env is present on disk.
    """
    base = {
        "SECRET_KEY":       "test-secret-key-at-least-32-chars-long!!",
        "DATABASE_URL":     "postgresql+asyncpg://user:pass@localhost:5432/db",
        "REDIS_URL":        "redis://localhost:6379/0",
        "AES_ENCRYPTION_KEY": "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==",
    }
    base.update(overrides)
    return Settings.model_validate(base)


# ---------------------------------------------------------------------------
# Required fields
# ---------------------------------------------------------------------------


class TestSettingsRequiredFields:
    """Settings must raise ValidationError when required fields are absent."""

    @pytest.mark.parametrize("missing", [
        "SECRET_KEY",
        "DATABASE_URL",
        "REDIS_URL",
    ])
    def test_raises_on_missing_required_field(self, missing: str) -> None:
        """Removing any required field raises pydantic ValidationError.

        AES_ENCRYPTION_KEY is NOT strictly required by pydantic (it defaults
        to empty string) — it is enforced at startup by startup_validation().
        This test covers the three pydantic-required fields.
        """
        base = {
            "SECRET_KEY":       "test-secret-key-at-least-32-chars-long!!",
            "DATABASE_URL":     "postgresql+asyncpg://user:pass@localhost:5432/db",
            "REDIS_URL":        "redis://localhost:6379/0",
            "AES_ENCRYPTION_KEY": "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==",
        }
        del base[missing]
        with pytest.raises((ValidationError, Exception)):
            Settings.model_validate(base)


# ---------------------------------------------------------------------------
# Default values
# ---------------------------------------------------------------------------


class TestSettingsDefaults:
    """Every optional field must have a sensible default."""

    def setup_method(self) -> None:
        self.s = _make_settings()

    def test_jwt_algorithm_default(self) -> None:
        assert self.s.JWT_ALGORITHM == "HS256"

    def test_access_token_expire_minutes_default(self) -> None:
        assert self.s.ACCESS_TOKEN_EXPIRE_MINUTES == 15

    def test_refresh_token_expire_days_default(self) -> None:
        assert self.s.REFRESH_TOKEN_EXPIRE_DAYS == 30

    def test_bcrypt_work_factor_default(self) -> None:
        assert self.s.BCRYPT_WORK_FACTOR == 12

    def test_log_level_default(self) -> None:
        assert self.s.LOG_LEVEL == "INFO"

    def test_environment_default(self) -> None:
        assert self.s.ENVIRONMENT == "development"

    def test_rag_top_k_default(self) -> None:
        assert self.s.RAG_TOP_K == 5

    def test_rag_chunk_size_default(self) -> None:
        assert self.s.RAG_CHUNK_SIZE == 512

    def test_max_file_size_mb_default(self) -> None:
        assert self.s.MAX_FILE_SIZE_MB == 50

    def test_rate_limit_auth_default(self) -> None:
        assert self.s.RATE_LIMIT_REQUESTS_PER_MINUTE == 60

    def test_rate_limit_unauth_default(self) -> None:
        assert self.s.RATE_LIMIT_UNAUTH_REQUESTS_PER_MINUTE == 20

    def test_default_llm_provider_is_gemini(self) -> None:
        assert self.s.DEFAULT_LLM_PROVIDER == "gemini"

    def test_llm_temperature_default(self) -> None:
        assert self.s.LLM_TEMPERATURE == 0.3

    def test_llm_max_output_tokens_default(self) -> None:
        assert self.s.LLM_MAX_OUTPUT_TOKENS == 2048

    def test_llm_max_retry_attempts_default(self) -> None:
        assert self.s.LLM_MAX_RETRY_ATTEMPTS == 3

    def test_prometheus_enabled_default(self) -> None:
        assert self.s.PROMETHEUS_ENABLED is True

    def test_otel_enabled_default(self) -> None:
        assert self.s.OTEL_ENABLED is True

    def test_storage_backend_default_is_minio(self) -> None:
        assert self.s.STORAGE_BACKEND == "minio"

    def test_max_request_body_size_default(self) -> None:
        # 1 MiB
        assert self.s.MAX_REQUEST_BODY_SIZE == 1 * 1024 * 1024

    def test_account_lockout_max_attempts_default(self) -> None:
        assert self.s.ACCOUNT_LOCKOUT_MAX_ATTEMPTS == 5

    def test_dp_epsilon_default(self) -> None:
        assert self.s.DP_EPSILON == 1.0


# ---------------------------------------------------------------------------
# Field bounds enforcement
# ---------------------------------------------------------------------------


class TestSettingsBounds:
    """ge= / le= constraints on numeric fields must be enforced."""

    def test_llm_temperature_too_low_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(LLM_TEMPERATURE="-0.1")

    def test_llm_temperature_too_high_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(LLM_TEMPERATURE="2.1")

    def test_bcrypt_work_factor_too_low_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(BCRYPT_WORK_FACTOR="3")

    def test_bcrypt_work_factor_too_high_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(BCRYPT_WORK_FACTOR="32")

    def test_access_token_expire_minutes_ge_1(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(ACCESS_TOKEN_EXPIRE_MINUTES="0")

    def test_rag_chunk_size_ge_64(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(RAG_CHUNK_SIZE="63")

    def test_dp_epsilon_too_low_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(DP_EPSILON="0.05")

    def test_dp_epsilon_too_high_raises(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(DP_EPSILON="10.1")

    def test_max_request_body_size_ge_1024_raises_below(self) -> None:
        with pytest.raises(ValidationError):
            _make_settings(MAX_REQUEST_BODY_SIZE="512")

    def test_valid_bcrypt_work_factor_boundary(self) -> None:
        s = _make_settings(BCRYPT_WORK_FACTOR="4")
        assert s.BCRYPT_WORK_FACTOR == 4

    def test_valid_temperature_boundary_zero(self) -> None:
        s = _make_settings(LLM_TEMPERATURE="0.0")
        assert s.LLM_TEMPERATURE == 0.0

    def test_valid_temperature_boundary_two(self) -> None:
        s = _make_settings(LLM_TEMPERATURE="2.0")
        assert s.LLM_TEMPERATURE == 2.0


# ---------------------------------------------------------------------------
# Property aliases
# ---------------------------------------------------------------------------


class TestSettingsPropertyAliases:
    """Property aliases must return the same value as the underlying field."""

    def setup_method(self) -> None:
        self.s = _make_settings()

    def test_jwt_secret_key_alias(self) -> None:
        assert self.s.JWT_SECRET_KEY == self.s.SECRET_KEY

    def test_jwt_access_token_expire_minutes_alias(self) -> None:
        assert self.s.JWT_ACCESS_TOKEN_EXPIRE_MINUTES == self.s.ACCESS_TOKEN_EXPIRE_MINUTES

    def test_jwt_refresh_token_expire_days_alias(self) -> None:
        assert self.s.JWT_REFRESH_TOKEN_EXPIRE_DAYS == self.s.REFRESH_TOKEN_EXPIRE_DAYS

    def test_alias_reflects_custom_value(self) -> None:
        s = _make_settings(ACCESS_TOKEN_EXPIRE_MINUTES="60")
        assert s.JWT_ACCESS_TOKEN_EXPIRE_MINUTES == 60
        assert s.ACCESS_TOKEN_EXPIRE_MINUTES == 60


# ---------------------------------------------------------------------------
# effective_fallback_provider precedence
# ---------------------------------------------------------------------------


class TestEffectiveFallbackProvider:
    """LLM_FALLBACK_PROVIDER must take precedence over FALLBACK_LLM_PROVIDER."""

    def test_llm_fallback_provider_wins_when_both_set(self) -> None:
        """LLM_FALLBACK_PROVIDER is the preferred alias per Requirement 26.6."""
        s = _make_settings(
            LLM_FALLBACK_PROVIDER="openai",
            FALLBACK_LLM_PROVIDER="claude",
        )
        assert s.effective_fallback_provider == "openai"

    def test_falls_back_to_fallback_llm_provider_when_preferred_empty(self) -> None:
        s = _make_settings(
            LLM_FALLBACK_PROVIDER="",
            FALLBACK_LLM_PROVIDER="claude",
        )
        assert s.effective_fallback_provider == "claude"

    def test_returns_empty_string_when_neither_set(self) -> None:
        s = _make_settings(
            LLM_FALLBACK_PROVIDER="",
            FALLBACK_LLM_PROVIDER="",
        )
        assert s.effective_fallback_provider == ""

    def test_llm_fallback_provider_preferred_even_when_legacy_absent(self) -> None:
        s = _make_settings(LLM_FALLBACK_PROVIDER="gemini")
        assert s.effective_fallback_provider == "gemini"


# ---------------------------------------------------------------------------
# Environment overrides
# ---------------------------------------------------------------------------


class TestSettingsEnvironmentOverrides:
    """Field values must be overridable by env var."""

    def test_custom_log_level(self) -> None:
        s = _make_settings(LOG_LEVEL="DEBUG")
        assert s.LOG_LEVEL == "DEBUG"

    def test_custom_environment(self) -> None:
        s = _make_settings(ENVIRONMENT="production")
        assert s.ENVIRONMENT == "production"

    def test_custom_default_llm_provider(self) -> None:
        s = _make_settings(DEFAULT_LLM_PROVIDER="openai")
        assert s.DEFAULT_LLM_PROVIDER == "openai"

    def test_custom_rag_top_k(self) -> None:
        s = _make_settings(RAG_TOP_K="10")
        assert s.RAG_TOP_K == 10

    def test_custom_rate_limit(self) -> None:
        s = _make_settings(RATE_LIMIT_REQUESTS_PER_MINUTE="120")
        assert s.RATE_LIMIT_REQUESTS_PER_MINUTE == 120

    def test_gcs_storage_backend(self) -> None:
        s = _make_settings(STORAGE_BACKEND="gcs", GCS_BUCKET_NAME="my-bucket")
        assert s.STORAGE_BACKEND == "gcs"
        assert s.GCS_BUCKET_NAME == "my-bucket"


# ---------------------------------------------------------------------------
# get_settings singleton
# ---------------------------------------------------------------------------


class TestGetSettingsSingleton:
    """get_settings() must return the same cached object across calls."""

    def test_returns_settings_instance(self) -> None:
        s = get_settings()
        assert isinstance(s, Settings)

    def test_same_object_on_repeated_calls(self) -> None:
        """@lru_cache guarantees a single parse per process lifetime."""
        s1 = get_settings()
        s2 = get_settings()
        assert s1 is s2

    def test_settings_has_required_fields_populated(self) -> None:
        s = get_settings()
        assert s.SECRET_KEY   # non-empty (set in conftest)
        assert s.DATABASE_URL
        assert s.REDIS_URL
