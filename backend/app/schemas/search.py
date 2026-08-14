# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : search.py
# Purpose : Pydantic schemas for the /search/semantic endpoint
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Dependencies:
#   - pydantic
# ============================================================

"""Pydantic v2 schemas for the semantic search endpoint.

Covers the request body and response shape for POST /search/semantic.

Requirements: 36.2, 36.3, 36.5
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class SemanticSearchRequest(BaseModel):
    """Request body for POST /search/semantic.

    Requirements: 36.2
    """

    query: str = Field(
        min_length=1,
        max_length=2000,
        description="Natural language search query.",
    )


class SemanticSearchResultItem(BaseModel):
    """A single semantic search result item returned in the response.

    Requirements: 36.2, 36.3, 36.5
    """

    source_type: str = Field(
        description="Content type: 'conversation' | 'note' | 'document' | 'memory'."
    )
    source_name: str = Field(
        description="Human-readable name of the source item (e.g. conversation title, note title)."
    )
    excerpt: str = Field(
        description="Matching excerpt from the content, truncated to 300 characters.",
        max_length=300,
    )
    relevance_score: float = Field(
        description="Cosine similarity score rounded to 2 decimal places (0.0–1.0).",
        ge=0.0,
        le=1.0,
    )
    deep_link: str = Field(
        description="Deep-link URI to navigate directly to the source item (e.g. aiassistant://notes/{id})."
    )


class SemanticSearchResponse(BaseModel):
    """Response body for POST /search/semantic.

    Only includes results with relevance_score ≥ 0.5.
    Content-type groups with no results above threshold are omitted entirely.

    Requirements: 36.2, 36.3, 36.5
    """

    results: list[SemanticSearchResultItem] = Field(
        description="List of search results sorted by relevance score descending."
    )
    total: int = Field(
        description="Total number of results returned (all above the 0.5 threshold)."
    )
