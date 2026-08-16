# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : security
# File    : differential_privacy.py
# Purpose : Differential privacy via Laplace noise injection for memory embeddings
#
# Architecture Layer : Security / Privacy
# Pattern Used       : Strategy Pattern (noise injection)
#
# Key Concepts:
#   - Laplace mechanism for (ε, 0)-differential privacy
#   - Per-dimension independent noise sampling
#   - Epsilon (ε) controls privacy-utility trade-off: smaller ε = more privacy
#   - Sensitivity = 1.0 for unit-normalized embeddings
#   - Redis-backed dynamic epsilon overrides at runtime
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Differential Privacy module — Laplace noise injection for memory embeddings.

The Laplace mechanism provides (ε, 0)-differential privacy. For each dimension
of the embedding vector, independent Laplace noise with scale = sensitivity/ε
is added before the vector is stored in ChromaDB.

The ``get_current_epsilon`` helper reads a runtime epsilon override from Redis
key ``"dp:epsilon"`` and falls back to ``Settings.DP_EPSILON`` when the key is
absent or Redis is unavailable.

Requirements: 37.1, 37.3, 37.4, 37.5, 37.8
"""

from __future__ import annotations

import logging

import numpy as np

logger = logging.getLogger(__name__)


class LaplaceNoiseInjector:
    """Adds calibrated Laplace noise to an embedding vector for differential privacy.

    The Laplace mechanism is the canonical (ε, 0)-DP mechanism for real-valued
    queries with bounded sensitivity.  Adding noise independently per dimension
    (via ``np.random.laplace(..., size=arr.shape)``) satisfies the composition
    theorem and ensures each dimension's noise is statistically independent.

    Requirements: 37.1, 37.3, 37.4
    """

    @staticmethod
    def add_noise(
        embedding: list[float],
        epsilon: float,
        sensitivity: float = 1.0,
    ) -> list[float]:
        """Apply independent Laplace noise to every dimension of *embedding*.

        Args:
            embedding: The original embedding vector (list of floats).
            epsilon: Privacy budget parameter ε > 0.  Smaller values give
                stronger privacy guarantees but reduce utility.
            sensitivity: L1 sensitivity of the embedding function.
                Defaults to 1.0 (appropriate for unit-normalized vectors).

        Returns:
            A new list of floats representing the noised embedding.

        Raises:
            ValueError: If *epsilon* is not positive.

        Requirements: 37.1, 37.3
        """
        if epsilon <= 0:
            raise ValueError(f"epsilon must be positive, got {epsilon!r}")

        scale = sensitivity / epsilon
        arr = np.array(embedding, dtype=np.float64)
        # Draw independent samples — one per dimension — guaranteeing
        # per-dimension noise independence required by the Laplace mechanism.
        noise = np.random.laplace(loc=0.0, scale=scale, size=arr.shape)
        return (arr + noise).tolist()


async def get_current_epsilon(redis: object = None) -> float:
    """Return the current differential privacy ε value.

    Precedence:
    1. Redis key ``"dp:epsilon"`` (dynamic, set by admin endpoint).
    2. ``Settings.DP_EPSILON`` (static default from environment / .env file).

    Args:
        redis: Optional async Redis client.  When ``None`` (or when Redis is
            unavailable), falls back to the settings value.

    Returns:
        The current ε value as a float.

    Requirements: 37.8
    """
    from app.config.settings import get_settings

    settings = get_settings()
    fallback = float(settings.DP_EPSILON)

    if redis is None:
        return fallback

    try:
        raw: str | None = await redis.get("dp:epsilon")  # type: ignore[union-attr,no-untyped-call]
        if raw is not None:
            return float(raw)
    except Exception as exc:
        logger.warning(
            "Failed to read dp:epsilon from Redis; falling back to settings: %s",
            exc,
        )

    return fallback
