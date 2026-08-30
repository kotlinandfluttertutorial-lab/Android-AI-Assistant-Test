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


def _make_redis_mock(epsilon_value: str | None = None) -> AsyncMock:
    """Return an async Redis mock whose .get("dp:epsilon") returns *epsilon_value*."""
    redis = AsyncMock()
    redis.get = AsyncMock(return_value=epsilon_value)
    redis.set = AsyncMock()
    redis.incrbyfloat = AsyncMock()
    redis.keys = AsyncMock(return_value=[])
    return redis


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

        Requirements: 37.1, 37.3
        """
        rng = np.random.default_rng(seed=42)
        n_samples = 1000
        noise_dim0 = []
        noise_dim1 = []

        for _ in range(n_samples):
            original = rng.standard_normal(2).tolist()
            noised = LaplaceNoiseInjector.add_noise(original, epsilon=1.0)
            noise_dim0.append(noised[0] - original[0])
            noise_dim1.append(noised[1] - original[1])

        # Compute Pearson correlation between the two noise dimensions
        correlation = float(np.corrcoef(noise_dim0, noise_dim1)[0, 1])
        assert (
            abs(correlation) < 0.1
        ), f"Noise dimensions should be independent (|r| < 0.1), got r={correlation:.4f}"

    def test_output_length_matches_input(self) -> None:
        """add_noise returns a list of the same length as the input embedding.

        Requirements: 37.1
        """
        embedding = [0.1, 0.2, 0.3, 0.4, 0.5]
        noised = LaplaceNoiseInjector.add_noise(embedding, epsilon=1.0)
        assert len(noised) == len(embedding)

    def test_returns_list_of_floats(self) -> None:
        """add_noise output contains only Python floats.

        Requirements: 37.1
        """
        embedding = [0.1] * 10
        noised = LaplaceNoiseInjector.add_noise(embedding, epsilon=1.0)
        assert all(isinstance(v, float) for v in noised)

    def test_noise_changes_values(self) -> None:
        """Noised embedding differs from original (with overwhelming probability).

        Requirements: 37.1
        """
        embedding = [0.5] * 384
        noised = LaplaceNoiseInjector.add_noise(embedding, epsilon=1.0)
        assert noised != embedding, "Noised embedding should differ from original."

    def test_invalid_epsilon_raises_value_error(self) -> None:
        """add_noise must raise ValueError for non-positive epsilon.

        Requirements: 37.1
        """
        with pytest.raises(ValueError, match="epsilon must be positive"):
            LaplaceNoiseInjector.add_noise([0.1, 0.2], epsilon=0.0)

    def test_epsilon_controls_noise_scale(self) -> None:
        """Larger epsilon produces smaller noise (tighter privacy guarantee = smaller scale).

        scale = sensitivity/epsilon; larger epsilon → smaller scale → less noise.

        Requirements: 37.3
        """
        np.random.seed(0)
        embedding = [0.5] * 384

        noised_tight = LaplaceNoiseInjector.add_noise(embedding, epsilon=0.1)
        np.random.seed(0)
        noised_loose = LaplaceNoiseInjector.add_noise(embedding, epsilon=5.0)

        noise_tight = np.mean(np.abs(np.array(noised_tight) - np.array(embedding)))
        noise_loose = np.mean(np.abs(np.array(noised_loose) - np.array(embedding)))
        assert (
            noise_tight > noise_loose
        ), "Smaller epsilon should produce larger noise magnitude."


# ---------------------------------------------------------------------------
# Default epsilon from settings
# ---------------------------------------------------------------------------


class TestDefaultEpsilonFromSettings:
    """Verify default epsilon = 1.0 is loaded from Settings.

    Requirements: 37.1
    """

    def test_default_dp_epsilon_is_1_0(self) -> None:
        """Settings.DP_EPSILON should default to 1.0.

        Requirements: 37.1
        """
        from app.config.settings import get_settings

        settings = get_settings()
        assert settings.DP_EPSILON == 1.0

    def test_get_current_epsilon_returns_settings_default_when_redis_is_none(
        self,
    ) -> None:
        """get_current_epsilon(redis=None) returns Settings.DP_EPSILON.

        Requirements: 37.8
        """
        result = _run(get_current_epsilon(redis=None))
        assert result == 1.0

    def test_get_current_epsilon_returns_settings_when_redis_returns_none(self) -> None:
        """get_current_epsilon falls back to settings when Redis key is absent.

        Requirements: 37.8
        """
        redis = _make_redis_mock(epsilon_value=None)
        result = _run(get_current_epsilon(redis=redis))
        assert result == 1.0


# ---------------------------------------------------------------------------
# GET /admin/privacy/epsilon — epsilon Redis override
# ---------------------------------------------------------------------------


class TestGetCurrentEpsilonRedis:
    """Tests for get_current_epsilon with Redis.

    Requirements: 37.8
    """

    def test_get_current_epsilon_returns_redis_value(self) -> None:
        """When Redis key dp:epsilon is set, get_current_epsilon returns it.

        Requirements: 37.8
        """
        redis = _make_redis_mock(epsilon_value="2.5")
        result = _run(get_current_epsilon(redis=redis))
        assert result == pytest.approx(2.5)

    def test_get_current_epsilon_falls_back_on_redis_error(self) -> None:
        """When Redis raises, get_current_epsilon falls back to settings value.

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

    def _make_app_with_mocked_deps(self):
        """Build a minimal FastAPI test app that exercises the admin router."""
        from fastapi import FastAPI
        from fastapi.testclient import TestClient

        from app.api.admin import router as admin_router
        from app.database.redis import get_redis
        from app.security.rbac import require_admin

        test_app = FastAPI()

        # Override auth dependency so tests don't need a real JWT
        test_app.dependency_overrides[require_admin] = lambda: None

        # Mock Redis
        mock_redis = _make_redis_mock()

        # Re-import to ensure we use the same function object
        from app.database.redis import get_redis

        async def _fake_redis_gen():
            yield mock_redis

        test_app.dependency_overrides[get_redis] = _fake_redis_gen
        test_app.include_router(admin_router)
        return TestClient(test_app), mock_redis

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
        assert response.status_code == 200
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
# Privacy budget tracking — store_memory increments Redis counter
# ---------------------------------------------------------------------------


class TestPrivacyBudgetTracking:
    """Tests verifying store_memory increments the per-user privacy budget.

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
        """When redis=None, store_memory still works but skips budget tracking.

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
        db.flush = AsyncMock()
        db.add = MagicMock()

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
                redis=None,
            )

        # Should succeed and return the memory
        assert result is expected_memory

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
