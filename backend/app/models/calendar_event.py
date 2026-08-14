# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : calendar_event.py
# Purpose : calendar_event — models module
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

"""ORM model for the ``calendar_events`` table.

Stores calendar events with start/end times, location, and source information.
Supports both locally created events and events synced from Google Calendar.
All events are strictly scoped to the owning user via a FK to ``users.id``.

Requirements: 13.1, 9.1, 9.2
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk


class CalendarEvent(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a user calendar event."""

    __tablename__ = "calendar_events"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        comment="Event title displayed in the calendar",
    )
    description: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        server_default="",
        comment="Optional event description or agenda",
    )
    start_time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        comment="Event start time (UTC)",
    )
    end_time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        comment="Event end time (UTC)",
    )
    location: Mapped[str | None] = mapped_column(
        String(1024),
        nullable=True,
        comment="Optional physical or virtual location",
    )
    is_all_day: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="True if this is an all-day event (time portion is ignored)",
    )
    source: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        default="local",
        server_default="local",
        comment="Event source: local | google_calendar",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="calendar_events")  # noqa: F821

    def __repr__(self) -> str:
        return (
            f"<CalendarEvent id={self.id!s} user_id={self.user_id!s} "
            f"title={self.title!r} start_time={self.start_time!r}>"
        )
