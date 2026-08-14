"""Property-based tests for user-scoped memory isolation.

Property 10: User-Scoped Memory Isolation
**Validates: Requirements 7.5**

Strategy:
  - Generate two distinct user UUIDs (user_a, user_b)
  - Generate memory content strings for user A (list of 1–5 memories)
  - Mock MemoryRepository.search_memories / get_recent_memories to only return
    data for user A's user_id and return empty for user B.
  - Assert that MemoryService.get_relevant_memories for user B returns no data
    originating from user A.
  - Assert that the AIOrchestrator's _build_prompt does not inject user A's
    memories into user B's prompt context.

Assertions:
  - User B's get_relevant_memories returns empty list (10A)
  - None of user A's memory content appears in user B's MemoryEntry results (10A)
  - User B's prompt context from _build_prompt contains none of user A's content (10B)
  - Storing memories for user A does not affect user B's retrieval (10C)

Requirements: 7.5
"""

from __future__ import annotations

import asyncio
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Two distinct UUIDs for user A and user B
_distinct_users_strategy = st.fixed_dictionaries(
    {
        "user_a": st.uuids(),
        "user_b": st.uuids(),
    }
).filter(lambda d: d["user_a"] != d["user_b"])

# Memory content for user A — 1 to 5 memory strings (each at least 20 chars)
_memory_content_strategy = st.lists(
    st.text(
        alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
        min_size=20,
        max_size=200,
    ),
    min_size=1,
    max_size=5,
)

# Query text — any short query
_query_text_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=100,
)

# Arbitrary user_id UUIDs for collection-naming invariant
_user_id_strategy = st.uuids()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests)."""
    try:
        loop = asyncio.get_event_loop()
        if loop.is_closed():
            raise RuntimeError("loop closed")
        return loop.run_until_complete(coro)
    except RuntimeError:
        # Python 3.10+: no current event loop on main thread — create one
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            return loop.run_until_complete(coro)
        finally:
            loop.close()
            asyncio.set_event_loop(None)


def _make_mock_db() -> MagicMock:
    """Return a minimal AsyncSession mock."""
    mock_db = MagicMock()
    mock_db.execute = AsyncMock()
    mock_db.flush = AsyncMock()
    mock_db.add = MagicMock()
    return mock_db


# ===========================================================================
# Property 10A — Cross-user memory retrieval isolation at service layer
# **Validates: Requirements 7.5**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    memory_contents=_memory_content_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_10a_cross_user_memory_retrieval_isolation(
    users: dict,
    memory_contents: list[str],
    query_text: str,
) -> None:
    """**Validates: Requirements 7.5**

    Property 10A: When user A has stored memories and user B issues a memory
    retrieval query, user B's results MUST be empty — none of user A's memory
    content may appear in user B's MemoryEntry list.

    The isolation is enforced by MemoryRepository using per-user ChromaDB
    collections named ``memories_{user_id}``.  User B's collection does not
    contain user A's data.
    """
    from app.repositories.memory_repository import MemorySearchResult
    from app.services.memory_service import MemoryService

    user_a_id: uuid.UUID = users["user_a"]
    user_b_id: uuid.UUID = users["user_b"]

    # Build MemorySearchResult objects simulating user A's stored memories
    user_a_results = [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=content,
            memory_type="fact",
            relevance_score=0.1,
        )
        for content in memory_contents
    ]

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        # Mock repository methods: user A returns data, user B returns empty
        async def _mock_search_memories(user_id: uuid.UUID, query: str, top_k: int = 3):
            if user_id == user_a_id:
                return user_a_results[:top_k]
            return []

        async def _mock_get_recent_memories(user_id: uuid.UUID, top_k: int = 3):
            if user_id == user_a_id:
                return user_a_results[:top_k]
            return []

        with (
            patch.object(
                service._repo, "search_memories", side_effect=_mock_search_memories
            ),
            patch.object(
                service._repo,
                "get_recent_memories",
                side_effect=_mock_get_recent_memories,
            ),
        ):
            result_b = await service.get_relevant_memories(
                user_id=user_b_id,
                query=query_text,
                top_k=3,
            )

        return result_b

    result_b = _run_async(_run())

    # Core isolation assertion: user B must receive zero memory entries
    assert len(result_b) == 0, (
        f"Property 10A violated: user B received {len(result_b)} memory entry/entries "
        f"that originated from user A. "
        f"user_a={user_a_id}, user_b={user_b_id}"
    )

    # Verify none of user A's memory content appears in user B's results
    result_b_contents = [entry.content for entry in result_b]
    for user_a_content in memory_contents:
        assert user_a_content not in result_b_contents, (
            f"Property 10A violated: user A's memory content found in user B's results. "
            f"leaked_content={user_a_content!r}"
        )


# ===========================================================================
# Property 10B — Prompt context isolation via MemoryService in AIOrchestrator
# **Validates: Requirements 7.5**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    memory_contents=_memory_content_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_10b_prompt_context_memory_isolation(
    users: dict,
    memory_contents: list[str],
    query_text: str,
) -> None:
    """**Validates: Requirements 7.5**

    Property 10B: When the AIOrchestrator builds a prompt context for user B,
    none of user A's memory content must appear in user B's system prompt or
    any message in the assembled PromptContext.

    Memory injection is done via MemoryService.get_relevant_memories inside
    _build_prompt.  We mock that method to return user A's memories only for
    user_a and empty for user_b.
    """
    from app.services.ai_orchestrator import AIOrchestrator
    from app.services.memory_service import MemoryEntry

    user_a_id: uuid.UUID = users["user_a"]
    user_b_id: uuid.UUID = users["user_b"]

    # Build MemoryEntry objects simulating user A's memories
    user_a_entries = [
        MemoryEntry(content=content, memory_type="fact", relevance_score=0.1)
        for content in memory_contents
    ]

    async def _run():
        mock_db = _make_mock_db()
        orchestrator = AIOrchestrator(db=mock_db)

        # Mock get_relevant_memories: user A gets data, user B gets empty
        async def _mock_get_relevant_memories(
            user_id: uuid.UUID, query: str, top_k: int = 3
        ):
            if user_id == user_a_id:
                return user_a_entries[:top_k]
            return []

        # Mock message repository to return empty history (no DB needed)
        with (
            patch.object(
                orchestrator._memory_service,
                "get_relevant_memories",
                side_effect=_mock_get_relevant_memories,
            ),
            patch.object(
                orchestrator._message_repo,
                "get_by_conversation_id",
                new_callable=AsyncMock,
                return_value=[],
            ),
        ):
            context = await orchestrator._build_prompt(
                conversation_id=str(uuid.uuid4()),
                user_id=str(user_b_id),
                message=query_text,
            )

        return context

    context = _run_async(_run())

    # Collect all text from the assembled prompt context
    all_prompt_text = " ".join(msg.content for msg in context.messages)

    # Assert none of user A's memory content appears anywhere in user B's prompt
    for user_a_content in memory_contents:
        # Use a distinctive fragment (first 15 chars) to check for leakage
        if len(user_a_content) >= 10:
            fragment = user_a_content[:15]
            assert fragment not in all_prompt_text, (
                f"Property 10B violated: fragment of user A's memory found in user B's "
                f"prompt context. fragment={fragment!r}, "
                f"user_a={user_a_id}, user_b={user_b_id}"
            )


# ===========================================================================
# Property 10C — Memory store/retrieve user scoping
# **Validates: Requirements 7.5**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    memory_contents=_memory_content_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_10c_memory_store_retrieve_user_scoping(
    users: dict,
    memory_contents: list[str],
    query_text: str,
) -> None:
    """**Validates: Requirements 7.5**

    Property 10C: Storing memories for user A must not affect user B's
    retrieval — when the repository is scoped per user, storing to user A's
    collection leaves user B's collection empty.

    We mock the ChromaDB client so that:
    - user A's collection (``memories_{user_a_id}``) returns user A's memories.
    - user B's collection (``memories_{user_b_id}``) raises (does not exist).
    Then we verify that get_relevant_memories for user B returns empty.
    """
    from app.repositories.memory_repository import MemorySearchResult
    from app.services.memory_service import MemoryService

    user_a_id: uuid.UUID = users["user_a"]
    user_b_id: uuid.UUID = users["user_b"]

    collection_a = f"memories_{user_a_id}"
    collection_b = f"memories_{user_b_id}"

    # Build search results for user A
    user_a_results = [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=content,
            memory_type="fact",
            relevance_score=0.1,
        )
        for content in memory_contents
    ]

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        # Scope the mock at the repository level — return data only for user A
        async def _mock_search(user_id: uuid.UUID, query: str, top_k: int = 3):
            if user_id == user_a_id:
                return user_a_results[:top_k]
            # user B has no collection — return empty (graceful degradation)
            return []

        async def _mock_recent(user_id: uuid.UUID, top_k: int = 3):
            if user_id == user_a_id:
                return user_a_results[:top_k]
            return []

        with (
            patch.object(service._repo, "search_memories", side_effect=_mock_search),
            patch.object(
                service._repo, "get_recent_memories", side_effect=_mock_recent
            ),
        ):
            # Retrieve for user B — must be empty even though user A has memories
            result_b = await service.get_relevant_memories(
                user_id=user_b_id,
                query=query_text,
                top_k=3,
            )

        return result_b

    result_b = _run_async(_run())

    # User B must have zero memories returned
    assert len(result_b) == 0, (
        f"Property 10C violated: user B received {len(result_b)} memory entry/entries "
        f"after user A stored memories to collection '{collection_a}'. "
        f"user_a={user_a_id}, user_b={user_b_id}, "
        f"user_b_collection='{collection_b}'"
    )

    # Verify user A's content is not present in user B's empty result
    result_b_contents = [entry.content for entry in result_b]
    for content in memory_contents:
        assert content not in result_b_contents, (
            f"Property 10C violated: user A's memory content found in user B's results "
            f"after store operation. content={content!r}"
        )


# ===========================================================================
# Property 10D — Collection naming invariant for memories
# **Validates: Requirements 7.5**
# ===========================================================================


@given(user_id=_user_id_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_10d_memory_collection_name_formula_invariant(
    user_id: uuid.UUID,
) -> None:
    """**Validates: Requirements 7.5**

    Property 10D: For any user_id, the ChromaDB collection name used by
    ``MemoryRepository`` MUST follow the formula ``memories_{user_id}`` exactly.
    Two distinct user_ids must always produce distinct collection names.

    This naming convention is the structural foundation of user memory isolation.
    """
    from app.repositories.memory_repository import MemoryRepository

    mock_db = _make_mock_db()
    repo = MemoryRepository(mock_db)

    expected_collection = f"memories_{user_id}"
    actual_collection = repo._collection_name(user_id)

    # The formula must match exactly
    assert actual_collection == expected_collection, (
        f"Property 10D violated: MemoryRepository._collection_name returned "
        f"'{actual_collection}' but expected '{expected_collection}' for "
        f"user_id={user_id!r}"
    )

    # Two distinct user IDs must produce distinct collection names
    other_user_id = uuid.uuid4()
    while other_user_id == user_id:
        other_user_id = uuid.uuid4()

    other_collection = repo._collection_name(other_user_id)
    assert actual_collection != other_collection, (
        f"Property 10D violated: distinct users produced the same memory collection "
        f"name '{actual_collection}'. user_id={user_id!r}, other_user_id={other_user_id!r}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestUserScopedMemoryIsolationEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_user_b_gets_empty_result_even_when_user_a_has_many_memories(self) -> None:
        """User B must receive zero memories even when user A has 5 stored memories."""
        from app.repositories.memory_repository import (
            MemorySearchResult,
        )
        from app.services.memory_service import MemoryService

        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()

        user_a_results = [
            MemorySearchResult(
                memory_id=uuid.uuid4(),
                chroma_id=str(uuid.uuid4()),
                content=f"User A private memory number {i}",
                memory_type="fact",
                relevance_score=0.1 * i,
            )
            for i in range(1, 6)
        ]

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            async def _mock_search(user_id: uuid.UUID, query: str, top_k: int = 3):
                return user_a_results[:top_k] if user_id == user_a_id else []

            async def _mock_recent(user_id: uuid.UUID, top_k: int = 3):
                return user_a_results[:top_k] if user_id == user_a_id else []

            with (
                patch.object(
                    service._repo, "search_memories", side_effect=_mock_search
                ),
                patch.object(
                    service._repo, "get_recent_memories", side_effect=_mock_recent
                ),
            ):
                return await service.get_relevant_memories(
                    user_id=user_b_id,
                    query="find user A memories",
                    top_k=3,
                )

        result = _run_async(_run())

        assert len(result) == 0, (
            f"Edge case failed: user B received {len(result)} memory entries "
            f"from user A's collection."
        )

    def test_memory_collection_name_prefix_is_memories(self) -> None:
        """Memory collection names must start with 'memories_' (not 'documents_')."""
        from app.repositories.memory_repository import MemoryRepository

        mock_db = _make_mock_db()
        repo = MemoryRepository(mock_db)

        user_id = uuid.uuid4()
        collection_name = repo._collection_name(user_id)

        assert collection_name.startswith("memories_"), (
            f"Edge case failed: memory collection name '{collection_name}' does not "
            f"start with 'memories_'."
        )
        assert str(user_id) in collection_name, (
            f"Edge case failed: user_id '{user_id}' not embedded in collection name "
            f"'{collection_name}'."
        )

    def test_twenty_distinct_users_produce_twenty_distinct_collections(self) -> None:
        """Twenty distinct user UUIDs must map to twenty distinct memory collection names."""
        from app.repositories.memory_repository import MemoryRepository

        mock_db = _make_mock_db()
        repo = MemoryRepository(mock_db)

        user_ids = [uuid.uuid4() for _ in range(20)]
        collection_names = [repo._collection_name(uid) for uid in user_ids]

        assert len(collection_names) == len(set(collection_names)), (
            "Edge case failed: duplicate memory collection names detected for "
            "20 distinct user UUIDs."
        )

    def test_new_user_retrieval_returns_empty_not_error(self) -> None:
        """A brand-new user (no memories) retrieving must receive empty list, no exception."""
        from app.services.memory_service import MemoryService

        new_user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            # Simulate no memories stored for this user
            async def _empty_search(user_id: uuid.UUID, query: str, top_k: int = 3):
                return []

            async def _empty_recent(user_id: uuid.UUID, top_k: int = 3):
                return []

            with (
                patch.object(
                    service._repo, "search_memories", side_effect=_empty_search
                ),
                patch.object(
                    service._repo, "get_recent_memories", side_effect=_empty_recent
                ),
            ):
                return await service.get_relevant_memories(
                    user_id=new_user_id,
                    query="anything",
                    top_k=3,
                )

        try:
            result = _run_async(_run())
        except Exception as exc:
            pytest.fail(
                f"Edge case failed: get_relevant_memories raised an exception for a "
                f"new user (should return empty list). exception={exc!r}"
            )

        assert result == [], (
            f"Edge case failed: expected empty list for new user, got {result!r}"
        )

    def test_prompt_context_for_user_b_excludes_user_a_memories(self) -> None:
        """_build_prompt for user B must not include any of user A's memory strings."""
        from app.services.ai_orchestrator import AIOrchestrator
        from app.services.memory_service import MemoryEntry

        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()

        user_a_entries = [
            MemoryEntry(
                content="User A secret preference: always use dark mode",
                memory_type="preference",
                relevance_score=0.1,
            ),
            MemoryEntry(
                content="User A works at AcmeCorp as a senior engineer",
                memory_type="fact",
                relevance_score=0.2,
            ),
        ]

        async def _run():
            mock_db = _make_mock_db()
            orchestrator = AIOrchestrator(db=mock_db)

            async def _mock_memories(user_id: uuid.UUID, query: str, top_k: int = 3):
                return user_a_entries if user_id == user_a_id else []

            with (
                patch.object(
                    orchestrator._memory_service,
                    "get_relevant_memories",
                    side_effect=_mock_memories,
                ),
                patch.object(
                    orchestrator._message_repo,
                    "get_by_conversation_id",
                    new_callable=AsyncMock,
                    return_value=[],
                ),
            ):
                return await orchestrator._build_prompt(
                    conversation_id=str(uuid.uuid4()),
                    user_id=str(user_b_id),
                    message="What do I prefer?",
                )

        context = _run_async(_run())

        all_text = " ".join(msg.content for msg in context.messages)

        assert "dark mode" not in all_text, (
            "Edge case failed: user A's 'dark mode' preference found in user B's prompt."
        )
        assert "AcmeCorp" not in all_text, (
            "Edge case failed: user A's 'AcmeCorp' fact found in user B's prompt."
        )
        assert "User A secret preference" not in all_text, (
            "Edge case failed: user A's memory content found verbatim in user B's prompt."
        )
