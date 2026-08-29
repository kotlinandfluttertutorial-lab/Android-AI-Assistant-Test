"""Shared pytest configuration for backend unit tests."""

from __future__ import annotations

import os

# Ensure required environment variables are available before any imports.
# These default values are used ONLY in tests; real values come from .env.
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import pytest
from unittest.mock import AsyncMock, MagicMock

@pytest.fixture(autouse=True)
def mock_redis():
    """Mock Redis client for all tests to avoid connection errors."""
    with (
        patch("app.database.redis.get_redis_client") as mock_get_client,
        patch("app.database.redis.get_redis") as mock_get_redis
    ):
        mock_client = AsyncMock()
        mock_client.incr.return_value = 1
        mock_client.ping.return_value = True
        mock_client.exists.return_value = 0
        mock_get_client.return_value = mock_client

        async def _fake_get_redis():
            yield mock_client

        mock_get_redis.return_value = _fake_get_redis()
        yield mock_client

from unittest.mock import patch
