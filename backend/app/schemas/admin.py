# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : admin.py
# Purpose : admin — schemas module
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 request/response schemas for the /admin/* endpoints.

All schemas use Pydantic v2 syntax (model_config, field_validator).

Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 9.7
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.security.input_sanitizer import sanitize_user_string

# Allowed values for the user management action field — defined at module level
# to avoid Pydantic v2 treating it as a model PrivateAttr.
_ALLOWED_USER_ACTIONS: frozenset[str] = frozenset(
    {"promote", "demote", "make_admin", "remove_admin", "deactivate", "reactivate"}
)


# ---------------------------------------------------------------------------
# Metrics — GET /admin/metrics
# ---------------------------------------------------------------------------


class ProviderCost(BaseModel):
    """Per-provider token cost breakdown."""

    provider: str = Field(description="LLM provider name, e.g. 'openai'")
    total_tokens: int = Field(description="Total tokens consumed by this provider")
    total_cost_usd: float = Field(description="Estimated cost in USD")


class MetricsResponse(BaseModel):
    """Response for GET /admin/metrics.

    Requirements: 15.1
    """

    active_users: int = Field(
        description="Number of users with sessions active in the last hour"
    )
    messages_per_hour: int = Field(description="Total messages sent in the last hour")
    total_tokens_consumed: int = Field(
        description="Cumulative tokens across all providers"
    )
    provider_costs: list[ProviderCost] = Field(
        description="Per-provider token cost breakdown"
    )
    error_rate_per_hour: float = Field(
        description="Fraction of requests that resulted in a 5xx error in the last hour"
    )
    snapshot_at: datetime = Field(
        description="UTC timestamp when metrics were collected"
    )


# ---------------------------------------------------------------------------
# Users — GET /admin/users, PATCH /admin/users/{id}
# ---------------------------------------------------------------------------


class UserAdminResponse(BaseModel):
    """Single user entry returned by GET /admin/users.

    Requirements: 15.2
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    email: str
    display_name: str
    role: str
    is_active: bool
    created_at: datetime
    updated_at: datetime


class PaginatedUsersResponse(BaseModel):
    """Paginated list of users.

    Requirements: 15.2
    """

    items: list[UserAdminResponse]
    total: int
    page: int
    page_size: int
    pages: int


class UserUpdateRequest(BaseModel):
    """Request body for PATCH /admin/users/{id}.

    Exactly one action field should be set per request.

    Requirements: 15.2, 15.4, 9.7
    """

    action: str = Field(
        max_length=50,
        description="One of: 'promote' (user→premium), 'demote' (premium→user), "
        "'make_admin' (→admin), 'remove_admin' (admin→user), 'deactivate', 'reactivate'",
        examples=["deactivate", "promote", "demote"],
    )

    @field_validator("action")
    @classmethod
    def validate_action(cls, v: str) -> str:
        v = sanitize_user_string(cls, v)
        if v not in _ALLOWED_USER_ACTIONS:
            raise ValueError(f"action must be one of {sorted(_ALLOWED_USER_ACTIONS)}")
        return v


class UserUpdateResponse(BaseModel):
    """Response body for PATCH /admin/users/{id}.

    Requirements: 15.2, 15.4
    """

    user_id: uuid.UUID
    action: str
    new_role: str | None = None
    is_active: bool
    tokens_revoked: int = Field(
        default=0,
        description="Number of refresh tokens revoked (non-zero when action='deactivate')",
    )


# ---------------------------------------------------------------------------
# Audit Logs — GET /admin/audit-logs
# ---------------------------------------------------------------------------


class AuditLogEntry(BaseModel):
    """Single audit log entry.

    Requirements: 15.5
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID | None
    event_type: str
    ip_address: str
    user_agent: str
    metadata: dict[str, Any] = Field(alias="metadata_")
    created_at: datetime

    model_config = ConfigDict(from_attributes=True, populate_by_name=True)


class PaginatedAuditLogsResponse(BaseModel):
    """Paginated audit log response.

    Requirements: 15.5
    """

    items: list[AuditLogEntry]
    total: int
    page: int
    page_size: int
    pages: int


# ---------------------------------------------------------------------------
# Error Summary — GET /admin/errors
# ---------------------------------------------------------------------------


class ErrorSummary(BaseModel):
    """Top-N error type summary entry.

    Requirements: 15.6
    """

    error_type: str = Field(description="Error class/type string")
    count: int = Field(description="Number of occurrences in the last 24 hours")
    last_seen: datetime = Field(description="Most recent occurrence timestamp")
    sample_message: str = Field(description="Sample error message for context")
    stack_trace_summary: str = Field(
        description="First 500 characters of the most recent stack trace"
    )


class ErrorSummaryResponse(BaseModel):
    """Response for GET /admin/errors.

    Requirements: 15.6
    """

    errors: list[ErrorSummary]
    window_hours: int = Field(default=24, description="Time window in hours")
    generated_at: datetime


# ---------------------------------------------------------------------------
# Feedback — GET /admin/feedback, POST /admin/feedback/export
# ---------------------------------------------------------------------------


class FeedbackItem(BaseModel):
    """Single feedback item.

    Requirements: 15.7
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID | None
    content: str
    category: str
    created_at: datetime


class PaginatedFeedbackResponse(BaseModel):
    """Paginated feedback list response.

    Requirements: 15.7
    """

    items: list[FeedbackItem]
    total: int
    page: int
    page_size: int
    pages: int


# ---------------------------------------------------------------------------
# Sessions — GET /admin/sessions
# ---------------------------------------------------------------------------


class SessionInfo(BaseModel):
    """Real-time active session entry.

    Requirements: 15.9
    """

    user_id: str
    session_id: str
    device_type: str = Field(
        default="unknown",
        description="Device type, e.g. 'android', 'ios', 'web'",
    )
    region: str = Field(
        default="unknown",
        description="Geographic region inferred from IP, e.g. 'us-east-1'",
    )
    current_feature: str = Field(
        default="unknown",
        description="Feature in active use, e.g. 'chat', 'rag', 'voice'",
    )
    connected_at: str = Field(description="ISO-8601 UTC timestamp of session start")
    duration_seconds: int = Field(
        default=0,
        description="Session duration in seconds since connected_at",
    )


class ActiveSessionsResponse(BaseModel):
    """Response for GET /admin/sessions.

    Requirements: 15.9
    """

    sessions: list[SessionInfo]
    total: int
    snapshot_at: datetime


# ---------------------------------------------------------------------------
# Firebase Remote Config — /admin/remote-config
# ---------------------------------------------------------------------------


class RemoteConfigEntry(BaseModel):
    """A single remote config key-value pair.

    Requirements: 15.8
    """

    key: str
    value: str
    description: str = ""
    last_updated: datetime | None = None


class RemoteConfigListResponse(BaseModel):
    """Response for GET /admin/remote-config.

    Requirements: 15.8
    """

    entries: list[RemoteConfigEntry]
    published_at: datetime | None = None


class RemoteConfigUpdateRequest(BaseModel):
    """Request body for PATCH /admin/remote-config/{key}.

    Requirements: 15.8, 9.7
    """

    value: str = Field(
        max_length=10_000,
        description="New value for this configuration key",
    )
    description: str = Field(
        default="",
        max_length=2_000,
        description="Optional description of what this key controls",
    )

    @field_validator("value", "description")
    @classmethod
    def sanitize_fields(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class RemoteConfigPublishResponse(BaseModel):
    """Response for POST /admin/remote-config/publish.

    Requirements: 15.8
    """

    published: bool
    entries_count: int
    published_at: datetime
    message: str = ""


# ---------------------------------------------------------------------------
# Celery Metrics — GET /admin/celery-metrics (Req 27.4, 15.1)
# ---------------------------------------------------------------------------


class CeleryMetricsResponse(BaseModel):
    """Response for GET /admin/celery-metrics.

    Requirements: 27.4, 15.1
    """

    queue_depth: int = Field(
        description="Number of pending tasks waiting in the Celery queue",
    )
    active_tasks: int = Field(
        description="Number of tasks currently being executed by Celery workers",
    )
    failed_tasks: int = Field(
        description="Number of permanently failed Celery tasks (after retries exhausted)",
    )


# ---------------------------------------------------------------------------
# Usage Analytics — GET /admin/usage-analytics (Req 15.3)
# ---------------------------------------------------------------------------


class UsageAnalyticsItem(BaseModel):
    """Token usage broken down by feature and LLM provider.

    Requirements: 15.3
    """

    feature: str = Field(description="AI feature name, e.g. 'chat', 'rag', 'voice'")
    provider: str = Field(description="LLM provider name, e.g. 'openai'")
    total_requests: int = Field(
        description="Number of completions for this feature/provider pair"
    )
    total_tokens: int = Field(description="Total tokens consumed (input + output)")
    cost_usd: float = Field(description="Estimated cumulative cost in USD")


class UsageAnalyticsResponse(BaseModel):
    """Response for GET /admin/usage-analytics.

    Requirements: 15.3
    """

    items: list[UsageAnalyticsItem] = Field(
        description="Usage broken down by feature and LLM provider"
    )
    generated_at: datetime = Field(
        description="UTC timestamp when analytics were collected"
    )
