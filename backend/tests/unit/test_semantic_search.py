"""Unit and integration tests for the semantic search endpoint and service.

Covers:
- User scoping: no cross-user results                (Req 36.7)
- Threshold filtering: no results below 0.5 returned (Req 36.3)
- Empty-group omission: absent collection → no group (Req 36.5)
- 3-second response SLA with mock ChromaDB of 100k entries (Req 36.2)

Requirements: 21.1, 21.2, 36.2, 36.3, 36.5, 36.7
"""

from __future__ import annotations

import asyncio
import os
import sys
import time
import types
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

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
        def __init__(self, *args, **kwargs) -> None:
            raise RuntimeError("Real chromadb.HttpClient must not be called in tests.")

    _chroma_stub.HttpClient = _StubHttpClient  # type: ignore[attr-defined]
    sys.modules["chromadb"] = _chroma_stub

# ---------------------------------------------------------------------------
# App imports (after stubs)
# ---------------------------------------------------------------------------
from app.services.search_service import SearchService

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_db() -> AsyncMock:
    """Return a minimal AsyncSession mock."""
    db = AsyncMock()
    db.execute = AsyncMock()
    return db


def _make_mock_collection(
    ids: list[str],
    documents: list[str],
    metadatas: list[dict],
    distances: list[float],
    count: int | None = None,
) -> MagicMock:
    """Build a mock ChromaDB collection that returns the given results."""
    collection = MagicMock()
    collection.count.return_value = count if count is not None else len(ids)
    collection.query.return_value = {
        "ids": [ids],
        "documents": [documents],
        "metadatas": [metadatas],
        "distances": [distances],
    }
    return collection


def _make_mock_chroma_client(collections: dict[str, MagicMock]) -> MagicMock:
    """Build a mock ChromaDB client that returns specific collections by name."""
    client = MagicMock()

    def _get_collection(name: str) -> MagicMock:
        if name not in collections:
            raise Exception(f"Collection {name!r} not found")
        return collections[name]

    client.get_collection.side_effect = _get_collection
    return client


def _fake_embedding(dim: int = 384) -> list[float]:
    return [0.1] * dim


def _run(coro):
    """Execute an async coroutine synchronously."""
    return asyncio.run(coro)


# ---------------------------------------------------------------------------
# 1. User scoping — no cross-user results
# ---------------------------------------------------------------------------


class TestUserScoping:
    """Semantic search MUST NOT return results from another user's collections.

    Requirements: 36.7
    """

    def test_only_user_scoped_collections_are_queried(self) -> None:
        """SearchService queries collections named {type}_{user_id}, not other users'.

        We use two different user IDs and verify the service only queries collections
        matching the requesting user's ID.

        Requirements: 36.7
        """
        user_id = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        other_user_id = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        user_note_collection = _make_mock_collection(
            ids=["note1"],
            documents=["User A note content"],
            metadatas=[{"source_name": "My Note", "source_id": "note1"}],
            distances=[0.1],
        )
        other_user_note_collection = _make_mock_collection(
            ids=["other_note1"],
            documents=["Other user note content"],
            metadatas=[{"source_name": "Other Note", "source_id": "other_note1"}],
            distances=[0.05],  # Even higher similarity
        )

        # Collections map: only user A's note collection is registered for notes
        all_collections: dict[str, MagicMock] = {
            f"notes_{user_id}": user_note_collection,
            f"notes_{other_user_id}": other_user_note_collection,
            # Other types don't exist for user A (will raise Exception → skipped)
        }

        mock_client = _make_mock_chroma_client(all_collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(
                    user_id=user_id, query="test query"
                )

        results = _run(_run_search())

        # Only user A's note should appear
        assert all(r.source_name != "Other Note" for r in results), (
            "Cross-user result returned! User scoping violation."
        )

        # Verify no call was made to other user's collections
        queried_collections = [
            call_args[0][0] for call_args in mock_client.get_collection.call_args_list
        ]
        assert not any(str(other_user_id) in name for name in queried_collections), (
            "Other user's collection was queried — scoping violation."
        )
        assert all(str(user_id) in name for name in queried_collections), (
            "Queried collection does not belong to the requesting user."
        )


# ---------------------------------------------------------------------------
# 2. Threshold filtering — no results below 0.5 returned
# ---------------------------------------------------------------------------


class TestThresholdFiltering:
    """Results with cosine similarity < 0.5 (distance > 0.5) must be excluded.

    Requirements: 36.3
    """

    def test_results_below_threshold_are_excluded(self) -> None:
        """Results with distance > 0.5 are filtered out.

        Requirements: 36.3
        """
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=["n1", "n2", "n3"],
            documents=[
                "High relevance doc",
                "Medium relevance doc",
                "Low relevance doc",
            ],
            metadatas=[
                {"source_name": "High Note", "source_id": "n1"},
                {"source_name": "Medium Note", "source_id": "n2"},
                {"source_name": "Low Note", "source_id": "n3"},
            ],
            distances=[0.1, 0.49, 0.51],  # n3 is below threshold (distance > 0.5)
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())

        result_names = [r.source_name for r in results]
        assert "High Note" in result_names, "High relevance result should be included."
        assert "Medium Note" in result_names, (
            "Medium relevance result (distance=0.49) should be included."
        )
        assert "Low Note" not in result_names, (
            "Low relevance result (distance=0.51) must be excluded by threshold filter."
        )

    def test_all_results_above_threshold_have_score_ge_0_5(self) -> None:
        """All returned results must have relevance_score ≥ 0.5.

        Requirements: 36.3
        """
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=[f"n{i}" for i in range(10)],
            documents=[f"Doc {i}" for i in range(10)],
            metadatas=[
                {"source_name": f"Note {i}", "source_id": f"n{i}"} for i in range(10)
            ],
            distances=[i * 0.1 for i in range(10)],  # 0.0, 0.1, ..., 0.9
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())

        # All returned results must have score >= 0.5
        for r in results:
            assert r.relevance_score >= 0.5, (
                f"Result with score {r.relevance_score} below threshold was returned."
            )

    def test_no_results_above_threshold_returns_empty_list(self) -> None:
        """When all results are below threshold, an empty list is returned.

        Requirements: 36.3, 36.4
        """
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=["n1", "n2"],
            documents=["Irrelevant doc 1", "Irrelevant doc 2"],
            metadatas=[
                {"source_name": "Note 1", "source_id": "n1"},
                {"source_name": "Note 2", "source_id": "n2"},
            ],
            distances=[0.6, 0.8],  # Both below threshold
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())
        assert results == [], f"Expected empty list but got {len(results)} results."


# ---------------------------------------------------------------------------
# 3. Empty-group omission
# ---------------------------------------------------------------------------


class TestEmptyGroupOmission:
    """Collections that don't exist or have 0 embeddings must be omitted.

    Requirements: 36.5
    """

    def test_nonexistent_collection_is_skipped(self) -> None:
        """Collections that raise an exception on get_collection are skipped.

        Requirements: 36.5
        """
        user_id = uuid.uuid4()
        # Only the notes collection exists; all others raise
        note_collection = _make_mock_collection(
            ids=["n1"],
            documents=["Note content"],
            metadatas=[{"source_name": "My Note", "source_id": "n1"}],
            distances=[0.2],
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())

        # Should only get notes results; no errors from missing collections
        assert all(r.source_type == "note" for r in results), (
            "Results from non-existent collections should not appear."
        )

    def test_empty_collection_is_skipped(self) -> None:
        """Collections with count=0 are skipped without querying.

        Requirements: 36.5
        """
        user_id = uuid.uuid4()
        empty_collection = MagicMock()
        empty_collection.count.return_value = 0  # Empty

        collections = {
            f"notes_{user_id}": empty_collection,
        }
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())

        # Empty collection → no results, and query should not be called
        assert results == [], "Empty collection should yield no results."
        empty_collection.query.assert_not_called()

    def test_source_types_with_no_results_are_absent_from_output(self) -> None:
        """Source types with zero qualifying results are absent from the result list.

        Requirements: 36.5
        """
        user_id = uuid.uuid4()

        # Notes has a result; all other collections don't exist
        note_collection = _make_mock_collection(
            ids=["n1"],
            documents=["Relevant note"],
            metadatas=[{"source_name": "Note", "source_id": "n1"}],
            distances=[0.15],
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())

        source_types_present = {r.source_type for r in results}
        assert "note" in source_types_present
        # conversation, document, memory should NOT be present
        assert "conversation" not in source_types_present
        assert "document" not in source_types_present
        assert "memory" not in source_types_present


# ---------------------------------------------------------------------------
# 4. 3-second response SLA with mock ChromaDB returning 100,000 entries
# ---------------------------------------------------------------------------


class TestResponseSLA:
    """Semantic search must respond within 3 seconds for corpus ≤ 100,000 embeddings.

    Requirements: 36.2
    """

    def test_response_within_3_seconds_with_100k_entries(self) -> None:
        """With 100,000 mock ChromaDB entries, semantic_search completes within 3 s.

        The mock collection simulates 100,000 entries but returns only the top-N
        results (as a real ChromaDB query would). The embedding model is also mocked.

        Requirements: 36.2
        """
        user_id = uuid.uuid4()
        n = 50  # Simulated top-N returned by ChromaDB

        # Build mock results: 50 results all above threshold
        ids = [f"doc{i}" for i in range(n)]
        documents = [
            f"Document content number {i} with some meaningful text." for i in range(n)
        ]
        metadatas = [
            {"source_name": f"Doc {i}", "source_id": f"doc{i}"} for i in range(n)
        ]
        distances = [0.01 + (i * 0.001) for i in range(n)]  # All well within threshold

        # Collection with 100,000 count but only returns top-50
        note_collection = _make_mock_collection(
            ids=ids,
            documents=documents,
            metadatas=metadatas,
            distances=distances,
            count=100_000,
        )

        collections = {
            f"notes_{user_id}": note_collection,
            # Other collections not present — skipped
        }
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(
                    user_id=user_id, query="test query"
                )

        start_time = time.perf_counter()
        results = _run(_run_search())
        elapsed = time.perf_counter() - start_time

        assert elapsed < 3.0, (
            f"Semantic search exceeded 3-second SLA with 100k mock entries: {elapsed:.3f}s"
        )
        assert len(results) == n, f"Expected {n} results, got {len(results)}."

    def test_relevance_score_is_rounded_to_2_decimal_places(self) -> None:
        """Relevance scores must be rounded to exactly 2 decimal places.

        Requirements: 36.2
        """
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=["n1"],
            documents=["Test doc"],
            metadatas=[{"source_name": "Test Note", "source_id": "n1"}],
            distances=[0.123456789],  # Distance that needs rounding
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())
        assert len(results) == 1
        score = results[0].relevance_score
        # round(1.0 - 0.123456789, 2) = round(0.876543211, 2) = 0.88
        assert score == round(1.0 - 0.123456789, 2), (
            f"Expected score {round(1.0 - 0.123456789, 2)}, got {score}"
        )
        # Verify it has at most 2 decimal places
        assert score == round(score, 2), (
            f"Score {score} has more than 2 decimal places."
        )

    def test_excerpt_truncated_to_300_chars(self) -> None:
        """Excerpt must be truncated to 300 characters.

        Requirements: 36.2, 36.3
        """
        long_doc = "A" * 500  # 500 chars
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=["n1"],
            documents=[long_doc],
            metadatas=[{"source_name": "Long Note", "source_id": "n1"}],
            distances=[0.1],
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())
        assert len(results) == 1
        assert len(results[0].excerpt) <= 300, (
            f"Excerpt exceeds 300 chars: {len(results[0].excerpt)} chars."
        )

    def test_deep_link_format_is_correct(self) -> None:
        """Deep links must follow the aiassistant://{type}s/{id} format.

        Requirements: 36.2
        """
        user_id = uuid.uuid4()
        note_collection = _make_mock_collection(
            ids=["note-abc-123"],
            documents=["Note content"],
            metadatas=[{"source_name": "My Note", "source_id": "note-abc-123"}],
            distances=[0.2],
        )

        collections = {f"notes_{user_id}": note_collection}
        mock_client = _make_mock_chroma_client(collections)
        mock_model = MagicMock()
        mock_model.encode.return_value = [_fake_embedding()]

        db = _make_db()
        service = SearchService(db)

        async def _run_search():
            with (
                patch("chromadb.HttpClient", return_value=mock_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.semantic_search(user_id=user_id, query="test")

        results = _run(_run_search())
        assert len(results) == 1
        assert results[0].deep_link == "aiassistant://notes/note-abc-123", (
            f"Unexpected deep link: {results[0].deep_link}"
        )
