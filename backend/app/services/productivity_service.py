# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : productivity_service.py
# Purpose : Business logic for the productivity domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Productivity service — business logic for Todos, Calendar, Reminders, and Habits.

This service wraps the :class:`~app.repositories.productivity_repository.ProductivityRepository`
and delegates AI-powered features to :class:`~app.services.ai_orchestrator.AIOrchestrator`.

All methods are user-scoped: a user can only access their own productivity data.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.calendar_event import CalendarEvent
from app.models.habit import HabitDefinition, HabitEntry
from app.models.reminder import Reminder
from app.models.todo_item import TodoItem
from app.repositories.productivity_repository import ProductivityRepository
from app.schemas.productivity import (
    CalendarEventCreate,
    CalendarEventUpdate,
    HabitCreate,
    HabitEntryCreate,
    HabitUpdate,
    ReminderCreate,
    ReminderUpdate,
    TodoCreate,
    TodoUpdate,
)
from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

logger = logging.getLogger(__name__)


class ProductivityService:
    """Business logic layer for the productivity suite.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._repo = ProductivityRepository(db)

    # ==================================================================
    # Todos
    # ==================================================================

    async def list_todos(
        self,
        user_id: uuid.UUID,
        page: int,
        page_size: int,
        is_completed: bool | None = None,
    ) -> tuple[list[TodoItem], int]:
        """Return a paginated list of to-do items for a user.

        Args:
            user_id:      Owning user UUID.
            page:         1-indexed page number.
            page_size:    Number of rows per page.
            is_completed: Optional filter by completion status.

        Returns:
            Tuple of (items, total_count).
        """
        return await self._repo.list_todos(user_id, page, page_size, is_completed)

    async def create_todo(self, user_id: uuid.UUID, data: TodoCreate) -> TodoItem:
        """Create a new to-do item.

        Args:
            user_id: Owning user UUID.
            data:    Validated request body.

        Returns:
            The newly created :class:`~app.models.todo_item.TodoItem`.
        """
        item = await self._repo.create_todo(
            user_id=user_id,
            title=data.title,
            description=data.description,
            due_date=data.due_date,
            priority=data.priority,
            tags=data.tags,
        )
        await self._db.commit()
        return item

    async def get_todo(self, todo_id: uuid.UUID, user_id: uuid.UUID) -> TodoItem:
        """Return a to-do item by ID, or raise 404 if not found.

        Args:
            todo_id: UUID of the to-do item.
            user_id: Owning user UUID (enforces user scoping).

        Returns:
            The :class:`~app.models.todo_item.TodoItem`.

        Raises:
            HTTPException: HTTP 404 when not found or not owned by user.
        """
        item = await self._repo.get_todo(todo_id, user_id)
        if item is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="To-do item not found.",
            )
        return item

    async def update_todo(
        self, todo_id: uuid.UUID, user_id: uuid.UUID, data: TodoUpdate
    ) -> TodoItem:
        """Update a to-do item, or raise 404 if not found.

        Args:
            todo_id: UUID of the to-do item.
            user_id: Owning user UUID (enforces user scoping).
            data:    Validated request body with optional fields.

        Returns:
            The updated :class:`~app.models.todo_item.TodoItem`.

        Raises:
            HTTPException: HTTP 404 when not found or not owned by user.
        """
        updates = data.model_dump(exclude_unset=True)
        item = await self._repo.update_todo(todo_id, user_id, **updates)
        if item is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="To-do item not found.",
            )
        await self._db.commit()
        return item

    async def delete_todo(self, todo_id: uuid.UUID, user_id: uuid.UUID) -> None:
        """Delete a to-do item, or raise 404 if not found.

        Args:
            todo_id: UUID of the to-do item.
            user_id: Owning user UUID (enforces user scoping).

        Raises:
            HTTPException: HTTP 404 when not found or not owned by user.
        """
        deleted = await self._repo.delete_todo(todo_id, user_id)
        if not deleted:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="To-do item not found.",
            )
        await self._db.commit()

    async def generate_todos_from_prompt(
        self, user_id: uuid.UUID, prompt: str, provider: LLMProvider
    ) -> list[TodoItem]:
        """Generate to-do items from a natural language prompt via AI.

        Args:
            user_id:  Owning user UUID.
            prompt:   Natural language description of what to accomplish.
            provider: LLM provider to use for generation.

        Returns:
            List of newly created :class:`~app.models.todo_item.TodoItem` rows.
        """
        orchestrator = AIOrchestrator(self._db)
        ai_prompt = (
            f"Generate a JSON array of todo items for this task: {prompt}. "
            "Each item should have: title (string), description (string), "
            "priority (low/medium/high). Return ONLY a valid JSON array with no markdown."
        )

        try:
            result = await orchestrator.complete(
                prompt=ai_prompt,
                provider=provider,
                max_tokens=1024,
                user_id=str(user_id),
            )
            todos_data = json.loads(result.text)
        except (json.JSONDecodeError, Exception) as exc:
            logger.warning("Failed to parse AI-generated todos: %s", exc)
            return []

        # Create todos in DB
        created_items: list[TodoItem] = []
        for todo_dict in todos_data:
            if not isinstance(todo_dict, dict):
                continue
            item = await self._repo.create_todo(
                user_id=user_id,
                title=todo_dict.get("title", "Untitled"),
                description=todo_dict.get("description", ""),
                priority=todo_dict.get("priority", "medium"),
            )
            created_items.append(item)

        await self._db.commit()
        return created_items

    # ==================================================================
    # Calendar Events
    # ==================================================================

    async def list_calendar_events(
        self,
        user_id: uuid.UUID,
        start_date: datetime | None = None,
        end_date: datetime | None = None,
    ) -> list[CalendarEvent]:
        """Return calendar events for a user, optionally filtered by date range."""
        return await self._repo.list_calendar_events(user_id, start_date, end_date)

    async def create_calendar_event(
        self, user_id: uuid.UUID, data: CalendarEventCreate
    ) -> CalendarEvent:
        """Create a new calendar event."""
        event = await self._repo.create_calendar_event(
            user_id=user_id,
            title=data.title,
            start_time=data.start_time,
            end_time=data.end_time,
            description=data.description,
            location=data.location,
            is_all_day=data.is_all_day,
            source=data.source,
        )
        await self._db.commit()
        return event

    async def update_calendar_event(
        self, event_id: uuid.UUID, user_id: uuid.UUID, data: CalendarEventUpdate
    ) -> CalendarEvent:
        """Update a calendar event, or raise 404 if not found."""
        updates = data.model_dump(exclude_unset=True)
        event = await self._repo.update_calendar_event(event_id, user_id, **updates)
        if event is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Calendar event not found.",
            )
        await self._db.commit()
        return event

    async def delete_calendar_event(
        self, event_id: uuid.UUID, user_id: uuid.UUID
    ) -> None:
        """Delete a calendar event, or raise 404 if not found."""
        deleted = await self._repo.delete_calendar_event(event_id, user_id)
        if not deleted:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Calendar event not found.",
            )
        await self._db.commit()

    async def suggest_meeting_times(
        self,
        user_id: uuid.UUID,
        prompt: str,
        duration_minutes: int,
        provider: LLMProvider,
    ) -> list[str]:
        """Suggest optimal meeting times via AI.

        Args:
            user_id:          Owning user UUID.
            prompt:           Natural language meeting requirements.
            duration_minutes: Duration of the meeting in minutes.
            provider:         LLM provider to use.

        Returns:
            List of ISO 8601 datetime strings for suggested meeting slots.
        """
        orchestrator = AIOrchestrator(self._db)
        ai_prompt = (
            f"Suggest 3 optimal meeting times for: {prompt}. "
            f"Duration: {duration_minutes} minutes. "
            "Return ONLY a JSON array of ISO 8601 datetime strings "
            "(e.g., ['2024-01-15T14:00:00Z'])."
        )

        try:
            result = await orchestrator.complete(
                prompt=ai_prompt,
                provider=provider,
                max_tokens=512,
                user_id=str(user_id),
            )
            suggestions = json.loads(result.text)
            return suggestions if isinstance(suggestions, list) else []
        except (json.JSONDecodeError, Exception) as exc:
            logger.warning("Failed to parse AI-suggested meeting times: %s", exc)
            return []

    # ==================================================================
    # Reminders
    # ==================================================================

    async def list_reminders(self, user_id: uuid.UUID) -> list[Reminder]:
        """Return all reminders for a user, sorted by trigger_time ASC."""
        return await self._repo.list_reminders(user_id)

    async def create_reminder(
        self, user_id: uuid.UUID, data: ReminderCreate
    ) -> Reminder:
        """Create a new reminder."""
        reminder = await self._repo.create_reminder(
            user_id=user_id,
            title=data.title,
            trigger_time=data.trigger_time,
            recurrence_rule=data.recurrence_rule,
            linked_todo_id=data.linked_todo_id,
        )
        await self._db.commit()
        return reminder

    async def update_reminder(
        self, reminder_id: uuid.UUID, user_id: uuid.UUID, data: ReminderUpdate
    ) -> Reminder:
        """Update a reminder, or raise 404 if not found."""
        updates = data.model_dump(exclude_unset=True)
        reminder = await self._repo.update_reminder(reminder_id, user_id, **updates)
        if reminder is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Reminder not found.",
            )
        await self._db.commit()
        return reminder

    async def delete_reminder(self, reminder_id: uuid.UUID, user_id: uuid.UUID) -> None:
        """Delete a reminder, or raise 404 if not found."""
        deleted = await self._repo.delete_reminder(reminder_id, user_id)
        if not deleted:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Reminder not found.",
            )
        await self._db.commit()

    async def suggest_reminder(
        self, user_id: uuid.UUID, prompt: str, provider: LLMProvider
    ) -> dict:
        """Suggest a reminder from natural language via AI.

        Args:
            user_id:  Owning user UUID.
            prompt:   Natural language description of what to be reminded about.
            provider: LLM provider to use.

        Returns:
            Dict with keys ``suggestion`` (ReminderCreate-compatible dict) and ``rationale``.
        """
        orchestrator = AIOrchestrator(self._db)
        ai_prompt = (
            f"Generate a reminder suggestion for: {prompt}. "
            "Return ONLY a JSON object with: title (string), trigger_time (ISO 8601), "
            "recurrence_rule (optional iCal RRULE), rationale (string explanation)."
        )

        try:
            result = await orchestrator.complete(
                prompt=ai_prompt,
                provider=provider,
                max_tokens=512,
                user_id=str(user_id),
            )
            data = json.loads(result.text)
            return {
                "suggestion": ReminderCreate(
                    title=data.get("title", "Reminder"),
                    trigger_time=datetime.fromisoformat(data.get("trigger_time")),
                    recurrence_rule=data.get("recurrence_rule"),
                ),
                "rationale": data.get("rationale", ""),
            }
        except (json.JSONDecodeError, ValueError, Exception) as exc:
            logger.warning("Failed to parse AI-suggested reminder: %s", exc)
            # Return fallback
            return {
                "suggestion": ReminderCreate(
                    title=prompt[:100],
                    trigger_time=datetime.now(tz=UTC),
                ),
                "rationale": "AI suggestion unavailable; using default values.",
            }

    # ==================================================================
    # Habits
    # ==================================================================

    async def list_habits(self, user_id: uuid.UUID) -> list[HabitDefinition]:
        """Return all habit definitions for a user."""
        return await self._repo.list_habits(user_id)

    async def create_habit(
        self, user_id: uuid.UUID, data: HabitCreate
    ) -> HabitDefinition:
        """Create a new habit definition."""
        habit = await self._repo.create_habit(
            user_id=user_id,
            name=data.name,
            description=data.description,
            recurrence=data.recurrence,
            target_frequency=data.target_frequency,
        )
        await self._db.commit()
        return habit

    async def update_habit(
        self, habit_id: uuid.UUID, user_id: uuid.UUID, data: HabitUpdate
    ) -> HabitDefinition:
        """Update a habit definition, or raise 404 if not found."""
        updates = data.model_dump(exclude_unset=True)
        habit = await self._repo.update_habit(habit_id, user_id, **updates)
        if habit is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Habit not found.",
            )
        await self._db.commit()
        return habit

    async def delete_habit(self, habit_id: uuid.UUID, user_id: uuid.UUID) -> None:
        """Delete a habit definition, or raise 404 if not found."""
        deleted = await self._repo.delete_habit(habit_id, user_id)
        if not deleted:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Habit not found.",
            )
        await self._db.commit()

    async def log_habit_entry(
        self, habit_id: uuid.UUID, user_id: uuid.UUID, data: HabitEntryCreate
    ) -> HabitEntry:
        """Log a habit completion entry.

        Verifies that the habit exists and belongs to the user before creating the entry.

        Args:
            habit_id: UUID of the habit.
            user_id:  Owning user UUID.
            data:     Validated request body.

        Returns:
            The newly created :class:`~app.models.habit.HabitEntry`.

        Raises:
            HTTPException: HTTP 404 when habit not found or not owned by user.
        """
        habit = await self._repo.get_habit(habit_id, user_id)
        if habit is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Habit not found.",
            )

        entry = await self._repo.create_habit_entry(
            habit_id=habit_id,
            user_id=user_id,
            completed_at=data.completed_at,
            note=data.note,
        )
        await self._db.commit()
        return entry

    async def get_habit_insights(
        self, habit_id: uuid.UUID, user_id: uuid.UUID, provider: LLMProvider
    ) -> str:
        """Generate AI insights for a habit.

        Args:
            habit_id: UUID of the habit.
            user_id:  Owning user UUID.
            provider: LLM provider to use.

        Returns:
            AI-generated insights text.

        Raises:
            HTTPException: HTTP 404 when habit not found or not owned by user.
        """
        habit = await self._repo.get_habit(habit_id, user_id)
        if habit is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="Habit not found.",
            )

        entries = await self._repo.list_habit_entries(habit_id, user_id)

        # Build stats summary
        total_entries = len(entries)
        recent_entries = [
            e for e in entries if (datetime.now(tz=UTC) - e.completed_at).days <= 30
        ]
        recent_count = len(recent_entries)

        orchestrator = AIOrchestrator(self._db)
        ai_prompt = (
            f"Generate motivational insights for habit '{habit.name}'. "
            f"Total completions: {total_entries}. Recent (30 days): {recent_count}. "
            f"Target: {habit.target_frequency}x per {habit.recurrence}. "
            "Provide 2-3 sentences of encouragement and analysis."
        )

        try:
            result = await orchestrator.complete(
                prompt=ai_prompt,
                provider=provider,
                max_tokens=256,
                user_id=str(user_id),
            )
            return result.text
        except Exception as exc:
            logger.warning("Failed to generate habit insights: %s", exc)
            return (
                f"You've completed '{habit.name}' {total_entries} times total, "
                f"with {recent_count} completions in the last 30 days. Keep it up!"
            )
