# ============================================================
# Android AI Assistant — Backend
# Module  : document
# File    : chunker.py
# Purpose : DocumentChunker — splits extracted text into overlapping
#           token-sized chunks with per-chunk metadata (page number,
#           character offsets, chunk index, checksum).
#
# Design rules:
#   - Tiktoken (BPE) tokenisation — same encoding used by RAGService.
#   - Configurable via ChunkingConfig (size, overlap, min/max guards).
#   - Page-aware: uses PageSpan list from DocumentContent.extraction_metadata
#     to annotate each chunk with the correct page number.
#   - All output fields are part of the DocumentChunk dataclass from
#     app.interfaces.core — no new data types introduced.
#   - Zero infrastructure dependencies — pure Python + tiktoken.
# ============================================================
"""DocumentChunker — tiktoken-based, page-aware text chunker."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from app.document.loader import PageSpan
from app.interfaces.core import DocumentChunk, DocumentContent

# ---------------------------------------------------------------------------
# ChunkingConfig
# ---------------------------------------------------------------------------


@dataclass
class ChunkingConfig:
    """Parameters controlling how :class:`DocumentChunker` splits text.

    Attributes:
        chunk_size:     Target chunk size in BPE tokens.  Clamped to
                        ``[min_chunk_size, max_chunk_size]``.
        overlap:        Number of tokens shared between consecutive chunks.
                        Clamped to ``chunk_size // 2``.
        min_chunk_size: Hard lower bound on ``chunk_size`` (tokens).
        max_chunk_size: Hard upper bound on ``chunk_size`` (tokens).
        tiktoken_model: Model name passed to ``tiktoken.encoding_for_model``.
                        Defaults to ``"gpt-3.5-turbo"`` (cl100k_base BPE) —
                        same as the existing RAGService.

    Validation (enforced in ``__post_init__``)::

        1 ≤ min_chunk_size ≤ chunk_size ≤ max_chunk_size
        overlap ≤ chunk_size // 2
    """

    chunk_size: int = 512
    overlap: int = 64
    min_chunk_size: int = 64
    max_chunk_size: int = 2048
    tiktoken_model: str = "gpt-3.5-turbo"

    def __post_init__(self) -> None:
        if self.min_chunk_size < 1:
            raise ValueError(
                f"ChunkingConfig.min_chunk_size must be ≥ 1, got {self.min_chunk_size}."
            )
        if self.max_chunk_size < self.min_chunk_size:
            raise ValueError(
                f"ChunkingConfig.max_chunk_size ({self.max_chunk_size}) must be ≥ "
                f"min_chunk_size ({self.min_chunk_size})."
            )
        # Clamp chunk_size to [min, max]
        self.chunk_size = max(self.min_chunk_size, min(self.chunk_size, self.max_chunk_size))
        # Clamp overlap to at most chunk_size // 2
        self.overlap = min(self.overlap, self.chunk_size // 2)
        if self.overlap < 0:
            raise ValueError(f"ChunkingConfig.overlap must be ≥ 0, got {self.overlap}.")


# ---------------------------------------------------------------------------
# DocumentChunker
# ---------------------------------------------------------------------------


class DocumentChunker:
    """Splits a :class:`~app.interfaces.core.DocumentContent` into
    :class:`~app.interfaces.core.DocumentChunk` objects.

    Algorithm
    ---------
    1. BPE-tokenise the full document text using tiktoken.
    2. Walk the token list with a sliding window of ``config.chunk_size``
       tokens and stride ``config.chunk_size - config.overlap``.
    3. Decode each window back to a string.
    4. Record the character start/end offsets by tracking decoded lengths.
    5. Resolve the page number for each chunk by checking which
       :class:`~app.document.loader.PageSpan` contains the chunk's
       ``char_start`` offset.  If no page spans are available (DOCX/TXT/MD),
       page_number is ``None``.

    Usage::

        config = ChunkingConfig(chunk_size=256, overlap=32)
        chunker = DocumentChunker(config)
        chunks = chunker.chunk(content, user_id="user-1")
    """

    def __init__(self, config: ChunkingConfig | None = None) -> None:
        self._config = config or ChunkingConfig()

    @property
    def config(self) -> ChunkingConfig:
        return self._config

    def chunk(
        self,
        content: DocumentContent,
        user_id: str = "",
    ) -> list[DocumentChunk]:
        """Split *content* into a list of :class:`~app.interfaces.core.DocumentChunk`.

        Args:
            content:  Loaded document from :class:`~app.document.loader.DocumentLoader`.
            user_id:  Optional owner ID propagated to each :class:`DocumentChunk`.

        Returns:
            Ordered list of :class:`~app.interfaces.core.DocumentChunk`.
            Empty list when ``content.text`` is blank.
        """
        text = content.text
        if not text or not text.strip():
            return []

        import tiktoken  # lazy import — keeps the module importable without tiktoken

        enc = tiktoken.encoding_for_model(self._config.tiktoken_model)
        tokens = enc.encode(text)

        if not tokens:
            return []

        stride = self._config.chunk_size - self._config.overlap
        if stride <= 0:
            stride = max(1, self._config.chunk_size)

        # Extract PageSpan list from extraction_metadata (PDF only)
        page_spans: list[PageSpan] = content.extraction_metadata.get("page_spans", [])

        # Pre-build a character-offset map: tokens[i] starts at char_offsets[i].
        # We build this by decoding each prefix up to token i and measuring length.
        # This is O(n) in number of tokens — acceptable for typical document sizes.
        char_offsets = _build_token_char_offsets(enc, tokens, text)

        chunks: list[DocumentChunk] = []
        chunk_index = 0
        start = 0

        while start < len(tokens):
            end = min(start + self._config.chunk_size, len(tokens))
            chunk_tokens = tokens[start:end]
            chunk_text = enc.decode(chunk_tokens)

            # Character offsets
            char_start = char_offsets[start] if start < len(char_offsets) else 0
            char_end = char_offsets[end] if end < len(char_offsets) else len(text)

            # Page number from page spans (None when spans are absent)
            page_number = _page_for_offset(page_spans, char_start)

            # Stable chunk ID: document_id + index + short hash of content
            short_hash = hashlib.sha256(chunk_text.encode()).hexdigest()[:8]
            chunk_id = f"{content.document_id}_chunk_{chunk_index}_{short_hash}"

            chunks.append(
                DocumentChunk(
                    chunk_id=chunk_id,
                    document_id=content.document_id,
                    document_name=content.filename or content.document_id,
                    user_id=user_id,
                    chunk_index=chunk_index,
                    text=chunk_text,
                    page_number=page_number,
                    char_start=char_start,
                    char_end=char_end,
                )
            )

            chunk_index += 1
            if end == len(tokens):
                break
            start += stride

        return chunks


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_token_char_offsets(enc, tokens: list, text: str) -> list[int]:
    """Build a list mapping token index → character offset in *text*.

    ``offsets[i]`` is the character offset of the start of ``tokens[i]``.
    One extra element is appended representing the end of the last token
    (i.e. ``len(text)`` or the decoded length).

    This matches the approach used in the existing ``rag_service.py``
    ``_build_token_char_offsets`` helper but is implemented here independently
    so the document package is self-contained.
    """
    offsets: list[int] = []
    cursor = 0
    for token in tokens:
        offsets.append(cursor)
        token_text = enc.decode([token])
        cursor += len(token_text)
    offsets.append(cursor)  # sentinel: end of last token
    return offsets


def _page_for_offset(page_spans: list[PageSpan], char_offset: int) -> int | None:
    """Return the 1-based page number containing *char_offset*, or None."""
    for span in page_spans:
        if span.contains(char_offset):
            return span.page_number
    # Fallback: if char_offset is beyond all spans but spans exist, return the last page
    if page_spans:
        return page_spans[-1].page_number
    return None
