# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : personas.py
# Purpose : Pydantic v2 schemas for persona endpoints
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

"""Pydantic v2 schemas for persona CRUD endpoints.

Covers create, update, and response shapes for the ``/api/v1/personas`` routes.

Field validation enforces the size constraints specified in Requirement 32.1:
- ``name``: 1–80 characters
- ``system_prompt``: 1–4,000 characters
- ``tone``: one of professional | casual | concise | detailed | creative
- ``scope_description``: 0–500 characters

Requirements: 32.1, 32.3, 32.5, 32.6, 32.8
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.models.persona import PersonaTone

# ---------------------------------------------------------------------------
# Create schema
# ---------------------------------------------------------------------------


class PersonaCreate(BaseModel):
    """Request body for POST /api/v1/personas.

    Requirements: 32.1, 32.8
    """

    name: str = Field(
        ...,
        min_length=1,
        max_length=80,
        description="Unique display name for the persona (1–80 characters).",
    )
    system_prompt: str = Field(
        ...,
        min_length=1,
        max_length=4000,
        description="Custom system prompt injected into AI context (1–4,000 characters).",
    )
    tone: PersonaTone = Field(
        default=PersonaTone.professional,
        description=(
            "Tone/style of the AI response. "
            "One of: professional, casual, concise, detailed, creative."
        ),
    )
    scope_description: str = Field(
        default="",
        max_length=500,
        description="Optional description of when to use this persona (0–500 characters).",
    )
    allowed_roles: list[str] = Field(
        default_factory=list,
        description=(
            "RBAC roles permitted to view this persona when admin_locked=True. "
            "Empty list means visible to all permitted users."
        ),
    )


# ---------------------------------------------------------------------------
# Update schema
# ---------------------------------------------------------------------------


class PersonaUpdate(BaseModel):
    """Request body for PUT /api/v1/personas/{id}.

    All fields are optional; only provided fields are applied.

    Requirements: 32.1, 32.5, 32.8
    """

    name: str | None = Field(
        default=None,
        min_length=1,
        max_length=80,
        description="New display name for the persona (1–80 characters).",
    )
    system_prompt: str | None = Field(
        default=None,
        min_length=1,
        max_length=4000,
        description="New system prompt (1–4,000 characters).",
    )
    tone: PersonaTone | None = Field(
        default=None,
        description="New tone/style of the AI response.",
    )
    scope_description: str | None = Field(
        default=None,
        max_length=500,
        description="New scope description (0–500 characters).",
    )
    allowed_roles: list[str] | None = Field(
        default=None,
        description="New list of RBAC roles permitted to view this persona.",
    )


# ---------------------------------------------------------------------------
# Response schema
# ---------------------------------------------------------------------------


class PersonaResponse(BaseModel):
    """Response schema for a single persona.

    Requirements: 32.1
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    name: str
    system_prompt: str
    tone: PersonaTone
    scope_description: str
    admin_locked: bool
    allowed_roles: list[str]
    created_at: datetime
    updated_at: datetime


# ---------------------------------------------------------------------------
# List response schema
# ---------------------------------------------------------------------------


class PersonaListResponse(BaseModel):
    """Response schema for GET /api/v1/personas.

    Requirements: 32.6
    """

    items: list[PersonaResponse]
    total: int = Field(description="Total number of accessible personas returned.")
