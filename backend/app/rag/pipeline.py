# ============================================================
# Android AI Assistant — Backend
# Module  : rag
# File    : pipeline.py
# Purpose : RAGPipeline — user-facing façade that orchestrates the full
#           RAG flow and owns all top-level error handling.
#
# Design:
#   RAGPipeline is the single entry point callers (API routers, agents)
#   use.  It wraps VectorRetriever and adds:
#     - Structured logging for every stage.
#     - A RAGAnswer dataclass with a richer response shape than
#       RetrievalResult (adds top-level success flag, latency, etc.).
#     - Graceful handling of ALL failure modes (no-docs, embed failure,
#       LLM failure, search failure) with descriptive answers.
#     - Convenience ask() shortcut for the common retrieve-and-generate case.
# ============================================================
"""RAGPipeline — top-level façade for the RAG question-answering flow."""

from __future__ import annotations

import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from app.interfaces.core import IRetriever, RetrievalResult
from app.rag.retriever import RetrievalConfig

logger = logging.getLogger(__name__)


@dataclass
class RAGAnswer:
    """Complete output of one :class:`RAGPipeline` question-answering call.

    Attributes:
        question:       The original user question.
        answer:         LLM-generated answer grounded in the retrieved context.
                        May be a graceful "no information found" message when
                        no relevant chunks were retrieved.
        sources:        List of source citation dicts.  Each dict has at minimum
                        ``document_name``, ``document_id``, ``page_number``,
                        ``chunk_index``, ``retrieval_path``, and ``excerpt``.
        chunk_count:    Number of chunks that were retrieved.
        has_sources:    True when at least one source chunk was retrieved.
        success:        False when the pipeline encountered an unrecoverable error.
        error_message:  Human-readable error description; empty on success.
        latency_ms:     Wall-clock time from question to answer in milliseconds.
        request_id:     Unique ID for this pipeline run (for log correlation).
    """

    question: str
    answer: str
    sources: list[dict[str, Any]] = field(default_factory=list)
    chunk_count: int = 0
    has_sources: bool = False
    success: bool = True
    error_message: str = ""
    latency_ms: float = 0.0
    request_id: str = ""

    @classmethod
    def from_retrieval_result(
        cls,
        question: str,
        result: RetrievalResult,
        latency_ms: float,
        request_id: str,
    ) -> RAGAnswer:
        return cls(
            question=question,
            answer=result.answer,
            sources=result.citations,
            chunk_count=len(result.chunks),
            has_sources=result.has_results,
            success=True,
            latency_ms=latency_ms,
            request_id=request_id,
        )

    @classmethod
    def error(
        cls,
        question: str,
        message: str,
        latency_ms: float,
        request_id: str,
    ) -> RAGAnswer:
        return cls(
            question=question,
            answer=(
                "I was unable to process your question due to a service error. Please try again."
            ),
            sources=[],
            chunk_count=0,
            has_sources=False,
            success=False,
            error_message=message,
            latency_ms=latency_ms,
            request_id=request_id,
        )


class RAGPipeline:
    """Orchestrates the full RAG question-answering pipeline.

    Wraps a :class:`~app.rag.retriever.VectorRetriever` with structured
    logging, timing, and a rich :class:`RAGAnswer` response type.

    Usage::

        from app.rag import RAGPipeline, RetrievalConfig
        from app.rag.retriever import VectorRetriever
        from app.embedding import SentenceTransformerEmbeddingProvider
        from app.vector import ChromaVectorStore
        from app.llm.service import get_llm_service

        pipeline = RAGPipeline(
            retriever=VectorRetriever(
                embedding_provider=SentenceTransformerEmbeddingProvider(),
                vector_store=ChromaVectorStore(),
                llm_service=get_llm_service(),
            ),
            config=RetrievalConfig(top_k=5, min_similarity=0.4),
        )

        answer = await pipeline.ask(user_id="uuid", question="What is X?")
        print(answer.answer)
        for source in answer.sources:
            print(source["document_name"], source["page_number"])
    """

    def __init__(
        self,
        retriever: IRetriever,
        config: RetrievalConfig | None = None,
    ) -> None:
        self._retriever = retriever
        self._config = config or RetrievalConfig()

    # ── Public API ────────────────────────────────────────────────────────────

    async def ask(
        self,
        user_id: str,
        question: str,
        top_k: int | None = None,
        document_ids: list[str] | None = None,
    ) -> RAGAnswer:
        """Retrieve + generate: the primary RAG entry point.

        Delegates to :meth:`retrieve_and_generate` and wraps the result in a
        :class:`RAGAnswer` with timing and structured logging.

        Args:
            user_id:      User whose documents are searched.
            question:     Natural-language question.
            top_k:        Override ``config.top_k``.
            document_ids: Restrict retrieval to these document UUIDs.

        Returns:
            :class:`RAGAnswer` — never raises.
        """
        request_id = str(uuid.uuid4())
        start_ms = time.monotonic() * 1000.0

        logger.info(
            "RAG pipeline ask start",
            extra={
                "request_id": request_id,
                "user_id": _redact_uid(user_id),
                "question_length": len(question),
                "top_k": top_k or self._config.top_k,
            },
        )

        try:
            result = await self._retriever.retrieve_and_generate(
                user_id=user_id,
                query=question,
                top_k=top_k,
                document_ids=document_ids,
            )
        except Exception as exc:
            latency_ms = time.monotonic() * 1000.0 - start_ms
            logger.error(
                "RAG pipeline ask failed",
                extra={
                    "request_id": request_id,
                    "user_id": _redact_uid(user_id),
                    "error": str(exc),
                    "latency_ms": latency_ms,
                },
            )
            return RAGAnswer.error(
                question=question,
                message=str(exc),
                latency_ms=latency_ms,
                request_id=request_id,
            )

        latency_ms = time.monotonic() * 1000.0 - start_ms
        answer = RAGAnswer.from_retrieval_result(
            question=question,
            result=result,
            latency_ms=latency_ms,
            request_id=request_id,
        )

        logger.info(
            "RAG pipeline ask complete",
            extra={
                "request_id": request_id,
                "user_id": _redact_uid(user_id),
                "chunk_count": answer.chunk_count,
                "has_sources": answer.has_sources,
                "answer_length": len(answer.answer),
                "latency_ms": latency_ms,
            },
        )
        return answer

    async def retrieve_only(
        self,
        user_id: str,
        question: str,
        top_k: int | None = None,
        document_ids: list[str] | None = None,
    ) -> RAGAnswer:
        """Retrieve chunks without calling the LLM.

        Useful when callers want to inspect retrieved chunks before deciding
        whether to generate, or when the LLM step is done separately.

        Args:
            user_id:      User whose documents are searched.
            question:     Natural-language query.
            top_k:        Override ``config.top_k``.
            document_ids: Optional document scope.

        Returns:
            :class:`RAGAnswer` with ``answer=""`` and ``sources`` populated.
        """
        request_id = str(uuid.uuid4())
        start_ms = time.monotonic() * 1000.0

        try:
            result = await self._retriever.retrieve(
                user_id=user_id,
                query=question,
                top_k=top_k,
                document_ids=document_ids,
            )
        except Exception as exc:
            latency_ms = time.monotonic() * 1000.0 - start_ms
            logger.warning(
                "RAG pipeline retrieve_only failed: %s", exc,
                extra={"request_id": request_id},
            )
            return RAGAnswer.error(
                question=question,
                message=str(exc),
                latency_ms=latency_ms,
                request_id=request_id,
            )

        # Build citations even when answer is empty so callers can render them
        if result.has_results and not result.citations:
            from app.rag.context_builder import ContextBuilder
            builder = ContextBuilder()
            citations = builder.build_citations(result.chunks)
            result = RetrievalResult(
                query=result.query,
                chunks=result.chunks,
                answer="",
                citations=citations,
            )

        latency_ms = time.monotonic() * 1000.0 - start_ms
        return RAGAnswer.from_retrieval_result(
            question=question,
            result=result,
            latency_ms=latency_ms,
            request_id=request_id,
        )

# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------


def _redact_uid(uid: str) -> str:
    """Keep only the first 8 chars of a user ID to reduce PII in structured logs.

    Matches the same redaction used in :func:`app.orchestration.observer._redact_uid`.
    The truncated prefix is sufficient for log correlation while preventing
    full UUIDs from appearing in Loki / Cloud Logging queries.
    """
    return uid[:8] + "…" if len(uid) > 8 else uid
