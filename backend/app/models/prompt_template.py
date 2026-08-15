# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : prompt_template.py
# Purpose : prompt_template — models module
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

"""ORM model for the ``prompt_templates`` table.

Prompt templates are versioned, reusable system-prompt fragments managed by
admins and used by the AI Orchestrator to build consistent, high-quality
prompts for different use cases (e.g. code analysis, meeting summarisation,
email drafting).

Versioning
----------
Each row is an immutable version snapshot identified by ``(name, version)``.
Multiple rows can share the same ``name`` — each has a distinct ``version``.
The ``is_active`` flag marks the version that the Orchestrator should use;
at most one version per name should be active at a time (enforced by
application logic in ``PromptService``).

Requirements: 9.3, 9.10, 25.1, 25.2
"""

from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, TimestampMixin, uuid_pk

if TYPE_CHECKING:
    from app.models.user import User


class PromptTemplate(Base, TimestampMixin):
    """SQLAlchemy ORM model representing a versioned prompt template.

    Each row is an immutable snapshot of a named template at a particular
    version.  The ``(name, version)`` pair is unique — multiple versions of
    the same logical template are stored as separate rows.  Only one row per
    name may have ``is_active=True`` at any given time; this invariant is
    maintained by ``PromptService``, not by a database constraint, so that
    the service can atomically flip the active flag within a single transaction.
    """

    __tablename__ = "prompt_templates"
    __table_args__ = (
        # Each (name, version) pair must be unique — prevents duplicate version
        # numbers within a named template family.  Replaces the old
        # UniqueConstraint on name alone which blocked multi-version storage.
        UniqueConstraint("name", "version", name="uq_prompt_templates_name_version"),
    )

    id: Mapped[uuid.UUID] = uuid_pk()
    name: Mapped[str] = mapped_column(
        String(255),
        nullable=False,
        index=True,
        comment=(
            "Logical template name shared across all versions, "
            "e.g. 'code_analysis_system_prompt'"
        ),
    )
    content: Mapped[str] = mapped_column(
        Text,
        nullable=False,
        comment="Jinja2-compatible template text with {{ variable }} placeholders",
    )
    version: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=1,
        comment="Monotonically increasing version number within a named template",
    )
    author_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="RESTRICT"),
        nullable=False,
        comment="Admin user who created or last modified this template version",
    )
    is_active: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=True,
        comment="Only the active version is used by the AI Orchestrator",
    )

    # ------------------------------------------------------------------
    # Relationships
    # ------------------------------------------------------------------
    author: Mapped[User] = relationship(
        "User", back_populates="prompt_templates"
    )

    def __repr__(self) -> str:
        return (
            f"<PromptTemplate id={self.id!s} name={self.name!r} "
            f"version={self.version} is_active={self.is_active}>"
        )
