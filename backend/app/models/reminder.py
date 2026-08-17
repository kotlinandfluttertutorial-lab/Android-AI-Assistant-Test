# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : reminder.py
# Purpose : reminder — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""ORM model for the ``reminders`` table.

Stores time-based reminders with optional recurrence rules (iCal RRULE format)
and optional linkage to a to-do item. All reminders are scoped to the owning
user via a FK to ``users.id``.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.todo_item import TodoItem
    from app.models.user import User


class Reminder(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a user reminder."""

    __tablename__ = "reminders"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Short description of what to be reminded about",
    )
    trigger_time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        index=True,
        comment="UTC time at which the reminder should fire",
    )
    recurrence_rule: Mapped[str | None] = mapped_column(
        String(512),
        nullable=True,
        comment="Optional iCal RRULE string defining recurrence pattern",
    )
    linked_todo_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("todo_items.id", ondelete="SET NULL"),
        nullable=True,
        comment="Optional FK to a related to-do item; set to NULL if the todo is deleted",
    )
    is_completed: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="Whether this reminder has been acknowledged/completed",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="reminders")
    linked_todo: Mapped[TodoItem | None] = relationship(
        "TodoItem", back_populates="reminders", foreign_keys=[linked_todo_id]
    )

    def __repr__(self) -> str:
        return (
            f"<Reminder id={self.id!s} user_id={self.user_id!s} "
            f"title={self.title!r} trigger_time={self.trigger_time!r}>"
        )
