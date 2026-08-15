# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : users.py
# Purpose : users — schemas module
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

"""Pydantic v2 schemas for GDPR data-privacy endpoints.

Covers:
- POST /users/me/export  → DataExportResponse
- DELETE /users/me       → AccountDeletionRequest / AccountDeletionResponse

Requirements: 28.1, 28.2
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from pydantic import BaseModel, ConfigDict, Field


def _utcnow_plus(hours: int) -> str:
    """Return an ISO 8601 UTC timestamp *hours* from now."""
    return (datetime.now(tz=datetime.UTC) + timedelta(hours=hours)).isoformat()


# ---------------------------------------------------------------------------
# Data export
# ---------------------------------------------------------------------------


class DataExportResponse(BaseModel):
    """Response body for POST /users/me/export (HTTP 200).

    Requirements: 28.1
    """

    model_config = ConfigDict(from_attributes=True)

    job_id: uuid.UUID = Field(
        description="UUID of the background export job that was enqueued.",
    )
    message: str = Field(
        default="Your data export has been queued. You will be notified when it is ready.",
        description="Human-readable confirmation message.",
    )
    estimated_completion: str = Field(
        description=(
            "ISO 8601 UTC timestamp by which the export will be complete (24 h from now)."
        ),
    )


# ---------------------------------------------------------------------------
# Account deletion
# ---------------------------------------------------------------------------


class AccountDeletionRequest(BaseModel):
    """Request body for DELETE /users/me.

    The caller must supply their email address as an explicit confirmation step.

    Requirements: 28.2
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    email: str = Field(
        description=(
            "The authenticated user's email address, provided as explicit "
            "confirmation before permanent account deletion."
        ),
        examples=["user@example.com"],
    )


class AccountDeletionResponse(BaseModel):
    """Response body for DELETE /users/me (HTTP 200).

    Requirements: 28.2
    """

    model_config = ConfigDict(from_attributes=True)

    message: str = Field(
        default=(
            "Your account deletion request has been received. "
            "All your data will be permanently removed within 72 hours."
        ),
        description="Human-readable confirmation message.",
    )
    scheduled_at: datetime = Field(
        description="UTC timestamp at which the deletion was scheduled.",
    )
    estimated_completion: str = Field(
        description="ISO 8601 UTC timestamp by which the deletion will be complete (72 h from now).",
    )
