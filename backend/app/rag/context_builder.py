# ============================================================
# Android AI Assistant — Backend
# Module  : rag
# File    : context_builder.py
# Purpose : ContextBuilder — turns retrieved chunks into the context
#           block and citation list that are passed to the LLM.
#
# Design rules:
#   - No infrastructure imports (no DB, no LLM, no vector store).
#   - Consistent citation format that mirrors _assemble_context() in
#     RAGService so both code paths produce the same output shape.
#   - Every returned citation carries document_name, page_number (or
#     char offsets for TXT/MD), chunk_index, similarity, and
#     retrieval_path so callers can render rich source references.
# ============================================================
"""ContextBuilder — assembles retrieved chunks into LLM-ready context."""

from __future__ import annotations

from typing import Any

from app.interfaces.core import DocumentChunk, RetrievedChunk

# Prompt header injected above the retrieved context block.
_RAG_SYSTEM_PREFIX = """\
You are a helpful assistant. Answer the question using ONLY the information in the \
provided context. If the context does not contain enough information to answer the \
question, say so clearly and do not invent facts.

When referencing information from the context, cite the source using the inline \
citation tags shown (e.g. [Source: report.pdf, Page 3]).
"""

# Separator between the context section and the user question.
_QUESTION_SEPARATOR = "\n\n---\n\nQuestion: "


class ContextBuilder:
    """Assembles retrieved chunks into an LLM-ready context block.

    Usage::

        builder = ContextBuilder(max_context_chars=8000)
        ctx = builder.build_context(chunks)
        citations = builder.build_citations(chunks)
        prompt = builder.build_prompt(question, chunks)
    """

    def __init__(
        self,
        max_context_chars: int = 12_000,
        include_similarity_in_citation: bool = False,
    ) -> None:
        """
        Args:
            max_context_chars:            Hard cap on the assembled context string
                                          length (in characters).  Chunks are added
                                          in order until the cap is reached.
            include_similarity_in_citation: When True, similarity score is appended
                                          to each citation dict (useful for debug
                                          UIs; disable for production responses).
        """
        self._max_context_chars = max_context_chars
        self._include_similarity = include_similarity_in_citation

    # ── Public API ────────────────────────────────────────────────────────────

    def build_context(self, chunks: list[RetrievedChunk]) -> str:
        """Produce a numbered context block from *chunks*.

        Format::

            Retrieved Context:

            --- Chunk 1 [Source: report.pdf, Page 3] ---
            <chunk text>

            --- Chunk 2 [Source: notes.md, Chars 0-412] ---
            <chunk text>

        Args:
            chunks: Ordered list of :class:`~app.interfaces.core.RetrievedChunk`
                    objects (highest similarity first).

        Returns:
            Multi-line string, or an empty string when *chunks* is empty.
        """
        if not chunks:
            return ""

        lines: list[str] = ["Retrieved Context:", ""]
        total_chars = len("Retrieved Context:\n\n")

        for i, rc in enumerate(chunks, 1):
            citation_tag = _citation_tag(rc.chunk)
            header = f"--- Chunk {i} {citation_tag} ---"
            section = f"{header}\n{rc.chunk.text}\n"

            if total_chars + len(section) > self._max_context_chars:
                # Truncate text to fit within the cap
                remaining = self._max_context_chars - total_chars - len(header) - 2
                if remaining <= 0:
                    break
                truncated_text = rc.chunk.text[:remaining] + "…"
                lines.append(header)
                lines.append(truncated_text)
                lines.append("")
                break  # no more chunks fit

            lines.append(header)
            lines.append(rc.chunk.text)
            lines.append("")
            total_chars += len(section)

        return "\n".join(lines)

    def build_citations(self, chunks: list[RetrievedChunk]) -> list[dict[str, Any]]:
        """Return a list of source citation dicts, one per chunk.

        Each dict has at minimum:
        - ``document_name``  – human-readable filename
        - ``document_id``    – stable UUID string
        - ``page_number``    – 1-based page or ``None`` for TXT/MD
        - ``chunk_index``    – zero-based position in the source document
        - ``retrieval_path`` – how the chunk was found (``"chroma_ann"``, etc.)
        - ``excerpt``        – first 200 chars of the chunk text

        Optional (when ``include_similarity_in_citation=True``):
        - ``similarity``     – cosine similarity score

        Args:
            chunks: Retrieved chunks to cite.

        Returns:
            List of citation dicts in the same order as *chunks*.
        """
        citations: list[dict[str, Any]] = []
        for rc in chunks:
            c = rc.chunk
            cit: dict[str, Any] = {
                "document_name": c.document_name,
                "document_id":   c.document_id,
                "page_number":   c.page_number,
                "chunk_index":   c.chunk_index,
                "char_start":    c.char_start,
                "char_end":      c.char_end,
                "retrieval_path": rc.retrieval_path,
                "excerpt":       c.text[:200],
            }
            if self._include_similarity:
                cit["similarity"] = rc.similarity
            citations.append(cit)
        return citations

    def build_rag_system_prompt(self) -> str:
        """Return the system prompt prefix used for RAG answer generation.

        Returns:
            A string instructing the LLM to answer from context only and
            to include inline citations.
        """
        return _RAG_SYSTEM_PREFIX.strip()

    def build_prompt(self, question: str, chunks: list[RetrievedChunk]) -> str:
        """Assemble the full user-turn prompt: context block + question.

        Args:
            question: The user's natural-language question.
            chunks:   Retrieved context chunks.

        Returns:
            A string that can be passed as ``LLMRequest.prompt``.
        """
        context_block = self.build_context(chunks)
        if context_block:
            return context_block + _QUESTION_SEPARATOR + question
        return question


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _citation_tag(chunk: DocumentChunk) -> str:
    """Format the inline ``[Source: …]`` tag for *chunk*."""
    if chunk.page_number is not None:
        return f"[Source: {chunk.document_name}, Page {chunk.page_number}]"
    if chunk.char_start or chunk.char_end:
        return f"[Source: {chunk.document_name}, Chars {chunk.char_start}-{chunk.char_end}]"
    return f"[Source: {chunk.document_name}]"
