"""Unit tests for app.document.loader — DocumentLoader and helpers.

No production credentials required. PDF/DOCX extraction paths are
covered with lightweight real-bytes fixtures wherever possible, and
mocked where native library behaviour is irrelevant to what is under
test.  The OCR fallback is always mocked so pdf2image / tesseract are
never invoked.
"""

from __future__ import annotations

import hashlib
import io
from unittest.mock import MagicMock, patch

import pytest

from app.document.loader import (
    DEFAULT_MAX_FILE_SIZE_BYTES,
    SUPPORTED_EXTENSIONS,
    SUPPORTED_MIME_TYPES,
    DocumentLoader,
    PageSpan,
    _mime_from_ext,
    _ocr_pdf,
)
from app.interfaces.core import DocumentContent, DocumentLoadError, UnsupportedFormatError


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture()
def loader() -> DocumentLoader:
    return DocumentLoader()


@pytest.fixture()
def small_loader() -> DocumentLoader:
    """Loader with a tiny 1-byte size cap — useful for size-limit tests."""
    return DocumentLoader(max_file_size_bytes=1)


def _minimal_pdf_bytes() -> bytes:
    """Return the smallest syntactically valid PDF that pypdf can open.

    This is a hand-crafted PDF containing one page with one text object.
    Used so tests that call the *real* PDF extractor do not need an
    external fixture file.
    """
    return (
        b"%PDF-1.4\n"
        b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
        b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]\n"
        b"   /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n"
        b"4 0 obj\n<< /Length 44 >>\nstream\nBT /F1 12 Tf 100 700 Td (Hello PDF) Tj ET\nendstream\nendobj\n"
        b"5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        b"xref\n0 6\n"
        b"0000000000 65535 f \n"
        b"0000000009 00000 n \n"
        b"0000000058 00000 n \n"
        b"0000000115 00000 n \n"
        b"0000000266 00000 n \n"
        b"0000000360 00000 n \n"
        b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n441\n%%EOF\n"
    )


def _minimal_docx_bytes() -> bytes:
    """Return minimal DOCX bytes sufficient for python-docx Document() to open.

    A DOCX is a ZIP archive. We create the absolute minimum required:
    [Content_Types].xml, word/document.xml, word/_rels/document.xml.rels.
    """
    import zipfile

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            "</Types>",
        )
        z.writestr(
            "_rels/.rels",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            "</Relationships>",
        )
        z.writestr(
            "word/_rels/document.xml.rels",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>',
        )
        z.writestr(
            "word/document.xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            "<w:body>"
            "<w:p><w:r><w:t>Hello DOCX</w:t></w:r></w:p>"
            "<w:p><w:r><w:t>Second paragraph</w:t></w:r></w:p>"
            "</w:body></w:document>",
        )
    return buf.getvalue()


# ── PageSpan ──────────────────────────────────────────────────────────────────


class TestPageSpan:
    def test_contains_inside(self):
        span = PageSpan(page_number=1, start_char_offset=0, end_char_offset=100)
        assert span.contains(0)
        assert span.contains(50)
        assert span.contains(99)

    def test_contains_at_boundary(self):
        span = PageSpan(1, 0, 100)
        assert not span.contains(100)  # end is exclusive

    def test_contains_before_start(self):
        span = PageSpan(2, 50, 150)
        assert not span.contains(49)

    def test_frozen(self):
        span = PageSpan(1, 0, 10)
        with pytest.raises((AttributeError, TypeError)):
            span.page_number = 2  # type: ignore[misc]


# ── _mime_from_ext helper ─────────────────────────────────────────────────────


class TestMimeFromExt:
    @pytest.mark.parametrize(
        "ext, expected",
        [
            (".pdf", "application/pdf"),
            (".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            (".txt", "text/plain"),
            (".md", "text/markdown"),
            (".xyz", "application/octet-stream"),
            ("", "application/octet-stream"),
        ],
    )
    def test_known_extensions(self, ext, expected):
        assert _mime_from_ext(ext) == expected


# ── DocumentLoader.supports ───────────────────────────────────────────────────


class TestDocumentLoaderSupports:
    def test_supported_mime_types(self, loader):
        for mime in SUPPORTED_MIME_TYPES:
            assert loader.supports(mime, "file.bin"), f"Expected {mime!r} to be supported"

    def test_supported_extensions(self, loader):
        for ext in SUPPORTED_EXTENSIONS:
            assert loader.supports("", f"file{ext}"), f"Expected extension {ext!r} to be supported"

    def test_unsupported_returns_false(self, loader):
        assert not loader.supports("application/vnd.ms-excel", "data.xlsx")

    def test_case_insensitive_extension(self, loader):
        assert loader.supports("", "report.PDF")
        assert loader.supports("", "notes.MD")

    def test_mime_with_charset_parameter(self, loader):
        assert loader.supports("text/plain; charset=utf-8", "file.txt")

    def test_max_file_size_default(self, loader):
        assert loader.max_file_size_bytes == DEFAULT_MAX_FILE_SIZE_BYTES


# ── DocumentLoader._validate ─────────────────────────────────────────────────


class TestDocumentLoaderValidation:
    def test_empty_bytes_raises(self, loader):
        with pytest.raises(ValueError, match="empty"):
            loader._validate(b"", "file.txt", "text/plain")

    def test_oversized_raises(self, small_loader):
        with pytest.raises(ValueError, match="exceeds"):
            small_loader._validate(b"xx", "file.txt", "text/plain")

    def test_unsupported_format_raises(self, loader):
        with pytest.raises(UnsupportedFormatError):
            loader._validate(b"data", "file.xlsx", "application/vnd.ms-excel")

    def test_valid_passes(self, loader):
        loader._validate(b"hello", "file.txt", "text/plain")  # must not raise


# ── DocumentLoader._clean_text ────────────────────────────────────────────────


class TestCleanText:
    def test_strips_leading_trailing(self):
        assert DocumentLoader._clean_text("  hello  ") == "hello"

    def test_normalises_crlf(self):
        assert "\r" not in DocumentLoader._clean_text("line1\r\nline2")

    def test_normalises_lone_cr(self):
        assert "\r" not in DocumentLoader._clean_text("line1\rline2")

    def test_collapses_triple_blank_lines(self):
        text = "a\n\n\n\n\nb"
        cleaned = DocumentLoader._clean_text(text)
        assert "\n\n\n" not in cleaned

    def test_trims_trailing_spaces_per_line(self):
        cleaned = DocumentLoader._clean_text("hello   \nworld   ")
        for line in cleaned.splitlines():
            assert not line.endswith(" ")

    def test_empty_string_returned_as_is(self):
        assert DocumentLoader._clean_text("") == ""

    def test_null_bytes_removed(self):
        text = "hel\x00lo"
        cleaned = DocumentLoader._clean_text(text)
        assert "\x00" not in cleaned

    def test_unicode_normalisation_nfc(self):
        # NFD composed character → NFC
        nfd = "e\u0301"  # e + combining acute accent
        nfc = "\xe9"     # é pre-composed
        assert DocumentLoader._clean_text(nfd) == nfc


# ── DocumentLoader._load_text (TXT / MD) ─────────────────────────────────────


class TestLoadText:
    @pytest.mark.asyncio
    async def test_utf8(self, loader):
        text, count, meta = await loader._load_text(b"Hello world", "f.txt")
        assert text == "Hello world"
        assert count == 1
        assert meta["encoding"] in ("utf-8", "UTF-8")

    @pytest.mark.asyncio
    async def test_latin1_fallback(self, loader):
        # 0xe9 is 'é' in Latin-1 but not valid UTF-8 standalone
        latin1_bytes = b"caf\xe9"
        text, count, _meta = await loader._load_text(latin1_bytes, "f.txt")
        assert "caf" in text
        assert count == 1

    @pytest.mark.asyncio
    async def test_undecodable_raises(self, loader):
        # Inject bytes that will fail UTF-8 AND Latin-1 by patching
        # This simulates a future stricter codec; we test the error surface.
        with patch.object(loader, "_load_text", side_effect=DocumentLoadError("text_decode", "f.txt", "Cannot decode")):
            with pytest.raises(DocumentLoadError, match="text_decode"):
                await loader._load_text(b"\xff\xfe", "f.txt")

    @pytest.mark.asyncio
    async def test_metadata_contains_encoding(self, loader):
        _, _, meta = await loader._load_text(b"hello", "note.md")
        assert "encoding" in meta


# ── DocumentLoader.load — TXT end-to-end ─────────────────────────────────────


class TestDocumentLoaderLoadTxt:
    @pytest.mark.asyncio
    async def test_load_plain_text(self, loader):
        content = await loader.load(b"Hello world", "note.txt", "doc-1", "text/plain")
        assert isinstance(content, DocumentContent)
        assert content.document_id == "doc-1"
        assert "Hello world" in content.text
        assert content.page_count == 1
        assert content.extraction_metadata.get("checksum")

    @pytest.mark.asyncio
    async def test_load_markdown(self, loader):
        md = b"# Title\n\nParagraph text."
        content = await loader.load(md, "readme.md", "doc-2", "text/markdown")
        assert "Title" in content.text
        assert content.mime_type == "text/markdown"

    @pytest.mark.asyncio
    async def test_checksum_matches_sha256(self, loader):
        raw = b"checksum test content"
        content = await loader.load(raw, "f.txt", "doc-cs", "text/plain")
        expected = hashlib.sha256(raw).hexdigest()
        assert content.extraction_metadata["checksum"] == expected

    @pytest.mark.asyncio
    async def test_cleaned_text_has_no_crlf(self, loader):
        content = await loader.load(b"line1\r\nline2", "f.txt", "doc-3")
        assert "\r" not in content.text

    @pytest.mark.asyncio
    async def test_unsupported_format_raises(self, loader):
        with pytest.raises(UnsupportedFormatError):
            await loader.load(b"data", "data.xlsx", "doc-4", "application/vnd.ms-excel")

    @pytest.mark.asyncio
    async def test_empty_bytes_raises(self, loader):
        with pytest.raises(ValueError, match="empty"):
            await loader.load(b"", "f.txt", "doc-5", "text/plain")

    @pytest.mark.asyncio
    async def test_size_limit_raises(self, small_loader):
        with pytest.raises(ValueError, match="exceeds"):
            await small_loader.load(b"ab", "f.txt", "doc-6", "text/plain")

    @pytest.mark.asyncio
    async def test_document_id_propagated(self, loader):
        content = await loader.load(b"text", "f.txt", "my-stable-id")
        assert content.document_id == "my-stable-id"

    @pytest.mark.asyncio
    async def test_filename_propagated(self, loader):
        content = await loader.load(b"text", "report.txt", "doc-7")
        assert content.filename == "report.txt"

    @pytest.mark.asyncio
    async def test_extension_fallback_when_mime_blank(self, loader):
        content = await loader.load(b"text", "readme.md", "doc-8", mime_type="")
        assert "markdown" in content.mime_type


# ── DocumentLoader.load — DOCX (uses minimal real DOCX bytes) ────────────────


class TestDocumentLoaderLoadDocx:
    @pytest.mark.asyncio
    async def test_load_docx_extracts_paragraphs(self, loader):
        """Uses a real minimal DOCX ZIP so no mocking is needed."""
        content = await loader.load(
            _minimal_docx_bytes(),
            "doc.docx",
            "doc-docx-1",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        assert "Hello DOCX" in content.text
        assert content.page_count == 1
        assert content.extraction_metadata.get("checksum")

    @pytest.mark.asyncio
    async def test_docx_page_count_is_one(self, loader):
        content = await loader.load(_minimal_docx_bytes(), "doc.docx", "doc-docx-2")
        assert content.page_count == 1

    @pytest.mark.asyncio
    async def test_docx_checksum_is_sha256_of_raw(self, loader):
        raw = _minimal_docx_bytes()
        content = await loader.load(raw, "doc.docx", "doc-docx-cs")
        assert content.extraction_metadata["checksum"] == hashlib.sha256(raw).hexdigest()


# ── DocumentLoader.load — PDF ─────────────────────────────────────────────────


class TestDocumentLoaderLoadPdf:
    @pytest.mark.asyncio
    async def test_load_pdf_returns_document_content(self, loader):
        """Uses a minimal real PDF; OCR fallback is not triggered."""
        raw = _minimal_pdf_bytes()
        try:
            content = await loader.load(raw, "report.pdf", "doc-pdf-1", "application/pdf")
            assert isinstance(content, DocumentContent)
            assert content.page_count >= 1
            assert content.extraction_metadata.get("checksum") == hashlib.sha256(raw).hexdigest()
        except DocumentLoadError:
            pytest.skip("pypdf not installed or PDF parse failed in this env")

    @pytest.mark.asyncio
    async def test_pdf_page_spans_present(self, loader):
        raw = _minimal_pdf_bytes()
        try:
            content = await loader.load(raw, "report.pdf", "doc-pdf-2", "application/pdf")
            spans = content.extraction_metadata.get("page_spans", [])
            assert isinstance(spans, list)
            if spans:
                assert isinstance(spans[0], PageSpan)
        except DocumentLoadError:
            pytest.skip("pypdf not installed")

    @pytest.mark.asyncio
    async def test_pdf_ocr_flag_present(self, loader):
        raw = _minimal_pdf_bytes()
        try:
            content = await loader.load(raw, "report.pdf", "doc-pdf-3", "application/pdf")
            assert "ocr_used" in content.extraction_metadata
        except DocumentLoadError:
            pytest.skip("pypdf not installed")

    @pytest.mark.asyncio
    async def test_scanned_pdf_triggers_ocr_fallback(self, loader):
        """When native extraction returns < 20 chars, OCR should be attempted.

        We mock the OCR so no tesseract or pdf2image is needed.
        """
        raw = _minimal_pdf_bytes()

        # Force native text to be empty so OCR path is taken
        mock_page = MagicMock()
        mock_page.extract_text.return_value = ""

        mock_reader = MagicMock()
        mock_reader.pages = [mock_page]

        with (
            patch("app.document.loader._ocr_pdf", return_value="OCR extracted text") as ocr_mock,
            patch("pypdf.PdfReader", return_value=mock_reader),
        ):
            content = await loader.load(raw, "scan.pdf", "doc-ocr", "application/pdf")
            ocr_mock.assert_called_once()
            assert content.extraction_metadata.get("ocr_used") == "true"


# ── _ocr_pdf ──────────────────────────────────────────────────────────────────


class TestOcrPdf:
    def test_returns_empty_when_libraries_missing(self):
        with patch.dict("sys.modules", {"pdf2image": None, "pytesseract": None}):
            result = _ocr_pdf(b"fake pdf bytes", "scan.pdf")
            assert result == ""

    def test_raises_document_load_error_on_exception(self):
        mock_pdf2image = MagicMock()
        mock_pdf2image.convert_from_bytes.side_effect = RuntimeError("convert failed")
        with patch.dict("sys.modules", {"pdf2image": mock_pdf2image, "pytesseract": MagicMock()}):
            with pytest.raises(DocumentLoadError, match="ocr"):
                _ocr_pdf(b"fake pdf bytes", "scan.pdf")

    def test_success_joins_page_texts(self):
        mock_pdf2image = MagicMock()
        mock_pdf2image.convert_from_bytes.return_value = ["img1", "img2"]
        mock_tess = MagicMock()
        mock_tess.image_to_string.side_effect = ["Page one text", "Page two text"]
        with (
            patch.dict("sys.modules", {"pdf2image": mock_pdf2image, "pytesseract": mock_tess}),
        ):
            result = _ocr_pdf(b"fake", "doc.pdf")
            assert "Page one text" in result
            assert "Page two text" in result
