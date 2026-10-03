# ============================================================
# Android AI Assistant — Backend
# Module  : document
# File    : loader.py
# Purpose : DocumentLoader — validates raw bytes, extracts plain text
#           from PDF / DOCX / TXT / Markdown, cleans whitespace, and
#           computes a SHA-256 checksum for deduplication.
#
# Implements IDocumentLoader from app.interfaces.core.
#
# Design rules:
#   - Zero infrastructure dependencies (no DB, Redis, ChromaDB, MinIO).
#   - All document libraries are lazy-imported so the module is importable
#     in test environments that don't have e.g. pdf2image installed.
#   - CPU-bound extraction runs in asyncio.to_thread so the event loop
#     is never blocked.
#   - Page spans (PageSpan) are returned inside DocumentContent's
#     extraction_metadata for use by DocumentChunker.
# ============================================================
"""DocumentLoader — format-aware text extractor for the RAG ingestion pipeline."""

from __future__ import annotations

import asyncio
import hashlib
import io
import os
import re
import unicodedata
from dataclasses import dataclass
from typing import Any

from app.interfaces.core import (
    DocumentContent,
    DocumentLoadError,
    IDocumentLoader,
    UnsupportedFormatError,
)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

#: Supported MIME types — used for upload validation.
SUPPORTED_MIME_TYPES: frozenset[str] = frozenset(
    {
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain",
        "text/markdown",
        "text/x-markdown",
    }
)

#: Supported file extensions — fallback when MIME type is absent or generic.
SUPPORTED_EXTENSIONS: frozenset[str] = frozenset({".pdf", ".docx", ".txt", ".md"})

#: Default upload size cap.  Can be overridden in DocumentLoader.__init__.
DEFAULT_MAX_FILE_SIZE_BYTES: int = 20 * 1024 * 1024  # 20 MB


# ---------------------------------------------------------------------------
# PageSpan — maps one page to its char-offset range in the extracted text
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PageSpan:
    """Maps one document page to its character offset range in the extracted text.

    Attributes:
        page_number:       1-based page number.
        start_char_offset: Index of the first character of this page in the
                           full extracted text string.
        end_char_offset:   Index one past the last character of this page.
    """

    page_number: int
    start_char_offset: int
    end_char_offset: int

    def contains(self, char_offset: int) -> bool:
        """Return True when *char_offset* falls within this page span."""
        return self.start_char_offset <= char_offset < self.end_char_offset


# ---------------------------------------------------------------------------
# DocumentLoader
# ---------------------------------------------------------------------------


class DocumentLoader(IDocumentLoader):
    """Validates, extracts, and cleans text from PDF, DOCX, TXT, and Markdown.

    Implements :class:`~app.interfaces.core.IDocumentLoader`.

    The extracted :class:`~app.interfaces.core.DocumentContent` carries:

    - ``text``              – cleaned plain text.
    - ``page_count``        – number of pages (1 for DOCX/TXT/MD).
    - ``extraction_metadata["checksum"]`` – SHA-256 hex digest of the raw bytes.
    - ``extraction_metadata["page_spans"]`` – ``list[PageSpan]`` (PDF only) that
      the :class:`~app.document.chunker.DocumentChunker` uses to annotate each
      chunk with the correct page number.
    - ``extraction_metadata["ocr_used"]`` – ``"true"`` when OCR was needed.

    Usage::

        loader = DocumentLoader(max_file_size_bytes=10 * 1024 * 1024)
        content = await loader.load(pdf_bytes, "report.pdf", "doc-uuid-1")
        # content.text  →  cleaned plain text
        # content.extraction_metadata["checksum"]  →  sha256 hex
    """

    def __init__(self, max_file_size_bytes: int = DEFAULT_MAX_FILE_SIZE_BYTES) -> None:
        self._max_bytes = max_file_size_bytes

    # ── IDocumentLoader interface ─────────────────────────────────────────────

    @property
    def max_file_size_bytes(self) -> int:  # type: ignore[override]
        return self._max_bytes

    def supports(self, mime_type: str, filename: str) -> bool:
        """Return True when either the MIME type or the file extension is supported."""
        norm_mime = mime_type.split(";")[0].strip().lower()
        ext = os.path.splitext(filename.lower())[1]
        return norm_mime in SUPPORTED_MIME_TYPES or ext in SUPPORTED_EXTENSIONS

    async def load(
        self,
        file_bytes: bytes,
        filename: str,
        document_id: str,
        mime_type: str = "",
    ) -> DocumentContent:
        """Validate, extract, and clean text from *file_bytes*.

        Args:
            file_bytes:  Raw file content.
            filename:    Original filename — used for format detection and
                         citation display.
            document_id: Stable document identifier.
            mime_type:   MIME type hint; may be empty or ``"application/octet-stream"``.

        Returns:
            :class:`~app.interfaces.core.DocumentContent` with cleaned text and metadata.

        Raises:
            :class:`~app.interfaces.core.UnsupportedFormatError`: Unsupported format.
            :class:`~app.interfaces.core.DocumentLoadError`:       Extraction failure.
            ValueError: File is empty or exceeds :attr:`max_file_size_bytes`.
        """
        self._validate(file_bytes, filename, mime_type)

        norm_mime = mime_type.split(";")[0].strip().lower()
        ext = os.path.splitext(filename.lower())[1]

        if norm_mime == "application/pdf" or ext == ".pdf":
            text, page_count, meta = await self._load_pdf(file_bytes, filename)
        elif (
            norm_mime
            == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            or ext == ".docx"
        ):
            text, page_count, meta = await self._load_docx(file_bytes, filename)
        elif norm_mime in {"text/plain", "text/markdown", "text/x-markdown"} or ext in {
            ".txt",
            ".md",
        }:
            text, page_count, meta = await self._load_text(file_bytes, filename)
        else:
            raise UnsupportedFormatError(filename, mime_type)

        checksum = hashlib.sha256(file_bytes).hexdigest()
        meta["checksum"] = checksum

        cleaned = self._clean_text(text)

        return DocumentContent(
            document_id=document_id,
            text=cleaned,
            page_count=page_count,
            mime_type=norm_mime or _mime_from_ext(ext),
            filename=filename,
            extraction_metadata=meta,
        )

    # ── Validation ────────────────────────────────────────────────────────────

    def _validate(self, file_bytes: bytes, filename: str, mime_type: str) -> None:
        if not file_bytes:
            raise ValueError(f"File '{filename}' is empty.")
        if len(file_bytes) > self._max_bytes:
            mb = self._max_bytes // (1024 * 1024)
            raise ValueError(
                f"File '{filename}' is {len(file_bytes)} bytes, "
                f"which exceeds the {mb} MB limit."
            )
        if not self.supports(mime_type, filename):
            raise UnsupportedFormatError(filename, mime_type)

    # ── Per-format extractors ─────────────────────────────────────────────────

    async def _load_pdf(
        self, file_bytes: bytes, filename: str
    ) -> tuple[str, int, dict[str, Any]]:
        def _extract() -> tuple[str, int, dict[str, Any]]:
            try:
                import pypdf  # lazy import — not required for non-PDF paths

                reader = pypdf.PdfReader(io.BytesIO(file_bytes))
                page_count = len(reader.pages)
                page_spans: list[PageSpan] = []
                parts: list[str] = []
                cursor = 0  # running character offset across all pages

                for page_num, page in enumerate(reader.pages, start=1):
                    page_text = page.extract_text() or ""
                    start = cursor
                    end = cursor + len(page_text)
                    page_spans.append(PageSpan(page_num, start, end))
                    parts.append(page_text)
                    cursor = end + 1  # +1 for the '\n' separator added below

                full_text = "\n".join(parts).strip()
                ocr_used = False

                # OCR fallback for scanned PDFs
                if not full_text or len(full_text) < 20:
                    full_text = _ocr_pdf(file_bytes, filename)
                    ocr_used = True
                    # Rebuild page_spans as a single span covering the whole OCR text
                    page_spans = [PageSpan(1, 0, len(full_text))]

                return full_text, page_count, {
                    "page_spans": page_spans,
                    "ocr_used": str(ocr_used).lower(),
                }

            except (UnsupportedFormatError, DocumentLoadError):
                raise
            except Exception as exc:
                raise DocumentLoadError(
                    stage="pdf_extraction", filename=filename, detail=str(exc)
                ) from exc

        return await asyncio.to_thread(_extract)

    async def _load_docx(
        self, file_bytes: bytes, filename: str
    ) -> tuple[str, int, dict[str, Any]]:
        def _extract() -> tuple[str, int, dict[str, Any]]:
            try:
                import docx  # lazy import

                doc = docx.Document(io.BytesIO(file_bytes))
                paragraphs = [p.text for p in doc.paragraphs if p.text.strip()]

                # Extract heading-level metadata (if present)
                headings = [
                    p.text.strip()
                    for p in doc.paragraphs
                    if p.style and p.style.name.startswith("Heading") and p.text.strip()
                ]
                meta: dict[str, Any] = {"headings": headings}
                return "\n".join(paragraphs), 1, meta

            except (UnsupportedFormatError, DocumentLoadError):
                raise
            except Exception as exc:
                raise DocumentLoadError(
                    stage="docx_extraction", filename=filename, detail=str(exc)
                ) from exc

        return await asyncio.to_thread(_extract)

    async def _load_text(
        self, file_bytes: bytes, filename: str
    ) -> tuple[str, int, dict[str, Any]]:
        """Decode plain-text or Markdown; detect encoding automatically."""
        for encoding in ("utf-8", "utf-8-sig", "latin-1"):
            try:
                text = file_bytes.decode(encoding)
                return text, 1, {"encoding": encoding}
            except UnicodeDecodeError:
                continue
        raise DocumentLoadError(
            stage="text_decode",
            filename=filename,
            detail="File cannot be decoded as UTF-8, UTF-8-BOM, or Latin-1.",
        )

    # ── Text cleaning ─────────────────────────────────────────────────────────

    @staticmethod
    def _clean_text(text: str) -> str:
        """Normalise whitespace and remove control characters from *text*.

        Steps applied in order:
        1. Unicode normalisation (NFC) — compose characters.
        2. Strip NULL bytes and other C0/C1 control characters (except newline,
           carriage return, and tab).
        3. Normalise carriage-return sequences (\\r\\n → \\n, lone \\r → \\n).
        4. Collapse runs of more than two consecutive blank lines into two.
        5. Strip leading/trailing whitespace from each line.
        6. Strip leading/trailing whitespace from the whole document.
        """
        if not text:
            return text

        # 1. Unicode normalisation
        text = unicodedata.normalize("NFC", text)

        # 2. Remove control characters except \\t, \\n, \\r
        text = "".join(
            ch
            for ch in text
            if ch in ("\t", "\n", "\r") or not unicodedata.category(ch).startswith("C")
        )

        # 3. Normalise line endings
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        # 4. Collapse excessive blank lines (more than 2 consecutive empty lines)
        text = re.sub(r"\n{3,}", "\n\n", text)

        # 5. Strip trailing whitespace per line
        lines = [line.rstrip() for line in text.split("\n")]
        text = "\n".join(lines)

        # 6. Strip document-level leading/trailing whitespace
        return text.strip()


# ---------------------------------------------------------------------------
# OCR fallback helper (module-level so it can be tested independently)
# ---------------------------------------------------------------------------


def _ocr_pdf(file_bytes: bytes, filename: str) -> str:
    """Attempt OCR on a scanned PDF using pdf2image + pytesseract.

    Returns an empty string (rather than raising) when the required optional
    libraries are not installed, so callers that only need native extraction
    are not broken.
    """
    try:
        import pytesseract  # lazy import
        from pdf2image import convert_from_bytes  # lazy import

        images = convert_from_bytes(file_bytes)
        texts = [pytesseract.image_to_string(img) for img in images]
        return "\n".join(texts).strip()

    except ImportError:
        return ""
    except Exception as exc:
        raise DocumentLoadError(stage="ocr", filename=filename, detail=str(exc)) from exc


def _mime_from_ext(ext: str) -> str:
    """Return a best-effort MIME type string for a file extension."""
    return {
        ".pdf": "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".txt": "text/plain",
        ".md": "text/markdown",
    }.get(ext.lower(), "application/octet-stream")
