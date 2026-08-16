# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : persona.py
# Purpose : persona — models module
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

"""ORM model for the ``personas`` table.

The ``Persona`` model stores custom AI personas that users can create to customize
the AI's personality, system prompt, tone, and scope. Each user can have up to 20
personas. Admin users can create admin-locked personas that are shared across
specific roles.

Security notes
--------------
- ``system_prompt`` is validated for prompt injection patterns before persistence
  (handled in the service layer via SafetyService).
- ``admin_locked`` personas cannot be edited or deleted by non-admin users.
- ``allowed_roles`` controls which RBAC roles can see and use the persona.

Requirements: 32.1, 32.2, 32.3, 32.4, 32.5, 32.6
"""

from __future__ import annotations

import enum
import uuid
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, Enum, ForeignKey, String, Text
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class PersonaTone(str, enum.Enum):
    """Tone options for persona behavior."""

    professional = "professional"
    casual = "casual"
    concise = "concise"
    detailed = "detailed"
    creative = "creative"


class Persona(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a custom AI persona.

    Relationships
    -------------
    - Many-to-one with ``User`` (creator/owner).
    """

    __tablename__ = "personas"

    id: Mapped[uuid.UUID] = uuid_pk()
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
        comment="User who created this persona; admin-locked personas still have an owner",
    )
    name: Mapped[str] = mapped_column(
        String(80),
        nullable=False,
        comment="Persona display name (1-80 characters)",
    )
    system_prompt: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="Custom system prompt injected into AI context (1-4,000 characters)",
    )
    tone: Mapped[PersonaTone] = mapped_column(
        Enum(PersonaTone, name="persona_tone", create_type=True),
        nullable=False,
        default=PersonaTone.professional,
        comment="Tone/style of the AI response",
    )
    scope_description: Mapped[str] = mapped_column(
        String(500),
        nullable=False,
        default="",
        server_default="",
        comment="Optional description of when to use this persona (0-500 characters)",
    )
    admin_locked: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="When True, only admins can edit/delete; visible to allowed_roles",
    )
    allowed_roles: Mapped[list[str]] = mapped_column(
        ARRAY(String),
        nullable=False,
        default=list,
        server_default="{}",
        comment="RBAC roles allowed to view/use this persona; empty list = owner-only",
    )

    # ------------------------------------------------------------------
    # Relationships (back-populated in parent models)
    # ------------------------------------------------------------------
    user: Mapped[User] = relationship("User", back_populates="personas")

    def __repr__(self) -> str:
        return (
            f"<Persona id={self.id!s} name={self.name!r} "
            f"tone={self.tone.value!r} admin_locked={self.admin_locked}>"
        )
