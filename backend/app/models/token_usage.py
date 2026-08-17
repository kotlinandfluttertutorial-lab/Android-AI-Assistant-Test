# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : token_usage.py
# Purpose : token_usage — models module
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

"""ORM model for the ``token_usage`` table.

Every AI completion that consumes tokens generates a ``TokenUsage`` row.  This
data drives:

- Per-user usage dashboards (Cost Dashboard screen).
- Admin cost reports.
- Billing / quota enforcement.
- Spending alert monitor (background Celery beat task).

Cost calculation
----------------
``cost_usd`` is pre-computed at insertion time by the AI Orchestrator using the
provider's published per-token pricing.  The column uses ``NUMERIC(10, 6)`` for
sub-cent precision (6 decimal places gives $0.000001 resolution, sufficient for
even the cheapest per-token prices).

Feature field
-------------
``feature`` is an enum distinguishing the AI capability that produced this usage
record (chat, RAG, code, voice, comparison, suggestions).  This enables the
Cost Dashboard to break down costs by feature in addition to provider and day.

Retention
---------
Records must be retained for at least 90 days; rows older than 90 days may be
purged by a scheduled maintenance job.

Requirements: 9.3, 9.10, 34.1
"""

from __future__ import annotations

import enum
import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import DateTime, Enum, ForeignKey, Integer, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk

if TYPE_CHECKING:
    from app.models.message import Message
    from app.models.user import User


class UsageFeature(enum.StrEnum):
    """The AI feature that generated a TokenUsage record."""

    chat = "chat"
    rag = "rag"
    code = "code"
    voice = "voice"
    comparison = "comparison"
    suggestions = "suggestions"


class TokenUsage(Base):
    """SQLAlchemy ORM model representing token consumption for one AI completion."""

    __tablename__ = "token_usage"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    message_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("messages.id", ondelete="CASCADE"),
        nullable=False,
        unique=True,
        comment="One-to-one link to the assistant Message that consumed these tokens",
    )
    provider: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        comment="LLM provider, e.g. 'openai', 'anthropic', 'gemini'",
    )
    input_tokens: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        comment="Number of tokens in the prompt sent to the provider",
    )
    output_tokens: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=0,
        comment="Number of tokens in the completion returned by the provider",
    )
    cost_usd: Mapped[float] = mapped_column(
        Numeric(10, 6),
        nullable=False,
        default=0,
        comment="Pre-computed cost in USD using the provider's published per-token price",
    )
    feature: Mapped[UsageFeature] = mapped_column(
        Enum(UsageFeature, name="usage_feature", create_type=True),
        nullable=False,
        default=UsageFeature.chat,
        server_default=UsageFeature.chat.value,
        comment=(
            "AI feature that generated this usage record "
            "(chat/rag/code/voice/comparison/suggestions)"
        ),
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="token_usages")
    message: Mapped[Message] = relationship("Message", back_populates="token_usage")

    def __repr__(self) -> str:
        return (
            f"<TokenUsage id={self.id!s} provider={self.provider!r} "
            f"input={self.input_tokens} output={self.output_tokens} "
            f"cost_usd={self.cost_usd}>"
        )
