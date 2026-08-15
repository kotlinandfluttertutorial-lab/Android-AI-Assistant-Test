# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : productivity.py
# Purpose : productivity — schemas module
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

"""Pydantic v2 schemas for productivity endpoints.

Covers Todos, Calendar Events, Reminders, and Habits sub-domains.
All response schemas use ``ConfigDict(from_attributes=True)`` for ORM
compatibility.

Requirements: 13.1, 9.1, 9.2, 9.7
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.security.input_sanitizer import sanitize_user_string

# Field length limits
_MAX_TITLE_LEN = 500
_MAX_DESC_LEN = 2_000
_MAX_LOCATION_LEN = 500
_MAX_RECURRENCE_LEN = 500
_MAX_PROMPT_LEN = 2_000
_MAX_TAG_LEN = 100
_MAX_TAGS_COUNT = 50
_MAX_NOTE_LEN = 5_000


# ---------------------------------------------------------------------------
# Todo schemas
# ---------------------------------------------------------------------------


class TodoCreate(BaseModel):
    """Request body for creating a new to-do item.

    Requirements: 9.7
    """

    title: str = Field(
        description="Short description of the to-do item.",
        max_length=_MAX_TITLE_LEN,
    )
    description: str = Field(
        default="",
        max_length=_MAX_DESC_LEN,
        description="Optional longer description.",
    )
    due_date: datetime | None = Field(default=None, description="Optional deadline (ISO 8601).")
    priority: str = Field(default="medium", description="Priority level: low | medium | high.")
    tags: list[str] = Field(default_factory=list, description="Optional tag labels.")

    @field_validator("title", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str) -> str:
        return sanitize_user_string(cls, v)

    @field_validator("priority")
    @classmethod
    def validate_priority(cls, v: str) -> str:
        allowed = {"low", "medium", "high"}
        if v not in allowed:
            raise ValueError(f"priority must be one of {allowed}")
        return v

    @field_validator("tags")
    @classmethod
    def sanitize_tags(cls, v: list[str]) -> list[str]:
        if len(v) > _MAX_TAGS_COUNT:
            raise ValueError(f"too many tags (maximum {_MAX_TAGS_COUNT})")
        return [sanitize_user_string(cls, tag[:_MAX_TAG_LEN]) for tag in v]


class TodoUpdate(BaseModel):
    """Request body for PATCH /todos/{id}. All fields are optional.

    Requirements: 9.7
    """

    title: str | None = Field(default=None, max_length=_MAX_TITLE_LEN)
    description: str | None = Field(default=None, max_length=_MAX_DESC_LEN)
    due_date: datetime | None = None
    priority: str | None = None
    is_completed: bool | None = None
    tags: list[str] | None = None

    @field_validator("title", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)

    @field_validator("priority")
    @classmethod
    def validate_priority(cls, v: str | None) -> str | None:
        if v is None:
            return v
        allowed = {"low", "medium", "high"}
        if v not in allowed:
            raise ValueError(f"priority must be one of {allowed}")
        return v

    @field_validator("tags")
    @classmethod
    def sanitize_tags(cls, v: list[str] | None) -> list[str] | None:
        if v is None:
            return v
        if len(v) > _MAX_TAGS_COUNT:
            raise ValueError(f"too many tags (maximum {_MAX_TAGS_COUNT})")
        return [sanitize_user_string(cls, tag[:_MAX_TAG_LEN]) for tag in v]


class TodoResponse(BaseModel):
    """Response schema for a single to-do item."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    description: str
    is_completed: bool
    due_date: datetime | None
    priority: str
    tags: list[str]
    created_at: datetime
    updated_at: datetime


class TodoListResponse(BaseModel):
    """Response schema for paginated to-do item lists."""

    items: list[TodoResponse]
    total: int = Field(description="Total number of to-do items for the user.")
    page: int = Field(description="Current 1-indexed page number.")
    page_size: int = Field(description="Number of items per page.")


class TodoGenerateRequest(BaseModel):
    """Request body for AI-generated to-do items.

    Requirements: 9.7
    """

    prompt: str = Field(
        min_length=1,
        max_length=_MAX_PROMPT_LEN,
        description="Natural language description of what to accomplish.",
    )

    @field_validator("prompt")
    @classmethod
    def sanitize_prompt(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class TodoGenerateResponse(BaseModel):
    """Response schema for AI-generated to-do items."""

    todos: list[TodoResponse]
    prompt: str = Field(description="The original prompt used to generate the todos.")


# ---------------------------------------------------------------------------
# Calendar Event schemas
# ---------------------------------------------------------------------------


class CalendarEventCreate(BaseModel):
    """Request body for creating a new calendar event.

    Requirements: 9.7
    """

    title: str = Field(
        description="Event title.",
        max_length=_MAX_TITLE_LEN,
    )
    description: str = Field(
        default="",
        max_length=_MAX_DESC_LEN,
        description="Optional event description.",
    )
    start_time: datetime = Field(description="Event start time (ISO 8601).")
    end_time: datetime = Field(description="Event end time (ISO 8601).")
    location: str | None = Field(
        default=None,
        max_length=_MAX_LOCATION_LEN,
        description="Optional location.",
    )
    is_all_day: bool = Field(default=False, description="True for all-day events.")
    source: str = Field(default="local", description="Event source: local | google_calendar.")

    @field_validator("title", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str) -> str:
        return sanitize_user_string(cls, v)

    @field_validator("location")
    @classmethod
    def sanitize_location(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)

    @field_validator("source")
    @classmethod
    def validate_source(cls, v: str) -> str:
        allowed = {"local", "google_calendar"}
        if v not in allowed:
            raise ValueError(f"source must be one of {allowed}")
        return v


class CalendarEventUpdate(BaseModel):
    """Request body for PATCH /calendar/events/{id}. All fields are optional.

    Requirements: 9.7
    """

    title: str | None = Field(default=None, max_length=_MAX_TITLE_LEN)
    description: str | None = Field(default=None, max_length=_MAX_DESC_LEN)
    start_time: datetime | None = None
    end_time: datetime | None = None
    location: str | None = Field(default=None, max_length=_MAX_LOCATION_LEN)
    is_all_day: bool | None = None

    @field_validator("title", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)

    @field_validator("location")
    @classmethod
    def sanitize_location(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)


class CalendarEventResponse(BaseModel):
    """Response schema for a single calendar event."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    description: str
    start_time: datetime
    end_time: datetime
    location: str | None
    is_all_day: bool
    source: str
    created_at: datetime
    updated_at: datetime


class CalendarEventListResponse(BaseModel):
    """Response schema for calendar event lists."""

    items: list[CalendarEventResponse]
    total: int = Field(description="Total number of events returned.")


class SuggestTimesRequest(BaseModel):
    """Request body for AI-powered meeting time suggestions.

    Requirements: 9.7
    """

    prompt: str = Field(
        max_length=_MAX_PROMPT_LEN,
        description="Natural language description of the meeting requirements.",
    )
    duration_minutes: int = Field(
        default=60,
        ge=1,
        le=1440,
        description="Duration of the meeting in minutes.",
    )

    @field_validator("prompt")
    @classmethod
    def sanitize_prompt(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class SuggestTimesResponse(BaseModel):
    """Response schema for suggested meeting times."""

    suggestions: list[str] = Field(description="List of suggested ISO 8601 datetime strings.")
    prompt: str = Field(description="The original prompt used to generate suggestions.")


# ---------------------------------------------------------------------------
# Reminder schemas
# ---------------------------------------------------------------------------


class ReminderCreate(BaseModel):
    """Request body for creating a new reminder.

    Requirements: 9.7
    """

    title: str = Field(
        description="Short description of the reminder.",
        max_length=_MAX_TITLE_LEN,
    )
    trigger_time: datetime = Field(description="When the reminder should fire (ISO 8601).")
    recurrence_rule: str | None = Field(
        default=None,
        max_length=_MAX_RECURRENCE_LEN,
        description="Optional iCal RRULE string.",
    )
    linked_todo_id: uuid.UUID | None = Field(
        default=None, description="Optional linked to-do item."
    )

    @field_validator("title")
    @classmethod
    def sanitize_title(cls, v: str) -> str:
        return sanitize_user_string(cls, v)

    @field_validator("recurrence_rule")
    @classmethod
    def sanitize_recurrence_rule(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)


class ReminderUpdate(BaseModel):
    """Request body for PATCH /reminders/{id}. All fields are optional.

    Requirements: 9.7
    """

    title: str | None = Field(default=None, max_length=_MAX_TITLE_LEN)
    trigger_time: datetime | None = None
    recurrence_rule: str | None = Field(default=None, max_length=_MAX_RECURRENCE_LEN)
    linked_todo_id: uuid.UUID | None = None
    is_completed: bool | None = None

    @field_validator("title")
    @classmethod
    def sanitize_title(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)

    @field_validator("recurrence_rule")
    @classmethod
    def sanitize_recurrence_rule(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)


class ReminderResponse(BaseModel):
    """Response schema for a single reminder."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    title: str
    trigger_time: datetime
    recurrence_rule: str | None
    linked_todo_id: uuid.UUID | None
    is_completed: bool
    created_at: datetime
    updated_at: datetime


class ReminderListResponse(BaseModel):
    """Response schema for reminder lists (sorted by trigger_time ASC)."""

    items: list[ReminderResponse]
    total: int = Field(description="Total number of reminders for the user.")


class SuggestReminderRequest(BaseModel):
    """Request body for AI-suggested reminders.

    Requirements: 9.7
    """

    prompt: str = Field(
        min_length=1,
        max_length=_MAX_PROMPT_LEN,
        description="Natural language description of what to be reminded about.",
    )

    @field_validator("prompt")
    @classmethod
    def sanitize_prompt(cls, v: str) -> str:
        return sanitize_user_string(cls, v)


class SuggestReminderResponse(BaseModel):
    """Response schema for an AI-suggested reminder."""

    suggestion: ReminderCreate
    rationale: str = Field(description="AI-generated explanation for the suggestion.")


# ---------------------------------------------------------------------------
# Habit schemas
# ---------------------------------------------------------------------------


class HabitCreate(BaseModel):
    """Request body for creating a new habit.

    Requirements: 9.7
    """

    name: str = Field(
        description="Name of the habit.",
        max_length=_MAX_TITLE_LEN,
    )
    description: str = Field(
        default="",
        max_length=_MAX_DESC_LEN,
        description="Optional description or motivation.",
    )
    recurrence: str = Field(default="daily", description="Recurrence schedule: daily | weekly.")
    target_frequency: int = Field(
        default=1,
        ge=1,
        le=365,
        description="Times to complete per recurrence period.",
    )

    @field_validator("name", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str) -> str:
        return sanitize_user_string(cls, v)

    @field_validator("recurrence")
    @classmethod
    def validate_recurrence(cls, v: str) -> str:
        allowed = {"daily", "weekly"}
        if v not in allowed:
            raise ValueError(f"recurrence must be one of {allowed}")
        return v


class HabitUpdate(BaseModel):
    """Request body for PATCH /habits/{id}. All fields are optional.

    Requirements: 9.7
    """

    name: str | None = Field(default=None, max_length=_MAX_TITLE_LEN)
    description: str | None = Field(default=None, max_length=_MAX_DESC_LEN)
    recurrence: str | None = None
    target_frequency: int | None = Field(default=None, ge=1, le=365)

    @field_validator("name", "description")
    @classmethod
    def sanitize_text_fields(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)

    @field_validator("recurrence")
    @classmethod
    def validate_recurrence(cls, v: str | None) -> str | None:
        if v is None:
            return v
        allowed = {"daily", "weekly"}
        if v not in allowed:
            raise ValueError(f"recurrence must be one of {allowed}")
        return v


class HabitResponse(BaseModel):
    """Response schema for a single habit."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    user_id: uuid.UUID
    name: str
    description: str
    recurrence: str
    target_frequency: int
    created_at: datetime
    updated_at: datetime


class HabitListResponse(BaseModel):
    """Response schema for habit lists."""

    items: list[HabitResponse]
    total: int = Field(description="Total number of habits for the user.")


class HabitEntryCreate(BaseModel):
    """Request body for logging a habit completion entry.

    Requirements: 9.7
    """

    completed_at: datetime = Field(description="When the habit was completed (ISO 8601).")
    note: str | None = Field(
        default=None,
        max_length=_MAX_NOTE_LEN,
        description="Optional note or reflection.",
    )

    @field_validator("note")
    @classmethod
    def sanitize_note(cls, v: str | None) -> str | None:
        if v is None:
            return v
        return sanitize_user_string(cls, v)


class HabitEntryResponse(BaseModel):
    """Response schema for a single habit entry."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    habit_id: uuid.UUID
    user_id: uuid.UUID
    completed_at: datetime
    note: str | None
    created_at: datetime


class HabitInsightsResponse(BaseModel):
    """Response schema for AI-generated habit insights."""

    habit_id: uuid.UUID
    insights: str = Field(description="AI-generated insights text about habit performance.")
    generated_at: datetime = Field(description="Timestamp when insights were generated.")
