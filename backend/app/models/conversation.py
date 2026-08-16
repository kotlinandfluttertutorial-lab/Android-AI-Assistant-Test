# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : conversation.py
# Purpose : conversation — models module
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

"""ORM model for the ``conversations`` table.

A ``Conversation`` is a logical container for a sequence of ``Message`` rows
exchanged between the user and an LLM provider.

Design decisions
----------------
- ``is_deleted`` implements soft-delete: deleted conversations are hidden from
  list endpoints but retained for audit and export purposes.
- ``is_pinned`` allows the user to keep important conversations at the top of
  the list regardless of ``updated_at`` order.
- ``provider`` records which LLM provider was active when the conversation was
  created (e.g. ``"openai"``, ``"gemini"``, ``"claude"``).

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, ForeignKey, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.message import Message
    from app.models.user import User


class Conversation(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a chat conversation."""

    __tablename__ = "conversations"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(
        String(512), nullable=False, default="New Conversation"
    )
    provider: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        default="",
        comment="LLM provider identifier, e.g. 'openai', 'gemini', 'claude', 'ollama'",
    )
    is_pinned: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_deleted: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        comment="Soft-delete flag; deleted rows are excluded from list queries",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="conversations")
    messages: Mapped[list[Message]] = relationship(
        "Message", back_populates="conversation", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return (
            f"<Conversation id={self.id!s} user_id={self.user_id!s} "
            f"title={self.title!r} is_deleted={self.is_deleted}>"
        )
