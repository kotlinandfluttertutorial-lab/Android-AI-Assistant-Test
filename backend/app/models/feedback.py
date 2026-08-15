# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : feedback.py
# Purpose : feedback — models module
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

"""ORM model for the ``feedback`` table.

Stores user-submitted feedback items.  Admins can view, tag by category, and
export as CSV via the Admin Dashboard.

Requirements: 15.7
"""

from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk


class Feedback(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a single piece of user feedback."""

    __tablename__ = "feedback"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
        comment="UUID of the user who submitted the feedback; NULL if user was deleted",
    )
    content: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="The full feedback text submitted by the user",
    )
    category: Mapped[str] = mapped_column(
        String(128),
        nullable=False,
        default="",
        index=True,
        comment="Admin-assigned category tag, e.g. 'bug', 'feature_request', 'praise'",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    user: Mapped[User | None] = relationship("User")  # noqa: F821

    def __repr__(self) -> str:
        return f"<Feedback id={self.id!s} user_id={self.user_id!s} category={self.category!r}>"
