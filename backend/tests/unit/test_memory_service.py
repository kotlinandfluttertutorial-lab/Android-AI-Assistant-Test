"""Unit tests for the Memory Service and Memory Repository.

Covers:
- POST /memory: store_memory respects privacy_mode flag          (Req 7.1, 7.6)
- GET  /memory: get_relevant_memories returns MemoryEntry list   (Req 7.2)
- GET  /memory: graceful degradation on retrieval failure         (Req 7.2)
- DELETE /memory: delete_memory respects user ownership           (Req 7.4, 7.5)
- Privacy mode toggle: set_privacy_mode updates User.privacy_mode (Req 7.6)
- No cross-user retrieval: collection name scoped to user_id      (Req 7.5)

Requirements: 7.1, 7.2, 7.4, 7.5, 7.6
"""

from __future__ import annotations

import os
import types
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Environment variables required before importing app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.models.memory import Memory, MemoryType
from app.models.user import User, UserRole
from app.repositories.memory_repository import MemoryRepository, MemorySearchResult
from app.services.memory_service import MemoryEntry, MemoryService

# ---------------------------------------------------------------------------
# Test helpers / fixtures
# ---------------------------------------------------------------------------


def _make_user(
    user_id: uuid.UUID | None = None,
    privacy_mode: bool = False,
) -> types.SimpleNamespace:
    """Build a lightweight stub that quacks like a User ORM object.

    Using SimpleNamespace avoids SQLAlchemy instrumentation issues that arise
    when ``__new__`` is called directly on a mapped class without going through
    the ORM's __init__ machinery.
    """
    return types.SimpleNamespace(
        id=user_id or uuid.uuid4(),
        email="test@example.com",
        password_hash="hashed",
        display_name="Test User",
        role=UserRole.user,
        is_active=True,
        privacy_mode=privacy_mode,
    )


def _make_memory(
    user_id: uuid.UUID | None = None,
    content: str = "I prefer concise answers.",
    memory_type: MemoryType = MemoryType.preference,
    chroma_id: str | None = None,
) -> types.SimpleNamespace:
    """Build a lightweight stub that quacks like a Memory ORM object.

    Using SimpleNamespace avoids SQLAlchemy instrumentation issues.
    """
    return types.SimpleNamespace(
        id=uuid.uuid4(),
        user_id=user_id or uuid.uuid4(),
        content=content,
        memory_type=memory_type,
        chroma_id=chroma_id or str(uuid.uuid4()),
        created_at=datetime.now(tz=timezone.utc),
    )


def _make_db_session(
    user: User | None = None,
    memories: list[Memory] | None = None,
) -> AsyncMock:
    """Return a mock AsyncSession that provides the given objects."""
    db = AsyncMock()

    async def _execute(query):
        mock_result = MagicMock()
        mock_result.scalar_one_or_none.return_value = user
        mock_result.scalars.return_value.all.return_value = memories or []
        return mock_result

    db.execute = _execute
    db.flush = AsyncMock()
    db.add = MagicMock()
    db.delete = AsyncMock()
    return db


# ---------------------------------------------------------------------------
# MemoryRepository — collection naming
# ---------------------------------------------------------------------------


class TestMemoryRepositoryCollectionName:
    """Tests for MemoryRepository._collection_name (user-scoped isolation)."""

    def test_collection_name_format(self) -> None:
        """Collection name must follow the memories_{user_id} convention.

        Requirements: 7.5
        """
        db = AsyncMock()
        repo = MemoryRepository(db)
        user_id = uuid.UUID("12345678-1234-5678-1234-567812345678")
        name = repo._collection_name(user_id)
        assert name == "memories_12345678-1234-5678-1234-567812345678"

    def test_different_users_get_different_collections(self) -> None:
        """Two different users must have different collection names.

        Requirements: 7.5
        """
        db = AsyncMock()
        repo = MemoryRepository(db)
        user_a = uuid.uuid4()
        user_b = uuid.uuid4()
        assert repo._collection_name(user_a) != repo._collection_name(user_b)

    def test_same_user_always_same_collection(self) -> None:
        """The same user always maps to the same collection name.

        Requirements: 7.5
        """
        db = AsyncMock()
        repo = MemoryRepository(db)
        user_id = uuid.uuid4()
        assert repo._collection_name(user_id) == repo._collection_name(user_id)


# ---------------------------------------------------------------------------
# MemoryService — store_memory with privacy_mode check
# ---------------------------------------------------------------------------


class TestMemoryServiceStore:
    """Tests for MemoryService.store_memory."""

    @pytest.mark.asyncio
    async def test_store_memory_privacy_mode_disabled_stores_memory(self) -> None:
        """When privacy_mode is False, memory is stored via the repository.

        Requirements: 7.1
        """
        user_id = uuid.uuid4()
        user = _make_user(user_id=user_id, privacy_mode=False)
        db = _make_db_session(user=user)

        with patch.object(
            MemoryRepository, "store_memory", new_callable=AsyncMock
        ) as mock_store:
            expected_memory = _make_memory(user_id=user_id)
            mock_store.return_value = expected_memory

            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="I prefer concise answers.",
                memory_type=MemoryType.preference,
            )

        assert result is not None
        assert result.content == expected_memory.content
        mock_store.assert_called_once()
        call_kwargs = mock_store.call_args.kwargs
        assert call_kwargs["user_id"] == user_id
        assert call_kwargs["content"] == "I prefer concise answers."
        assert call_kwargs["memory_type"] == MemoryType.preference

    @pytest.mark.asyncio
    async def test_store_memory_privacy_mode_enabled_returns_none(self) -> None:
        """When privacy_mode is True, store_memory returns None without storing.

        Requirements: 7.6
        """
        user_id = uuid.uuid4()
        user = _make_user(user_id=user_id, privacy_mode=True)
        db = _make_db_session(user=user)

        with patch.object(
            MemoryRepository, "store_memory", new_callable=AsyncMock
        ) as mock_store:
            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="Should not be stored.",
                memory_type=MemoryType.fact,
            )

        assert result is None
        mock_store.assert_not_called()

    @pytest.mark.asyncio
    async def test_store_memory_user_not_found_still_stores(self) -> None:
        """If user lookup returns None (edge case), memory is stored (no privacy flag to check).

        Requirements: 7.1
        """
        user_id = uuid.uuid4()
        db = _make_db_session(user=None)  # user not found

        with patch.object(
            MemoryRepository, "store_memory", new_callable=AsyncMock
        ) as mock_store:
            expected_memory = _make_memory(user_id=user_id)
            mock_store.return_value = expected_memory

            service = MemoryService(db)
            result = await service.store_memory(
                user_id=user_id,
                content="Some content.",
                memory_type=MemoryType.fact,
            )

        assert result is not None
        mock_store.assert_called_once()


# ---------------------------------------------------------------------------
# MemoryService — get_relevant_memories
# ---------------------------------------------------------------------------


class TestMemoryServiceRetrieve:
    """Tests for MemoryService.get_relevant_memories."""

    @pytest.mark.asyncio
    async def test_returns_memory_entries_from_search(self) -> None:
        """Semantic search results are converted to MemoryEntry objects.

        Requirements: 7.2
        """
        user_id = uuid.uuid4()
        db = _make_db_session()

        search_results = [
            MemorySearchResult(
                memory_id=uuid.uuid4(),
                chroma_id=str(uuid.uuid4()),
                content="I prefer Python over Java.",
                memory_type="preference",
                relevance_score=0.1,
            ),
            MemorySearchResult(
                memory_id=uuid.uuid4(),
                chroma_id=str(uuid.uuid4()),
                content="I work in healthcare.",
                memory_type="fact",
                relevance_score=0.3,
            ),
        ]

        with patch.object(
            MemoryRepository, "search_memories", new_callable=AsyncMock
        ) as mock_search:
            mock_search.return_value = search_results

            service = MemoryService(db)
            entries = await service.get_relevant_memories(
                user_id=user_id,
                query="What are my programming preferences?",
                top_k=3,
            )

        assert len(entries) == 2
        assert isinstance(entries[0], MemoryEntry)
        assert entries[0].content == "I prefer Python over Java."
        assert entries[0].memory_type == "preference"
        assert entries[1].content == "I work in healthcare."

    @pytest.mark.asyncio
    async def test_falls_back_to_recent_on_empty_semantic_search(self) -> None:
        """When semantic search returns empty, falls back to recent memories.

        Requirements: 7.2
        """
        user_id = uuid.uuid4()
        db = _make_db_session()

        fallback_results = [
            MemorySearchResult(
                memory_id=uuid.uuid4(),
                chroma_id=str(uuid.uuid4()),
                content="Fallback memory content.",
                memory_type="fact",
                relevance_score=1.0,
            )
        ]

        with (
            patch.object(
                MemoryRepository, "search_memories", new_callable=AsyncMock
            ) as mock_search,
            patch.object(
                MemoryRepository, "get_recent_memories", new_callable=AsyncMock
            ) as mock_recent,
        ):
            mock_search.return_value = []
            mock_recent.return_value = fallback_results

            service = MemoryService(db)
            entries = await service.get_relevant_memories(
                user_id=user_id,
                query="Some query",
                top_k=3,
            )

        assert len(entries) == 1
        assert entries[0].content == "Fallback memory content."
        mock_recent.assert_called_once_with(user_id=user_id, top_k=3)

    @pytest.mark.asyncio
    async def test_returns_empty_list_on_retrieval_failure(self) -> None:
        """If memory retrieval raises an exception, returns empty list (graceful degradation).

        Requirements: 7.2
        """
        user_id = uuid.uuid4()
        db = _make_db_session()

        with patch.object(
            MemoryRepository,
            "search_memories",
            side_effect=RuntimeError("ChromaDB connection refused"),
        ):
            service = MemoryService(db)
            entries = await service.get_relevant_memories(
                user_id=user_id,
                query="Some query",
                top_k=3,
            )

        assert entries == []

    @pytest.mark.asyncio
    async def test_top_k_three_at_most_three_memories(self) -> None:
        """get_relevant_memories with top_k=3 returns at most 3 results.

        Requirements: 7.2
        """
        user_id = uuid.uuid4()
        db = _make_db_session()

        search_results = [
            MemorySearchResult(
                memory_id=uuid.uuid4(),
                chroma_id=str(uuid.uuid4()),
                content=f"Memory {i}",
                memory_type="fact",
                relevance_score=float(i) * 0.1,
            )
            for i in range(5)  # Return 5 but only 3 should be requested
        ]

        with patch.object(
            MemoryRepository, "search_memories", new_callable=AsyncMock
        ) as mock_search:
            # Simulate repository honouring top_k
            mock_search.return_value = search_results[:3]

            service = MemoryService(db)
            entries = await service.get_relevant_memories(
                user_id=user_id,
                query="query",
                top_k=3,
            )

        assert len(entries) == 3
        mock_search.assert_called_once_with(
            user_id=user_id,
            query="query",
            top_k=3,
        )


# ---------------------------------------------------------------------------
# MemoryService — delete_memory
# ---------------------------------------------------------------------------


class TestMemoryServiceDelete:
    """Tests for MemoryService.delete_memory."""

    @pytest.mark.asyncio
    async def test_delete_memory_returns_true_when_found(self) -> None:
        """Deleting an existing memory returns True.

        Requirements: 7.4
        """
        user_id = uuid.uuid4()
        memory_id = uuid.uuid4()
        db = _make_db_session()

        with patch.object(
            MemoryRepository, "delete_memory", new_callable=AsyncMock
        ) as mock_del:
            mock_del.return_value = True

            service = MemoryService(db)
            result = await service.delete_memory(
                memory_id=memory_id,
                user_id=user_id,
            )

        assert result is True
        mock_del.assert_called_once_with(memory_id=memory_id, user_id=user_id)

    @pytest.mark.asyncio
    async def test_delete_memory_returns_false_when_not_found(self) -> None:
        """Deleting a non-existent memory returns False.

        Requirements: 7.4
        """
        user_id = uuid.uuid4()
        memory_id = uuid.uuid4()
        db = _make_db_session()

        with patch.object(
            MemoryRepository, "delete_memory", new_callable=AsyncMock
        ) as mock_del:
            mock_del.return_value = False

            service = MemoryService(db)
            result = await service.delete_memory(
                memory_id=memory_id,
                user_id=user_id,
            )

        assert result is False

    @pytest.mark.asyncio
    async def test_delete_memory_cross_user_returns_false(self) -> None:
        """Attempting to delete another user's memory returns False (access denied).

        Requirements: 7.5
        """
        attacker_user_id = uuid.uuid4()
        victim_memory_id = uuid.uuid4()
        db = _make_db_session()

        with patch.object(
            MemoryRepository, "delete_memory", new_callable=AsyncMock
        ) as mock_del:
            # Simulate the repository returning False because user_id doesn't match
            mock_del.return_value = False

            service = MemoryService(db)
            result = await service.delete_memory(
                memory_id=victim_memory_id,
                user_id=attacker_user_id,
            )

        assert result is False
        # Repository MUST be called with the attacker's user_id (not the victim's)
        mock_del.assert_called_once_with(
            memory_id=victim_memory_id,
            user_id=attacker_user_id,
        )


# ---------------------------------------------------------------------------
# MemoryService — privacy mode toggle
# ---------------------------------------------------------------------------


class TestMemoryServicePrivacyMode:
    """Tests for MemoryService.set_privacy_mode.

    Requirements: 7.6
    """

    @pytest.mark.asyncio
    async def test_set_privacy_mode_true_updates_user(self) -> None:
        """set_privacy_mode(True) updates the user's privacy_mode flag.

        Requirements: 7.6
        """
        user_id = uuid.uuid4()
        user = _make_user(user_id=user_id, privacy_mode=False)
        db = _make_db_session(user=user)

        service = MemoryService(db)
        result = await service.set_privacy_mode(user_id=user_id, privacy_mode=True)

        assert result is True
        assert user.privacy_mode is True

    @pytest.mark.asyncio
    async def test_set_privacy_mode_false_updates_user(self) -> None:
        """set_privacy_mode(False) re-enables memory capture.

        Requirements: 7.6
        """
        user_id = uuid.uuid4()
        user = _make_user(user_id=user_id, privacy_mode=True)
        db = _make_db_session(user=user)

        service = MemoryService(db)
        result = await service.set_privacy_mode(user_id=user_id, privacy_mode=False)

        assert result is True
        assert user.privacy_mode is False

    @pytest.mark.asyncio
    async def test_set_privacy_mode_returns_false_if_user_not_found(self) -> None:
        """set_privacy_mode returns False when the user cannot be found.

        Requirements: 7.6
        """
        user_id = uuid.uuid4()
        db = _make_db_session(user=None)

        service = MemoryService(db)
        result = await service.set_privacy_mode(user_id=user_id, privacy_mode=True)

        assert result is False

    @pytest.mark.asyncio
    async def test_privacy_mode_does_not_delete_existing_memories(self) -> None:
        """Enabling privacy mode must NOT delete any existing memories.

        Requirements: 7.6
        """
        user_id = uuid.uuid4()
        user = _make_user(user_id=user_id, privacy_mode=False)
        db = _make_db_session(user=user)

        service = MemoryService(db)

        with patch.object(
            MemoryRepository, "delete_memory", new_callable=AsyncMock
        ) as mock_del:
            await service.set_privacy_mode(user_id=user_id, privacy_mode=True)

        # delete_memory must never be called during a privacy mode toggle
        mock_del.assert_not_called()


# ---------------------------------------------------------------------------
# MemoryService — list_memories
# ---------------------------------------------------------------------------


class TestMemoryServiceList:
    """Tests for MemoryService.list_memories."""

    @pytest.mark.asyncio
    async def test_list_memories_returns_all_user_memories(self) -> None:
        """list_memories returns all memories for the user.

        Requirements: 7.3
        """
        user_id = uuid.uuid4()
        db = _make_db_session()
        stored_memories = [_make_memory(user_id=user_id) for _ in range(3)]

        with patch.object(
            MemoryRepository, "list_memories", new_callable=AsyncMock
        ) as mock_list:
            mock_list.return_value = stored_memories

            service = MemoryService(db)
            result = await service.list_memories(user_id=user_id)

        assert len(result) == 3
        mock_list.assert_called_once_with(user_id=user_id)

    @pytest.mark.asyncio
    async def test_list_memories_returns_empty_when_no_memories(self) -> None:
        """list_memories returns an empty list when user has no memories.

        Requirements: 7.3
        """
        user_id = uuid.uuid4()
        db = _make_db_session()

        with patch.object(
            MemoryRepository, "list_memories", new_callable=AsyncMock
        ) as mock_list:
            mock_list.return_value = []

            service = MemoryService(db)
            result = await service.list_memories(user_id=user_id)

        assert result == []
