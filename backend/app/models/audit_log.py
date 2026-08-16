# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : audit_log.py
# Purpose : audit_log — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Audit Logging
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""ORM model for the ``audit_logs`` table.

Audit logs record security-relevant events for compliance and forensic
investigation.  Every log entry captures who performed an action, from where,
and with which client.

Recorded event types
--------------------
- ``login``          — successful authentication (password or OAuth).
- ``logout``         — explicit session termination.
- ``token_refresh``  — a refresh token was exchanged for new access/refresh tokens.
- ``failed_login``   — an authentication attempt failed (wrong password, unknown
                       email, locked account).
- ``mcp_invoke``     — a user invoked an MCP tool connector (write operations).

Design decisions
----------------
- ``user_id`` is nullable to support logging of ``failed_login`` events where
  the user identity is unconfirmed (email may not exist in the database).
- ``metadata`` stores a JSONB payload that varies by event type, for example
  ``{"provider": "google"}`` for an OAuth login or ``{"tool": "github:create_issue"}``
  for an MCP invocation.
- Audit logs are append-only; there is no update or delete path through the ORM.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class AuditLog(Base):
    """SQLAlchemy ORM model representing a single security audit log entry.

    Rows are immutable after insertion (no ``updated_at`` column).
    """

    __tablename__ = "audit_logs"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
        index=True,
        comment="NULL for pre-authentication events (e.g. failed_login with unknown email)",
    )
    event_type: Mapped[str] = mapped_column(
        String(64),
        nullable=False,
        index=True,
        comment=(
            "Security event identifier. Known values: "
            "login, logout, token_refresh, failed_login, mcp_invoke"
        ),
    )
    ip_address: Mapped[str] = mapped_column(
        String(45),
        nullable=False,
        comment="Client IP address (IPv4 or IPv6, max 45 chars for IPv6)",
    )
    user_agent: Mapped[str] = mapped_column(
        String(512),
        nullable=False,
        default="",
        comment="HTTP User-Agent header value from the request",
    )
    metadata_: Mapped[dict[str, object]] = mapped_column(
        "metadata",  # column name in the database
        JSONB,
        nullable=False,
        default=dict,
        comment='Event-specific JSONB payload, e.g. {"provider": "google"} for OAuth login',
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
    user: Mapped[User | None] = relationship("User", back_populates="audit_logs")

    def __repr__(self) -> str:
        return f"<AuditLog id={self.id!s} event_type={self.event_type!r} user_id={self.user_id!s}>"
