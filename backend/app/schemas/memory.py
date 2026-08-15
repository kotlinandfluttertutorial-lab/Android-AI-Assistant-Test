# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : memory.py
# Purpose : Pydantic v2 request/response schemas for memory endpoints
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - Pydantic v2
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 schemas for the memory API endpoints.

Covers:
- POST /memory        → MemoryCreate / MemoryResponse
- GET  /memory        → MemorySearchRequest / MemorySearchResponse
- DELETE /memory/{id} → (no body; returns HTTP 204)

Requirements: 7.1, 7.2, 7.3, 7.4
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

# ---------------------------------------------------------------------------
# POST /memory — store a new memory
# ---------------------------------------------------------------------------


class MemoryCreate(BaseModel):
    """Request body for POST /memory.

    Requirements: 7.1
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    content: str = Field(
        min_length=1,
        max_length=4096,
        description="The memory text to store (fact, preference, or writing style observation).",
        examples=["I prefer concise, bullet-point answers."],
    )
    memory_type: str = Field(
        default="fact",
        description="Classification of the memory. One of: preference, fact, style.",
        examples=["preference"],
        pattern="^(preference|fact|style)$",
    )


class MemoryResponse(BaseModel):
    """Response body for a stored or retrieved memory.

    Requirements: 7.1, 7.3
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID = Field(description="UUID of the memory row in PostgreSQL.")
    content: str = Field(description="The stored memory text.")
    memory_type: str = Field(description="Classification of the memory.")
    created_at: datetime = Field(description="UTC timestamp when the memory was created.")


# ---------------------------------------------------------------------------
# GET /memory — semantic search
# ---------------------------------------------------------------------------


class MemorySearchRequest(BaseModel):
    """Query parameters for GET /memory semantic search.

    Requirements: 7.2
    """

    model_config = ConfigDict(str_strip_whitespace=True)

    query: str = Field(
        min_length=1,
        max_length=2048,
        description="The query string for semantic similarity search.",
        examples=["What are my coding preferences?"],
    )
    top_k: int = Field(
        default=3,
        ge=1,
        le=10,
        description="Maximum number of memories to return (default: 3).",
    )


class MemorySearchResult(BaseModel):
    """A single memory in a search result set.

    Requirements: 7.2
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID = Field(description="UUID of the memory row in PostgreSQL.")
    content: str = Field(description="The memory text.")
    memory_type: str = Field(description="Classification of the memory.")
    relevance_score: float = Field(
        description=(
            "Semantic similarity distance from ChromaDB. Lower values indicate higher relevance."
        )
    )
    created_at: datetime = Field(description="UTC timestamp when the memory was created.")


class MemorySearchResponse(BaseModel):
    """Response body for GET /memory.

    Requirements: 7.2
    """

    model_config = ConfigDict(from_attributes=True)

    query: str = Field(description="The query string that was searched.")
    results: list[MemorySearchResult] = Field(
        description="Top-K most relevant memories, ordered by relevance.",
    )
    total: int = Field(description="Number of memories returned.")


# ---------------------------------------------------------------------------
# Privacy mode
# ---------------------------------------------------------------------------


class PrivacyModeUpdate(BaseModel):
    """Request body for PATCH /users/me/privacy-mode.

    Requirements: 7.6
    """

    model_config = ConfigDict()

    privacy_mode: bool = Field(
        description=(
            "When True, memory capture is disabled. "
            "When False, memory capture is re-enabled. "
            "Existing memories are never deleted by this toggle."
        ),
        examples=[True],
    )


class PrivacyModeResponse(BaseModel):
    """Response body for PATCH /users/me/privacy-mode.

    Requirements: 7.6
    """

    model_config = ConfigDict(from_attributes=True)

    privacy_mode: bool = Field(description="The updated privacy mode state.")
    message: str = Field(description="Human-readable confirmation message.")
