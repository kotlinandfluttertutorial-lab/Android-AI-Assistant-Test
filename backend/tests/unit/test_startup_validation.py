"""Unit tests for startup_validation() and get_missing_env_vars().

Tests mock os.environ to remove each required variable in turn and verify that:
- startup_validation() logs a structured error naming the missing variable.
- startup_validation() raises SystemExit with code 1 before binding to any port.
- get_missing_env_vars() accurately reports which variables are absent.

Validates: Requirements 21.1, 26.3, 26.5
"""

from __future__ import annotations

import logging
import os
from unittest.mock import patch

import pytest

# Ensure all required env vars are present for the module-level import of main.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault(
    "AES_ENCRYPTION_KEY",
    "dGVzdC1hZXMtMjU2LWtleS0zMi1ieXRlcy1iYXNlNjQh",  # safe test placeholder
)


# ---------------------------------------------------------------------------
# Helper: build an environment dict that has all required vars set, then
# selectively remove one to simulate a missing variable.
# ---------------------------------------------------------------------------

_REQUIRED_VARS = {
    "SECRET_KEY": "test-secret-key-at-least-32-chars-long!!",
    "DATABASE_URL": "postgresql+asyncpg://test:test@localhost/test",
    "REDIS_URL": "redis://localhost:6379/0",
    "AES_ENCRYPTION_KEY": "dGVzdC1hZXMtMjU2LWtleS0zMi1ieXRlcy1iYXNlNjQh",
}


def _env_without(var_name: str) -> dict[str, str]:
    """Return a copy of os.environ with *var_name* removed."""
    env = {**os.environ, **_REQUIRED_VARS}  # guarantee all required vars are set
    env.pop(var_name, None)
    return env


# ---------------------------------------------------------------------------
# Tests for startup_validation()
# ---------------------------------------------------------------------------


class TestStartupValidation:
    """startup_validation() must exit code-1 for every missing required var."""

    @pytest.mark.parametrize("missing_var", list(_REQUIRED_VARS.keys()))
    def test_exits_with_code_1_when_var_missing(self, missing_var: str) -> None:
        """Removing any single required variable causes SystemExit(1).

        Validates: Requirements 26.3, 26.5
        """
        # Import here (not at module level) so we always get a fresh reference
        # after the env is already set up at the top of this file.
        from app.main import startup_validation

        with patch.dict(os.environ, _env_without(missing_var), clear=True):
            with pytest.raises(SystemExit) as exc_info:
                startup_validation()
        assert exc_info.value.code == 1, (
            f"Expected exit code 1 when {missing_var!r} is missing, "
            f"got {exc_info.value.code!r}"
        )

    @pytest.mark.parametrize("missing_var", list(_REQUIRED_VARS.keys()))
    def test_logs_missing_variable_name(
        self,
        missing_var: str,
        caplog: pytest.LogCaptureFixture,
    ) -> None:
        """startup_validation() logs an ERROR containing the missing variable name.

        Validates: Requirements 26.3, 26.5
        """
        from app.main import startup_validation

        with patch.dict(os.environ, _env_without(missing_var), clear=True):
            with caplog.at_level(logging.ERROR, logger="app.main"):
                with pytest.raises(SystemExit):
                    startup_validation()

        # The structured error log must mention the missing variable name.
        log_text = " ".join(record.getMessage() for record in caplog.records)
        assert missing_var in log_text, (
            f"Expected {missing_var!r} to appear in the error log, "
            f"but log contained: {log_text!r}"
        )

    def test_does_not_exit_when_all_vars_present(self) -> None:
        """startup_validation() completes without raising when all vars are set.

        Validates: Requirements 26.3, 26.5
        """
        from app.main import startup_validation

        with patch.dict(os.environ, _REQUIRED_VARS, clear=False):
            # Should not raise SystemExit
            startup_validation()

    def test_exits_with_code_1_when_var_is_whitespace_only(self) -> None:
        """A whitespace-only value is treated as missing (SystemExit 1).

        Validates: Requirement 26.3
        """
        from app.main import startup_validation

        env = {**os.environ, **_REQUIRED_VARS, "SECRET_KEY": "   "}
        with patch.dict(os.environ, env, clear=True):
            with pytest.raises(SystemExit) as exc_info:
                startup_validation()
        assert exc_info.value.code == 1

    def test_aes_key_absence_causes_exit(self) -> None:
        """AES_ENCRYPTION_KEY absence specifically triggers SystemExit(1).

        Validates: Requirement 26.5
        """
        from app.main import startup_validation

        with patch.dict(os.environ, _env_without("AES_ENCRYPTION_KEY"), clear=True):
            with pytest.raises(SystemExit) as exc_info:
                startup_validation()
        assert exc_info.value.code == 1

    def test_aes_key_absence_logged(self, caplog: pytest.LogCaptureFixture) -> None:
        """AES_ENCRYPTION_KEY absence is specifically logged by name.

        Validates: Requirement 26.5
        """
        from app.main import startup_validation

        with patch.dict(os.environ, _env_without("AES_ENCRYPTION_KEY"), clear=True):
            with caplog.at_level(logging.ERROR, logger="app.main"):
                with pytest.raises(SystemExit):
                    startup_validation()

        log_text = " ".join(r.getMessage() for r in caplog.records)
        assert "AES_ENCRYPTION_KEY" in log_text


# ---------------------------------------------------------------------------
# Tests for get_missing_env_vars()
# ---------------------------------------------------------------------------


class TestGetMissingEnvVars:
    """get_missing_env_vars() must accurately report all absent required vars."""

    def test_returns_empty_list_when_all_present(self) -> None:
        """Returns [] when every required var is set.

        Validates: Requirement 26.4
        """
        from app.main import get_missing_env_vars

        with patch.dict(os.environ, _REQUIRED_VARS, clear=False):
            result = get_missing_env_vars()
        assert result == []

    @pytest.mark.parametrize("missing_var", list(_REQUIRED_VARS.keys()))
    def test_returns_missing_var_name(self, missing_var: str) -> None:
        """Returns a list containing the name of the removed variable.

        Validates: Requirements 26.3, 26.4
        """
        from app.main import get_missing_env_vars

        with patch.dict(os.environ, _env_without(missing_var), clear=True):
            result = get_missing_env_vars()
        assert missing_var in result, (
            f"Expected {missing_var!r} in missing vars, got {result!r}"
        )

    def test_returns_multiple_missing_vars(self) -> None:
        """Reports all missing variables when more than one is absent.

        Validates: Requirement 26.3
        """
        from app.main import get_missing_env_vars

        env = _env_without("SECRET_KEY")
        env.pop("AES_ENCRYPTION_KEY", None)

        with patch.dict(os.environ, env, clear=True):
            result = get_missing_env_vars()

        assert "SECRET_KEY" in result
        assert "AES_ENCRYPTION_KEY" in result

    def test_whitespace_only_value_is_missing(self) -> None:
        """A var set to only whitespace is treated as absent.

        Validates: Requirement 26.3
        """
        from app.main import get_missing_env_vars

        env = {**os.environ, **_REQUIRED_VARS, "DATABASE_URL": "  "}
        with patch.dict(os.environ, env, clear=True):
            result = get_missing_env_vars()
        assert "DATABASE_URL" in result
