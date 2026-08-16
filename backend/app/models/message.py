# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : message.py
# Purpose : message — models module
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

"""ORM model for the ``messages`` table.

Each row records a single turn in a ``Conversation``: either a user prompt, an
AI assistant reply, a system-level instruction, or a tool call/result.

Token tracking
--------------
``input_tokens`` and ``output_tokens`` are populated by the AI Orchestrator
after each completion.  A corresponding ``TokenUsage`` row is created for
billing/analytics.  The fields are kept on ``Message`` as well to avoid a JOIN
on the hot conversation-history query path.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import enum
import uuid
from typing import TYPE_CHECKING

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk

if TYPE_CHECKING:
    from app.models.conversation import Conversation
    from app.models.token_usage import TokenUsage


class MessageRole(str, enum.Enum):
    """The originator role of a message, matching OpenAI's convention."""

    user = "user"
    assistant = "assistant"
    system = "system"
    tool = "tool"


class Message(Base):
    """SQLAlchemy ORM model representing a single message in a conversation.

    Note that ``Message`` does **not** inherit ``TimestampMixin`` because it has
    only ``created_at`` (messages are immutable once stored) and no
    ``updated_at`` column, which keeps the schema lean on the largest table.
    """

    __tablename__ = "messages"

    id: Mapped[uuid.UUID] = uuid_pk()
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("conversations.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    role: Mapped[MessageRole] = mapped_column(
        Enum(MessageRole, name="message_role", create_type=True),
        nullable=False,
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)
    input_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    provider: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        default="",
        comment="LLM provider that produced this message, e.g. 'openai'",
    )
    created_at: Mapped[str] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    conversation: Mapped[Conversation] = relationship(
        "Conversation", back_populates="messages"
    )
    token_usage: Mapped[TokenUsage | None] = relationship(
        "TokenUsage", back_populates="message", uselist=False
    )

    def __repr__(self) -> str:
        return (
            f"<Message id={self.id!s} role={self.role.value!r} "
            f"conversation_id={self.conversation_id!s}>"
        )
