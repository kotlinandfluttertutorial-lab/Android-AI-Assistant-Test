# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : repositories
# File    : token_usage_repository.py
# Purpose : Database access layer for token_usage entities
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

"""Database access layer for token usage records.

All queries operate on the ``token_usage`` table via the SQLAlchemy async
session. This repository provides CRUD operations for token usage tracking.

Requirements: 2.9
"""

from __future__ import annotations

import uuid
from decimal import Decimal

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.token_usage import TokenUsage, UsageFeature


class TokenUsageRepository:
    """CRUD and lookup operations for the ``token_usage`` table.

    Args:
        db: SQLAlchemy async session for the current request.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def create(
        self,
        *,
        user_id: uuid.UUID,
        message_id: uuid.UUID,
        provider: str,
        input_tokens: int,
        output_tokens: int,
        cost_usd: Decimal = Decimal(0),
        feature: UsageFeature = UsageFeature.chat,
    ) -> TokenUsage:
        """Record token usage for a single AI completion.

        Args:
            user_id: UUID of the user who triggered the completion.
            message_id: UUID of the assistant message that consumed these tokens.
            provider: LLM provider identifier (e.g. 'openai', 'anthropic').
            input_tokens: Number of tokens in the prompt sent to the provider.
            output_tokens: Number of tokens in the completion returned.
            cost_usd: Pre-computed cost in USD using the provider's per-token price.
            feature: AI feature that generated this record (chat/rag/code/voice/
                comparison/suggestions). Defaults to ``chat``.

        Returns:
            The newly created and flushed :class:`~app.models.token_usage.TokenUsage`.

        Requirements: 2.9, 34.1
        """
        usage = TokenUsage(
            user_id=user_id,
            message_id=message_id,
            provider=provider,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=float(cost_usd),
            feature=feature,
        )
        self._db.add(usage)
        await self._db.flush()
        return usage

    async def get_by_user_id(
        self,
        user_id: uuid.UUID,
        limit: int = 100,
    ) -> list[TokenUsage]:
        """Return recent token usage records for a user.

        Args:
            user_id: UUID of the user.
            limit: Maximum number of records to return.

        Returns:
            List of :class:`~app.models.token_usage.TokenUsage` rows.

        Requirements: 2.9
        """
        result = await self._db.execute(
            select(TokenUsage)
            .where(TokenUsage.user_id == user_id)
            .order_by(TokenUsage.created_at.desc())
            .limit(limit)
        )
        return list(result.scalars().all())
