# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : message_repository.py
# Purpose : Database access layer for message entities
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

"""Database access layer for messages.

All queries operate on the ``messages`` table via the SQLAlchemy async session.
This repository provides CRUD operations for messages within conversations.

Requirements: 2.1, 2.3, 2.6, 2.7
"""

from __future__ import annotations

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.message import Message, MessageRole


class MessageRepository:
    """CRUD and lookup operations for the ``messages`` table.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def get_by_conversation_id(
        self,
        conversation_id: uuid.UUID,
        limit: int | None = None,
    ) -> list[Message]:
        """Return all messages in a conversation, ordered by creation time (oldest first).

        Args:
            conversation_id: UUID of the conversation.
            limit: Maximum number of messages to retrieve. If None, retrieves all.

        Returns:
            List of :class:`~app.models.message.Message` rows ordered by created_at ASC.

        Requirements: 2.3
        """
        query = (
            select(Message)
            .where(Message.conversation_id == conversation_id)
            .order_by(Message.created_at)
        )
        if limit is not None:
            query = query.limit(limit)

        result = await self._db.execute(query)
        return list(result.scalars().all())

    async def create(
        self,
        *,
        conversation_id: uuid.UUID,
        role: MessageRole,
        content: str,
        input_tokens: int = 0,
        output_tokens: int = 0,
        provider: str = "",
    ) -> Message:
        """Create a new message in a conversation.

        Args:
            conversation_id: UUID of the parent conversation.
            role: Message role (user, assistant, system, tool).
            content: Message text content.
            input_tokens: Number of input tokens (for assistant messages).
            output_tokens: Number of output tokens (for assistant messages).
            provider: LLM provider identifier (for assistant messages).

        Returns:
            The newly created and flushed :class:`~app.models.message.Message`.

        Requirements: 2.1
        """
        message = Message(
            conversation_id=conversation_id,
            role=role,
            content=content,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            provider=provider,
        )
        self._db.add(message)
        await self._db.flush()
        return message

    async def get_paginated_by_conversation_id(
        self,
        conversation_id: uuid.UUID,
        offset: int,
        limit: int,
    ) -> list[Message]:
        """Return a paginated slice of messages in a conversation (oldest-first).

        Args:
            conversation_id: UUID of the conversation.
            offset:          Number of rows to skip (0-indexed).
            limit:           Maximum number of rows to return.

        Returns:
            List of :class:`~app.models.message.Message` rows ordered by created_at ASC.

        Requirements: 2.6
        """
        result = await self._db.execute(
            select(Message)
            .where(Message.conversation_id == conversation_id)
            .order_by(Message.created_at)
            .offset(offset)
            .limit(limit)
        )
        return list(result.scalars().all())

    async def count_by_conversation_id(self, conversation_id: uuid.UUID) -> int:
        """Count all messages in a conversation.

        Args:
            conversation_id: UUID of the conversation.

        Returns:
            Total message count.

        Requirements: 2.6
        """
        result = await self._db.execute(
            select(func.count()).where(Message.conversation_id == conversation_id)
        )
        return result.scalar_one()

    async def count_tokens_in_conversation(
        self,
        conversation_id: uuid.UUID,
    ) -> int:
        """Estimate total token count for all messages in a conversation.

        This is a rough estimate based on input_tokens + output_tokens stored
        in each message. For more accurate estimates, use the provider's tokenizer.

        Args:
            conversation_id: UUID of the conversation.

        Returns:
            Sum of input_tokens + output_tokens across all messages.

        Requirements: 2.3, 2.4
        """
        messages = await self.get_by_conversation_id(conversation_id)
        return sum(msg.input_tokens + msg.output_tokens for msg in messages)
