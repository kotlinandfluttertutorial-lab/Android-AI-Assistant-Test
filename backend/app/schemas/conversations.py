# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : conversations.py
# Purpose : conversations — schemas module
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

"""Pydantic v2 schemas for conversation and message endpoints.

Covers CRUD operations for conversations, paginated message listing,
conversation export, and message regeneration.

Requirements: 11.3, 11.4, 11.6, 2.6, 2.7, 9.7
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.security.input_sanitizer import sanitize_user_string

# Maximum lengths for conversation / message fields
_MAX_TITLE_LEN = 500
_MAX_PROVIDER_LEN = 100


# ---------------------------------------------------------------------------
# Conversation schemas
# ---------------------------------------------------------------------------


class ConversationCreate(BaseModel):
    """Request body for POST /conversations.

    Requirements: 9.7
    """

    title: str = Field(
        default="New Conversation",
        max_length=_MAX_TITLE_LEN,
        description="Human-readable conversation title.",
    )
    provider: str = Field(
        default="",
        max_length=_MAX_PROVIDER_LEN,
        description="LLM provider identifier, e.g. 'openai'.",
    )

    @field_validator("title", "provider")
    @classmethod
    def sanitize_strings(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class ConversationUpdate(BaseModel):
    """Request body for PATCH /conversations/{id}.

    All fields are optional; only provided fields are updated.

    Requirements: 9.7
    """

    title: str | None = Field(
        default=None,
        max_length=_MAX_TITLE_LEN,
        description="New conversation title.",
    )
    is_pinned: bool | None = Field(default=None, description="Pin or unpin the conversation.")

    @field_validator("title")
    @classmethod
    def sanitize_title(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)


class ConversationResponse(BaseModel):
    """Response schema for a single conversation."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    provider: str
    is_pinned: bool
    is_deleted: bool
    created_at: datetime
    updated_at: datetime


class ConversationListResponse(BaseModel):
    """Response schema for paginated conversation lists."""

    items: list[ConversationResponse]
    total: int = Field(description="Total number of non-deleted conversations for the user.")
    page: int = Field(description="Current 1-indexed page number.")
    page_size: int = Field(description="Number of items per page.")


# ---------------------------------------------------------------------------
# Message schemas
# ---------------------------------------------------------------------------


class MessageResponse(BaseModel):
    """Response schema for a single message."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    conversation_id: uuid.UUID
    role: str = Field(description="Message role: user | assistant | system | tool.")
    content: str
    input_tokens: int
    output_tokens: int
    provider: str
    created_at: datetime


class MessageListResponse(BaseModel):
    """Response schema for paginated message lists."""

    items: list[MessageResponse]
    total: int = Field(description="Total number of messages in the conversation.")
    page: int = Field(description="Current 1-indexed page number.")
    page_size: int = Field(description="Number of items per page.")


# ---------------------------------------------------------------------------
# Export schema
# ---------------------------------------------------------------------------


class ExportRequest(BaseModel):
    """Query parameters for POST /conversations/{id}/export."""

    format: Literal["markdown", "pdf"] = Field(description="Export format: 'markdown' or 'pdf'.")


# ---------------------------------------------------------------------------
# Regenerate schema
# ---------------------------------------------------------------------------


class RegenerateResponse(BaseModel):
    """Response schema for POST /conversations/{id}/messages/{message_id}/regenerate."""

    message: MessageResponse
    note: str = Field(description="Informational note about the regeneration status.")
