"""Property-based tests for RAG citation completeness.

Property 9: RAG Citation Completeness
**Validates: Requirements 4.7**

Strategy:
  - Generate a list of mock ``RetrievedChunk`` objects, each with a unique
    ``document_name`` (non-empty), ``page_number`` (≥1), and ``content``
    (non-empty text representing a factual claim from that document).
  - Pass those chunks directly to ``_build_context_string`` (the citation
    injection helper) and to ``RAGService._assemble_context`` / ``_format_citations``.
  - Also simulate the full ``query_documents`` flow by mocking ChromaDB so
    that the generated chunks are returned, then assert on the ``QueryResult``.

Assertions (per the spec task 30.7):
  1. **Context string completeness** — every chunk's content appears in the
     assembled context string together with a citation that contains both the
     ``document_name`` and the ``page_number``.
  2. **Citation list completeness** — ``_format_citations`` returns exactly one
     citation dict per chunk; each dict has non-empty ``document_name`` and
     ``page_number ≥ 1``; the ordering matches the chunk ordering.
  3. **QueryResult completeness** — for any set of mock retrieval results
     produced by ``query_documents``, every ``RetrievedChunk`` in
     ``QueryResult.retrieved_chunks`` has a non-empty ``document_name`` and a
     ``page_number ≥ 1``.
  4. **Citation format invariant** — the citation marker
     ``[Source: <document_name>, Page <page_number>]`` is present at least once
     in the context for every retrieved chunk.

Requirements: 4.7
"""

from __future__ import annotations

import asyncio
import os
import re
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

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Document names: non-empty printable ASCII strings that could represent
# real file names (letters, digits, spaces, hyphens, underscores, dots).
_document_name_strategy = (
    st.text(
        alphabet=st.characters(
            whitelist_categories=("L", "N"),
            whitelist_characters="-_. ",
        ),
        min_size=1,
        max_size=80,
    )
    .map(str.strip)
    .filter(lambda s: len(s) >= 1)
)

# Page numbers: positive integers (1-based per the RAG service contract)
_page_number_strategy = st.integers(min_value=1, max_value=9999)

# Chunk content: non-empty text representing a factual claim
_chunk_content_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=5,
    max_size=500,
)

# A single chunk dict (mirrors the fields of RetrievedChunk)
_chunk_dict_strategy = st.fixed_dictionaries(
    {
        "document_name": _document_name_strategy,
        "page_number": _page_number_strategy,
        "content": _chunk_content_strategy,
    }
)

# A list of 1–10 chunks (non-empty list to ensure citation assertions are testable)
_chunk_list_strategy = st.lists(_chunk_dict_strategy, min_size=1, max_size=10)

# Query text (arbitrary short query)
_query_strategy = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=100,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_mock_embeddings(n: int = 1) -> np.ndarray:
    """Return n fake 384-dim embedding vectors (matches SentenceTransformer output)."""
    return np.array([[0.1] * 384] * n, dtype=np.float32)


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests)."""
    return asyncio.run(coro)


def _make_retrieved_chunks(chunk_dicts: list[dict]) -> list:
    """Convert raw dicts to RetrievedChunk instances."""
    from app.services.rag_service import RetrievedChunk

    return [
        RetrievedChunk(
            content=d["content"],
            document_name=d["document_name"],
            page_number=d["page_number"],
        )
        for d in chunk_dicts
    ]


def _citation_marker(document_name: str, page_number: int) -> str:
    """Return the expected citation string for a given chunk."""
    return f"[Source: {document_name}, Page {page_number}]"


# ---------------------------------------------------------------------------
# Property 9A — _build_context_string includes a citation for every chunk
# **Validates: Requirements 4.7**
# ---------------------------------------------------------------------------


@given(query=_query_strategy, chunks=_chunk_list_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_9a_context_string_has_citation_for_every_chunk(
    query: str, chunks: list[dict]
) -> None:
    """**Validates: Requirements 4.7**

    Property 9A: ``_build_context_string`` MUST include, for every retrieved
    chunk, a citation marker of the form ``[Source: <document_name>, Page <page_number>]``
    in the assembled context.

    Every factual claim derived from a retrieved chunk is represented by that
    chunk's content, and the citation marker proves the document name and page
    number are present for each one.
    """
    from app.services.rag_service import _build_context_string

    retrieved_chunks = _make_retrieved_chunks(chunks)
    context = _build_context_string(query, retrieved_chunks)

    for i, chunk_data in enumerate(chunks):
        expected_citation = _citation_marker(
            chunk_data["document_name"], chunk_data["page_number"]
        )

        assert expected_citation in context, (
            f"Property 9A violated for chunk {i}: expected citation "
            f"{expected_citation!r} not found in context.\n"
            f"document_name={chunk_data['document_name']!r}, "
            f"page_number={chunk_data['page_number']}, "
            f"context_snippet={context[:400]!r}"
        )


# ---------------------------------------------------------------------------
# Property 9B — _build_context_string citation contains non-empty document name
# **Validates: Requirements 4.7**
# ---------------------------------------------------------------------------


@given(query=_query_strategy, chunks=_chunk_list_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_9b_context_string_citations_have_nonempty_document_name(
    query: str, chunks: list[dict]
) -> None:
    """**Validates: Requirements 4.7**

    Property 9B: Every citation appearing in the context string MUST contain a
    non-empty document name (i.e. the ``[Source: X, Page N]`` marker must have
    a non-blank X field).

    This catches any path where document_name could silently become empty or
    "unknown" while a citation marker is still emitted.
    """
    from app.services.rag_service import _build_context_string

    retrieved_chunks = _make_retrieved_chunks(chunks)
    context = _build_context_string(query, retrieved_chunks)

    # Extract all citation markers from the context using a regex
    # Pattern matches: [Source: <anything>, Page <digits>]
    citation_pattern = re.compile(r"\[Source:\s*(.*?),\s*Page\s*(\d+)\]")
    found_citations = citation_pattern.findall(context)

    # There must be at least one citation per chunk
    assert len(found_citations) >= len(chunks), (
        f"Property 9B violated: expected at least {len(chunks)} citation(s) "
        f"in context, found {len(found_citations)}.\n"
        f"context_snippet={context[:400]!r}"
    )

    for doc_name, page_num_str in found_citations:
        assert doc_name.strip() != "", (
            f"Property 9B violated: citation has empty document name. "
            f"Found citation: [Source: {doc_name!r}, Page {page_num_str}]\n"
            f"context_snippet={context[:400]!r}"
        )
        assert page_num_str.isdigit() and int(page_num_str) >= 1, (
            f"Property 9B violated: citation has invalid page number {page_num_str!r}. "
            f"Expected a positive integer."
        )


# ---------------------------------------------------------------------------
# Property 9C — _format_citations returns one citation dict per chunk
# **Validates: Requirements 4.7**
# ---------------------------------------------------------------------------


@given(chunks=_chunk_list_strategy)
@settings(
    max_examples=100,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_9c_format_citations_completeness(chunks: list[dict]) -> None:
    """**Validates: Requirements 4.7**

    Property 9C: ``RAGService._format_citations`` MUST return exactly one citation
    dict per chunk.  Each dict MUST contain:
      - ``document_name``: a non-empty string
      - ``page_number``: an integer ≥ 1
      - ``chunk_index``: the zero-based position of the chunk in the result list

    The ordering of the returned citation list MUST match the ordering of the
    input chunk list.
    """
    from app.services.rag_service import RAGService

    retrieved_chunks = _make_retrieved_chunks(chunks)
    service = RAGService()
    citations = service._format_citations(retrieved_chunks)

    assert len(citations) == len(chunks), (
        f"Property 9C violated: _format_citations returned {len(citations)} citation(s) "
        f"for {len(chunks)} chunk(s). Expected a 1:1 correspondence."
    )

    for i, (citation, chunk_data) in enumerate(zip(citations, chunks)):
        # document_name must be present and non-empty
        assert "document_name" in citation, (
            f"Property 9C violated at index {i}: 'document_name' key missing from citation dict."
        )
        assert citation["document_name"] != "", (
            f"Property 9C violated at index {i}: citation has empty document_name. "
            f"chunk_data={chunk_data!r}"
        )

        # page_number must be present and ≥ 1
        assert "page_number" in citation, (
            f"Property 9C violated at index {i}: 'page_number' key missing from citation dict."
        )
        assert isinstance(citation["page_number"], int), (
            f"Property 9C violated at index {i}: page_number is not an int. "
            f"Got {type(citation['page_number']).__name__!r}: {citation['page_number']!r}"
        )
        assert citation["page_number"] >= 1, (
            f"Property 9C violated at index {i}: page_number={citation['page_number']} < 1."
        )

        # chunk_index must match the position in the list
        assert "chunk_index" in citation, (
            f"Property 9C violated at index {i}: 'chunk_index' key missing from citation dict."
        )
        assert citation["chunk_index"] == i, (
            f"Property 9C violated at index {i}: chunk_index={citation['chunk_index']!r}, "
            f"expected {i}."
        )

        # document_name and page_number must match the source chunk
        assert citation["document_name"] == chunk_data["document_name"], (
            f"Property 9C violated at index {i}: citation document_name "
            f"{citation['document_name']!r} != chunk document_name "
            f"{chunk_data['document_name']!r}"
        )
        assert citation["page_number"] == chunk_data["page_number"], (
            f"Property 9C violated at index {i}: citation page_number "
            f"{citation['page_number']!r} != chunk page_number "
            f"{chunk_data['page_number']!r}"
        )


# ---------------------------------------------------------------------------
# Property 9D — query_documents QueryResult has citation data on every chunk
# **Validates: Requirements 4.7**
# ---------------------------------------------------------------------------


@given(query=_query_strategy, chunks=_chunk_list_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_9d_query_documents_result_chunks_have_citations(
    query: str, chunks: list[dict]
) -> None:
    """**Validates: Requirements 4.7**

    Property 9D: When ``query_documents`` returns a ``QueryResult``, every
    element of ``QueryResult.retrieved_chunks`` MUST have:
      - a non-empty ``document_name``
      - a ``page_number ≥ 1``

    The full retrieval flow is exercised by mocking ChromaDB so it returns
    the generated chunks as its results.  No external service is called.
    """
    from app.services.rag_service import RAGService

    user_id = uuid.uuid4()

    # Build ChromaDB mock response from the generated chunks
    chroma_ids = [f"chunk_{i}" for i in range(len(chunks))]
    chroma_documents = [c["content"] for c in chunks]
    chroma_metadatas = [
        {
            "document_id": str(uuid.uuid4()),
            "document_name": c["document_name"],
            "page_number": c["page_number"],
            "chunk_index": i,
        }
        for i, c in enumerate(chunks)
    ]

    mock_collection = MagicMock()
    mock_collection.query.return_value = {
        "ids": [chroma_ids],
        "documents": [chroma_documents],
        "metadatas": [chroma_metadatas],
        "distances": [[0.1 * (i + 1) for i in range(len(chunks))]],
    }

    mock_chroma_client = MagicMock()
    mock_chroma_client.get_collection.return_value = mock_collection

    mock_model = MagicMock()
    mock_model.encode.return_value = _make_mock_embeddings(1)

    service = RAGService()

    async def _run_query():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.query_documents(
                user_id=user_id,
                query=query,
                db=None,  # use ChromaDB metadata path (no DB session)
            )

    result = _run_async(_run_query())

    # The result must contain exactly as many chunks as were retrieved
    assert len(result.retrieved_chunks) == len(chunks), (
        f"Property 9D violated: QueryResult has {len(result.retrieved_chunks)} chunk(s) "
        f"but {len(chunks)} were returned by ChromaDB."
    )

    for i, (rc, chunk_data) in enumerate(zip(result.retrieved_chunks, chunks)):
        # document_name must be non-empty
        assert rc.document_name != "", (
            f"Property 9D violated at chunk {i}: document_name is empty. "
            f"chunk_data={chunk_data!r}"
        )

        # page_number must be ≥ 1
        assert rc.page_number >= 1, (
            f"Property 9D violated at chunk {i}: page_number={rc.page_number} < 1. "
            f"chunk_data={chunk_data!r}"
        )


# ---------------------------------------------------------------------------
# Property 9E — context from query_documents contains citation for every chunk
# **Validates: Requirements 4.7**
# ---------------------------------------------------------------------------


@given(query=_query_strategy, chunks=_chunk_list_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_9e_query_documents_context_has_citation_for_every_chunk(
    query: str, chunks: list[dict]
) -> None:
    """**Validates: Requirements 4.7**

    Property 9E: The ``QueryResult.context`` string returned by ``query_documents``
    MUST contain a ``[Source: <document_name>, Page <page_number>]`` citation marker
    for every retrieved chunk.

    This is the end-to-end citation completeness check: the pre-formatted
    context ready for LLM injection must carry all source attributions.
    """
    from app.services.rag_service import RAGService

    user_id = uuid.uuid4()

    chroma_ids = [f"chunk_{i}" for i in range(len(chunks))]
    chroma_documents = [c["content"] for c in chunks]
    chroma_metadatas = [
        {
            "document_id": str(uuid.uuid4()),
            "document_name": c["document_name"],
            "page_number": c["page_number"],
            "chunk_index": i,
        }
        for i, c in enumerate(chunks)
    ]

    mock_collection = MagicMock()
    mock_collection.query.return_value = {
        "ids": [chroma_ids],
        "documents": [chroma_documents],
        "metadatas": [chroma_metadatas],
        "distances": [[0.1 * (i + 1) for i in range(len(chunks))]],
    }

    mock_chroma_client = MagicMock()
    mock_chroma_client.get_collection.return_value = mock_collection

    mock_model = MagicMock()
    mock_model.encode.return_value = _make_mock_embeddings(1)

    service = RAGService()

    async def _run_query():
        with (
            patch("chromadb.HttpClient", return_value=mock_chroma_client),
            patch.object(service, "_get_embedding_model", return_value=mock_model),
        ):
            return await service.query_documents(
                user_id=user_id,
                query=query,
                db=None,
            )

    result = _run_async(_run_query())

    for i, rc in enumerate(result.retrieved_chunks):
        expected_citation = _citation_marker(rc.document_name, rc.page_number)
        assert expected_citation in result.context, (
            f"Property 9E violated at chunk {i}: expected citation "
            f"{expected_citation!r} not found in QueryResult.context.\n"
            f"document_name={rc.document_name!r}, page_number={rc.page_number}, "
            f"context_snippet={result.context[:500]!r}"
        )


# ===========================================================================
# Deterministic edge-case tests (complement the Hypothesis property tests)
# ===========================================================================


class TestRAGCitationCompletenessEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    def test_single_chunk_context_has_citation(self) -> None:
        """A single-chunk context must contain exactly one citation."""
        from app.services.rag_service import (
            RetrievedChunk,
            _build_context_string,
        )

        chunks = [
            RetrievedChunk(
                content="Paris is the capital of France.",
                document_name="geography.pdf",
                page_number=42,
            )
        ]
        context = _build_context_string("What is the capital of France?", chunks)

        assert "[Source: geography.pdf, Page 42]" in context, (
            f"Edge case failed: single-chunk context missing citation. context={context!r}"
        )

    def test_multiple_chunks_all_have_distinct_citations(self) -> None:
        """Each of multiple chunks must have its own citation in the context."""
        from app.services.rag_service import (
            RetrievedChunk,
            _build_context_string,
        )

        chunks = [
            RetrievedChunk(
                content="Water boils at 100°C.",
                document_name="chemistry.pdf",
                page_number=5,
            ),
            RetrievedChunk(
                content="The speed of light is 3×10⁸ m/s.",
                document_name="physics.txt",
                page_number=12,
            ),
            RetrievedChunk(
                content="DNA has a double-helix structure.",
                document_name="biology.md",
                page_number=88,
            ),
        ]
        context = _build_context_string("Explain basic science facts.", chunks)

        assert "[Source: chemistry.pdf, Page 5]" in context
        assert "[Source: physics.txt, Page 12]" in context
        assert "[Source: biology.md, Page 88]" in context

    def test_empty_chunks_returns_empty_context(self) -> None:
        """An empty chunk list must produce an empty context string (no citations)."""
        from app.services.rag_service import _build_context_string

        context = _build_context_string("Any query", [])
        assert context == "", f"Expected empty context for no chunks, got {context!r}"

    def test_format_citations_empty_input_returns_empty_list(self) -> None:
        """_format_citations with an empty list returns an empty list."""
        from app.services.rag_service import RAGService

        service = RAGService()
        result = service._format_citations([])
        assert result == [], f"Expected empty list, got {result!r}"

    def test_format_citations_preserves_ordering(self) -> None:
        """Citations must be ordered to match the chunk ordering."""
        from app.services.rag_service import RAGService, RetrievedChunk

        chunks = [
            RetrievedChunk(
                content="First fact.", document_name="doc_a.pdf", page_number=1
            ),
            RetrievedChunk(
                content="Second fact.", document_name="doc_b.pdf", page_number=7
            ),
            RetrievedChunk(
                content="Third fact.", document_name="doc_c.pdf", page_number=3
            ),
        ]
        service = RAGService()
        citations = service._format_citations(chunks)

        assert citations[0]["document_name"] == "doc_a.pdf"
        assert citations[0]["page_number"] == 1
        assert citations[0]["chunk_index"] == 0

        assert citations[1]["document_name"] == "doc_b.pdf"
        assert citations[1]["page_number"] == 7
        assert citations[1]["chunk_index"] == 1

        assert citations[2]["document_name"] == "doc_c.pdf"
        assert citations[2]["page_number"] == 3
        assert citations[2]["chunk_index"] == 2

    def test_page_number_1_is_cited_correctly(self) -> None:
        """Page 1 must appear as 'Page 1' in the citation (not Page 0 or absent)."""
        from app.services.rag_service import (
            RetrievedChunk,
            _build_context_string,
        )

        chunks = [
            RetrievedChunk(
                content="Introduction text.", document_name="report.docx", page_number=1
            )
        ]
        context = _build_context_string("What is in the introduction?", chunks)

        assert "[Source: report.docx, Page 1]" in context, (
            f"Edge case failed: page 1 citation not correctly formatted. context={context!r}"
        )

    def test_high_page_number_is_cited_correctly(self) -> None:
        """Very high page numbers (e.g. 9999) must be cited accurately."""
        from app.services.rag_service import (
            RetrievedChunk,
            _build_context_string,
        )

        chunks = [
            RetrievedChunk(
                content="Appendix Z content.",
                document_name="encyclopedia.pdf",
                page_number=9999,
            )
        ]
        context = _build_context_string("Find appendix Z.", chunks)

        assert "[Source: encyclopedia.pdf, Page 9999]" in context, (
            f"Edge case failed: high page number citation wrong. context={context!r}"
        )

    def test_query_documents_no_chunks_returns_empty_context(self) -> None:
        """When ChromaDB returns zero results, context must be empty."""
        from app.services.rag_service import RAGService

        user_id = uuid.uuid4()

        mock_collection = MagicMock()
        mock_collection.query.return_value = {
            "ids": [[]],
            "documents": [[]],
            "metadatas": [[]],
            "distances": [[]],
        }

        mock_chroma_client = MagicMock()
        mock_chroma_client.get_collection.return_value = mock_collection

        mock_model = MagicMock()
        mock_model.encode.return_value = _make_mock_embeddings(1)

        service = RAGService()

        async def _run():
            with (
                patch("chromadb.HttpClient", return_value=mock_chroma_client),
                patch.object(service, "_get_embedding_model", return_value=mock_model),
            ):
                return await service.query_documents(
                    user_id=user_id, query="anything", db=None
                )

        result = asyncio.run(_run())

        assert result.retrieved_chunks == [], (
            "Expected empty retrieved_chunks for no ChromaDB results."
        )
        assert result.context == "", f"Expected empty context, got {result.context!r}"

    def test_assemble_context_method_includes_all_citations(self) -> None:
        """RAGService._assemble_context must include a citation for every chunk."""
        from app.services.rag_service import RAGService, RetrievedChunk

        chunks = [
            RetrievedChunk(
                content="Fact one.", document_name="source_a.pdf", page_number=2
            ),
            RetrievedChunk(
                content="Fact two.", document_name="source_b.txt", page_number=14
            ),
        ]
        service = RAGService()
        context = service._assemble_context(chunks)

        assert "[Source: source_a.pdf, Page 2]" in context, (
            f"_assemble_context missing citation for chunk 0. context={context!r}"
        )
        assert "[Source: source_b.txt, Page 14]" in context, (
            f"_assemble_context missing citation for chunk 1. context={context!r}"
        )
