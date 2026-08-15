# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : memory_service.py
# Purpose : Business logic for the memory domain
#
# Architecture Layer : Service
# Pattern Used       : Service Layer (Business Logic)
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#   - ChromaDB semantic search via MemoryRepository
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Memory Service — stores, retrieves, deletes, and protects long-term user memories.

The Memory Service stores user-specific facts, preferences, and writing-style
observations as vector embeddings in ChromaDB and retrieves the most semantically
relevant ones for each AI request.

Architecture
------------
- ``MemoryService`` is the business-logic layer consumed by API routers and the
  ``AIOrchestrator``.
- ``MemoryRepository`` (``app.repositories.memory_repository``) is the data-access
  layer that wraps ChromaDB per-user collections and the PostgreSQL ``memories`` table.

Privacy mode
------------
When ``privacy_mode`` is ``True`` on the user's record, memory capture is disabled:
- New memories are NOT stored (``store_memory`` is a no-op).
- Existing memories are NOT deleted (they remain retrievable and injectable).
- Retrieval still works normally so the AI can use historical memories.

Requirements: 7.1, 7.2, 7.4, 7.5, 7.6
"""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.memory import Memory, MemoryType
from app.models.user import User
from app.repositories.memory_repository import MemoryRepository, MemorySearchResult

logger = logging.getLogger(__name__)


@dataclass
class MemoryEntry:
    """A single memory item retrieved for prompt injection.

    Attributes:
        content: The memory text to inject into the system prompt.
        memory_type: Classification of the memory (preference, fact, style).
        relevance_score: Semantic similarity score from ChromaDB (0.0 – 1.0).
    """

    content: str
    memory_type: str
    relevance_score: float = 1.0


class MemoryService:
    """Business logic for storing, retrieving, and deleting user memories.

    Delegates ChromaDB and PostgreSQL operations to ``MemoryRepository``.

    Args:
        db: SQLAlchemy async session for the current request.

    Requirements: 7.1, 7.2, 7.4, 7.5, 7.6
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._repo = MemoryRepository(db)

    # ------------------------------------------------------------------
    # Store a new memory (POST /memory)
    # ------------------------------------------------------------------

    async def store_memory(
        self,
        user_id: uuid.UUID,
        content: str,
        memory_type: MemoryType = MemoryType.fact,
        redis=None,
    ) -> Memory | None:
        """Store a new memory for the user if privacy mode is not enabled.

        When the user's ``privacy_mode`` flag is ``True``, this method returns
        ``None`` without storing anything (Requirement 7.6).

        After a successful store, the per-user privacy budget key
        ``privacy_budget:{user_id}`` in Redis is incremented by the epsilon
        value used for noise injection (Requirement 37.8).

        Args:
            user_id: UUID of the user.
            content: The memory text to store.
            memory_type: Classification of the memory.
            redis: Optional async Redis client used for epsilon lookup and
                privacy budget tracking.

        Returns:
            The stored :class:`~app.models.memory.Memory` row, or ``None`` when
            privacy mode is enabled.

        Requirements: 7.1, 7.6, 37.8
        """
        from app.security.differential_privacy import (
            get_current_epsilon,
        )

        # Check privacy mode — do not store if disabled
        user = await self._get_user(user_id)
        if user is not None and getattr(user, "privacy_mode", False):
            logger.info(
                "Memory capture skipped for user %s — privacy mode is enabled.",
                user_id,
            )
            return None

        # Fetch current epsilon before the repo call so we pass a concrete value
        epsilon = await get_current_epsilon(redis)

        result = await self._repo.store_memory(
            user_id=user_id,
            content=content,
            memory_type=memory_type,
            epsilon=epsilon,
            redis=redis,
        )

        # Increment per-user privacy budget in Redis (graceful degradation)
        if redis is not None:
            try:
                await redis.incrbyfloat(f"privacy_budget:{user_id}", epsilon)
            except Exception as exc:
                logger.warning(
                    "Failed to increment privacy_budget for user %s: %s",
                    user_id,
                    exc,
                )

        return result

    # ------------------------------------------------------------------
    # Retrieve top-K relevant memories (GET /memory)
    # ------------------------------------------------------------------

    async def get_relevant_memories(
        self,
        user_id: uuid.UUID,
        query: str,
        top_k: int = 3,
    ) -> list[MemoryEntry]:
        """Retrieve the top-K most relevant memories for the current query.

        Performs semantic (vector) search via ChromaDB.  If ChromaDB is
        unavailable or returns no results, falls back to the most recent
        memories from PostgreSQL.

        IF memory retrieval fails or returns no results, returns an empty list
        so the caller can proceed with prompt construction without memories.

        Args:
            user_id: UUID of the user whose memories to retrieve.
            query: The current user message used as the semantic search query.
            top_k: Maximum number of memories to return (default: 3).

        Returns:
            List of :class:`MemoryEntry` objects, ordered by relevance (best first).
            Returns an empty list on error or when no memories are found.

        Requirements: 7.2, 7.5
        """
        try:
            results: list[MemorySearchResult] = await self._repo.search_memories(
                user_id=user_id,
                query=query,
                top_k=top_k,
            )

            if not results:
                # Fallback to recent memories from PostgreSQL
                results = await self._repo.get_recent_memories(
                    user_id=user_id,
                    top_k=top_k,
                )

            # Enforce top_k limit defensively — the repo may return more results
            # than requested (e.g. when using a mock or an unbounded data source).
            results = results[:top_k]

            return [
                MemoryEntry(
                    content=r.content,
                    memory_type=r.memory_type,
                    relevance_score=r.relevance_score,
                )
                for r in results
            ]
        except Exception as exc:
            # Requirement 7.2: IF memory retrieval fails, proceed without memories
            logger.warning(
                "Memory retrieval failed for user %s; proceeding without memories. Error: %s",
                user_id,
                exc,
            )
            return []

    # ------------------------------------------------------------------
    # Delete a memory (DELETE /memory/{id})
    # ------------------------------------------------------------------

    async def delete_memory(
        self,
        memory_id: uuid.UUID,
        user_id: uuid.UUID,
    ) -> bool:
        """Delete a specific memory embedding from ChromaDB and PostgreSQL.

        The deletion is scoped to the authenticated user — a user cannot delete
        another user's memories.

        Args:
            memory_id: UUID of the memory to delete.
            user_id: UUID of the requesting user (enforces ownership).

        Returns:
            ``True`` if deleted, ``False`` if not found or access denied.

        Requirements: 7.4
        """
        return await self._repo.delete_memory(
            memory_id=memory_id,
            user_id=user_id,
        )

    # ------------------------------------------------------------------
    # List all memories for a user
    # ------------------------------------------------------------------

    async def list_memories(
        self,
        user_id: uuid.UUID,
    ) -> list[Memory]:
        """Return all memories for the given user.

        Args:
            user_id: UUID of the user.

        Returns:
            List of :class:`~app.models.memory.Memory` rows, newest first.

        Requirements: 7.3
        """
        return await self._repo.list_memories(user_id=user_id)

    # ------------------------------------------------------------------
    # Toggle privacy mode (PATCH /users/me/privacy-mode)
    # ------------------------------------------------------------------

    async def set_privacy_mode(
        self,
        user_id: uuid.UUID,
        privacy_mode: bool,
    ) -> bool:
        """Toggle memory capture on/off without deleting existing memories.

        When ``privacy_mode`` is ``True``:
        - New memories are NOT captured.
        - Existing memories are NOT deleted.

        When ``privacy_mode`` is ``False``:
        - Memory capture resumes.

        Args:
            user_id: UUID of the user.
            privacy_mode: The new privacy mode value.

        Returns:
            ``True`` if the user was found and updated; ``False`` otherwise.

        Requirements: 7.6
        """
        user = await self._get_user(user_id)
        if user is None:
            return False
        user.privacy_mode = privacy_mode
        await self._db.flush()
        return True

    # ------------------------------------------------------------------
    # Private helpers
    # ------------------------------------------------------------------

    async def _get_user(self, user_id: uuid.UUID) -> User | None:
        """Fetch the user record for the given user_id.

        Args:
            user_id: UUID of the user.

        Returns:
            :class:`~app.models.user.User` or ``None`` if not found.
        """
        result = await self._db.execute(select(User).where(User.id == user_id))
        return result.scalar_one_or_none()
