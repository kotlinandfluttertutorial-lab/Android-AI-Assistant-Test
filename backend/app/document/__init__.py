"""Document ingestion pipeline for the AI Assistant RAG system.

Public surface
--------------
DocumentLoader      – validates, extracts, and cleans document text.
DocumentChunker     – splits cleaned text into overlapping token chunks.
ChunkingConfig      – dataclass controlling chunk size, overlap, and limits.
DocumentIngestion   – convenience façade: load + chunk in one call.
PageSpan            – maps a page number to its character offset range.
IngestedDocument    – complete output of a full ingestion run.

Usage::

    from app.document import DocumentLoader, DocumentChunker, ChunkingConfig

    loader = DocumentLoader()
    content = await loader.load(pdf_bytes, "report.pdf", "doc-uuid-1")

    chunker = DocumentChunker(ChunkingConfig(chunk_size=512, overlap=64))
    chunks = chunker.chunk(content)
"""

from app.document.loader import DocumentLoader, PageSpan
from app.document.chunker import ChunkingConfig, DocumentChunker
from app.document.pipeline import DocumentIngestion, IngestedDocument

__all__ = [
    "ChunkingConfig",
    "DocumentChunker",
    "DocumentIngestion",
    "DocumentLoader",
    "IngestedDocument",
    "PageSpan",
]
