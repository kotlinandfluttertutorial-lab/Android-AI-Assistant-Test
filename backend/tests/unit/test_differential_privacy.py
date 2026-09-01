# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit
# File    : test_differential_privacy.py
# Purpose : Unit tests for differential privacy implementation
#
# Architecture Layer : Test
# Pattern Used       : AAA (Arrange-Act-Assert)
#
# Key Concepts:
#   - Laplace mechanism noise independence per dimension
#   - Epsilon default from settings
#   - Admin endpoint epsilon validation
#   - Redis-backed epsilon lookup
#   - Privacy budget tracking in Redis
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Unit tests for Differential Privacy (Task 39).

Covers:
- Noise applied independently per dimension (correlation ≈ 0)   (Req 37.1, 37.3)
- Default epsilon = 1.0 from Settings                            (Req 37.1)
- PUT /admin/privacy/epsilon with epsilon=0.05 → HTTP 422        (Req 37.2)
- PUT /admin/privacy/epsilon with epsilon=15.0 → HTTP 422        (Req 37.2)
- get_current_epsilon returns Redis value when set               (Req 37.8)
- store_memory increments privacy_budget Redis counter           (Req 37.8)

Requirements: 21.1, 37.1, 37.2, 37.6, 37.8
"""

from __future__ import annotations

import asyncio
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import numpy as np
import pytest
from typing import Any

# Environment must be set before app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

from app.security.differential_privacy import (
    LaplaceNoiseInjector,
    get_current_epsilon,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_redis_mock(epsilon_value: str | None = None):
    """Return a fake Redis client suitable for FastAPI dependency injection.

    Builds a minimal stub object (not AsyncMock/MagicMock) so FastAPI's
    dependency resolver does not mistake it for a coroutine or callable
    sub-dependency.  Each method is an AsyncMock so ``await redis.method()``
    works normally inside route handlers.
    """

    class FakeRedis:
        def __init__(self, epsilon_val: str | None):
            self.set = AsyncMock()
            self.get = AsyncMock(return_value=epsilon_val)
            self.incrbyfloat = AsyncMock()
            self.keys = AsyncMock(return_value=[])
            self.mock = self

    return FakeRedis(epsilon_value)


def _run(coro):
    """Run an async coroutine synchronously."""
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# LaplaceNoiseInjector — noise independence per dimension
# ---------------------------------------------------------------------------


class TestLaplaceNoiseInjector:
    """Tests for LaplaceNoiseInjector.add_noise.

    Requirements: 37.1, 37.3
    """

    def test_noise_applied_independently_per_dimension(self) -> None:
        """Noise vectors across many samples should have near-zero correlation.

        Independence is guaranteed by drawing from
        ``np.random.laplace(..., size=arr.shape)`` which generates a separate
        random sample for each dimension.  With 1000 samples of a 2-dim embedding,
        the Pearson correlation coefficient between the two noise dimensions
        should be approximately 0 (well within ±0.1).
        """
        # Sensible epsilon for testing noise distribution
        epsilon = 1.0
        # 1000 samples of a 2-dimension embedding
        samples = []
        for _ in range(1000):
            # Input vector [0.0, 0.0]
            noised = LaplaceNoiseInjector.add_noise([0.0, 0.0], epsilon=epsilon)
            samples.append(noised)

        arr = np.array(samples)
        # Calculate Pearson correlation coefficient between dimension 0 and 1
        corr_matrix = np.corrcoef(arr[:, 0], arr[:, 1])
        correlation = corr_matrix[0, 1]

        # The noise added to each dimension MUST be independent.
        # Correlation should be near 0.
        assert abs(correlation) < 0.1, f"Expected near-zero correlation, got {correlation}"

    def test_output_length_matches_input(self) -> None:
        """The output embedding must have the same dimension as the input.

        Requirements: 37.3
        """
        injector = LaplaceNoiseInjector()
        input_vec = [0.1, 0.2, 0.3, 0.4, 0.5]
        output_vec = injector.add_noise(input_vec, epsilon=1.0)
        assert len(output_vec) == len(input_vec)

    def test_returns_list_of_floats(self) -> None:
        """The output must be a standard Python list of floats.

        Requirements: 37.3
        """
        input_vec = [1.0, 2.0]
        output_vec = LaplaceNoiseInjector.add_noise(input_vec, epsilon=1.0)
        assert isinstance(output_vec, list)
        assert all(isinstance(x, float) for x in output_vec)

    def test_noise_changes_values(self) -> None:
        """Verify that the noise injector actually modifies the values.

        Requirements: 37.1
        """
        input_vec = [0.5, 0.5, 0.5]
        output_vec = LaplaceNoiseInjector.add_noise(input_vec, epsilon=0.5)
        # Probability of noise being exactly 0 for all dimensions is near-zero.
        assert output_vec != input_vec

    def test_invalid_epsilon_raises_value_error(self) -> None:
        """Providing an epsilon <= 0 must raise a ValueError.

        Requirements: 37.1
        """
        input_vec = [0.1]
        with pytest.raises(ValueError, match="epsilon must be positive"):
            LaplaceNoiseInjector.add_noise(input_vec, epsilon=0.0)
        with pytest.raises(ValueError, match="epsilon must be positive"):
            LaplaceNoiseInjector.add_noise(input_vec, epsilon=-1.0)

    def test_epsilon_controls_noise_scale(self) -> None:
        """Smaller epsilon must produce larger variance (more noise).

        Requirements: 37.1
        """
        input_vec = [0.0] * 100
        # High epsilon (low noise)
        output_high = LaplaceNoiseInjector.add_noise(input_vec, epsilon=10.0)
        std_high = np.std(output_high)

        # Low epsilon (high noise)
        output_low = LaplaceNoiseInjector.add_noise(input_vec, epsilon=0.1)
        std_low = np.std(output_low)

        assert std_low > std_high


# ---------------------------------------------------------------------------
# get_current_epsilon — defaults and Redis overrides
# ---------------------------------------------------------------------------


class TestDefaultEpsilonFromSettings:
    """Tests for get_current_epsilon default behaviour.

    Requirements: 37.1, 37.8
    """

    def test_default_dp_epsilon_is_1_0(self) -> None:
        """Settings.DP_EPSILON should defaults to 1.0."""
        from app.config.settings import get_settings

        settings = get_settings()
        assert settings.DP_EPSILON == 1.0

    def test_get_current_epsilon_returns_settings_default_when_redis_is_none(
        self,
    ) -> None:
        """get_current_epsilon(redis=None) must return Settings.DP_EPSILON.

        Requirements: 37.8
        """
        result = _run(get_current_epsilon(redis=None))
        assert result == 1.0

    def test_get_current_epsilon_returns_settings_when_redis_returns_none(
        self,
    ) -> None:
        """If Redis lookup returns None, fall back to settings.

        Requirements: 37.8
        """
        redis = AsyncMock()
        redis.get = AsyncMock(return_value=None)

        result = _run(get_current_epsilon(redis=redis))
        assert result == 1.0


class TestGetCurrentEpsilonRedis:
    """Tests for get_current_epsilon with Redis overrides.

    Requirements: 37.8
    """

    def test_get_current_epsilon_returns_redis_value(self) -> None:
        """If "dp:epsilon" key exists in Redis, its value must be returned.

        Requirements: 37.8
        """
        redis = AsyncMock()
        redis.get = AsyncMock(return_value="2.5")

        result = _run(get_current_epsilon(redis=redis))
        assert result == 2.5

    def test_get_current_epsilon_falls_back_on_redis_error(self) -> None:
        """If Redis is unreachable, fall back gracefully to settings.

        Requirements: 37.8
        """
        redis = AsyncMock()
        redis.get = AsyncMock(side_effect=RuntimeError("connection refused"))

        result = _run(get_current_epsilon(redis=redis))
        assert result == 1.0  # settings default


# ---------------------------------------------------------------------------
# PUT /admin/privacy/epsilon — validation via TestClient
# ---------------------------------------------------------------------------


class TestAdminEpsilonEndpoint:
    """Tests for PUT /admin/privacy/epsilon HTTP validation.

    Requirements: 37.2, 37.6
    """

    def _make_app_with_mocked_deps(self, epsilon_value: str | None = None):
        """Build a minimal FastAPI test app that exercises the admin router."""
        from fastapi import FastAPI
        from fastapi.testclient import TestClient

        # CRITICAL: Import router AFTER potentially mocking dependencies or ensure
        # overrides use the exact same objects. If any dependency (like require_admin
        # or get_redis) is a MagicMock, FastAPI will try to resolve its *args and
        # **kwargs as query parameters, causing HTTP 422. We override them with
        # plain functions to prevent this.
        from app.api.admin import router as admin_router
        from app.security.rbac import require_admin
        from app.database.redis import get_redis
        from app.database import get_db

        app = FastAPI()
        mock_redis = _make_redis_mock(epsilon_value=epsilon_value)

        # Use plain functions for overrides to avoid MagicMock signature issues
        def override_require_admin():
            return None

        async def override_get_redis():
            yield mock_redis

        async def override_get_db():
            yield AsyncMock()

        app.dependency_overrides[require_admin] = override_require_admin
        app.dependency_overrides[get_redis] = override_get_redis
        app.dependency_overrides[get_db] = override_get_db

        app.include_router(admin_router)
        return TestClient(app), mock_redis

    def test_put_epsilon_below_minimum_returns_422(self) -> None:
        """PUT /admin/privacy/epsilon with epsilon=0.05 must return HTTP 422.

        Requirements: 37.2
        """
        client, _ = self._make_app_with_mocked_deps()
        response = client.put("/admin/privacy/epsilon", json={"epsilon": 0.05})
        assert response.status_code == 422

    def test_put_epsilon_above_maximum_returns_422(self) -> None:
        """PUT /admin/privacy/epsilon with epsilon=15.0 must return HTTP 422.

        Requirements: 37.2
        """
        client, _ = self._make_app_with_mocked_deps()
        response = client.put("/admin/privacy/epsilon", json={"epsilon": 15.0})
        assert response.status_code == 422

    def test_put_epsilon_valid_value_returns_200_and_sets_redis(self) -> None:
        """PUT /admin/privacy/epsilon with a valid value stores it in Redis.

        Requirements: 37.2, 37.6
        """
        client, mock_redis = self._make_app_with_mocked_deps()
        response = client.put("/admin/privacy/epsilon", json={"epsilon": 2.0})
        assert response.status_code == 200, f"Expected 200 but got {response.status_code}: {response.text}"
        data = response.json()
        assert data["epsilon"] == pytest.approx(2.0)
        assert data["mechanism"] == "Laplace"
        # Verify Redis.set was called with the correct key and value
        mock_redis.set.assert_called_once_with("dp:epsilon", "2.0")

    def test_put_epsilon_boundary_minimum_returns_200(self) -> None:
        """PUT /admin/privacy/epsilon with epsilon=0.1 (boundary) must return HTTP 200.

        Requirements: 37.2
        """
        client, _ = self._make_app_with_mocked_deps()
        response = client.put("/admin/privacy/epsilon", json={"epsilon": 0.1})
        assert response.status_code == 200

    def test_put_epsilon_boundary_maximum_returns_200(self) -> None:
        """PUT /admin/privacy/epsilon with epsilon=10.0 (boundary) must return HTTP 200.

        Requirements: 37.2
        """
        client, _ = self._make_app_with_mocked_deps()
        response = client.put("/admin/privacy/epsilon", json={"epsilon": 10.0})
        assert response.status_code == 200


# ---------------------------------------------------------------------------
# Privacy budget tracking — Req 37.8
# ---------------------------------------------------------------------------


class TestPrivacyBudgetTracking:
    """Tests for privacy budget (epsilon) tracking in Redis.

    Requirements: 37.8
    """

    @pytest.mark.asyncio
    async def test_store_memory_increments_privacy_budget(self) -> None:
        """After store_memory succeeds, privacy_budget:{user_id} is incremented by epsilon.

        Requirements: 37.8
        """
        import types

        from app.models.memory import Memory, MemoryType
        from app.repositories.memory_repository import MemoryRepository
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()

        # Mock user with privacy_mode=False
        mock_user = types.SimpleNamespace(
            id=user_id,
            email="test@example.com",
            privacy_mode=False,
        )

        # Mock database session
        db = AsyncMock()
        db_result = MagicMock()
        db_result.scalar_one_or_none.return_value = mock_user
        db.execute = AsyncMock(return_value=db_result)
        db.flush = AsyncMock()
        db.add = MagicMock()

        # Mock Redis
        redis = _make_redis_mock(epsilon_value="1.0")  # Redis has dp:epsilon = 1.0

        # Mock the repo's store_memory to avoid ChromaDB connection
        expected_memory = MagicMock(spec=Memory)
        with patch.object(
            MemoryRepository,
            "store_memory",
            new_callable=AsyncMock,
            return_value=expected_memory,
        ):
            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="Test memory content",
                memory_type=MemoryType.fact,
                redis=redis,
            )

        assert result is expected_memory
        # Verify privacy_budget was incremented
        redis.incrbyfloat.assert_called_once_with(
            f"privacy_budget:{user_id}", pytest.approx(1.0)
        )

    @pytest.mark.asyncio
    async def test_store_memory_does_not_increment_when_redis_is_none(self) -> None:
        """If redis client is missing, store_memory proceeds but skips increment.

        Requirements: 37.8
        """
        import types

        from app.models.memory import Memory, MemoryType
        from app.repositories.memory_repository import MemoryRepository
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()
        mock_user = types.SimpleNamespace(
            id=user_id,
            email="test@example.com",
            privacy_mode=False,
        )

        db = AsyncMock()
        db_result = MagicMock()
        db_result.scalar_one_or_none.return_value = mock_user
        db.execute = AsyncMock(return_value=db_result)

        expected_memory = MagicMock(spec=Memory)
        with patch.object(
            MemoryRepository,
            "store_memory",
            new_callable=AsyncMock,
            return_value=expected_memory,
        ) as mock_store:
            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="Test",
                memory_type=MemoryType.fact,
                redis=None,
            )

        assert result is expected_memory
        mock_store.assert_called_once()

    @pytest.mark.asyncio
    async def test_store_memory_privacy_mode_skips_budget_increment(self) -> None:
        """When privacy_mode=True, store_memory returns None and does NOT increment budget.

        Requirements: 7.6, 37.8
        """
        import types

        from app.models.memory import MemoryType
        from app.repositories.memory_repository import MemoryRepository
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()

        mock_user = types.SimpleNamespace(
            id=user_id,
            email="test@example.com",
            privacy_mode=True,
        )

        db = AsyncMock()
        db_result = MagicMock()
        db_result.scalar_one_or_none.return_value = mock_user
        db.execute = AsyncMock(return_value=db_result)

        redis = _make_redis_mock()

        with patch.object(
            MemoryRepository, "store_memory", new_callable=AsyncMock
        ) as mock_store:
            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="Private content",
                memory_type=MemoryType.fact,
                redis=redis,
            )

        assert result is None
        mock_store.assert_not_called()
        redis.incrbyfloat.assert_not_called()
