# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : error_log.py
# Purpose : error_log — models module
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

"""ORM model for the ``error_logs`` table.

Stores application error events with type, message, and stack trace.
The Admin Dashboard surfaces the top-10 most frequent error types in the
last 24 hours with stack trace summaries.

Requirements: 15.6
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, uuid_pk


class ErrorLog(Base):
    """SQLAlchemy ORM model for a single recorded application error event."""

    __tablename__ = "error_logs"

    id: Mapped[uuid.UUID] = uuid_pk()
    error_type: Mapped[str] = mapped_column(
        String(255),
        nullable=False,
        index=True,
        comment="Short error class/type string, e.g. 'ValueError', 'HTTPException'",
    )
    message: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="Human-readable error message",
    )
    stack_trace: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        default="",
        comment="Full stack trace text; may be truncated for large traces",
    )
    endpoint: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        default="",
        comment="API endpoint path where the error occurred",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
    )

    def __repr__(self) -> str:
        return (
            f"<ErrorLog id={self.id!s} error_type={self.error_type!r} "
            f"created_at={self.created_at!s}>"
        )
