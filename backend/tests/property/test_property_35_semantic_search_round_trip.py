"""Property-based tests for semantic search round-trip retrieval.

Property 35: Semantic Search Round-Trip
**Validates: Requirements 36.6**

Strategy:
  - Generate text excerpts of ≥10 words.
  - Store each excerpt as a content embedding in one of the four collections
    (conversations, notes, documents, memories) via a mock ChromaDB.
  - Submit the exact excerpt as the search query.
  - Assert the result referencing the originating item has a relevance score ≥ 0.90.

Design notes
------------
The property targets the *service layer* — the SearchService's query-and-filter logic.
All storage dependencies (ChromaDB, PostgreSQL/SQLAlchemy, SentenceTransformer) are
mocked so the test is deterministic, fast, and requires no external services.

The chromadb stub is injected into sys.modules before any app imports (same pattern
as test_property_4_rag_round_trip_retrieval.py).

Requirements: 36.6
"""

from __future__ import annotations

import asyncio
import os
import sys
import types
import uuid
from unittest.mock import MagicMock, patch

# ---------------------------------------------------------------------------
# Environment variables BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

# ---------------------------------------------------------------------------
# Inject lightweight chromadb stub into sys.modules BEFORE app imports
# ---------------------------------------------------------------------------
if "chromadb" not in sys.modules:
    _chroma_stub = types.ModuleType("chromadb")

    class _StubHttpClient:
        """Stub for chromadb.HttpClient — replaced by MagicMock in tests."""

        def __init__(self, *args, **kwargs) -> None:
            raise RuntimeError("Real chromadb.HttpClient must not be called in tests.")

    _chroma_stub.HttpClient = _StubHttpClient  # type: ignore[attr-defined]
    sys.modules["chromadb"] = _chroma_stub

from unittest.mock import AsyncMock

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.services.search_service import SearchService

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Minimum relevance score for round-trip retrieval (Property 35)
_ROUND_TRIP_MIN_SCORE = 0.90

# ChromaDB distance that corresponds to a 0.90 relevance score
# relevance_score = round(1.0 - distance, 2) ≥ 0.90  →  distance ≤ 0.10
_ROUND_TRIP_MAX_DISTANCE = 0.10


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Word strategy: at least 3-char alphanumeric words
_word_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N")),
    min_size=3,
    max_size=15,
).filter(lambda w: len(w) >= 3)

# Excerpt strategy: at least 10 words (fulfils "≥10 words" requirement)
_excerpt_strategy = (
    st.lists(
        _word_strategy,
        min_size=10,
        max_size=30,
    )
    .map(lambda words: " ".join(words))
    .filter(lambda s: len(s.split()) >= 10)
)

# Source type strategy: one of the four supported content types
_source_type_strategy = st.sampled_from(["conversation", "note", "document", "memory"])

# Source ID strategy: simple UUID-like string
_source_id_strategy = st.from_regex(
    r"[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}", fullmatch=True
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _fake_embedding(dim: int = 384) -> list[float]:
    """Return a fixed fake embedding vector."""
    return [0.1] * dim


def _make_db() -> AsyncMock:
    """Return a minimal AsyncSession mock."""
    db = AsyncMock()
    db.execute = AsyncMock()
    return db


def _build_chroma_mock(
    user_id: uuid.UUID,
    source_type: str,
    source_id: str,
    excerpt: str,
    distance: float,
) -> MagicMock:
    """Build a ChromaDB mock that returns the given excerpt with the given distance.

    This simulates the ChromaDB round-trip: when queried with the exact excerpt,
    it returns the stored item with high similarity (low distance).
    """
    collection_name_map = {
        "conversation": f"conversations_{user_id}",
        "note": f"notes_{user_id}",
        "document": f"documents_{user_id}",
        "memory": f"memories_{user_id}",
    }
    target_collection_name = collection_name_map[source_type]

    # Mock collection that returns the stored excerpt
    target_collection = MagicMock()
    target_collection.count.return_value = 1
    target_collection.query.return_value = {
        "ids": [[source_id]],
        "documents": [[excerpt]],
        "metadatas": [
            [
                {
                    "source_name": f"{source_type.capitalize()} {source_id[:8]}",
                    "source_id": source_id,
                }
            ]
        ],
        "distances": [[distance]],
    }

    # Other collections don't exist (will raise → skipped)
    def _get_collection(name: str) -> MagicMock:
        if name == target_collection_name:
            return target_collection
        raise Exception(f"Collection {name!r} not found")

    client = MagicMock()
    client.get_collection.side_effect = _get_collection
    return client


def _run_round_trip(
    user_id: uuid.UUID,
    source_type: str,
    source_id: str,
    excerpt: str,
    simulated_distance: float,
) -> list:
    """Run a semantic search round-trip: store excerpt → query with exact text.

    The ChromaDB mock returns the stored item with the given simulated distance,
    representing the similarity between the query (exact excerpt) and the stored item.

    Returns the list of SemanticSearchResultItem objects.
    """
    mock_client = _build_chroma_mock(
        user_id=user_id,
        source_type=source_type,
        source_id=source_id,
        excerpt=excerpt,
        distance=simulated_distance,
    )
    mock_model = MagicMock()
    mock_model.encode.return_value = [_fake_embedding()]

    db = _make_db()
    service = SearchService(db)

    async def _do_search():
        with (
            patch("chromadb.HttpClient", return_value=mock_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.semantic_search(user_id=user_id, query=excerpt)

    return asyncio.run(_do_search())


# ===========================================================================
# Property 35 — Semantic Search Round-Trip
# **Validates: Requirements 36.6**
# ===========================================================================


@given(
    excerpt=_excerpt_strategy,
    source_type=_source_type_strategy,
    source_id=_source_id_strategy,
)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_35_semantic_search_round_trip(
    excerpt: str,
    source_type: str,
    source_id: str,
) -> None:
    """**Validates: Requirements 36.6**

    Property 35: Semantic Search Round-Trip

    For any text excerpt of ≥10 words stored as a content embedding, submitting that
    exact excerpt as a search query MUST satisfy:

    1. At least one result is returned.
    2. The result referencing the originating item has a relevance score ≥ 0.90.

    This validates that the search service correctly surfaces exact-match content
    with high confidence, satisfying the round-trip retrieval guarantee.

    The excerpt generator always produces strings of ≥10 space-separated words.
    ChromaDB and the embedding model are mocked with an exact-match distance of 0.05
    (relevance = 0.95), simulating the high similarity expected when querying with
    the exact stored text.
    """
    # Verify minimum 10 words (test setup invariant)
    word_count = len(excerpt.split())
    assert (
        word_count >= 10
    ), f"Setup error: excerpt has fewer than 10 words: {word_count}"

    user_id = uuid.uuid4()

    # Simulate high similarity (exact text query): distance = 0.05 → score = 0.95
    simulated_distance = 0.05

    results = _run_round_trip(
        user_id=user_id,
        source_type=source_type,
        source_id=source_id,
        excerpt=excerpt,
        simulated_distance=simulated_distance,
    )

    # -----------------------------------------------------------------------
    # Assertion 1: at least one result is returned
    # -----------------------------------------------------------------------
    assert len(results) > 0, (
        f"Property 35 violated: no results returned for exact-match query. "
        f"excerpt={excerpt[:60]!r}, source_type={source_type!r}"
    )

    # -----------------------------------------------------------------------
    # Assertion 2: the originating item has relevance score ≥ 0.90
    # -----------------------------------------------------------------------
    originating_results = [r for r in results if source_id in r.deep_link]
    assert len(originating_results) > 0, (
        f"Property 35 violated: originating item not found in results. "
        f"source_id={source_id!r}, deep_links={[r.deep_link for r in results]!r}"
    )

    best_score = max(r.relevance_score for r in originating_results)
    assert best_score >= _ROUND_TRIP_MIN_SCORE, (
        f"Property 35 violated: relevance score {best_score} is below minimum "
        f"{_ROUND_TRIP_MIN_SCORE} for exact-match query. "
        f"excerpt={excerpt[:60]!r}, source_type={source_type!r}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the Hypothesis property test)
# ===========================================================================


class TestSemanticSearchRoundTripEdgeCases:
    """Deterministic edge cases for Property 35."""

    def test_minimum_10_word_excerpt_round_trip(self) -> None:
        """Exact 10-word excerpt must be retrievable with score ≥ 0.90."""
        excerpt = "the quick brown fox jumps over the lazy sleeping dog"
        assert len(excerpt.split()) == 10

        user_id = uuid.uuid4()
        results = _run_round_trip(
            user_id=user_id,
            source_type="note",
            source_id="note-min-10",
            excerpt=excerpt,
            simulated_distance=0.05,
        )

        assert len(results) > 0, "10-word excerpt should produce at least one result."
        assert (
            results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE
        ), f"Score {results[0].relevance_score} below minimum {_ROUND_TRIP_MIN_SCORE}."

    def test_conversation_source_type_round_trip(self) -> None:
        """Round-trip must work for conversation source type."""
        excerpt = "this is a sample conversation about machine learning and artificial intelligence"
        user_id = uuid.uuid4()

        results = _run_round_trip(
            user_id=user_id,
            source_type="conversation",
            source_id="conv-abc-123",
            excerpt=excerpt,
            simulated_distance=0.05,
        )

        assert len(results) > 0
        assert results[0].source_type == "conversation"
        assert results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE

    def test_document_source_type_round_trip(self) -> None:
        """Round-trip must work for document source type."""
        excerpt = "enterprise document management system with advanced search and retrieval capabilities"
        user_id = uuid.uuid4()

        results = _run_round_trip(
            user_id=user_id,
            source_type="document",
            source_id="doc-xyz-456",
            excerpt=excerpt,
            simulated_distance=0.04,
        )

        assert len(results) > 0
        assert results[0].source_type == "document"
        assert results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE

    def test_memory_source_type_round_trip(self) -> None:
        """Round-trip must work for memory source type."""
        excerpt = "user prefers concise responses and works in software engineering at tech company"
        user_id = uuid.uuid4()

        results = _run_round_trip(
            user_id=user_id,
            source_type="memory",
            source_id="mem-def-789",
            excerpt=excerpt,
            simulated_distance=0.03,
        )

        assert len(results) > 0
        assert results[0].source_type == "memory"
        assert results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE

    def test_boundary_distance_0_10_meets_threshold(self) -> None:
        """Distance exactly at 0.10 boundary should yield score = 0.90 (meets threshold)."""
        excerpt = "boundary case test with exactly ten words here for testing"
        assert len(excerpt.split()) >= 10

        user_id = uuid.uuid4()
        results = _run_round_trip(
            user_id=user_id,
            source_type="note",
            source_id="boundary-note",
            excerpt=excerpt,
            simulated_distance=0.10,  # score = round(1.0 - 0.10, 2) = 0.90
        )

        assert len(results) > 0
        assert (
            results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE
        ), f"Boundary score {results[0].relevance_score} must be ≥ {_ROUND_TRIP_MIN_SCORE}."

    def test_excerpt_with_numeric_tokens_round_trip(self) -> None:
        """Excerpts with numeric tokens are retrievable with score ≥ 0.90."""
        excerpt = (
            "version 3 release candidate build number 42 launched on january 15 2024"
        )
        user_id = uuid.uuid4()

        results = _run_round_trip(
            user_id=user_id,
            source_type="document",
            source_id="doc-numeric",
            excerpt=excerpt,
            simulated_distance=0.06,
        )

        assert len(results) > 0
        assert results[0].relevance_score >= _ROUND_TRIP_MIN_SCORE
