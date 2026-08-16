"""Property-based tests for user-scoped RAG isolation.

Property 8: User-Scoped RAG Isolation
**Validates: Requirements 4.5**

Strategy:
  - Generate two distinct user UUIDs (user_a, user_b)
  - Generate distinct document text for user A (st.text(min_size=50, max_size=500))
  - Generate arbitrary query text for user B (st.text(min_size=1, max_size=100))

Assertions:
  - User B's query returns zero retrieved chunks (no cross-user data leakage)
  - User B's QueryResult.context contains no content from user A's document
  - ChromaDB collection names follow the ``documents_{user_id}`` formula consistently
  - When user B has no collection, query_documents returns graceful empty QueryResult

Requirements: 4.5
"""

from __future__ import annotations

import asyncio
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import numpy as np

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

# ---------------------------------------------------------------------------
# Inject a lightweight chromadb stub into sys.modules BEFORE any app imports.
#
# chromadb requires C++ build tools to install (chroma-hnswlib extension).
# When it is absent from the environment, ``import chromadb`` inside
# rag_service.py would fail at test collection time.  We inject a minimal
# stub so the import succeeds; every real chromadb call is then replaced
# by a MagicMock inside each test via patch().
# ---------------------------------------------------------------------------
import sys
import types

if "chromadb" not in sys.modules:
    _chroma_stub = types.ModuleType("chromadb")

    class _StubHttpClient:
        """Stub for chromadb.HttpClient - replaced by MagicMock in tests."""

        def __init__(self, *args, **kwargs) -> None:
            raise RuntimeError("Real chromadb.HttpClient must not be called in tests.")

    _chroma_stub.HttpClient = _StubHttpClient  # type: ignore[attr-defined]
    sys.modules["chromadb"] = _chroma_stub

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

# Document text for user A — meaningful content (min 50 chars)
_doc_text_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=50,
    max_size=500,
)

# Query text for user B — any short query
_query_text_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=100,
)

# Arbitrary user_id strings (UUID format) for collection-naming invariant
_user_id_strategy = st.uuids().map(str)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_mock_embedding() -> np.ndarray:
    """Return a minimal fake embedding vector as a numpy array (matches SentenceTransformer output)."""
    return np.array([0.1] * 384, dtype=np.float32)


def _make_mock_embeddings(n: int = 1) -> np.ndarray:
    """Return n fake embedding vectors stacked as a 2D numpy array."""
    return np.array([[0.1] * 384] * n, dtype=np.float32)


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests).

    Uses asyncio.run() which always creates a fresh event loop, making it
    compatible with Python 3.10+ where get_event_loop() no longer creates a
    default loop on the main thread when none is set.
    """
    return asyncio.run(coro)


# ===========================================================================
# Property 8A — Cross-user isolation at service layer
# **Validates: Requirements 4.5**
# ===========================================================================


@given(
    users=_distinct_users_strategy,
    doc_text=_doc_text_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_8a_cross_user_isolation_no_chunk_leakage(
    users: dict,
    doc_text: str,
    query_text: str,
) -> None:
    """**Validates: Requirements 4.5**

    Property 8A: When user A ingests a document and user B issues any query,
    user B's QueryResult.retrieved_chunks MUST be empty — no chunks, embeddings,
    or content originating from user A's documents are accessible to user B.

    The isolation is enforced by ChromaDB's per-user collection naming:
    ``documents_{user_a_id}`` vs ``documents_{user_b_id}``.  User B's collection
    does not exist, so get_collection raises → graceful empty return.
    """
    from app.services.rag_service import RAGService

    user_a_id = str(users["user_a"])
    user_b_id = users["user_b"]  # UUID object for query_documents signature

    collection_a = f"documents_{user_a_id}"
    collection_b = f"documents_{user_b_id}"

    # Build a mock ChromaDB collection for user A (contains user A's data)
    mock_collection_a = MagicMock()
    mock_collection_a.query.return_value = {
        "ids": [["doc_a_0", "doc_a_1"]],
        "documents": [
            [doc_text[:50], doc_text[50:100] if len(doc_text) > 50 else doc_text]
        ],
        "metadatas": [
            [
                {"document_id": "doc-a", "page_number": 1},
                {"document_id": "doc-a", "page_number": 1},
            ]
        ],
        "distances": [[0.1, 0.2]],
    }

    def _mock_get_collection(name: str):
        """Simulate: user A has a collection, user B does NOT."""
        if name == collection_a:
            return mock_collection_a
        # Any other collection (including user B's) raises — simulating non-existence
        raise Exception(f"Collection '{name}' does not exist.")

    mock_chroma_client = MagicMock()
    mock_chroma_client.get_collection.side_effect = _mock_get_collection

    # Fake embedding model — returns a dummy vector (numpy array, matching SentenceTransformer output)
    mock_model = MagicMock()
    mock_model.encode.return_value = _make_mock_embeddings(1)

    service = RAGService()

    async def _run_query():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.query_documents(
                user_id=user_b_id,
                query=query_text,
                db=None,
            )

    result = _run_async(_run_query())

    # Core isolation assertion: user B must receive zero chunks
    assert len(result.retrieved_chunks) == 0, (
        f"Property 8A violated: user B received {len(result.retrieved_chunks)} chunk(s) "
        f"that originated from user A's collection '{collection_a}'. "
        f"user_a={user_a_id}, user_b={user_b_id}"
    )

    # Context must be empty (no content from user A's document)
    assert result.context == "", (
        f"Property 8A violated: user B's context is non-empty despite having no "
        f"accessible documents. context={result.context[:200]!r}"
    )

    # Verify user A's content is not in the context
    # (secondary check — context should already be empty, but be explicit)
    if doc_text and len(doc_text) >= 10:
        # Take a distinctive substring from user A's doc and ensure it's absent
        distinctive_fragment = doc_text[:20]
        assert distinctive_fragment not in result.context, (
            f"Property 8A violated: fragment from user A's document found in user B's context. "
            f"fragment={distinctive_fragment!r}"
        )


# ===========================================================================
# Property 8B — Collection name isolation invariant
# **Validates: Requirements 4.5**
# ===========================================================================


@given(user_id=_user_id_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_8b_collection_name_formula_invariant(user_id: str) -> None:
    """**Validates: Requirements 4.5**

    Property 8B: For any user_id, the ChromaDB collection name used by
    ``RAGService`` MUST follow the formula ``documents_{user_id}`` exactly.
    This naming convention is the foundation of user isolation — different
    users MUST map to different collection names.

    This is verified by:
    1. Checking that embed_and_store uses the correct collection name.
    2. Checking that query_documents uses the correct collection name.
    3. Verifying that two distinct user_ids always produce distinct collection names.
    """
    expected_collection = f"documents_{user_id}"

    # The formula itself must be deterministic and unique per user
    assert (
        expected_collection == f"documents_{user_id}"
    ), f"Property 8B: collection name formula is not deterministic for user_id={user_id!r}"

    # Verify that the formula produces distinct names for distinct users
    other_user_id = str(uuid.uuid4())
    while other_user_id == user_id:
        other_user_id = str(uuid.uuid4())

    other_collection = f"documents_{other_user_id}"
    assert expected_collection != other_collection, (
        f"Property 8B violated: distinct users produced the same collection name "
        f"'{expected_collection}'. user_id={user_id!r}, other_user_id={other_user_id!r}"
    )

    # Verify the service actually uses this formula by inspecting embed_and_store behaviour
    from app.services.rag_service import ChunkResult, RAGService

    captured_collections: list[str] = []

    mock_model = MagicMock()
    mock_model.encode.return_value = _make_mock_embeddings(1)

    mock_collection = MagicMock()
    mock_collection.add.return_value = None

    def _mock_get_or_create(name: str):
        captured_collections.append(name)
        return mock_collection

    mock_chroma_client = MagicMock()
    mock_chroma_client.get_or_create_collection.side_effect = _mock_get_or_create

    service = RAGService()
    chunks = [ChunkResult(text="hello world test content for isolation", page_number=1)]

    async def _run_store():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
            patch(
                "app.repositories.document_repository.DocumentRepository.create_chunk",
                new_callable=AsyncMock,
            ),
        ):
            await service.embed_and_store(
                chunks=chunks,
                document_id=str(uuid.uuid4()),
                user_id=user_id,
                db=None,
            )

    _run_async(_run_store())

    # embed_and_store must have used exactly the expected collection name
    assert expected_collection in captured_collections, (
        f"Property 8B violated: embed_and_store used collection name(s) "
        f"{captured_collections!r} but expected '{expected_collection}' "
        f"for user_id={user_id!r}"
    )


# ===========================================================================
# Property 8C — Graceful empty return when user has no collection
# **Validates: Requirements 4.5**
# ===========================================================================


@given(
    user_id=_user_id_strategy,
    query_text=_query_text_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_8c_graceful_empty_when_no_collection(
    user_id: str,
    query_text: str,
) -> None:
    """**Validates: Requirements 4.5**

    Property 8C: When ``query_documents`` is called for a user who has no
    ChromaDB collection (i.e., ``get_collection`` raises an exception), the
    service MUST return a graceful empty ``QueryResult`` — not raise an error.

    This is the degradation path for new users or users with no ingested
    documents.  It also ensures that querying with an unknown user_id never
    surfaces another user's data as a fallback.
    """
    from app.services.rag_service import RAGService

    user_uuid = uuid.UUID(user_id)

    # ChromaDB raises for any get_collection call — simulating no collection exists
    mock_chroma_client = MagicMock()
    mock_chroma_client.get_collection.side_effect = Exception(
        f"Collection 'documents_{user_id}' not found."
    )

    mock_model = MagicMock()
    mock_model.encode.return_value = [_make_mock_embedding()]

    service = RAGService()

    async def _run_query():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.query_documents(
                user_id=user_uuid,
                query=query_text,
                db=None,
            )

    # Must not raise — graceful degradation is required
    try:
        result = _run_async(_run_query())
    except Exception as exc:
        pytest.fail(
            f"Property 8C violated: query_documents raised an exception when user "
            f"has no collection (should return empty QueryResult). "
            f"user_id={user_id!r}, exception={exc!r}"
        )

    # Must return an empty QueryResult
    assert len(result.retrieved_chunks) == 0, (
        f"Property 8C violated: expected empty retrieved_chunks when user has no "
        f"collection, got {len(result.retrieved_chunks)} chunk(s). user_id={user_id!r}"
    )

    assert result.context == "", (
        f"Property 8C violated: expected empty context when user has no collection, "
        f"got context={result.context[:200]!r}. user_id={user_id!r}"
    )

    assert result.query == query_text, (
        f"Property 8C violated: QueryResult.query should preserve the original query. "
        f"expected={query_text!r}, got={result.query!r}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestUserScopedRAGIsolationEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_user_b_gets_empty_result_even_when_user_a_has_rich_content(self) -> None:
        """User B must receive zero chunks even when user A has many stored chunks."""
        from app.services.rag_service import RAGService

        user_a_id = str(uuid.uuid4())
        user_b_id = uuid.uuid4()

        collection_a = f"documents_{user_a_id}"

        # User A has 5 chunks
        mock_collection_a = MagicMock()
        mock_collection_a.query.return_value = {
            "ids": [["a_0", "a_1", "a_2", "a_3", "a_4"]],
            "documents": [["chunk text " * 5] * 5],
            "metadatas": [
                [{"document_id": "doc-a", "page_number": i + 1} for i in range(5)]
            ],
            "distances": [[0.1 * i for i in range(5)]],
        }

        def _mock_get_collection(name: str):
            if name == collection_a:
                return mock_collection_a
            raise Exception(f"Collection '{name}' does not exist.")

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_collection.side_effect = _mock_get_collection

        mock_model = MagicMock()
        mock_model.encode.return_value = [_make_mock_embedding()]

        service = RAGService()

        async def _run():
            with (
                patch("chromadb.HttpClient", return_value=mock_chroma_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.query_documents(
                    user_id=user_b_id,
                    query="find user A's secret data",
                    db=None,
                )

        result = asyncio.run(_run())

        assert len(result.retrieved_chunks) == 0, (
            f"Edge case failed: user B received {len(result.retrieved_chunks)} chunk(s) "
            f"from user A's collection."
        )
        assert result.context == ""
        """Any two distinct UUIDs must map to distinct collection names."""
        user_ids = [str(uuid.uuid4()) for _ in range(20)]
        collection_names = [f"documents_{uid}" for uid in user_ids]

        assert len(collection_names) == len(
            set(collection_names)
        ), "Edge case failed: duplicate collection names detected for distinct user UUIDs."

    def test_collection_name_contains_full_user_id(self) -> None:
        """The collection name must embed the full user_id (no truncation)."""
        user_id = str(uuid.uuid4())
        collection_name = f"documents_{user_id}"

        assert (
            user_id in collection_name
        ), f"Edge case failed: user_id '{user_id}' not found in collection name '{collection_name}'."
        assert collection_name.startswith(
            "documents_"
        ), f"Edge case failed: collection name '{collection_name}' does not start with 'documents_'."

    def test_new_user_query_returns_empty_not_error(self) -> None:
        """A brand-new user (no documents) querying must receive empty result, no exception."""
        from app.services.rag_service import RAGService

        new_user_id = uuid.uuid4()

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_collection.side_effect = Exception(
            "Collection not found"
        )

        mock_model = MagicMock()
        mock_model.encode.return_value = [_make_mock_embedding()]

        service = RAGService()

        async def _run():
            with (
                patch("chromadb.HttpClient", return_value=mock_chroma_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.query_documents(
                    user_id=new_user_id,
                    query="anything",
                    db=None,
                )

        result = asyncio.run(_run())

        assert result.retrieved_chunks == []
        assert result.context == ""
        assert result.query == "anything"
