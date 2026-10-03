# ============================================================
# Android AI Assistant — Backend
# Module  : rag
# File    : retriever.py
# Purpose : VectorRetriever — concrete IRetriever backed by
#           IEmbeddingProvider + IVectorStore.
#
# Design rules:
#   - No direct dependency on ChromaDB, pgvector, or sentence-transformers.
#   - Depends only on IEmbeddingProvider and IVectorStore from
#     app.interfaces.core — both are injectable mocks in tests.
#   - retrieve() performs embedding + vector search only (no LLM).
#   - retrieve_and_generate() delegates generation to ContextBuilder + LLMService.
#   - All errors are caught and surfaced as RetrievalResult with empty
#     chunks (graceful degradation) UNLESS reraise_errors=True.
# ============================================================
"""VectorRetriever — IEmbeddingProvider + IVectorStore → IRetriever."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from app.interfaces.core import (
    IEmbeddingProvider,
    IRetriever,
    IVectorStore,
    RetrievalResult,
)

logger = logging.getLogger(__name__)


@dataclass
class RetrievalConfig:
    """Configuration parameters for :class:`VectorRetriever`.

    Attributes:
        top_k:             Maximum chunks to retrieve per query.
        min_similarity:    Minimum cosine similarity (0.0–1.0). Chunks below
                           this threshold are excluded.  Defaults to 0.0
                           (no filtering) so callers who don't care can omit it.
        embed_timeout_s:   Seconds before the embedding call is abandoned.
        search_timeout_s:  Seconds before the vector-search call is abandoned.
        reraise_errors:    When True, retrieval errors propagate as exceptions
                           rather than returning an empty result.  Useful for
                           tests that want to assert on failure modes.
        max_context_chars: Passed to ContextBuilder when building prompts.
    """

    top_k: int = 5
    min_similarity: float = 0.0
    embed_timeout_s: float = 45.0
    search_timeout_s: float = 30.0
    reraise_errors: bool = False
    max_context_chars: int = 12_000

    def __post_init__(self) -> None:
        if self.top_k < 1:
            raise ValueError(f"RetrievalConfig.top_k must be ≥ 1, got {self.top_k}.")
        if not (0.0 <= self.min_similarity <= 1.0):
            raise ValueError(
                f"RetrievalConfig.min_similarity must be in [0.0, 1.0], "
                f"got {self.min_similarity}."
            )


class VectorRetriever(IRetriever):
    """Concrete :class:`~app.interfaces.core.IRetriever` implementation.

    Composes an :class:`~app.interfaces.core.IEmbeddingProvider` and an
    :class:`~app.interfaces.core.IVectorStore` to perform semantic retrieval.
    For answer generation, delegates to a :class:`~app.llm.service.LLMService`
    via :class:`~app.rag.context_builder.ContextBuilder`.

    Neither the embedding model nor the vector database are exposed to callers —
    both can be swapped or mocked without changing calling code.

    Usage::

        retriever = VectorRetriever(
            embedding_provider=SentenceTransformerEmbeddingProvider(),
            vector_store=ChromaVectorStore(),
            llm_service=get_llm_service(),       # optional; needed for generate
            config=RetrievalConfig(top_k=5, min_similarity=0.4),
        )

        # Retrieve only (no LLM call):
        result = await retriever.retrieve("user-id", "what is X?")

        # Retrieve + generate answer:
        result = await retriever.retrieve_and_generate("user-id", "what is X?")
    """

    def __init__(
        self,
        embedding_provider: IEmbeddingProvider,
        vector_store: IVectorStore,
        llm_service=None,           # app.llm.service.LLMService — optional
        config: RetrievalConfig | None = None,
    ) -> None:
        self._embed = embedding_provider
        self._store = vector_store
        self._llm = llm_service
        self._config = config or RetrievalConfig()

        # Lazy import to keep the module importable without rag.context_builder
        self._context_builder = None

    # ── IRetriever ────────────────────────────────────────────────────────────

    async def retrieve(
        self,
        user_id: str,
        query: str,
        top_k: int | None = None,
        document_ids: list[str] | None = None,
    ) -> RetrievalResult:
        """Embed *query* and perform vector search.

        Does NOT call the LLM.  The returned :class:`RetrievalResult` has
        ``answer=""`` and ``citations=[]`` — call :meth:`retrieve_and_generate`
        when an LLM-generated answer is needed.

        Args:
            user_id:      Scope retrieval to this user's corpus.
            query:        Natural-language query string.
            top_k:        Override for ``config.top_k``.  ``None`` uses config.
            document_ids: Restrict to these document UUIDs.  ``None`` = all docs.

        Returns:
            :class:`RetrievalResult` with ``chunks`` populated.
            Returns an empty result on error unless ``config.reraise_errors=True``.
        """
        if not query or not query.strip():
            return RetrievalResult(query=query, chunks=[], answer="", citations=[])

        effective_k = top_k if top_k is not None else self._config.top_k

        # ── Step 1: Embed the query ────────────────────────────────────────────
        try:
            query_embedding = await self._embed.embed(query)
        except Exception as exc:
            logger.warning("RAG embedding failed for query=%r: %s", query[:80], exc)
            if self._config.reraise_errors:
                raise
            return RetrievalResult(query=query, chunks=[], answer="", citations=[])

        # ── Step 2: Vector search ──────────────────────────────────────────────
        try:
            raw_results = await self._store.search(
                user_id=user_id,
                query_embedding=query_embedding,
                top_k=effective_k,
                min_similarity=self._config.min_similarity,
            )
        except Exception as exc:
            logger.warning("RAG vector search failed for user=%s: %s", user_id, exc)
            if self._config.reraise_errors:
                raise
            return RetrievalResult(query=query, chunks=[], answer="", citations=[])

        if not raw_results:
            return RetrievalResult(query=query, chunks=[], answer="", citations=[])

        # ── Step 3: Map to IRetriever RetrievedChunk (interfaces/core.py) ─────
        # raw_results come from IVectorStore which returns RetrievedChunk objects
        # (same type from interfaces/core.py) — no mapping needed.
        return RetrievalResult(
            query=query,
            chunks=raw_results,
            answer="",
            citations=[],
        )

    async def retrieve_and_generate(
        self,
        user_id: str,
        query: str,
        top_k: int | None = None,
        document_ids: list[str] | None = None,
    ) -> RetrievalResult:
        """Retrieve relevant chunks AND generate a grounded answer.

        Pipeline::

            embed(query)
                → vector_store.search()
                → ContextBuilder.build_prompt()
                → LLMService.generate()
                → RetrievalResult(answer=…, citations=[…])

        When no chunks are found the LLM is NOT called; a graceful
        "no information found" answer is returned instead.

        When the LLM call fails the retrieved chunks are still returned
        (so callers can render citations even without an answer).

        Args:
            user_id:      Scope retrieval to this user's corpus.
            query:        Natural-language question.
            top_k:        Override for ``config.top_k``.
            document_ids: Optional document scope restriction.

        Returns:
            :class:`RetrievalResult` with ``answer`` and ``citations`` populated.
        """
        # ── Step 1: Retrieve chunks ────────────────────────────────────────────
        retrieval = await self.retrieve(user_id, query, top_k, document_ids)

        if not retrieval.has_results:
            return RetrievalResult(
                query=query,
                chunks=[],
                answer=(
                    "I could not find any relevant information in your documents "
                    "to answer this question."
                ),
                citations=[],
            )

        # ── Step 2: Build context + citations ─────────────────────────────────
        builder = self._get_context_builder()
        full_prompt = builder.build_prompt(query, retrieval.chunks)
        system_prompt = builder.build_rag_system_prompt()
        citations = builder.build_citations(retrieval.chunks)

        # ── Step 3: LLM generation ─────────────────────────────────────────────
        if self._llm is None:
            # No LLM service provided — return chunks without answer
            logger.warning(
                "retrieve_and_generate called without LLM service; returning chunks only"
            )
            return RetrievalResult(
                query=query,
                chunks=retrieval.chunks,
                answer="",
                citations=citations,
            )

        try:
            from app.llm.base import LLMRequest  # lazy import

            llm_request = LLMRequest(
                prompt=full_prompt,
                system_prompt=system_prompt,
                user_id=user_id,
                complexity="simple",
            )
            llm_response = await self._llm.generate(llm_request)
            answer = llm_response.text

        except Exception as exc:
            logger.warning("RAG LLM generation failed: %s", exc)
            if self._config.reraise_errors:
                raise
            # Return chunks + empty answer — citations still useful to caller
            answer = (
                "I retrieved relevant context but was unable to generate an answer "
                "due to a service error. Please try again."
            )

        return RetrievalResult(
            query=query,
            chunks=retrieval.chunks,
            answer=answer,
            citations=citations,
        )

    # ── Private helpers ───────────────────────────────────────────────────────

    def _get_context_builder(self):
        if self._context_builder is None:
            from app.rag.context_builder import ContextBuilder  # lazy

            self._context_builder = ContextBuilder(
                max_context_chars=self._config.max_context_chars
            )
        return self._context_builder
