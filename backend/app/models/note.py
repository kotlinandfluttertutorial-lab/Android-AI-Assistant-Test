# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : note.py
# Purpose : note — models module
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

"""ORM model for the ``notes`` table.

Notes are user-authored text documents with optional Markdown formatting.
Users can tag notes, apply AI summarisation or AI rewriting, and sync them
to the backend from the Android client.

Tags
----
``tags`` is stored as a PostgreSQL native ``ARRAY(TEXT)`` column.  This allows
efficient GIN-indexed tag membership queries (``@>`` operator) without
requiring a separate join table for the common case of filtering by a single
tag.

Sync
----
The Android ``NoteEntity`` has a ``syncStatus`` field; the backend is the
source of truth.  On reconnection the Android client pushes any ``pending``
notes; the backend accepts them and returns the server-authoritative state.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

from sqlalchemy import ForeignKey, String, Text
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class Note(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a user note."""

    __tablename__ = "notes"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        default="",
        comment="Note title displayed in the NotesList screen",
    )
    content: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        comment="Note body; Markdown formatting is supported by the editor",
    )
    tags: Mapped[list[str]] = mapped_column(
        ARRAY(Text),
        nullable=False,
        default=list,
        comment="User-defined tag labels; stored as a native PostgreSQL text array",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="notes")

    def __repr__(self) -> str:
        return f"<Note id={self.id!s} user_id={self.user_id!s} title={self.title!r}>"
