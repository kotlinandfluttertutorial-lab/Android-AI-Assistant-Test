# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : productivity_repository.py
# Purpose : Database access layer for productivity entities
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Database access layer for the productivity suite.

Provides CRUD operations for Todos, Calendar Events, Reminders, Habits, and
Habit Entries. All queries are strictly scoped to the authenticated user to
prevent cross-user data leakage.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.calendar_event import CalendarEvent
from app.models.habit import HabitDefinition, HabitEntry
from app.models.reminder import Reminder
from app.models.todo_item import TodoItem


class ProductivityRepository:
    """CRUD operations for the productivity suite tables.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ==================================================================
    # Todos
    # ==================================================================

    async def create_todo(
        self,
        user_id: uuid.UUID,
        title: str,
        description: str = "",
        due_date: datetime | None = None,
        priority: str = "medium",
        tags: list[str] | None = None,
    ) -> TodoItem:
        """Insert a new to-do item.

        Args:
            user_id:     Owning user UUID.
            title:       Short description of the task.
            description: Optional longer description.
            due_date:    Optional deadline (timezone-aware).
            priority:    Priority level: low | medium | high.
            tags:        Optional list of string tags.

        Returns:
            The newly created and flushed :class:`~app.models.todo_item.TodoItem`.
        """
        item = TodoItem(
            user_id=user_id,
            title=title,
            description=description,
            due_date=due_date,
            priority=priority,
            tags=tags if tags is not None else [],
        )
        self._db.add(item)
        await self._db.flush()
        await self._db.refresh(item)
        return item

    async def get_todo(self, todo_id: uuid.UUID, user_id: uuid.UUID) -> TodoItem | None:
        """Return a to-do item by primary key, scoped to the given user.

        Args:
            todo_id: UUID primary key.
            user_id: Owning user UUID (enforces user scoping).

        Returns:
            The matching :class:`~app.models.todo_item.TodoItem`, or ``None``.
        """
        result = await self._db.execute(
            select(TodoItem).where(
                TodoItem.id == todo_id,
                TodoItem.user_id == user_id,
            )
        )
        return result.scalar_one_or_none()

    async def list_todos(
        self,
        user_id: uuid.UUID,
        page: int = 1,
        page_size: int = 20,
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
        base_where = [TodoItem.user_id == user_id]
        if is_completed is not None:
            base_where.append(TodoItem.is_completed == is_completed)

        # Count query
        count_result = await self._db.execute(
            select(func.count()).select_from(TodoItem).where(*base_where)
        )
        total = count_result.scalar_one()

        # Paginated query
        offset = (page - 1) * page_size
        result = await self._db.execute(
            select(TodoItem)
            .where(*base_where)
            .order_by(TodoItem.created_at.desc())
            .offset(offset)
            .limit(page_size)
        )
        items = list(result.scalars().all())
        return items, total

    async def update_todo(
        self,
        todo_id: uuid.UUID,
        user_id: uuid.UUID,
        **kwargs: Any,
    ) -> TodoItem | None:
        """Update arbitrary fields on a to-do item.

        Only recognised fields are applied; unknown keys are ignored.

        Args:
            todo_id: UUID of the to-do item to update.
            user_id: Owning user UUID (enforces user scoping).
            **kwargs: Field–value pairs to apply.

        Returns:
            The updated :class:`~app.models.todo_item.TodoItem`, or ``None`` if not found.
        """
        item = await self.get_todo(todo_id, user_id)
        if item is None:
            return None

        allowed_fields = {
            "title",
            "description",
            "due_date",
            "priority",
            "is_completed",
            "tags",
        }
        for field, value in kwargs.items():
            if field in allowed_fields:
                setattr(item, field, value)

        await self._db.flush()
        await self._db.refresh(item)
        return item

    async def delete_todo(self, todo_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """Delete a to-do item by primary key, scoped to the given user.

        Args:
            todo_id: UUID of the to-do item to delete.
            user_id: Owning user UUID (enforces user scoping).

        Returns:
            ``True`` if the item was deleted, ``False`` if not found.
        """
        item = await self.get_todo(todo_id, user_id)
        if item is None:
            return False
        await self._db.delete(item)
        await self._db.flush()
        return True

    # ==================================================================
    # Calendar Events
    # ==================================================================

    async def create_calendar_event(
        self,
        user_id: uuid.UUID,
        title: str,
        start_time: datetime,
        end_time: datetime,
        **kwargs: Any,
    ) -> CalendarEvent:
        """Insert a new calendar event.

        Args:
            user_id:    Owning user UUID.
            title:      Event title.
            start_time: Event start time (timezone-aware).
            end_time:   Event end time (timezone-aware).
            **kwargs:   Optional fields: description, location, is_all_day, source.

        Returns:
            The newly created :class:`~app.models.calendar_event.CalendarEvent`.
        """
        event = CalendarEvent(
            user_id=user_id,
            title=title,
            start_time=start_time,
            end_time=end_time,
            description=kwargs.get("description", ""),
            location=kwargs.get("location"),
            is_all_day=kwargs.get("is_all_day", False),
            source=kwargs.get("source", "local"),
        )
        self._db.add(event)
        await self._db.flush()
        await self._db.refresh(event)
        return event

    async def get_calendar_event(
        self, event_id: uuid.UUID, user_id: uuid.UUID
    ) -> CalendarEvent | None:
        """Return a calendar event by primary key, scoped to the given user."""
        result = await self._db.execute(
            select(CalendarEvent).where(
                CalendarEvent.id == event_id,
                CalendarEvent.user_id == user_id,
            )
        )
        return result.scalar_one_or_none()

    async def list_calendar_events(
        self,
        user_id: uuid.UUID,
        start_date: datetime | None = None,
        end_date: datetime | None = None,
    ) -> list[CalendarEvent]:
        """Return calendar events for a user, optionally filtered by date range.

        Args:
            user_id:    Owning user UUID.
            start_date: Optional lower bound for start_time (inclusive).
            end_date:   Optional upper bound for start_time (inclusive).

        Returns:
            List of :class:`~app.models.calendar_event.CalendarEvent` ordered
            by start_time ASC.
        """
        conditions = [CalendarEvent.user_id == user_id]
        if start_date is not None:
            conditions.append(CalendarEvent.start_time >= start_date)
        if end_date is not None:
            conditions.append(CalendarEvent.start_time <= end_date)

        result = await self._db.execute(
            select(CalendarEvent)
            .where(*conditions)
            .order_by(CalendarEvent.start_time.asc())
        )
        return list(result.scalars().all())

    async def update_calendar_event(
        self,
        event_id: uuid.UUID,
        user_id: uuid.UUID,
        **kwargs: Any,
    ) -> CalendarEvent | None:
        """Update arbitrary fields on a calendar event."""
        event = await self.get_calendar_event(event_id, user_id)
        if event is None:
            return None

        allowed_fields = {
            "title",
            "description",
            "start_time",
            "end_time",
            "location",
            "is_all_day",
        }
        for field, value in kwargs.items():
            if field in allowed_fields:
                setattr(event, field, value)

        await self._db.flush()
        await self._db.refresh(event)
        return event

    async def delete_calendar_event(
        self, event_id: uuid.UUID, user_id: uuid.UUID
    ) -> bool:
        """Delete a calendar event, scoped to the given user."""
        event = await self.get_calendar_event(event_id, user_id)
        if event is None:
            return False
        await self._db.delete(event)
        await self._db.flush()
        return True

    # ==================================================================
    # Reminders
    # ==================================================================

    async def create_reminder(
        self,
        user_id: uuid.UUID,
        title: str,
        trigger_time: datetime,
        **kwargs: Any,
    ) -> Reminder:
        """Insert a new reminder.

        Args:
            user_id:      Owning user UUID.
            title:        Short description of the reminder.
            trigger_time: When the reminder should fire (timezone-aware).
            **kwargs:     Optional fields: recurrence_rule, linked_todo_id.

        Returns:
            The newly created :class:`~app.models.reminder.Reminder`.
        """
        reminder = Reminder(
            user_id=user_id,
            title=title,
            trigger_time=trigger_time,
            recurrence_rule=kwargs.get("recurrence_rule"),
            linked_todo_id=kwargs.get("linked_todo_id"),
        )
        self._db.add(reminder)
        await self._db.flush()
        await self._db.refresh(reminder)
        return reminder

    async def get_reminder(
        self, reminder_id: uuid.UUID, user_id: uuid.UUID
    ) -> Reminder | None:
        """Return a reminder by primary key, scoped to the given user."""
        result = await self._db.execute(
            select(Reminder).where(
                Reminder.id == reminder_id,
                Reminder.user_id == user_id,
            )
        )
        return result.scalar_one_or_none()

    async def list_reminders(self, user_id: uuid.UUID) -> list[Reminder]:
        """Return all reminders for a user, sorted by trigger_time ASC."""
        result = await self._db.execute(
            select(Reminder)
            .where(Reminder.user_id == user_id)
            .order_by(Reminder.trigger_time.asc())
        )
        return list(result.scalars().all())

    async def update_reminder(
        self,
        reminder_id: uuid.UUID,
        user_id: uuid.UUID,
        **kwargs: Any,
    ) -> Reminder | None:
        """Update arbitrary fields on a reminder."""
        reminder = await self.get_reminder(reminder_id, user_id)
        if reminder is None:
            return None

        allowed_fields = {
            "title",
            "trigger_time",
            "recurrence_rule",
            "linked_todo_id",
            "is_completed",
        }
        for field, value in kwargs.items():
            if field in allowed_fields:
                setattr(reminder, field, value)

        await self._db.flush()
        await self._db.refresh(reminder)
        return reminder

    async def delete_reminder(self, reminder_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """Delete a reminder, scoped to the given user."""
        reminder = await self.get_reminder(reminder_id, user_id)
        if reminder is None:
            return False
        await self._db.delete(reminder)
        await self._db.flush()
        return True

    # ==================================================================
    # Habits
    # ==================================================================

    async def create_habit(
        self,
        user_id: uuid.UUID,
        name: str,
        **kwargs: Any,
    ) -> HabitDefinition:
        """Insert a new habit definition.

        Args:
            user_id:  Owning user UUID.
            name:     Name of the habit.
            **kwargs: Optional fields: description, recurrence, target_frequency.

        Returns:
            The newly created :class:`~app.models.habit.HabitDefinition`.
        """
        habit = HabitDefinition(
            user_id=user_id,
            name=name,
            description=kwargs.get("description", ""),
            recurrence=kwargs.get("recurrence", "daily"),
            target_frequency=kwargs.get("target_frequency", 1),
        )
        self._db.add(habit)
        await self._db.flush()
        await self._db.refresh(habit)
        return habit

    async def get_habit(
        self, habit_id: uuid.UUID, user_id: uuid.UUID
    ) -> HabitDefinition | None:
        """Return a habit definition by primary key, scoped to the given user."""
        result = await self._db.execute(
            select(HabitDefinition).where(
                HabitDefinition.id == habit_id,
                HabitDefinition.user_id == user_id,
            )
        )
        return result.scalar_one_or_none()

    async def list_habits(self, user_id: uuid.UUID) -> list[HabitDefinition]:
        """Return all habit definitions for a user, ordered by created_at ASC."""
        result = await self._db.execute(
            select(HabitDefinition)
            .where(HabitDefinition.user_id == user_id)
            .order_by(HabitDefinition.created_at.asc())
        )
        return list(result.scalars().all())

    async def update_habit(
        self,
        habit_id: uuid.UUID,
        user_id: uuid.UUID,
        **kwargs: Any,
    ) -> HabitDefinition | None:
        """Update arbitrary fields on a habit definition."""
        habit = await self.get_habit(habit_id, user_id)
        if habit is None:
            return None

        allowed_fields = {"name", "description", "recurrence", "target_frequency"}
        for field, value in kwargs.items():
            if field in allowed_fields:
                setattr(habit, field, value)

        await self._db.flush()
        await self._db.refresh(habit)
        return habit

    async def delete_habit(self, habit_id: uuid.UUID, user_id: uuid.UUID) -> bool:
        """Delete a habit definition (and cascade-deletes all entries).."""
        habit = await self.get_habit(habit_id, user_id)
        if habit is None:
            return False
        await self._db.delete(habit)
        await self._db.flush()
        return True

    # ==================================================================
    # Habit Entries
    # ==================================================================

    async def create_habit_entry(
        self,
        habit_id: uuid.UUID,
        user_id: uuid.UUID,
        completed_at: datetime,
        note: str | None = None,
    ) -> HabitEntry:
        """Insert a habit completion entry.

        Args:
            habit_id:     UUID of the parent habit.
            user_id:      Owning user UUID.
            completed_at: When the habit was completed (timezone-aware).
            note:         Optional note or reflection.

        Returns:
            The newly created :class:`~app.models.habit.HabitEntry`.
        """
        entry = HabitEntry(
            habit_id=habit_id,
            user_id=user_id,
            completed_at=completed_at,
            note=note,
        )
        self._db.add(entry)
        await self._db.flush()
        await self._db.refresh(entry)
        return entry

    async def list_habit_entries(
        self, habit_id: uuid.UUID, user_id: uuid.UUID
    ) -> list[HabitEntry]:
        """Return all habit entries for a specific habit, scoped to the user.

        Results are ordered by completed_at ASC.

        Args:
            habit_id: UUID of the habit.
            user_id:  Owning user UUID (enforces user scoping).

        Returns:
            List of :class:`~app.models.habit.HabitEntry` ordered by completed_at ASC.
        """
        result = await self._db.execute(
            select(HabitEntry)
            .where(
                HabitEntry.habit_id == habit_id,
                HabitEntry.user_id == user_id,
            )
            .order_by(HabitEntry.completed_at.asc())
        )
        return list(result.scalars().all())
