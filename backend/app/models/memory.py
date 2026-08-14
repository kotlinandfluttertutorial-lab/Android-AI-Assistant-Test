# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : memory.py
# Purpose : memory — models module
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

"""ORM model for the ``memories`` table.

The Memory Service extracts and stores facts, preferences, and writing-style
observations from user conversations.  These are recalled during subsequent
AI requests to personalise responses.

Memory types
------------
- ``preference`` — explicit user preferences (e.g. "I prefer concise answers").
- ``fact``       — factual information about the user (e.g. "I work in healthcare").
- ``style``      — writing or communication style signals (e.g. "uses formal tone").

Privacy note
------------
Memory data is considered sensitive: ``MemoryRepositoryImpl`` on the Android
client does **not** cache these rows locally.  Users can view, edit, and delete
individual memories via the Profile screen; deletion removes the corresponding
ChromaDB embedding within 10 seconds.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime

from sqlalchemy import DateTime, Enum, ForeignKey, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk


class MemoryType(str, enum.Enum):
    """Classification of a stored memory fragment."""

    preference = "preference"
    fact = "fact"
    style = "style"


class Memory(Base):
    """SQLAlchemy ORM model representing a single extracted memory fragment."""

    __tablename__ = "memories"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    content: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="The extracted memory text injected into future AI prompts",
    )
    memory_type: Mapped[MemoryType] = mapped_column(
        Enum(MemoryType, name="memory_type", create_type=True),
        nullable=False,
        index=True,
    )
    chroma_id: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        index=True,
        comment="ChromaDB document ID for the memory's semantic embedding vector",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="memories")  # noqa: F821

    def __repr__(self) -> str:
        return (
            f"<Memory id={self.id!s} user_id={self.user_id!s} "
            f"type={self.memory_type.value!r}>"
        )
