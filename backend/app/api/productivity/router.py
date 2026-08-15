# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/productivity
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the productivity domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Productivity router — /todos/*, /calendar/*, /reminders/*, /habits/* endpoints.

Provides REST endpoints for the complete productivity suite covering four
sub-domains: Todos, Calendar Events, Reminders, and Habits.

All endpoints are protected at router level via ``get_current_user`` and
strictly enforce user-scoping to prevent cross-user data access.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.productivity import (
    CalendarEventCreate,
    CalendarEventListResponse,
    CalendarEventResponse,
    CalendarEventUpdate,
    HabitCreate,
    HabitEntryCreate,
    HabitEntryResponse,
    HabitInsightsResponse,
    HabitListResponse,
    HabitResponse,
    HabitUpdate,
    ReminderCreate,
    ReminderListResponse,
    ReminderResponse,
    ReminderUpdate,
    SuggestReminderRequest,
    SuggestReminderResponse,
    SuggestTimesRequest,
    SuggestTimesResponse,
    TodoCreate,
    TodoGenerateRequest,
    TodoGenerateResponse,
    TodoListResponse,
    TodoResponse,
    TodoUpdate,
)
from app.security.dependencies import TokenPayload, get_current_user
from app.services.ai_orchestrator import LLMProvider
from app.services.productivity_service import ProductivityService

router = APIRouter(
    prefix="/productivity",
    tags=["productivity"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _current_user_id(current_user: TokenPayload) -> uuid.UUID:
    """Convert the string ``sub`` claim to a UUID."""
    return uuid.UUID(current_user.sub)


# ---------------------------------------------------------------------------
# Todo endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/todos",
    response_model=TodoListResponse,
    summary="List to-do items (paginated)",
)
async def list_todos(
    page: int = Query(default=1, ge=1, description="1-indexed page number."),
    page_size: int = Query(default=20, ge=1, le=100, description="Items per page."),
    is_completed: bool | None = Query(
        default=None, description="Filter by completion status."
    ),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoListResponse:
    """Return a paginated list of to-do items for the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    items, total = await service.list_todos(user_id, page, page_size, is_completed)
    return TodoListResponse(
        items=[TodoResponse.model_validate(item) for item in items],
        total=total,
        page=page,
        page_size=page_size,
    )


@router.post(
    "/todos",
    response_model=TodoResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a to-do item",
)
async def create_todo(
    body: TodoCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoResponse:
    """Create a new to-do item owned by the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    item = await service.create_todo(user_id, body)
    return TodoResponse.model_validate(item)


@router.get(
    "/todos/{todo_id}",
    response_model=TodoResponse,
    summary="Get a to-do item by ID",
)
async def get_todo(
    todo_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoResponse:
    """Return a single to-do item. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    item = await service.get_todo(todo_id, user_id)
    return TodoResponse.model_validate(item)


@router.patch(
    "/todos/{todo_id}",
    response_model=TodoResponse,
    summary="Update a to-do item",
)
async def update_todo(
    todo_id: uuid.UUID,
    body: TodoUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoResponse:
    """Update a to-do item. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    item = await service.update_todo(todo_id, user_id, body)
    return TodoResponse.model_validate(item)


@router.delete(
    "/todos/{todo_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a to-do item",
)
async def delete_todo(
    todo_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a to-do item. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    await service.delete_todo(todo_id, user_id)


@router.post(
    "/todos/generate",
    response_model=TodoGenerateResponse,
    summary="Generate to-do items from natural language",
)
async def generate_todos(
    body: TodoGenerateRequest,
    provider: str = Query(default="openai", description="LLM provider identifier."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> TodoGenerateResponse:
    """Generate to-do items from a natural language prompt using AI."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    llm_provider = LLMProvider(provider)
    items = await service.generate_todos_from_prompt(user_id, body.prompt, llm_provider)
    return TodoGenerateResponse(
        todos=[TodoResponse.model_validate(item) for item in items],
        prompt=body.prompt,
    )


# ---------------------------------------------------------------------------
# Calendar Event endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/calendar/events",
    response_model=CalendarEventListResponse,
    summary="List calendar events",
)
async def list_calendar_events(
    start_date: datetime | None = Query(
        default=None, description="Lower bound for start_time (ISO 8601)."
    ),
    end_date: datetime | None = Query(
        default=None, description="Upper bound for start_time (ISO 8601)."
    ),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> CalendarEventListResponse:
    """Return calendar events for the authenticated user, optionally filtered by date range."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    events = await service.list_calendar_events(user_id, start_date, end_date)
    return CalendarEventListResponse(
        items=[CalendarEventResponse.model_validate(event) for event in events],
        total=len(events),
    )


@router.post(
    "/calendar/events",
    response_model=CalendarEventResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a calendar event",
)
async def create_calendar_event(
    body: CalendarEventCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> CalendarEventResponse:
    """Create a new calendar event owned by the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    event = await service.create_calendar_event(user_id, body)
    return CalendarEventResponse.model_validate(event)


@router.patch(
    "/calendar/events/{event_id}",
    response_model=CalendarEventResponse,
    summary="Update a calendar event",
)
async def update_calendar_event(
    event_id: uuid.UUID,
    body: CalendarEventUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> CalendarEventResponse:
    """Update a calendar event. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    event = await service.update_calendar_event(event_id, user_id, body)
    return CalendarEventResponse.model_validate(event)


@router.delete(
    "/calendar/events/{event_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a calendar event",
)
async def delete_calendar_event(
    event_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a calendar event. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    await service.delete_calendar_event(event_id, user_id)


@router.post(
    "/calendar/suggest-times",
    response_model=SuggestTimesResponse,
    summary="Suggest meeting times via AI",
)
async def suggest_meeting_times(
    body: SuggestTimesRequest,
    provider: str = Query(default="openai", description="LLM provider identifier."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SuggestTimesResponse:
    """Generate AI-powered meeting time suggestions."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    llm_provider = LLMProvider(provider)
    suggestions = await service.suggest_meeting_times(
        user_id, body.prompt, body.duration_minutes, llm_provider
    )
    return SuggestTimesResponse(suggestions=suggestions, prompt=body.prompt)


# ---------------------------------------------------------------------------
# Reminder endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/reminders",
    response_model=ReminderListResponse,
    summary="List reminders (sorted by trigger_time ASC)",
)
async def list_reminders(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ReminderListResponse:
    """Return all reminders for the authenticated user, sorted by trigger_time ASC."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    reminders = await service.list_reminders(user_id)
    return ReminderListResponse(
        items=[ReminderResponse.model_validate(rem) for rem in reminders],
        total=len(reminders),
    )


@router.post(
    "/reminders",
    response_model=ReminderResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a reminder",
)
async def create_reminder(
    body: ReminderCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ReminderResponse:
    """Create a new reminder owned by the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    reminder = await service.create_reminder(user_id, body)
    return ReminderResponse.model_validate(reminder)


@router.patch(
    "/reminders/{reminder_id}",
    response_model=ReminderResponse,
    summary="Update a reminder",
)
async def update_reminder(
    reminder_id: uuid.UUID,
    body: ReminderUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ReminderResponse:
    """Update a reminder. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    reminder = await service.update_reminder(reminder_id, user_id, body)
    return ReminderResponse.model_validate(reminder)


@router.delete(
    "/reminders/{reminder_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a reminder",
)
async def delete_reminder(
    reminder_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a reminder. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    await service.delete_reminder(reminder_id, user_id)


@router.post(
    "/reminders/suggest",
    response_model=SuggestReminderResponse,
    summary="Suggest a reminder via AI",
)
async def suggest_reminder(
    body: SuggestReminderRequest,
    provider: str = Query(default="openai", description="LLM provider identifier."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SuggestReminderResponse:
    """Generate an AI-powered reminder suggestion from natural language."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    llm_provider = LLMProvider(provider)
    result = await service.suggest_reminder(user_id, body.prompt, llm_provider)
    return SuggestReminderResponse(
        suggestion=result["suggestion"],
        rationale=result["rationale"],
    )


# ---------------------------------------------------------------------------
# Habit endpoints
# ---------------------------------------------------------------------------


@router.get(
    "/habits",
    response_model=HabitListResponse,
    summary="List habit definitions",
)
async def list_habits(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HabitListResponse:
    """Return all habit definitions for the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    habits = await service.list_habits(user_id)
    return HabitListResponse(
        items=[HabitResponse.model_validate(habit) for habit in habits],
        total=len(habits),
    )


@router.post(
    "/habits",
    response_model=HabitResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create a habit definition",
)
async def create_habit(
    body: HabitCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HabitResponse:
    """Create a new habit definition owned by the authenticated user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    habit = await service.create_habit(user_id, body)
    return HabitResponse.model_validate(habit)


@router.patch(
    "/habits/{habit_id}",
    response_model=HabitResponse,
    summary="Update a habit definition",
)
async def update_habit(
    habit_id: uuid.UUID,
    body: HabitUpdate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HabitResponse:
    """Update a habit definition. Raises 404 if not found or not owned by user."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    habit = await service.update_habit(habit_id, user_id, body)
    return HabitResponse.model_validate(habit)


@router.delete(
    "/habits/{habit_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a habit definition",
)
async def delete_habit(
    habit_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Delete a habit definition (cascade-deletes all entries). Raises 404 if not found."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    await service.delete_habit(habit_id, user_id)


@router.post(
    "/habits/{habit_id}/entries",
    response_model=HabitEntryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Log a habit completion entry",
)
async def log_habit_entry(
    habit_id: uuid.UUID,
    body: HabitEntryCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HabitEntryResponse:
    """Log a habit completion entry. Raises 404 if habit not found."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    entry = await service.log_habit_entry(habit_id, user_id, body)
    return HabitEntryResponse.model_validate(entry)


@router.get(
    "/habits/{habit_id}/insights",
    response_model=HabitInsightsResponse,
    summary="Get AI insights for a habit",
)
async def get_habit_insights(
    habit_id: uuid.UUID,
    provider: str = Query(default="openai", description="LLM provider identifier."),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> HabitInsightsResponse:
    """Generate AI-powered insights for a habit. Raises 404 if habit not found."""
    user_id = _current_user_id(current_user)
    service = ProductivityService(db)
    llm_provider = LLMProvider(provider)
    insights = await service.get_habit_insights(habit_id, user_id, llm_provider)
    return HabitInsightsResponse(
        habit_id=habit_id,
        insights=insights,
        generated_at=datetime.now(tz=timezone.utc),
    )
