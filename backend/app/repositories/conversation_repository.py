# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : conversation_repository.py
# Purpose : Database access layer for conversation entities
#
# Architecture Layer : Repository
# Pattern Used       : Repository Pattern
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Database access layer for conversations.

All queries operate on the ``conversations`` table via the SQLAlchemy async
session.  Soft-delete semantics are enforced here: ``list_by_user`` and
``count_by_user`` always exclude rows where ``is_deleted=True``.

Requirements: 11.3, 11.4, 11.6
"""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.conversation import Conversation


class ConversationRepository:
    """CRUD and lookup operations for the ``conversations`` table.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    # ------------------------------------------------------------------
    # Read
    # ------------------------------------------------------------------

    async def list_by_user(
        self,
        user_id: uuid.UUID,
        page: int,
        page_size: int,
    ) -> list[Conversation]:
        """Return a paginated list of non-deleted conversations for a user.

        Results are sorted by ``updated_at`` DESC (most recently active first).

        Args:
            user_id:   UUID of the owning user.
            page:      1-indexed page number.
            page_size: Number of rows per page.

        Returns:
            List of :class:`~app.models.conversation.Conversation` rows.

        Requirements: 11.3
        """
        offset = (page - 1) * page_size
        result = await self._db.execute(
            select(Conversation)
            .where(
                Conversation.user_id == user_id,
                Conversation.is_deleted.is_(False),
            )
            .order_by(Conversation.updated_at.desc())
            .offset(offset)
            .limit(page_size)
        )
        return list(result.scalars().all())

    async def get_by_id(self, conversation_id: uuid.UUID) -> Conversation | None:
        """Return the conversation with the given primary key, or ``None``.

        Args:
            conversation_id: UUID primary key to look up.

        Returns:
            The matching :class:`~app.models.conversation.Conversation`, or ``None``.
        """
        result = await self._db.execute(
            select(Conversation).where(Conversation.id == conversation_id)
        )
        return result.scalar_one_or_none()

    async def count_by_user(self, user_id: uuid.UUID) -> int:
        """Count non-deleted conversations for pagination metadata.

        Args:
            user_id: UUID of the owning user.

        Returns:
            Total count of active (non-deleted) conversations.

        Requirements: 11.3
        """
        result = await self._db.execute(
            select(func.count()).where(
                Conversation.user_id == user_id,
                Conversation.is_deleted.is_(False),
            )
        )
        return result.scalar_one()

    # ------------------------------------------------------------------
    # Create
    # ------------------------------------------------------------------

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        title: str = "New Conversation",
        provider: str = "",
    ) -> Conversation:
        """Insert a new conversation row.

        Args:
            user_id:  UUID of the owning user.
            title:    Human-readable title.
            provider: LLM provider identifier (e.g. ``"openai"``).

        Returns:
            The newly created and flushed :class:`~app.models.conversation.Conversation`.

        Requirements: 11.4
        """
        conversation = Conversation(
            user_id=user_id,
            title=title,
            provider=provider,
        )
        self._db.add(conversation)
        await self._db.flush()
        return conversation

    # ------------------------------------------------------------------
    # Update
    # ------------------------------------------------------------------

    async def update(
        self,
        conversation_id: uuid.UUID,
        **kwargs: Any,
    ) -> Conversation | None:
        """Update arbitrary fields on a conversation.

        Only the following fields are accepted: ``title``, ``is_pinned``.
        Unknown keys are silently ignored to protect against mass-assignment.

        Args:
            conversation_id: UUID of the conversation to update.
            **kwargs:         Field–value pairs to apply.

        Returns:
            The updated :class:`~app.models.conversation.Conversation`, or
            ``None`` if not found.

        Requirements: 11.4
        """
        conversation = await self.get_by_id(conversation_id)
        if conversation is None:
            return None

        allowed_fields = {"title", "is_pinned"}
        for field, value in kwargs.items():
            if field in allowed_fields:
                setattr(conversation, field, value)

        await self._db.flush()
        return conversation

    # ------------------------------------------------------------------
    # Soft-delete
    # ------------------------------------------------------------------

    async def soft_delete(self, conversation_id: uuid.UUID) -> Conversation | None:
        """Mark a conversation as deleted without removing the row.

        Sets ``is_deleted = True`` on the identified row.  The row is retained
        for audit purposes but will be excluded from all list/count queries.

        Args:
            conversation_id: UUID of the conversation to soft-delete.

        Returns:
            The updated :class:`~app.models.conversation.Conversation`, or
            ``None`` if not found.

        Requirements: 11.6
        """
        conversation = await self.get_by_id(conversation_id)
        if conversation is None:
            return None

        conversation.is_deleted = True
        await self._db.flush()
        return conversation
