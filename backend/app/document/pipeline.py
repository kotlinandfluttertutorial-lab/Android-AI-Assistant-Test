# ============================================================
# Android AI Assistant — Backend
# Module  : document
# File    : pipeline.py
# Purpose : DocumentIngestion — convenience façade that runs the full
#           load → clean → chunk pipeline in a single call.
# ============================================================
"""DocumentIngestion — load + chunk in one call."""

from __future__ import annotations

from dataclasses import dataclass, field

from app.document.chunker import DocumentChunker
from app.document.loader import DocumentLoader
from app.interfaces.core import DocumentChunk, DocumentContent


@dataclass
class IngestedDocument:
    """Complete output of a full document ingestion run.

    Attributes:
        content:  The loaded, cleaned :class:`~app.interfaces.core.DocumentContent`.
        chunks:   Ordered list of :class:`~app.interfaces.core.DocumentChunk`.
        checksum: SHA-256 hex digest of the original raw bytes (from metadata).
    """

    content: DocumentContent
    chunks: list[DocumentChunk] = field(default_factory=list)

    @property
    def checksum(self) -> str:
        return self.content.extraction_metadata.get("checksum", "")

    @property
    def chunk_count(self) -> int:
        return len(self.chunks)


class DocumentIngestion:
    """Façade that runs loader + chunker as a single pipeline.

    Usage::

        pipeline = DocumentIngestion(
            loader=DocumentLoader(),
            chunker=DocumentChunker(ChunkingConfig(chunk_size=512, overlap=64)),
        )
        result = await pipeline.ingest(
            file_bytes=pdf_bytes,
            filename="report.pdf",
            document_id="doc-uuid-1",
            user_id="user-uuid-1",
        )
        # result.content.text   →  cleaned text
        # result.chunks         →  List[DocumentChunk]
        # result.checksum       →  SHA-256 hex of raw bytes
    """

    def __init__(
        self,
        loader: DocumentLoader | None = None,
        chunker: DocumentChunker | None = None,
    ) -> None:
        self._loader = loader or DocumentLoader()
        self._chunker = chunker or DocumentChunker()

    async def ingest(
        self,
        file_bytes: bytes,
        filename: str,
        document_id: str,
        mime_type: str = "",
        user_id: str = "",
    ) -> IngestedDocument:
        """Run the full load → clean → chunk pipeline.

        Args:
            file_bytes:  Raw file content.
            filename:    Original filename (for format detection and citations).
            document_id: Stable document identifier.
            mime_type:   MIME type hint; may be empty.
            user_id:     Owner user ID propagated into every chunk.

        Returns:
            :class:`IngestedDocument` with populated ``content`` and ``chunks``.

        Raises:
            :class:`~app.interfaces.core.UnsupportedFormatError`: Unsupported format.
            :class:`~app.interfaces.core.DocumentLoadError`:       Extraction failure.
            ValueError: File is empty or exceeds the loader's size limit.
        """
        content = await self._loader.load(file_bytes, filename, document_id, mime_type)
        chunks = self._chunker.chunk(content, user_id=user_id)
        return IngestedDocument(content=content, chunks=chunks)
