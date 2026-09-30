# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : suggestions.py
# Purpose : Pydantic v2 schemas for context-aware suggestion endpoints
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

"""Pydantic v2 schemas for the /api/v1/suggestions endpoints.

Covers the request body (ScreenContextPayload) and response shapes
(ContextSuggestionResponse, SuggestionsResponse) for the POST
/api/v1/suggestions/context route.

Requirements: 33.1, 33.2, 33.6, 33.7
"""

from __future__ import annotations

import enum
from datetime import datetime

from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Enums
# ---------------------------------------------------------------------------


class ScreenType(enum.StrEnum):
    """Screen context types that can request suggestions.

    Requirements: 33.1
    """

    notes = "notes"
    calendar = "calendar"
    chat = "chat"


class SuggestionType(enum.StrEnum):
    """AI suggestion action types.

    Requirements: 33.2
    """

    summarize = "summarize"
    expand = "expand"
    add_action_items = "add_action_items"
    draft_agenda = "draft_agenda"
    prep_questions = "prep_questions"
    lookup_attendees = "lookup_attendees"
    continue_conversation = "continue_conversation"


# ---------------------------------------------------------------------------
# Request schema
# ---------------------------------------------------------------------------


class ScreenContextPayload(BaseModel):
    """Request body for POST /api/v1/suggestions/context.

    Clients send their current screen context so the backend can generate
    relevant AI suggestions. Fields are screen-type-specific and optional
    except for screen_type.

    Requirements: 33.1, 33.2
    """

    screen_type: ScreenType = Field(
        ...,
        description="The current screen type (notes | calendar | chat).",
    )

    # notes context
    note_content: str | None = Field(
        default=None,
        max_length=500,
        description="Content of the note being edited (first 500 chars). Used for notes screen.",
    )
    note_length: int | None = Field(
        default=None,
        description="Total length of the note in characters. Used for notes screen.",
    )

    # calendar context
    event_title: str | None = Field(
        default=None,
        description="Title of the calendar event. Used for calendar screen.",
    )
    event_datetime: datetime | None = Field(
        default=None,
        description="Date and time of the calendar event. Used for calendar screen.",
    )
    attendees: list[str] | None = Field(
        default=None,
        description="List of attendee names or emails. Used for calendar screen.",
    )

    # chat context
    last_message_content: str | None = Field(
        default=None,
        description="Content of the last message in the conversation. Used for chat screen.",
    )
    last_message_age_seconds: int | None = Field(
        default=None,
        description="Seconds elapsed since the last message was sent. Used for chat screen.",
    )
    conversation_title: str | None = Field(
        default=None,
        description="Title of the conversation. Used for chat screen.",
    )

    # LLM provider selection
    provider: str = Field(
        default="openai",
        description="LLM provider to use for generating suggestions (default: openai).",
    )


# ---------------------------------------------------------------------------
# Response schemas
# ---------------------------------------------------------------------------


class ContextSuggestionResponse(BaseModel):
    """A single context-aware AI suggestion chip.

    Requirements: 33.2
    """

    id: str = Field(description="UUID string identifying this suggestion.")
    type: SuggestionType = Field(description="The type of AI action this suggestion represents.")
    display_text: str = Field(description="Human-readable chip label shown to the user.")
    pre_fill_text: str = Field(
        description="Text pre-filled in the input field when the suggestion is tapped."
    )
    target_screen_type: str = Field(description="The screen type this suggestion belongs to.")


class SuggestionsResponse(BaseModel):
    """Response wrapper for POST /api/v1/suggestions/context.

    Returns 1–3 suggestions, or an empty list when privacy mode is enabled
    or when the AI call fails/times out.

    Requirements: 33.6, 33.7
    """

    suggestions: list[ContextSuggestionResponse] = Field(
        default_factory=list,
        description="List of context-aware suggestions (0–3 items).",
    )
