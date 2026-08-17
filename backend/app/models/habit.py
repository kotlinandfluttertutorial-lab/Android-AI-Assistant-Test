# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : habit.py
# Purpose : habit — models module
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

"""ORM models for the ``habit_definitions`` and ``habit_entries`` tables.

``HabitDefinition`` stores the template for a recurring habit (name, recurrence
schedule, target frequency). ``HabitEntry`` records each individual completion
event for a habit.

Both tables are scoped to the owning user via a FK to ``users.id``.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class HabitDefinition(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a habit definition/template."""

    __tablename__ = "habit_definitions"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    name: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Name of the habit (e.g., 'Morning run', 'Read for 30 minutes')",
    )
    description: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        server_default="",
        comment="Optional description or motivation note for the habit",
    )
    recurrence: Mapped[str] = mapped_column(
        String(16),
        nullable=False,
        default="daily",
        server_default="daily",
        comment="How often the habit repeats: daily | weekly",
    )
    target_frequency: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=1,
        server_default="1",
        comment="Number of times to complete the habit per recurrence period",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="habit_definitions")
    entries: Mapped[list[HabitEntry]] = relationship(
        "HabitEntry", back_populates="habit", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return (
            f"<HabitDefinition id={self.id!s} user_id={self.user_id!s} "
            f"name={self.name!r} recurrence={self.recurrence!r}>"
        )


class HabitEntry(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a single habit completion entry."""

    __tablename__ = "habit_entries"

    id: Mapped[uuid.UUID] = uuid_pk()
    habit_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("habit_definitions.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    completed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        comment="UTC timestamp when the habit was completed",
    )
    note: Mapped[str | None] = mapped_column(
        Text,
        nullable=True,
        comment="Optional note or reflection about this completion",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    habit: Mapped[HabitDefinition] = relationship(
        "HabitDefinition", back_populates="entries"
    )
    user: Mapped[User] = relationship("User", back_populates="habit_entries")

    def __repr__(self) -> str:
        return (
            f"<HabitEntry id={self.id!s} habit_id={self.habit_id!s} "
            f"user_id={self.user_id!s} completed_at={self.completed_at!r}>"
        )
