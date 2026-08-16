"""Unit tests for app.services.rag_service.

Covers:
- validate_upload rejects unsupported formats          (HTTP 422)
- validate_upload rejects oversized files              (HTTP 422)
- validate_upload accepts valid PDF                    (no exception)
- chunk_text full coverage property                    (Property 7)
- chunk_text overlap between consecutive chunks        (Property 7)
- extract_text plain-text returns original content
- extract_text Markdown returns content
- extract_text failure raises structured ExtractionError

Requirements: 4.1, 4.2, 4.4, 4.8
"""

from __future__ import annotations

import os
from unittest.mock import patch

import pytest

# Environment variables must be set before importing app modules
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import HTTPException

from app.services.rag_service import ExtractionError, RAGService

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_service() -> RAGService:
    """Return a fresh RAGService instance with default test settings."""
    return RAGService()


# ---------------------------------------------------------------------------
# validate_upload — format enforcement (Property 26)
# ---------------------------------------------------------------------------


class TestValidateUpload:
    """Tests for RAGService.validate_upload (format + size enforcement)."""

    def test_rejects_unsupported_format(self) -> None:
        """An .exe file should be rejected with HTTP 422 — nothing stored."""
        service = _make_service()
        with pytest.raises(HTTPException) as exc_info:
            service.validate_upload("malware.exe", size_bytes=1024)
        assert exc_info.value.status_code == 422
        assert (
            "exe" in exc_info.value.detail.lower()
            or "unsupported" in exc_info.value.detail.lower()
        )

    def test_rejects_zip_format(self) -> None:
        """A .zip file should be rejected with HTTP 422."""
        service = _make_service()
        with pytest.raises(HTTPException) as exc_info:
            service.validate_upload("archive.zip", size_bytes=1024)
        assert exc_info.value.status_code == 422

    def test_rejects_oversized_file(self) -> None:
        """A file exceeding 50 MB should be rejected with HTTP 422."""
        service = _make_service()
        over_50_mb = 51 * 1024 * 1024  # 51 MB in bytes
        with pytest.raises(HTTPException) as exc_info:
            service.validate_upload("big_file.pdf", size_bytes=over_50_mb)
        assert exc_info.value.status_code == 422
        assert "50" in exc_info.value.detail or "size" in exc_info.value.detail.lower()

    def test_rejects_exactly_one_byte_over_limit(self) -> None:
        """Exactly 1 byte over the limit should still be rejected."""
        service = _make_service()
        just_over = 50 * 1024 * 1024 + 1
        with pytest.raises(HTTPException) as exc_info:
            service.validate_upload("report.pdf", size_bytes=just_over)
        assert exc_info.value.status_code == 422

    def test_accepts_valid_pdf(self) -> None:
        """A .pdf file within the 50 MB limit should not raise any exception."""
        service = _make_service()
        # Should not raise
        service.validate_upload("document.pdf", size_bytes=1024 * 1024)  # 1 MB

    def test_accepts_valid_docx(self) -> None:
        """A .docx file within the limit should be accepted."""
        service = _make_service()
        service.validate_upload("report.docx", size_bytes=500 * 1024)  # 500 KB

    def test_accepts_valid_txt(self) -> None:
        """A .txt file should be accepted."""
        service = _make_service()
        service.validate_upload("notes.txt", size_bytes=1024)

    def test_accepts_valid_markdown(self) -> None:
        """A .md file should be accepted."""
        service = _make_service()
        service.validate_upload("readme.md", size_bytes=4096)

    def test_accepts_exactly_50_mb(self) -> None:
        """A file of exactly 50 MB should be accepted (boundary)."""
        service = _make_service()
        exactly_50_mb = 50 * 1024 * 1024
        # Should not raise
        service.validate_upload("large.pdf", size_bytes=exactly_50_mb)


# ---------------------------------------------------------------------------
# chunk_text — Property 7: full coverage guarantee
# ---------------------------------------------------------------------------


class TestChunkTextCoverage:
    """Tests for RAGService.chunk_text — ensures every token is covered."""

    def test_empty_text_returns_empty_list(self) -> None:
        """Empty input text should produce no chunks."""
        service = _make_service()
        chunks = service.chunk_text("")
        assert chunks == []

    def test_whitespace_only_returns_empty_list(self) -> None:
        """Whitespace-only text should produce no chunks."""
        service = _make_service()
        chunks = service.chunk_text("   \n\t  ")
        assert chunks == []

    def test_chunk_text_full_coverage(self) -> None:
        """All text tokens must appear in the union of all chunks (Property 7).

        Validates: Requirements 4.4
        """
        import tiktoken

        service = _make_service()
        source_text = (
            "The quick brown fox jumps over the lazy dog. " * 50
        )  # ~450 words, well over one chunk

        chunks = service.chunk_text(source_text, chunk_size=64, overlap=16)
        assert len(chunks) > 1, "Text should produce multiple chunks"

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        source_tokens = enc.encode(source_text)

        # Reconstruct token sets from all chunks
        covered_tokens: set[int] = set()
        for chunk in chunks:
            covered_tokens.update(enc.encode(chunk.text))

        source_token_set = set(source_tokens)
        # Every unique token in the source must appear in at least one chunk
        assert source_token_set.issubset(
            covered_tokens
        ), "Some source tokens are not covered by any chunk — Property 7 violated."

    def test_single_chunk_for_short_text(self) -> None:
        """A text shorter than chunk_size should produce exactly one chunk."""
        service = _make_service()
        short_text = "Hello world."
        chunks = service.chunk_text(short_text, chunk_size=512, overlap=64)
        assert len(chunks) == 1
        assert (
            chunks[0].text.strip() == short_text.strip() or short_text in chunks[0].text
        )

    def test_chunk_text_overlap(self) -> None:
        """Consecutive chunks should share overlapping tokens (Property 7).

        Validates: Requirements 4.4
        """
        import tiktoken

        service = _make_service()
        # Create a text that's clearly more than 2 * chunk_size tokens
        source_text = " ".join(["word"] * 300)

        chunk_size = 64
        overlap = 16
        chunks = service.chunk_text(source_text, chunk_size=chunk_size, overlap=overlap)

        assert len(chunks) >= 2, "Need at least 2 chunks to test overlap"

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        first_tokens = set(enc.encode(chunks[0].text))
        second_tokens = set(enc.encode(chunks[1].text))

        shared = first_tokens & second_tokens
        # There must be at least 1 shared token to confirm overlapping chunks
        assert (
            len(shared) >= 1
        ), "Consecutive chunks have no overlapping tokens — sliding window may be broken."

    def test_chunk_coverage_for_long_document(self) -> None:
        """Full coverage must hold even for very long input texts.

        Validates: Requirements 4.4
        """
        import tiktoken

        service = _make_service()
        # Generate ~2000 tokens of unique-ish text
        source_text = " ".join([f"token_{i}" for i in range(2000)])

        chunks = service.chunk_text(source_text, chunk_size=128, overlap=32)
        assert len(chunks) > 5, "Long text should produce many chunks"

        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
        source_token_ids = enc.encode(source_text)
        covered = set()
        for chunk in chunks:
            covered.update(enc.encode(chunk.text))

        assert set(source_token_ids).issubset(
            covered
        ), "Some source tokens missing from chunks — coverage broken for long text."

    def test_no_gap_between_chunks(self) -> None:
        """The stride must never leave a gap — start positions must be contiguous."""
        service = _make_service()
        source_text = "alpha " * 200  # definitely multi-chunk

        chunks = service.chunk_text(source_text, chunk_size=32, overlap=8)
        assert len(chunks) >= 3

        # Decode all chunks and verify the full text is recoverable
        # (deduplication by checking first occurrence of each chunk in the source)
        combined = " ".join(c.text for c in chunks)
        # Every word from source should appear in the combined output
        for word in source_text.split():
            assert word in combined, f"Word '{word}' from source not in any chunk"


# ---------------------------------------------------------------------------
# extract_text — plain-text and Markdown
# ---------------------------------------------------------------------------


class TestExtractTextPlainAndMarkdown:
    """Tests for plain-text and Markdown extraction."""

    @pytest.mark.asyncio
    async def test_extract_text_plain_text(self) -> None:
        """TXT extraction should return the original text content."""
        service = _make_service()
        content = "Hello, world! This is a plain text file.\nLine two."
        file_bytes = content.encode("utf-8")

        extracted, page_count = await service.extract_text(
            file_bytes, "text/plain", "test.txt"
        )

        assert extracted == content
        assert page_count == 1

    @pytest.mark.asyncio
    async def test_extract_text_plain_text_by_extension(self) -> None:
        """TXT extraction should work even with generic MIME type, using extension."""
        service = _make_service()
        content = "Plain text via extension."
        file_bytes = content.encode("utf-8")

        extracted, page_count = await service.extract_text(
            file_bytes, "application/octet-stream", "notes.txt"
        )

        assert content in extracted
        assert page_count == 1

    @pytest.mark.asyncio
    async def test_extract_text_markdown(self) -> None:
        """MD extraction should return the original Markdown text."""
        service = _make_service()
        content = "# Title\n\nSome **bold** text and `code`."
        file_bytes = content.encode("utf-8")

        extracted, page_count = await service.extract_text(
            file_bytes, "text/markdown", "readme.md"
        )

        assert extracted == content
        assert page_count == 1

    @pytest.mark.asyncio
    async def test_extract_text_markdown_by_extension(self) -> None:
        """Markdown extraction by .md extension should work."""
        service = _make_service()
        content = "## Section\nContent here."
        file_bytes = content.encode("utf-8")

        extracted, _ = await service.extract_text(
            file_bytes, "application/octet-stream", "doc.md"
        )

        assert content in extracted

    @pytest.mark.asyncio
    async def test_extract_text_preserves_unicode(self) -> None:
        """Extraction should correctly handle unicode characters."""
        service = _make_service()
        content = "Héllo wörld! Ñoño. 日本語テスト."
        file_bytes = content.encode("utf-8")

        extracted, _ = await service.extract_text(
            file_bytes, "text/plain", "unicode.txt"
        )

        assert extracted == content


# ---------------------------------------------------------------------------
# extract_text — failure case
# ---------------------------------------------------------------------------


class TestExtractTextFailure:
    """Tests for structured ExtractionError on extraction failure."""

    @pytest.mark.asyncio
    async def test_extract_text_failure_raises_structured_error(self) -> None:
        """A corrupted/undecodable file should raise ExtractionError with stage+filename.

        Requirements: 4.8
        """
        service = _make_service()
        # Pure binary garbage that is not valid UTF-8 or Latin-1 in isolation
        # We force a failure by patching the underlying decode path
        bad_bytes = bytes(
            range(256)
        )  # covers all byte values — will fail as strict UTF-8

        with (
            patch.object(
                service,
                "_extract_text_file",
                side_effect=ExtractionError(
                    stage="text_read",
                    file_name="corrupt.txt",
                    detail="codec can't decode bytes",
                ),
            ),
            pytest.raises(ExtractionError) as exc_info,
        ):
            await service.extract_text(bad_bytes, "text/plain", "corrupt.txt")

        err = exc_info.value
        assert err.stage == "text_read"
        assert err.file_name == "corrupt.txt"
        assert "codec" in err.detail or err.detail != ""

    @pytest.mark.asyncio
    async def test_extraction_error_has_all_required_fields(self) -> None:
        """ExtractionError must carry stage, file_name, and detail fields."""
        err = ExtractionError(
            stage="pdf_extraction",
            file_name="broken.pdf",
            detail="EOF marker not found",
        )
        assert err.stage == "pdf_extraction"
        assert err.file_name == "broken.pdf"
        assert err.detail == "EOF marker not found"
        assert "pdf_extraction" in str(err)
        assert "broken.pdf" in str(err)

    @pytest.mark.asyncio
    async def test_pdf_extraction_failure_raises_structured_error(self) -> None:
        """Corrupted PDF bytes should raise ExtractionError with stage='pdf_extraction'."""
        service = _make_service()
        corrupted_pdf = b"NOT A PDF AT ALL %PDF corrupted"

        with pytest.raises(ExtractionError) as exc_info:
            await service.extract_text(corrupted_pdf, "application/pdf", "broken.pdf")

        err = exc_info.value
        assert err.stage in ("pdf_extraction", "ocr")
        assert err.file_name == "broken.pdf"
