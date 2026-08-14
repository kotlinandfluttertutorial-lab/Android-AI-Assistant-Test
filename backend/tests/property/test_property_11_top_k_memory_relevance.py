"""Property-based tests for top-K memory relevance.

Property 11: Top-K Memory Relevance
**Validates: Requirements 7.2**

Strategy:
  - Generate user messages and memory stores with known relevance scores.
  - Mock MemoryRepository.search_memories to return a controlled set of results.
  - Assert injected memories are the top-3 by relevance score.
  - Assert no memory from another user appears in the result set.

Assertions:
  - Result length == min(N, 3) for any N memories (11A)
  - Returned entries are the top-3 highest-relevance items (11B)
  - Cross-user contamination is absent: user B's results contain no user A content (11C)
  - Graceful degradation on exception: returns [] without propagating (11D)

Requirements: 7.2
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

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# N memories count: 0 to 10
_n_memories_strategy = st.integers(min_value=0, max_value=10)

# For property 11B: 5 to 10 memories with distinct relevance scores
_distinct_relevance_strategy = st.lists(
    st.floats(min_value=0.01, max_value=1.0, allow_nan=False, allow_infinity=False),
    min_size=5,
    max_size=10,
    unique=True,
)

# Two distinct UUIDs for cross-user contamination test
_distinct_users_strategy = st.fixed_dictionaries(
    {
        "user_a": st.uuids(),
        "user_b": st.uuids(),
    }
).filter(lambda d: d["user_a"] != d["user_b"])

# Query text
_query_text_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=100,
)


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


def _make_search_results(n: int, *, base_score: float = 0.9) -> list:
    """Build N MemorySearchResult objects with distinct descending relevance scores."""
    from app.repositories.memory_repository import MemorySearchResult

    return [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=f"memory content index {i} unique-{uuid.uuid4().hex[:8]}",
            memory_type="fact",
            relevance_score=round(base_score - i * 0.05, 4),
        )
        for i in range(n)
    ]


# ===========================================================================
# Property 11A — Top-K count enforcement
# **Validates: Requirements 7.2**
# ===========================================================================


@given(n=_n_memories_strategy, query_text=_query_text_strategy)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_11a_top_k_count_enforcement(n: int, query_text: str) -> None:
    """**Validates: Requirements 7.2**

    Property 11A: For any N memories in the store, get_relevant_memories with
    top_k=3 must return exactly min(N, 3) entries — never more than top_k.
    """
    from app.services.memory_service import MemoryService

    user_id = uuid.uuid4()
    all_results = _make_search_results(n)

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        # search_memories returns all N results (sorted best-first already)
        mock_search = AsyncMock(return_value=all_results)

        with patch.object(service._repo, "search_memories", mock_search):
            return await service.get_relevant_memories(
                user_id=user_id,
                query=query_text,
                top_k=3,
            )

    result = _run_async(_run())
    expected_count = min(n, 3)

    assert len(result) == expected_count, (
        f"Property 11A violated: expected {expected_count} results for N={n}, "
        f"got {len(result)}."
    )


# ===========================================================================
# Property 11B — Top-3 are the highest-relevance entries
# **Validates: Requirements 7.2**
# ===========================================================================


@given(scores=_distinct_relevance_strategy, query_text=_query_text_strategy)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_11b_top_3_highest_relevance_entries(
    scores: list[float],
    query_text: str,
) -> None:
    """**Validates: Requirements 7.2**

    Property 11B: When search_memories returns N >= 5 memories with distinct
    relevance scores sorted descending, the 3 returned entries must each have a
    relevance_score >= every non-returned entry's score.
    """
    from app.repositories.memory_repository import MemorySearchResult
    from app.services.memory_service import MemoryService

    user_id = uuid.uuid4()

    # Sort scores descending — highest relevance first (simulates search result ordering)
    sorted_scores = sorted(scores, reverse=True)

    search_results = [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=f"memory for score {score:.4f} uid {uuid.uuid4().hex[:6]}",
            memory_type="preference",
            relevance_score=score,
        )
        for score in sorted_scores
    ]

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        mock_search = AsyncMock(return_value=search_results)

        with patch.object(service._repo, "search_memories", mock_search):
            return await service.get_relevant_memories(
                user_id=user_id,
                query=query_text,
                top_k=3,
            )

    result = _run_async(_run())

    # There are len(scores) >= 5 entries; top_k=3, so there are non-returned entries
    assert len(result) == 3, (
        f"Property 11B violated: expected 3 results, got {len(result)}."
    )

    returned_scores = {entry.relevance_score for entry in result}
    all_scores_set = set(sorted_scores)
    non_returned_scores = all_scores_set - returned_scores

    # Every returned score must be >= every non-returned score
    min_returned = min(returned_scores)
    max_non_returned = (
        max(non_returned_scores) if non_returned_scores else float("-inf")
    )

    assert min_returned >= max_non_returned, (
        f"Property 11B violated: a non-returned entry has a higher relevance score "
        f"than a returned entry. "
        f"min_returned={min_returned}, max_non_returned={max_non_returned}."
    )


# ===========================================================================
# Property 11C — Cross-user contamination absence
# **Validates: Requirements 7.2**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_11c_cross_user_contamination_absence(
    users: dict,
    query_text: str,
) -> None:
    """**Validates: Requirements 7.2**

    Property 11C: When two distinct users exist, calling get_relevant_memories
    for user A must return only user A's memories — none of user B's content
    must appear in the result set.
    """
    from app.repositories.memory_repository import MemorySearchResult
    from app.services.memory_service import MemoryService

    user_a_id: uuid.UUID = users["user_a"]
    user_b_id: uuid.UUID = users["user_b"]

    # Build distinct memory results per user with identifiable content
    user_a_results = [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=f"user_a_exclusive_{user_a_id.hex[:8]}_item_{i}",
            memory_type="fact",
            relevance_score=0.9 - i * 0.1,
        )
        for i in range(3)
    ]
    user_b_results = [
        MemorySearchResult(
            memory_id=uuid.uuid4(),
            chroma_id=str(uuid.uuid4()),
            content=f"user_b_exclusive_{user_b_id.hex[:8]}_item_{i}",
            memory_type="fact",
            relevance_score=0.95 - i * 0.1,
        )
        for i in range(3)
    ]

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        # search_memories is scoped per user_id
        async def _mock_search(user_id: uuid.UUID, query: str, top_k: int = 3):
            if user_id == user_a_id:
                return user_a_results[:top_k]
            if user_id == user_b_id:
                return user_b_results[:top_k]
            return []

        with patch.object(service._repo, "search_memories", side_effect=_mock_search):
            return await service.get_relevant_memories(
                user_id=user_a_id,
                query=query_text,
                top_k=3,
            )

    result = _run_async(_run())

    result_contents = [entry.content for entry in result]

    # Verify none of user B's content appears in user A's results
    for b_entry in user_b_results:
        assert b_entry.content not in result_contents, (
            f"Property 11C violated: user B's memory content found in user A's results. "
            f"leaked_content={b_entry.content!r}, "
            f"user_a={user_a_id}, user_b={user_b_id}."
        )

    # Verify user A only receives her own content
    for entry in result:
        assert f"user_a_exclusive_{user_a_id.hex[:8]}" in entry.content, (
            f"Property 11C violated: unexpected content in user A's results: "
            f"{entry.content!r}."
        )


# ===========================================================================
# Property 11D — Graceful degradation on exception
# **Validates: Requirements 7.2**
# ===========================================================================


@given(query_text=_query_text_strategy)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_11d_graceful_degradation_on_exception(query_text: str) -> None:
    """**Validates: Requirements 7.2**

    Property 11D: When both search_memories and get_recent_memories raise an
    exception, get_relevant_memories must return [] without propagating any
    exception to the caller.
    """
    from app.services.memory_service import MemoryService

    user_id = uuid.uuid4()

    async def _run():
        mock_db = _make_mock_db()
        service = MemoryService(mock_db)

        # Both repo methods raise
        mock_search = AsyncMock(side_effect=RuntimeError("ChromaDB unavailable"))
        mock_recent = AsyncMock(side_effect=RuntimeError("PostgreSQL unavailable"))

        with (
            patch.object(service._repo, "search_memories", mock_search),
            patch.object(service._repo, "get_recent_memories", mock_recent),
        ):
            return await service.get_relevant_memories(
                user_id=user_id,
                query=query_text,
                top_k=3,
            )

    try:
        result = _run_async(_run())
    except Exception as exc:
        raise AssertionError(
            f"Property 11D violated: get_relevant_memories propagated an exception "
            f"instead of returning []. exception={exc!r}"
        ) from exc

    assert result == [], (
        f"Property 11D violated: expected [] on exception, got {result!r}."
    )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestTopKMemoryRelevanceEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_n0_memories_returns_empty_list(self) -> None:
        """N=0 memories → get_relevant_memories returns []."""
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            mock_search = AsyncMock(return_value=[])
            mock_recent = AsyncMock(return_value=[])

            with (
                patch.object(service._repo, "search_memories", mock_search),
                patch.object(service._repo, "get_recent_memories", mock_recent),
            ):
                return await service.get_relevant_memories(
                    user_id=user_id, query="anything", top_k=3
                )

        result = _run_async(_run())
        assert result == [], f"N=0 edge case failed: expected [], got {result!r}."

    def test_n2_memories_returns_exactly_2(self) -> None:
        """N=2 memories → get_relevant_memories returns exactly 2 entries."""
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()
        two_results = _make_search_results(2)

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            mock_search = AsyncMock(return_value=two_results)

            with patch.object(service._repo, "search_memories", mock_search):
                return await service.get_relevant_memories(
                    user_id=user_id, query="anything", top_k=3
                )

        result = _run_async(_run())
        assert len(result) == 2, (
            f"N=2 edge case failed: expected 2 results, got {len(result)}."
        )

    def test_n5_memories_returns_exactly_3(self) -> None:
        """N=5 memories → get_relevant_memories returns exactly 3 (top_k limit)."""
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()
        five_results = _make_search_results(5)

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            mock_search = AsyncMock(return_value=five_results)

            with patch.object(service._repo, "search_memories", mock_search):
                return await service.get_relevant_memories(
                    user_id=user_id, query="anything", top_k=3
                )

        result = _run_async(_run())
        assert len(result) == 3, (
            f"N=5 edge case failed: expected 3 results (top_k), got {len(result)}."
        )

    def test_top_k_1_returns_exactly_1_memory(self) -> None:
        """top_k=1 → get_relevant_memories returns exactly 1 memory."""
        from app.services.memory_service import MemoryService

        user_id = uuid.uuid4()
        five_results = _make_search_results(5)

        async def _run():
            mock_db = _make_mock_db()
            service = MemoryService(mock_db)

            mock_search = AsyncMock(return_value=five_results[:1])

            with patch.object(service._repo, "search_memories", mock_search):
                return await service.get_relevant_memories(
                    user_id=user_id, query="anything", top_k=1
                )

        result = _run_async(_run())
        assert len(result) == 1, (
            f"top_k=1 edge case failed: expected 1 result, got {len(result)}."
        )
