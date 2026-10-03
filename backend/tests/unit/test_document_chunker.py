"""Unit tests for app.document.chunker — DocumentChunker, ChunkingConfig, helpers.

No production credentials required.
Tiktoken is available in the test venv (listed in requirements.txt).
"""

from __future__ import annotations

import pytest

from app.document.chunker import (
    ChunkingConfig,
    DocumentChunker,
    _build_token_char_offsets,
    _page_for_offset,
)
from app.document.loader import PageSpan
from app.interfaces.core import DocumentChunk, DocumentContent, UnsupportedFormatError


# ── Fixtures ──────────────────────────────────────────────────────────────────


def _content(text: str, document_id: str = "doc-1", filename: str = "test.txt") -> DocumentContent:
    return DocumentContent(
        document_id=document_id,
        text=text,
        page_count=1,
        mime_type="text/plain",
        filename=filename,
    )


def _content_with_spans(text: str, spans: list[PageSpan]) -> DocumentContent:
    return DocumentContent(
        document_id="doc-pdf",
        text=text,
        page_count=len(spans),
        mime_type="application/pdf",
        filename="doc.pdf",
        extraction_metadata={"page_spans": spans},
    )


# ── ChunkingConfig ────────────────────────────────────────────────────────────


class TestChunkingConfig:
    def test_default_values(self):
        c = ChunkingConfig()
        assert c.chunk_size == 512
        assert c.overlap == 64
        assert c.min_chunk_size == 64
        assert c.max_chunk_size == 2048

    def test_chunk_size_clamped_to_min(self):
        c = ChunkingConfig(chunk_size=10, min_chunk_size=64)
        assert c.chunk_size == 64

    def test_chunk_size_clamped_to_max(self):
        c = ChunkingConfig(chunk_size=9999, max_chunk_size=2048)
        assert c.chunk_size == 2048

    def test_overlap_clamped_to_half_chunk_size(self):
        c = ChunkingConfig(chunk_size=100, overlap=80)
        assert c.overlap == 50  # clamped to 100 // 2

    def test_min_chunk_size_below_one_raises(self):
        with pytest.raises(ValueError, match="min_chunk_size"):
            ChunkingConfig(min_chunk_size=0)

    def test_max_below_min_raises(self):
        with pytest.raises(ValueError, match="max_chunk_size"):
            ChunkingConfig(min_chunk_size=100, max_chunk_size=50)

    def test_custom_tiktoken_model(self):
        c = ChunkingConfig(tiktoken_model="gpt-4")
        assert c.tiktoken_model == "gpt-4"


# ── _build_token_char_offsets ─────────────────────────────────────────────────


class TestBuildTokenCharOffsets:
    def test_length_is_tokens_plus_one(self):
        import tiktoken
        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        text = "Hello world"
        tokens = enc.encode(text)
        offsets = _build_token_char_offsets(enc, tokens, text)
        assert len(offsets) == len(tokens) + 1

    def test_first_offset_is_zero(self):
        import tiktoken
        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        text = "Hello"
        tokens = enc.encode(text)
        offsets = _build_token_char_offsets(enc, tokens, text)
        assert offsets[0] == 0

    def test_last_offset_equals_decoded_length(self):
        import tiktoken
        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        text = "Hello world"
        tokens = enc.encode(text)
        offsets = _build_token_char_offsets(enc, tokens, text)
        decoded_len = sum(len(enc.decode([t])) for t in tokens)
        assert offsets[-1] == decoded_len

    def test_offsets_are_monotonically_non_decreasing(self):
        import tiktoken
        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        text = "The quick brown fox jumps"
        tokens = enc.encode(text)
        offsets = _build_token_char_offsets(enc, tokens, text)
        for i in range(1, len(offsets)):
            assert offsets[i] >= offsets[i - 1]


# ── _page_for_offset ──────────────────────────────────────────────────────────


class TestPageForOffset:
    def test_returns_none_when_no_spans(self):
        assert _page_for_offset([], 50) is None

    def test_returns_correct_page(self):
        spans = [PageSpan(1, 0, 100), PageSpan(2, 100, 200)]
        assert _page_for_offset(spans, 0) == 1
        assert _page_for_offset(spans, 50) == 1
        assert _page_for_offset(spans, 100) == 2
        assert _page_for_offset(spans, 199) == 2

    def test_returns_last_page_when_beyond_all_spans(self):
        spans = [PageSpan(1, 0, 100), PageSpan(2, 100, 200)]
        assert _page_for_offset(spans, 300) == 2


# ── DocumentChunker.chunk ─────────────────────────────────────────────────────


class TestDocumentChunker:
    def _chunker(self, chunk_size=128, overlap=16) -> DocumentChunker:
        return DocumentChunker(ChunkingConfig(chunk_size=chunk_size, overlap=overlap))

    def test_empty_text_returns_empty_list(self):
        chunker = self._chunker()
        assert chunker.chunk(_content("")) == []
        assert chunker.chunk(_content("   ")) == []

    def test_short_text_produces_one_chunk(self):
        chunker = self._chunker(chunk_size=512, overlap=64)
        content = _content("Short document text.")
        chunks = chunker.chunk(content)
        assert len(chunks) == 1

    def test_long_text_produces_multiple_chunks(self):
        # 5000-character text should exceed a 128-token chunk
        text = "word " * 1000
        chunker = self._chunker(chunk_size=64, overlap=8)
        chunks = chunker.chunk(_content(text))
        assert len(chunks) > 1

    def test_chunks_are_ordered_by_index(self):
        text = "token " * 500
        chunks = self._chunker(chunk_size=64, overlap=8).chunk(_content(text))
        indices = [c.chunk_index for c in chunks]
        assert indices == list(range(len(indices)))

    def test_chunk_ids_are_unique(self):
        text = "word " * 500
        chunks = self._chunker(chunk_size=64, overlap=8).chunk(_content(text))
        ids = [c.chunk_id for c in chunks]
        assert len(ids) == len(set(ids))

    def test_chunk_ids_contain_document_id(self):
        text = "some text " * 50
        content = _content(text, document_id="my-doc")
        chunks = self._chunker().chunk(content)
        for chunk in chunks:
            assert "my-doc" in chunk.chunk_id

    def test_user_id_propagated(self):
        text = "hello " * 50
        chunks = self._chunker().chunk(_content(text), user_id="user-42")
        for chunk in chunks:
            assert chunk.user_id == "user-42"

    def test_document_name_from_filename(self):
        content = _content("hello " * 30, filename="report.txt")
        chunks = self._chunker().chunk(content)
        for chunk in chunks:
            assert chunk.document_name == "report.txt"

    def test_chunks_are_document_chunk_instances(self):
        chunks = self._chunker().chunk(_content("text " * 50))
        assert all(isinstance(c, DocumentChunk) for c in chunks)

    def test_char_start_end_set(self):
        text = "word " * 200
        chunks = self._chunker(chunk_size=64, overlap=8).chunk(_content(text))
        for chunk in chunks:
            assert chunk.char_start >= 0
            assert chunk.char_end >= chunk.char_start

    def test_overlap_produces_shared_content(self):
        """Adjacent chunks should share some tokens when overlap > 0."""
        text = "alpha beta gamma delta epsilon zeta eta theta iota kappa " * 20
        chunker = self._chunker(chunk_size=32, overlap=8)
        chunks = chunker.chunk(_content(text))
        if len(chunks) < 2:
            pytest.skip("Not enough tokens to produce multiple chunks")
        # The end of chunk[0] and start of chunk[1] should have some overlap in content
        end_of_first = chunks[0].text[-20:]
        start_of_second = chunks[1].text[:20]
        # Not necessarily identical strings due to BPE boundaries, but char ranges should overlap
        assert chunks[1].char_start < chunks[0].char_end

    def test_page_number_none_without_spans(self):
        chunks = self._chunker().chunk(_content("text " * 50))
        for chunk in chunks:
            assert chunk.page_number is None

    def test_page_number_from_spans_single_page(self):
        text = "word " * 100
        spans = [PageSpan(page_number=1, start_char_offset=0, end_char_offset=len(text) + 10)]
        content = _content_with_spans(text, spans)
        chunks = self._chunker().chunk(content)
        for chunk in chunks:
            assert chunk.page_number == 1

    def test_page_number_from_spans_multi_page(self):
        page1 = "alpha " * 50   # ~300 chars
        page2 = "beta " * 50    # ~250 chars
        text = page1 + page2
        spans = [
            PageSpan(1, 0, len(page1)),
            PageSpan(2, len(page1), len(text)),
        ]
        content = _content_with_spans(text, spans)
        chunks = self._chunker(chunk_size=64, overlap=8).chunk(content)
        pages_seen = {c.page_number for c in chunks}
        # At least two different pages should be detected given the text length
        assert 1 in pages_seen
        assert 2 in pages_seen

    def test_chunk_text_is_not_empty(self):
        chunks = self._chunker().chunk(_content("word " * 100))
        for chunk in chunks:
            assert chunk.text.strip()

    def test_default_config_used_when_none_passed(self):
        chunker = DocumentChunker()
        assert chunker.config.chunk_size == 512

    def test_chunk_size_configurable(self):
        small = DocumentChunker(ChunkingConfig(chunk_size=64, overlap=8))
        large = DocumentChunker(ChunkingConfig(chunk_size=512, overlap=64))
        text = "word " * 300
        content = _content(text)
        small_chunks = small.chunk(content)
        large_chunks = large.chunk(content)
        # Smaller chunk size → more chunks
        assert len(small_chunks) >= len(large_chunks)


# ── DocumentIngestion pipeline ────────────────────────────────────────────────


class TestDocumentIngestionPipeline:
    """Smoke-test the façade: IngestedDocument carries content + chunks."""

    @pytest.mark.asyncio
    async def test_ingest_txt_returns_ingested_document(self):
        from app.document.pipeline import DocumentIngestion, IngestedDocument

        pipeline = DocumentIngestion()
        result = await pipeline.ingest(
            file_bytes=b"Hello world. This is a test document with enough content.",
            filename="test.txt",
            document_id="doc-pipe-1",
            mime_type="text/plain",
            user_id="user-pipe",
        )
        assert isinstance(result, IngestedDocument)
        assert result.content.document_id == "doc-pipe-1"
        assert result.checksum  # non-empty SHA-256

    @pytest.mark.asyncio
    async def test_ingest_produces_chunks(self):
        from app.document.pipeline import DocumentIngestion

        # Generate enough text to produce at least one chunk
        text = ("word " * 100).encode()
        pipeline = DocumentIngestion()
        result = await pipeline.ingest(text, "words.txt", "doc-pipe-2")
        assert result.chunk_count >= 1

    @pytest.mark.asyncio
    async def test_ingest_chunk_count_matches_chunks_len(self):
        from app.document.pipeline import DocumentIngestion

        pipeline = DocumentIngestion()
        result = await pipeline.ingest(b"sample text", "f.txt", "doc-pipe-3")
        assert result.chunk_count == len(result.chunks)

    @pytest.mark.asyncio
    async def test_ingest_unsupported_format_raises(self):
        from app.document.pipeline import DocumentIngestion

        pipeline = DocumentIngestion()
        with pytest.raises(UnsupportedFormatError):
            await pipeline.ingest(b"data", "f.xlsx", "doc-pipe-4", "application/vnd.ms-excel")
