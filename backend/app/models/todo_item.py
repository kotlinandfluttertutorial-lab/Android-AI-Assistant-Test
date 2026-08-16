# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : todo_item.py
# Purpose : todo_item — models module
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

"""ORM model for the ``todo_items`` table.

Stores user to-do items with title, description, priority, due date, and tags.
All items are strictly scoped to the owning user via a FK to ``users.id``.

Tags are stored as a PostgreSQL native ``ARRAY(TEXT)`` column for efficient
single-query tag filtering without a join table.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, ForeignKey, String, Text
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.reminder import Reminder
    from app.models.user import User


class TodoItem(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a user to-do item."""

    __tablename__ = "todo_items"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Short description of the to-do item",
    )
    description: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        server_default="",
        comment="Optional longer description or notes",
    )
    is_completed: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="Whether the to-do item has been completed",
    )
    due_date: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True),
        nullable=True,
        comment="Optional deadline for the to-do item (UTC)",
    )
    priority: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default="medium",
        server_default="medium",
        comment="Priority level: low | medium | high",
    )
    tags: Mapped[list[str]] = mapped_column(
        ARRAY(Text),
        nullable=False,
        default=list,
        server_default="ARRAY[]::text[]",
        comment="User-defined tag labels stored as a native PostgreSQL text array",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="todo_items")
    reminders: Mapped[list[Reminder]] = relationship(
        "Reminder", back_populates="linked_todo", foreign_keys="Reminder.linked_todo_id"
    )

    def __repr__(self) -> str:
        return (
            f"<TodoItem id={self.id!s} user_id={self.user_id!s} "
            f"title={self.title!r} priority={self.priority!r}>"
        )
