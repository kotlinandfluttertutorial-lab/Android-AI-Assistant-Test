# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : usage.py
# Purpose : Pydantic v2 request/response schemas for /usage/* endpoints
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Pydantic v2 BaseModel
#
# Dependencies:
#   - app.models.token_usage (UsageFeature)
# ============================================================

"""Pydantic v2 schemas for the AI Cost Dashboard endpoints.

Covers:
- GET  /usage/cost         — aggregated token usage + estimated cost (Req 34.1, 34.2)
- POST /usage/alerts       — create spending alert threshold (Req 34.4)
- DELETE /usage/alerts/{id} — remove spending alert (Req 34.4)
- GET  /usage/alerts       — list spending alerts for the current user (Req 34.4)

Requirements: 34.1, 34.2, 34.4, 34.7, 34.8
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Cost aggregation response schemas
# ---------------------------------------------------------------------------


class DailyCostRowSchema(BaseModel):
    """Aggregated cost for one (feature, provider, calendar-day) combination.

    Requirements: 34.1, 34.2
    """

    model_config = ConfigDict(from_attributes=False)

    feature: str = Field(
        description="AI feature name: chat | rag | code | voice | comparison | suggestions"
    )
    provider: str = Field(description="LLM provider identifier, e.g. 'openai', 'anthropic', 'gemini'")
    day: str = Field(description="ISO-8601 calendar date (UTC) for this aggregated row, e.g. '2025-01-15'")
    input_tokens: int = Field(description="Total input tokens consumed on this day by this feature/provider")
    output_tokens: int = Field(
        description="Total output tokens consumed on this day by this feature/provider"
    )
    cost_usd: float = Field(description="Estimated cost in USD for this day/feature/provider combination")


class CostSummaryResponse(BaseModel):
    """Response for GET /usage/cost.

    Returns aggregated token usage and estimated cost for the last 90 days,
    broken down by feature, LLM provider, and calendar day (UTC).

    Requirements: 34.1, 34.2, 34.7
    """

    model_config = ConfigDict(from_attributes=False)

    total_input_tokens: int = Field(description="Total input tokens across all features, providers, and days")
    total_output_tokens: int = Field(description="Total output tokens across all features, providers, and days")
    total_cost_usd: float = Field(description="Total estimated cost in USD across the entire window")
    rows: list[DailyCostRowSchema] = Field(
        description="Per-(feature, provider, day) aggregated cost rows, ordered by day descending"
    )
    window_days: int = Field(
        default=90,
        description="Number of days included in this aggregation window",
    )


# ---------------------------------------------------------------------------
# Spending alert schemas
# ---------------------------------------------------------------------------


class SpendingAlertCreateRequest(BaseModel):
    """Request body for POST /usage/alerts.

    Requirements: 34.4
    """

    threshold_usd: Decimal = Field(
        description=(
            "Spending threshold in USD. When the user's accumulated daily cost "
            "reaches or exceeds this value, an in-app notification is sent. "
            "Valid range: $0.01 – $999.99."
        ),
        examples=[5.00, 10.00, 50.00],
    )

    @field_validator("threshold_usd")
    @classmethod
    def validate_threshold(cls, v: Decimal) -> Decimal:
        min_val = Decimal("0.01")
        max_val = Decimal("999.99")
        if v < min_val or v > max_val:
            raise ValueError(f"threshold_usd must be between {min_val} and {max_val}; got {v}")
        return v


class SpendingAlertResponse(BaseModel):
    """Response schema for a single spending alert.

    Requirements: 34.4, 34.5, 34.6
    """

    model_config = ConfigDict(from_attributes=False)

    id: uuid.UUID = Field(description="Unique identifier for this spending alert")
    user_id: uuid.UUID = Field(description="UUID of the user who owns this alert")
    threshold_usd: float = Field(description="Spending threshold in USD ($0.01 – $999.99)")
    is_triggered: bool = Field(
        description="True when the alert monitor has detected the threshold was crossed"
    )
    triggered_at: datetime | None = Field(
        default=None,
        description="UTC timestamp when the threshold was first crossed, or null",
    )
    dismissed_at: datetime | None = Field(
        default=None,
        description="UTC timestamp when the user explicitly dismissed the banner, or null",
    )
    created_at: datetime = Field(description="UTC timestamp when this alert was created")


class SpendingAlertListResponse(BaseModel):
    """Response for GET /usage/alerts.

    Requirements: 34.4
    """

    model_config = ConfigDict(from_attributes=False)

    alerts: list[SpendingAlertResponse] = Field(
        description="All spending alerts owned by the authenticated user (max 3)"
    )


class SpendingAlertDeleteResponse(BaseModel):
    """Response for DELETE /usage/alerts/{id}.

    Requirements: 34.4
    """

    deleted: bool = Field(description="True if the alert was found and deleted")
    alert_id: uuid.UUID = Field(description="The ID of the alert that was targeted")
