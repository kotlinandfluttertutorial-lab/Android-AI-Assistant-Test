# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : user.py
# Purpose : user — models module
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

"""ORM model for the ``users`` table.

The ``User`` model stores all account-level data: credentials, OAuth linkage,
display profile, role, and notification preferences.

Security notes
--------------
- ``password_hash`` stores a bcrypt digest, never the plaintext password.
  The bcrypt work factor (rounds) is controlled by ``settings.BCRYPT_WORK_FACTOR``
  (default 12 — OWASP recommended minimum).  Hashing itself is performed in
  ``app.services.auth_service``, not in this model, to keep the ORM layer free
  of side-effects.
- ``google_id`` is nullable; it is populated on first Google OAuth2 sign-in and
  linked to the local account.
- ``push_token`` is the Firebase Cloud Messaging (FCM) device token used to send
  push notifications; it is rotated by the mobile client and stored here.

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import enum
import uuid

from sqlalchemy import Boolean, Enum, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk


class UserRole(str, enum.Enum):
    """Roles that control access to premium and admin features."""

    user = "user"
    premium = "premium"
    admin = "admin"


class User(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a registered application user.

    Relationships
    -------------
    - One-to-many with ``Conversation``, ``Document``, ``Memory``, ``APIKey``,
      ``Note``, ``Job``, ``TokenUsage``, ``PromptTemplate`` (author).
    - Nullable one-to-many with ``AuditLog`` (nullable because audit logs may
      record pre-authentication events such as failed login attempts).
    """

    __tablename__ = "users"

    id: Mapped[uuid.UUID] = uuid_pk()
    email: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    password_hash: Mapped[str] = mapped_column(
        String(255),
        nullable=False,
        comment="bcrypt digest — work factor controlled by settings.BCRYPT_WORK_FACTOR (default 12)",
    )
    google_id: Mapped[str | None] = mapped_column(
        String(255), unique=True, nullable=True
    )
    display_name: Mapped[str] = mapped_column(String(255), nullable=False, default="")
    avatar_url: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    role: Mapped[UserRole] = mapped_column(
        Enum(UserRole, name="user_role", create_type=True),
        nullable=False,
        default=UserRole.user,
    )
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    push_token: Mapped[str | None] = mapped_column(
        String(512),
        nullable=True,
        comment="Firebase Cloud Messaging device token; rotated by the mobile client",
    )
    fcm_token: Mapped[str | None] = mapped_column(
        String(512),
        nullable=True,
        comment=(
            "Firebase Cloud Messaging (FCM) device token used for push notifications. "
            "Updated via PUT /notifications/device-token. "
            "Requirements: 16.7"
        ),
    )
    privacy_mode: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment=(
            "When True, memory capture is disabled for this user's session. "
            "Existing memories are NOT deleted when privacy_mode is enabled. "
            "Requirement 7.6"
        ),
    )

    # ------------------------------------------------------------------
    # Relationships (back-populated in child models)
    # ------------------------------------------------------------------
    conversations: Mapped[list[Conversation]] = relationship(  # noqa: F821
        "Conversation", back_populates="user", cascade="all, delete-orphan"
    )
    documents: Mapped[list[Document]] = relationship(  # noqa: F821
        "Document", back_populates="user", cascade="all, delete-orphan"
    )
    memories: Mapped[list[Memory]] = relationship(  # noqa: F821
        "Memory", back_populates="user", cascade="all, delete-orphan"
    )
    api_keys: Mapped[list[APIKey]] = relationship(  # noqa: F821
        "APIKey", back_populates="user", cascade="all, delete-orphan"
    )
    audit_logs: Mapped[list[AuditLog]] = relationship(  # noqa: F821
        "AuditLog", back_populates="user"
    )
    prompt_templates: Mapped[list[PromptTemplate]] = relationship(  # noqa: F821
        "PromptTemplate", back_populates="author", cascade="all, delete-orphan"
    )
    token_usages: Mapped[list[TokenUsage]] = relationship(  # noqa: F821
        "TokenUsage", back_populates="user", cascade="all, delete-orphan"
    )
    notes: Mapped[list[Note]] = relationship(  # noqa: F821
        "Note", back_populates="user", cascade="all, delete-orphan"
    )
    jobs: Mapped[list[Job]] = relationship(  # noqa: F821
        "Job", back_populates="user", cascade="all, delete-orphan"
    )
    todo_items: Mapped[list[TodoItem]] = relationship(  # noqa: F821
        "TodoItem", back_populates="user", cascade="all, delete-orphan"
    )
    calendar_events: Mapped[list[CalendarEvent]] = relationship(  # noqa: F821
        "CalendarEvent", back_populates="user", cascade="all, delete-orphan"
    )
    reminders: Mapped[list[Reminder]] = relationship(  # noqa: F821
        "Reminder", back_populates="user", cascade="all, delete-orphan"
    )
    habit_definitions: Mapped[list[HabitDefinition]] = relationship(  # noqa: F821
        "HabitDefinition", back_populates="user", cascade="all, delete-orphan"
    )
    habit_entries: Mapped[list[HabitEntry]] = relationship(  # noqa: F821
        "HabitEntry", back_populates="user", cascade="all, delete-orphan"
    )
    spending_alerts: Mapped[list[SpendingAlert]] = relationship(  # noqa: F821
        "SpendingAlert", back_populates="user", cascade="all, delete-orphan"
    )
    personas: Mapped[list[Persona]] = relationship(  # noqa: F821
        "Persona", back_populates="user", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return f"<User id={self.id!s} email={self.email!r} role={self.role.value!r}>"
