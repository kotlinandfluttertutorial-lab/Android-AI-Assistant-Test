"""Unit tests for app.rag.context_builder — ContextBuilder and _citation_tag.

No LLM, no vector store, no credentials required.
"""

from __future__ import annotations

import pytest

from app.interfaces.core import DocumentChunk, RetrievedChunk
from app.rag.context_builder import ContextBuilder, _citation_tag


# ── Helpers ───────────────────────────────────────────────────────────────────

def _chunk(
    chunk_id: str = "c1",
    document_id: str = "doc-1",
    document_name: str = "report.pdf",
    user_id: str = "u1",
    chunk_index: int = 0,
    text: str = "The quick brown fox jumps over the lazy dog.",
    page_number: int | None = 1,
    char_start: int = 0,
    char_end: int = 0,
) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        document_name=document_name,
        user_id=user_id,
        chunk_index=chunk_index,
        text=text,
        page_number=page_number,
        char_start=char_start,
        char_end=char_end,
    )


def _rc(
    chunk: DocumentChunk | None = None,
    similarity: float = 0.9,
    retrieval_path: str = "chroma_ann",
) -> RetrievedChunk:
    return RetrievedChunk(
        chunk=chunk or _chunk(),
        similarity=similarity,
        retrieval_path=retrieval_path,
    )


# ── _citation_tag ─────────────────────────────────────────────────────────────

class TestCitationTag:
    def test_page_number_present(self):
        c = _chunk(document_name="report.pdf", page_number=3)
        tag = _citation_tag(c)
        assert "report.pdf" in tag
        assert "Page 3" in tag

    def test_char_offsets_used_when_no_page(self):
        c = _chunk(document_name="notes.md", page_number=None, char_start=10, char_end=100)
        tag = _citation_tag(c)
        assert "Chars 10-100" in tag

    def test_fallback_when_no_page_no_offsets(self):
        c = _chunk(document_name="file.txt", page_number=None, char_start=0, char_end=0)
        tag = _citation_tag(c)
        assert "file.txt" in tag
        assert "Page" not in tag
        assert "Chars" not in tag

    def test_page_takes_priority_over_offsets(self):
        c = _chunk(page_number=5, char_start=10, char_end=50)
        tag = _citation_tag(c)
        assert "Page 5" in tag
        assert "Chars" not in tag


# ── ContextBuilder.build_context ─────────────────────────────────────────────

class TestBuildContext:
    def test_empty_chunks_returns_empty_string(self):
        assert ContextBuilder().build_context([]) == ""

    def test_single_chunk_contains_header(self):
        ctx = ContextBuilder().build_context([_rc()])
        assert "Retrieved Context:" in ctx

    def test_single_chunk_numbered_1(self):
        ctx = ContextBuilder().build_context([_rc()])
        assert "Chunk 1" in ctx

    def test_multiple_chunks_numbered_sequentially(self):
        chunks = [_rc(_chunk(chunk_id=f"c{i}")) for i in range(3)]
        ctx = ContextBuilder().build_context(chunks)
        assert "Chunk 1" in ctx
        assert "Chunk 2" in ctx
        assert "Chunk 3" in ctx

    def test_chunk_text_included(self):
        c = _chunk(text="unique content xyz")
        ctx = ContextBuilder().build_context([_rc(c)])
        assert "unique content xyz" in ctx

    def test_citation_tag_in_context(self):
        c = _chunk(document_name="doc.pdf", page_number=7)
        ctx = ContextBuilder().build_context([_rc(c)])
        assert "doc.pdf" in ctx
        assert "Page 7" in ctx

    def test_char_offset_citation_in_context(self):
        c = _chunk(document_name="notes.md", page_number=None, char_start=5, char_end=80)
        ctx = ContextBuilder().build_context([_rc(c)])
        assert "Chars 5-80" in ctx

    def test_max_context_chars_truncates(self):
        # Use a cap (2000) well below the full text length (50000)
        long_text = "word " * 10_000  # ~50000 chars
        c = _chunk(text=long_text)
        ctx = ContextBuilder(max_context_chars=2000).build_context([_rc(c)])
        # Context must be meaningfully shorter than the original text
        assert len(ctx) < len(long_text) // 2

    def test_max_context_chars_stops_adding_chunks(self):
        chunks = [_rc(_chunk(chunk_id=f"c{i}", text="a" * 100)) for i in range(20)]
        ctx = ContextBuilder(max_context_chars=500).build_context(chunks)
        # Should not contain all 20 chunks
        assert "Chunk 20" not in ctx


# ── ContextBuilder.build_citations ───────────────────────────────────────────

class TestBuildCitations:
    def test_empty_returns_empty_list(self):
        assert ContextBuilder().build_citations([]) == []

    def test_one_citation_per_chunk(self):
        chunks = [_rc(_chunk(chunk_id=f"c{i}")) for i in range(3)]
        citations = ContextBuilder().build_citations(chunks)
        assert len(citations) == 3

    def test_citation_has_document_name(self):
        c = _chunk(document_name="annual.pdf")
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["document_name"] == "annual.pdf"

    def test_citation_has_document_id(self):
        c = _chunk(document_id="d-uuid-1")
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["document_id"] == "d-uuid-1"

    def test_citation_has_page_number(self):
        c = _chunk(page_number=4)
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["page_number"] == 4

    def test_citation_has_chunk_index(self):
        c = _chunk(chunk_index=2)
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["chunk_index"] == 2

    def test_citation_has_retrieval_path(self):
        rc = _rc(retrieval_path="chroma_ann")
        cits = ContextBuilder().build_citations([rc])
        assert cits[0]["retrieval_path"] == "chroma_ann"

    def test_citation_has_excerpt(self):
        c = _chunk(text="This is the chunk text content.")
        cits = ContextBuilder().build_citations([_rc(c)])
        assert "This is the chunk text content." in cits[0]["excerpt"]

    def test_excerpt_truncated_to_200_chars(self):
        c = _chunk(text="x" * 300)
        cits = ContextBuilder().build_citations([_rc(c)])
        assert len(cits[0]["excerpt"]) == 200

    def test_similarity_not_included_by_default(self):
        cits = ContextBuilder().build_citations([_rc(similarity=0.87)])
        assert "similarity" not in cits[0]

    def test_similarity_included_when_configured(self):
        builder = ContextBuilder(include_similarity_in_citation=True)
        cits = builder.build_citations([_rc(similarity=0.87)])
        assert cits[0]["similarity"] == pytest.approx(0.87, abs=1e-4)

    def test_char_start_and_end_present(self):
        c = _chunk(page_number=None, char_start=10, char_end=50)
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["char_start"] == 10
        assert cits[0]["char_end"] == 50

    def test_none_page_number_preserved(self):
        c = _chunk(page_number=None)
        cits = ContextBuilder().build_citations([_rc(c)])
        assert cits[0]["page_number"] is None


# ── ContextBuilder.build_rag_system_prompt ───────────────────────────────────

class TestBuildRagSystemPrompt:
    def test_returns_non_empty_string(self):
        prompt = ContextBuilder().build_rag_system_prompt()
        assert isinstance(prompt, str)
        assert len(prompt) > 50

    def test_instructs_to_use_context_only(self):
        prompt = ContextBuilder().build_rag_system_prompt()
        lower = prompt.lower()
        assert "context" in lower

    def test_mentions_citation(self):
        prompt = ContextBuilder().build_rag_system_prompt()
        lower = prompt.lower()
        assert "cit" in lower  # citation / cite / citing


# ── ContextBuilder.build_prompt ──────────────────────────────────────────────

class TestBuildPrompt:
    def test_contains_question(self):
        prompt = ContextBuilder().build_prompt("What is X?", [_rc()])
        assert "What is X?" in prompt

    def test_contains_context_when_chunks_present(self):
        prompt = ContextBuilder().build_prompt("Q?", [_rc(_chunk(text="important info"))])
        assert "important info" in prompt

    def test_question_only_when_no_chunks(self):
        prompt = ContextBuilder().build_prompt("What is Y?", [])
        assert prompt == "What is Y?"

    def test_question_separator_between_context_and_question(self):
        prompt = ContextBuilder().build_prompt("What?", [_rc()])
        assert "Question:" in prompt
