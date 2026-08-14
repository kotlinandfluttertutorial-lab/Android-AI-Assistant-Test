"""Property-based tests for RAG round-trip verbatim phrase retrieval.

Property 6: RAG Round-Trip — Verbatim Phrase Retrieval
**Validates: Requirements 4.9, 21.5**

Strategy:
  - Generate a random verbatim phrase (8-80 printable characters, no
    leading/trailing whitespace) that can realistically appear inside a document.
  - Construct a text document that contains the verbatim phrase surrounded by
    filler text.
  - Run the real ``RAGService.chunk_text`` to split the document into chunks.
  - Identify which produced chunks contain the verbatim phrase (ground truth).
  - Mock the ChromaDB query step to return exactly those matching chunks.
  - Call ``query_documents`` with the verbatim phrase as the query.
  - Assert that:
    1. At least one retrieved chunk's ``content`` contains the verbatim phrase.
    2. The ``QueryResult.retrieved_chunks`` is non-empty (document is referenced).
    3. The document name in at least one retrieved chunk matches the source document.

Design notes
------------
The property targets the *service layer* - the RAG pipeline's chunking and
retrieval logic - rather than the HTTP routing layer.  All storage dependencies
(ChromaDB, PostgreSQL/SQLAlchemy, SentenceTransformer) are mocked so the test
is deterministic, fast, and requires no external services.

Because ``chromadb`` is an optional heavy dependency that may not be installed
in the test environment (it requires C++ build tools), this module injects a
lightweight stub for ``chromadb`` into ``sys.modules`` before any app imports
so that ``import chromadb`` inside ``rag_service.py`` succeeds without the real
package being present.  Actual chromadb calls are always intercepted by mocks.

Requirements: 4.9, 21.5
"""

from __future__ import annotations

import asyncio
import os
import sys
import types
import uuid
from unittest.mock import MagicMock, patch

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
if "chromadb" not in sys.modules:
    _chroma_stub = types.ModuleType("chromadb")

    class _StubHttpClient:
        """Stub for chromadb.HttpClient - replaced by MagicMock in tests."""

        def __init__(self, *args, **kwargs) -> None:
            raise RuntimeError("Real chromadb.HttpClient must not be called in tests.")

    _chroma_stub.HttpClient = _StubHttpClient  # type: ignore[attr-defined]
    sys.modules["chromadb"] = _chroma_stub

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Verbatim phrase constraints: long enough to be meaningful, short enough to
# always land inside a single 512-token chunk.
_MIN_PHRASE_LEN = 8
_MAX_PHRASE_LEN = 80

# Filler text used to pad the document around the verbatim phrase.
_FILLER = (
    "The enterprise AI assistant provides advanced capabilities for document "
    "analysis, natural language processing, and intelligent retrieval. "
    "Users can upload documents in multiple formats and ask questions about "
    "their contents to receive accurate and cited responses. "
)

# Default chunk settings (per Requirement 4.3)
_DEFAULT_CHUNK_SIZE = 512
_DEFAULT_OVERLAP = 64


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Verbatim phrase: printable ASCII letters, digits, punctuation, and spaces.
# Stripped so there's no leading/trailing whitespace; newlines excluded to
# avoid token-boundary surprises.
_verbatim_phrase_strategy = (
    st.text(
        alphabet=st.characters(
            whitelist_categories=("L", "N", "P"),  # letters, numbers, punctuation
            whitelist_characters=" ",  # allow spaces within the phrase
        ),
        min_size=_MIN_PHRASE_LEN,
        max_size=_MAX_PHRASE_LEN,
    )
    .map(str.strip)
    .filter(lambda s: len(s) >= _MIN_PHRASE_LEN and "\n" not in s and "\r" not in s)
)

# Document name: realistic filename with .txt extension
_document_name_strategy = st.from_regex(r"[a-z][a-z0-9_]{2,15}\.txt", fullmatch=True)

# Filler repetition count
_filler_count_strategy = st.integers(min_value=1, max_value=5)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_document_text(phrase: str, filler_before: int, filler_after: int) -> str:
    """Construct a document that embeds the verbatim phrase in filler text."""
    before = _FILLER * filler_before
    after = _FILLER * filler_after
    return f"{before} {phrase} {after}".strip()


def _make_mock_embeddings(n: int) -> np.ndarray:
    """Return n identical fake 384-dim float32 embedding vectors."""
    return np.array([[0.1] * 384] * n, dtype=np.float32)


def _run_async(coro):
    """Execute an async coroutine synchronously using asyncio.run().

    Uses asyncio.run() which always creates a fresh event loop, making it
    compatible with Python 3.10+ where get_event_loop() no longer creates a
    default loop on the main thread when none is set.
    """
    return asyncio.run(coro)


def _chunks_containing_phrase(chunks: list, phrase: str) -> list:
    """Return the subset of ChunkResult objects whose text contains the phrase."""
    return [c for c in chunks if phrase in c.text]


# ---------------------------------------------------------------------------
# Core round-trip helper used by both the property test and edge cases
# ---------------------------------------------------------------------------


def _run_round_trip(
    phrase: str,
    document_text: str,
    document_name: str,
):
    """Run the full RAG round-trip: chunk -> ingest (mock) -> query (mock).

    Steps:
      1. Use the real RAGService.chunk_text to split the document into chunks.
      2. Identify which chunks contain the verbatim phrase (ground truth).
      3. Mock ChromaDB to return exactly those chunks for the query.
      4. Call query_documents with the verbatim phrase as the query.
      5. Return the QueryResult.

    Returns:
        QueryResult from query_documents.
    """
    from app.services.rag_service import ChunkResult, RAGService

    service = RAGService()
    user_id = uuid.uuid4()
    document_id = str(uuid.uuid4())

    # Step 1 - real chunking (no mocks)
    all_chunks: list[ChunkResult] = service.chunk_text(
        document_text,
        chunk_size=_DEFAULT_CHUNK_SIZE,
        overlap=_DEFAULT_OVERLAP,
    )

    # Step 2 - find which chunks contain the phrase (ground truth for the mock)
    matching_chunks = _chunks_containing_phrase(all_chunks, phrase)

    # Step 3 - build a mock ChromaDB that returns the matching chunks
    # We populate the metadata with document_name so the no-DB query path in
    # query_documents can construct RetrievedChunk objects with the correct name.
    if matching_chunks:
        chroma_ids = [f"{document_id}_{i}" for i in range(len(matching_chunks))]
        chroma_documents = [c.text for c in matching_chunks]
        chroma_metadatas = [
            {
                "document_id": document_id,
                "document_name": document_name,
                "chunk_index": i,
                "page_number": c.page_number,
            }
            for i, c in enumerate(matching_chunks)
        ]
    else:
        # Degenerate case: phrase not found in any chunk (used in edge-case tests)
        chroma_ids = []
        chroma_documents = []
        chroma_metadatas = []

    mock_collection = MagicMock()
    mock_collection.query.return_value = {
        "ids": [chroma_ids],
        "documents": [chroma_documents],
        "metadatas": [chroma_metadatas],
        "distances": [[0.01] * len(chroma_ids)],
    }

    mock_chroma_client = MagicMock()
    mock_chroma_client.get_collection.return_value = mock_collection

    # Fake embedding model - content-agnostic fixed vector
    mock_model = MagicMock()
    mock_model.encode.return_value = _make_mock_embeddings(1)

    async def _run_query():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.query_documents(
                user_id=user_id,
                query=phrase,
                db=None,  # use ChromaDB-only metadata path
            )

    return _run_async(_run_query())


# ===========================================================================
# Property 6 - RAG Round-Trip: Verbatim Phrase Retrieval
# **Validates: Requirements 4.9, 21.5**
# ===========================================================================


@given(
    phrase=_verbatim_phrase_strategy,
    document_name=_document_name_strategy,
    filler_before=_filler_count_strategy,
    filler_after=_filler_count_strategy,
)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_6_rag_round_trip_verbatim_phrase_retrieval(
    phrase: str,
    document_name: str,
    filler_before: int,
    filler_after: int,
) -> None:
    """**Validates: Requirements 4.9, 21.5**

    Property 6: For any valid text document containing a verbatim phrase,
    ingesting the document and then querying with that exact verbatim phrase
    MUST satisfy:

    1. At least one retrieved chunk's ``content`` contains the verbatim phrase.
    2. The ``QueryResult.retrieved_chunks`` list is non-empty (the document is
       referenced in the response).
    3. The ``document_name`` in at least one retrieved chunk matches the source
       document name.

    This validates Requirement 4.9 (RAG round-trip property) and Requirement
    21.5 (round-trip property test requirement).

    The chunking step is real (``RAGService.chunk_text``); ChromaDB and the
    embedding model are mocked so the test is deterministic, fast, and requires
    no external services.
    """
    document_text = _build_document_text(phrase, filler_before, filler_after)

    # Sanity: the phrase must actually appear in the document we built
    assert phrase in document_text, (
        f"Setup error: phrase {phrase!r} not found in constructed document text."
    )

    result = _run_round_trip(phrase, document_text, document_name)

    # -----------------------------------------------------------------------
    # Assertion 1: response is non-empty - document is referenced
    # -----------------------------------------------------------------------
    assert len(result.retrieved_chunks) > 0, (
        f"Property 6 violated: querying the verbatim phrase produced no retrieved "
        f"chunks. phrase={phrase!r}, document={document_name!r}, "
        f"doc_length={len(document_text)}"
    )

    # -----------------------------------------------------------------------
    # Assertion 2: the verbatim phrase appears in at least one retrieved chunk
    # -----------------------------------------------------------------------
    phrase_in_chunk = any(phrase in chunk.content for chunk in result.retrieved_chunks)
    assert phrase_in_chunk, (
        f"Property 6 violated: verbatim phrase not found in any retrieved chunk. "
        f"phrase={phrase!r}, "
        f"chunks={[c.content[:80] for c in result.retrieved_chunks]!r}"
    )

    # -----------------------------------------------------------------------
    # Assertion 3: source document name is referenced in at least one chunk
    # -----------------------------------------------------------------------
    doc_referenced = any(
        chunk.document_name == document_name for chunk in result.retrieved_chunks
    )
    assert doc_referenced, (
        f"Property 6 violated: source document '{document_name}' not referenced "
        f"in any retrieved chunk. "
        f"document_names={[c.document_name for c in result.retrieved_chunks]!r}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the Hypothesis property test)
# ===========================================================================


class TestRAGRoundTripEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property test."""

    def test_short_phrase_in_short_document_single_chunk(self) -> None:
        """A short phrase in a very short document (one chunk) must be retrievable."""
        phrase = "unique identifier phrase"
        document_name = "short_doc.txt"
        document_text = f"Introduction. {phrase} Conclusion."

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Short document should produce at least one retrieved chunk."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase {phrase!r} not found in any chunk of short document."
        )
        assert any(c.document_name == document_name for c in result.retrieved_chunks), (
            f"Document name '{document_name}' not found in retrieved chunks."
        )

    def test_phrase_at_start_of_document(self) -> None:
        """A verbatim phrase at the very start of the document must be retrieved."""
        phrase = "opening statement of document"
        document_name = "start_phrase.txt"
        document_text = f"{phrase} {_FILLER * 2}"

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Phrase at start of document should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase at document start {phrase!r} not found in retrieved chunks."
        )

    def test_phrase_at_end_of_document(self) -> None:
        """A verbatim phrase at the very end of the document must be retrieved."""
        phrase = "final concluding statement"
        document_name = "end_phrase.txt"
        document_text = f"{_FILLER * 2} {phrase}"

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Phrase at end of document should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase at document end {phrase!r} not found in retrieved chunks."
        )

    def test_phrase_spans_overlap_region(self) -> None:
        """A phrase placed near the chunk boundary must still be retrievable.

        We construct a document slightly longer than one chunk so that the phrase
        is placed near the chunk boundary, ensuring it falls in the overlap window
        of at least one chunk.
        """
        import tiktoken

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        phrase = "overlap boundary phrase test"

        # Build text: fill up to chunk_size - 5 tokens, then append phrase
        seed_text = _FILLER * 10
        tokens = enc.encode(seed_text)
        # Take tokens up to chunk_size - 5 and decode back
        prefix_tokens = tokens[: _DEFAULT_CHUNK_SIZE - 5]
        prefix_text = enc.decode(prefix_tokens)
        document_text = f"{prefix_text} {phrase} {_FILLER}"
        document_name = "overlap_test.txt"

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Phrase near chunk boundary should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase near chunk boundary {phrase!r} not found in retrieved chunks."
        )

    def test_multi_word_phrase_with_punctuation(self) -> None:
        """A phrase containing internal punctuation must be retrievable verbatim."""
        phrase = "AI-powered retrieval, version 2.0"
        document_name = "punctuation_test.txt"
        document_text = f"{_FILLER} {phrase} {_FILLER}"

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Phrase with punctuation should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase with punctuation {phrase!r} not found in retrieved chunks."
        )

    def test_phrase_appearing_in_multiple_chunks(self) -> None:
        """A phrase that repeats across the document should appear in >= 1 chunk."""
        phrase = "repeated verbatim phrase"
        # Repeat the phrase many times to force it into multiple chunks
        document_name = "repeated_phrase.txt"
        document_text = f"{phrase} {_FILLER} {phrase} {_FILLER} {phrase}"

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Repeatedly occurring phrase should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Repeated phrase {phrase!r} not found in any retrieved chunk."
        )

    def test_query_result_preserves_original_query(self) -> None:
        """The QueryResult.query field must preserve the original verbatim phrase."""
        phrase = "preserve this exact query text"
        document_name = "query_preserve.txt"
        document_text = f"{_FILLER} {phrase} {_FILLER}"

        result = _run_round_trip(phrase, document_text, document_name)

        assert result.query == phrase, (
            f"QueryResult.query should equal the original phrase. "
            f"expected={phrase!r}, got={result.query!r}"
        )

    def test_long_document_with_phrase_in_middle(self) -> None:
        """A phrase embedded in the middle of a multi-chunk document must be retrieved."""
        phrase = "buried middle section phrase"
        document_name = "long_document.txt"
        # Enough filler to force several chunks before and after the phrase
        document_text = _build_document_text(phrase, filler_before=4, filler_after=4)

        result = _run_round_trip(phrase, document_text, document_name)

        assert len(result.retrieved_chunks) > 0, (
            "Phrase in middle of long document should produce retrieved chunks."
        )
        assert any(phrase in c.content for c in result.retrieved_chunks), (
            f"Phrase in middle of long document {phrase!r} not found in retrieved chunks."
        )
        assert any(c.document_name == document_name for c in result.retrieved_chunks), (
            f"Document name '{document_name}' not found in retrieved chunks."
        )
