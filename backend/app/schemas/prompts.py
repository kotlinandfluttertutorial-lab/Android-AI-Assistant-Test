# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : prompts.py
# Purpose : prompts — schemas module
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

"""Pydantic v2 request/response schemas for the /prompts/* endpoints.

These schemas cover the versioned prompt template management API used by
admin users to create, inspect, and roll back prompt template versions.

Requirements: 25.1, 25.2, 9.7
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Response schemas
# ---------------------------------------------------------------------------


class PromptTemplateResponse(BaseModel):
    """Single versioned prompt template row.

    Returned by GET /prompts/{name}, PATCH /prompts/{name}, and
    POST /prompts/{name}/rollback.

    Requirements: 25.1
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID = Field(description="Unique row identifier.")
    name: str = Field(description="Logical template name shared across all versions.")
    content: str = Field(description="Jinja2-compatible template text.")
    version: int = Field(
        description="Monotonically increasing version number within the named template."
    )
    author_id: uuid.UUID = Field(
        description="UUID of the admin who authored this version."
    )
    is_active: bool = Field(
        description="Whether this version is the currently active one."
    )
    created_at: datetime = Field(
        description="UTC timestamp when this version was created."
    )
    updated_at: datetime = Field(
        description="UTC timestamp of the last ORM-level update."
    )


class PromptTemplateListResponse(BaseModel):
    """Paginated list of distinct template names.

    Returned by GET /prompts.

    Requirements: 25.1
    """

    names: list[str] = Field(
        description="Alphabetically sorted list of distinct template names."
    )
    total: int = Field(description="Total number of distinct template names.")


class PromptVersionHistoryResponse(BaseModel):
    """All versions for a single named template.

    Returned by GET /prompts/{name}/history.

    Requirements: 25.1
    """

    name: str = Field(description="Logical template name.")
    versions: list[PromptTemplateResponse] = Field(
        description="All versions ordered by version number ascending."
    )
    total: int = Field(description="Total number of versions.")


# ---------------------------------------------------------------------------
# Request schemas
# ---------------------------------------------------------------------------


class PromptTemplateUpdateRequest(BaseModel):
    """Request body for PATCH /prompts/{name}.

    Creates a new version of the named template with the provided content.

    Requirements: 25.1, 9.7
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    content: str = Field(
        min_length=1,
        max_length=50_000,
        description="New Jinja2-compatible template text. Must be non-empty.",
        examples=["You are a helpful assistant. Context: {{ context }}"],
    )

    @field_validator("content")
    @classmethod
    def sanitize_content(cls, v: str) -> str:
        # Prompt templates are admin-only and may legitimately contain HTML-like
        # syntax for Jinja2 rendering. We apply only SQL injection detection here
        # to prevent database attacks while allowing template markup.
        from app.security.input_sanitizer import detect_sql_injection

        if detect_sql_injection(v):
            raise ValueError(
                "Input rejected: potential SQL injection pattern detected in template content."
            )
        return v


class PromptRollbackResponse(BaseModel):
    """Response body for POST /prompts/{name}/rollback.

    Returns the newly created version row (which is a copy of the rolled-back
    content) plus a human-readable message.

    Requirements: 25.2
    """

    message: str = Field(description="Human-readable confirmation of the rollback.")
    template: PromptTemplateResponse = Field(
        description="The newly created version that restores the requested content."
    )
