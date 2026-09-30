# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : privacy.py
# Purpose : Pydantic schemas for differential-privacy admin endpoints
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Request/Response DTO
#
# Key Concepts:
#   - Pydantic v2 BaseModel with Field validators
#   - Epsilon (ε) bounded to [0.1, 10.0] range
#   - Per-user privacy budget tracking via Redis
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 request/response schemas for differential-privacy admin endpoints.

Schemas
-------
EpsilonUpdateRequest  — body for PUT /admin/privacy/epsilon
EpsilonResponse       — response for epsilon read/write operations
PrivacyBudgetResponse — response for GET /admin/privacy/budget

Requirements: 37.2, 37.6, 37.7
"""

from __future__ import annotations

from datetime import UTC, datetime

from pydantic import BaseModel, Field


class EpsilonUpdateRequest(BaseModel):
    """Request body for PUT /admin/privacy/epsilon.

    Requirements: 37.2
    """

    epsilon: float = Field(
        description=(
            "New differential-privacy epsilon (ε) value. "
            "Must be between 0.1 (strong privacy) and 10.0 (weak privacy / high utility)."
        ),
    )


class EpsilonResponse(BaseModel):
    """Response returned after reading or updating the DP epsilon value.

    Requirements: 37.2, 37.6
    """

    epsilon: float = Field(description="Current differential-privacy epsilon (ε) value.")
    mechanism: str = Field(
        default="Laplace",
        description="Noise mechanism in use (always 'Laplace' in this implementation).",
    )
    updated_at: datetime = Field(
        default_factory=lambda: datetime.now(UTC),
        description="Timestamp (UTC) when epsilon was last updated.",
    )


class UserBudgetEntry(BaseModel):
    """A single per-user privacy budget entry.

    Requirements: 37.7
    """

    user_id: str = Field(description="User identifier extracted from Redis key.")
    consumed_budget: float = Field(
        description="Total privacy budget (sum of ε values) consumed by this user."
    )


class PrivacyBudgetResponse(BaseModel):
    """Response for GET /admin/privacy/budget.

    Returns all per-user cumulative privacy budgets tracked in Redis.

    Requirements: 37.7
    """

    budgets: list[UserBudgetEntry] = Field(
        default_factory=list,
        description="Per-user privacy budget entries, ordered by user_id.",
    )
    total_users_tracked: int = Field(
        description="Number of users with a non-zero privacy budget recorded."
    )
    retrieved_at: datetime = Field(
        default_factory=lambda: datetime.now(UTC),
        description="Timestamp (UTC) when budgets were retrieved.",
    )
