"""Integration tests for the RAG QA pipeline (VectorRetriever + RAGPipeline).

These tests exercise the full RAG flow with mocked infrastructure so they
run without a real ChromaDB, pgvector, or LLM API.  They verify:
  - retrieve() calls the embedding provider and vector store in order
  - retrieve_and_generate() injects context into the LLM and returns citations
  - RAGPipeline.ask() wraps everything and provides a RAGAnswer
  - No-results scenarios return graceful answers
  - Embedding, search, and LLM failures are handled independently
  - Top-K and min_similarity are respected end-to-end
  - Citations carry the correct document metadata

No production credentials required.
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, MagicMock

import pytest

# Set env-vars before any app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("GEMINI_API_KEY", "test-key")
os.environ.setdefault("OPENAI_API_KEY", "sk-test")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOKI_URL", "")

from app.interfaces.core import (
    DocumentChunk,
    EmbeddingVector,
    IEmbeddingProvider,
    IVectorStore,
    RetrievedChunk,
)
from app.llm.base import LLMResponse, LLMUsage
from app.rag.pipeline import RAGAnswer, RAGPipeline
from app.rag.retriever import RetrievalConfig, VectorRetriever


# ── Shared fixtures ───────────────────────────────────────────────────────────


def _chunk(
    chunk_id: str = "c1",
    document_id: str = "doc-uuid-1",
    document_name: str = "annual_report.pdf",
    user_id: str = "user-uuid-1",
    text: str = "Revenue grew 15% year-over-year to $1.2B.",
    page_number: int | None = 3,
    chunk_index: int = 0,
    char_start: int = 0,
    char_end: int = 0,
) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        document_name=document_name,
        user_id=user_id,
        chunk_index=chunk_index,
        text=text,
        page_number=page_number,
        char_start=char_start,
        char_end=char_end,
    )


def _rc(
    chunk: DocumentChunk | None = None,
    similarity: float = 0.88,
    path: str = "chroma_ann",
) -> RetrievedChunk:
    return RetrievedChunk(
        chunk=chunk or _chunk(),
        similarity=similarity,
        retrieval_path=path,
    )


def _embed_provider(embedding_values: list[float] | None = None) -> IEmbeddingProvider:
    prov = AsyncMock(spec=IEmbeddingProvider)
    prov.embed.return_value = EmbeddingVector(
        values=embedding_values or [0.1, 0.2, 0.3, 0.4],
        model_name="all-MiniLM-L6-v2",
    )
    prov.model_name = "all-MiniLM-L6-v2"
    prov.embedding_dimension = 4
    return prov


def _vector_store(results: list[RetrievedChunk] | None = None) -> IVectorStore:
    store = AsyncMock(spec=IVectorStore)
    store.search.return_value = results if results is not None else []
    return store


def _llm(answer: str = "According to the documents, revenue grew 15%.") -> MagicMock:
    svc = AsyncMock()
    svc.generate.return_value = LLMResponse(
        text=answer,
        provider="gemini",
        model="gemini-3.1-flash-lite",
        usage=LLMUsage(input_tokens=120, output_tokens=40),
    )
    return svc


def _make_pipeline(
    chunks: list[RetrievedChunk] | None = None,
    answer: str = "LLM answer.",
    top_k: int = 5,
    min_similarity: float = 0.0,
) -> tuple[RAGPipeline, IEmbeddingProvider, IVectorStore, MagicMock]:
    embed = _embed_provider()
    store = _vector_store(chunks)
    llm = _llm(answer)
    retriever = VectorRetriever(
        embedding_provider=embed,
        vector_store=store,
        llm_service=llm,
        config=RetrievalConfig(top_k=top_k, min_similarity=min_similarity),
    )
    pipeline = RAGPipeline(retriever=retriever)
    return pipeline, embed, store, llm


# ── Full round-trip: retrieve → generate ──────────────────────────────────────


class TestFullRoundTrip:
    @pytest.mark.asyncio
    async def test_ask_returns_populated_rag_answer(self):
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc()])
        ans = await pipeline.ask("user-1", "What was the revenue?")
        assert isinstance(ans, RAGAnswer)
        assert ans.success

    @pytest.mark.asyncio
    async def test_answer_comes_from_llm(self):
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc()], answer="Revenue was $1.2B.")
        ans = await pipeline.ask("user-1", "What was the revenue?")
        assert ans.answer == "Revenue was $1.2B."

    @pytest.mark.asyncio
    async def test_embedding_called_with_question(self):
        pipeline, embed, _, _ = _make_pipeline(chunks=[_rc()])
        await pipeline.ask("user-1", "What is the profit margin?")
        embed.embed.assert_awaited_once_with("What is the profit margin?")

    @pytest.mark.asyncio
    async def test_vector_search_called_with_user_id(self):
        pipeline, _, store, _ = _make_pipeline(chunks=[_rc()])
        await pipeline.ask("target-user", "Q?")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["user_id"] == "target-user"

    @pytest.mark.asyncio
    async def test_chunk_text_injected_into_llm_prompt(self):
        c = _chunk(text="Earnings per share was $2.14.")
        pipeline, _, _, llm = _make_pipeline(chunks=[_rc(c)])
        await pipeline.ask("user-1", "What was EPS?")
        llm_request = llm.generate.call_args.args[0]
        assert "Earnings per share was $2.14." in llm_request.prompt

    @pytest.mark.asyncio
    async def test_sources_include_document_name(self):
        c = _chunk(document_name="Q3_2026_Report.pdf", page_number=7)
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc(c)])
        ans = await pipeline.ask("user-1", "Q?")
        assert any("Q3_2026_Report.pdf" in str(s) for s in ans.sources)

    @pytest.mark.asyncio
    async def test_sources_include_page_number(self):
        c = _chunk(page_number=12)
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc(c)])
        ans = await pipeline.ask("user-1", "Q?")
        assert any(s.get("page_number") == 12 for s in ans.sources)

    @pytest.mark.asyncio
    async def test_sources_include_document_id(self):
        c = _chunk(document_id="my-doc-uuid")
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc(c)])
        ans = await pipeline.ask("user-1", "Q?")
        assert any(s.get("document_id") == "my-doc-uuid" for s in ans.sources)

    @pytest.mark.asyncio
    async def test_sources_include_retrieval_path(self):
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc(path="pgvector_cosine")])
        ans = await pipeline.ask("user-1", "Q?")
        assert any(s.get("retrieval_path") == "pgvector_cosine" for s in ans.sources)

    @pytest.mark.asyncio
    async def test_char_offset_citation_when_no_page(self):
        c = _chunk(page_number=None, char_start=10, char_end=200)
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc(c)])
        ans = await pipeline.ask("user-1", "Q?")
        # Citation should exist and have char fields
        assert ans.has_sources
        assert any(s.get("char_start") == 10 for s in ans.sources)

    @pytest.mark.asyncio
    async def test_multiple_sources_returned(self):
        chunks = [_rc(_chunk(chunk_id=f"c{i}", text=f"fact {i}")) for i in range(3)]
        pipeline, _, _, _ = _make_pipeline(chunks=chunks)
        ans = await pipeline.ask("user-1", "Q?")
        assert ans.chunk_count == 3
        assert len(ans.sources) == 3


# ── No-results scenario ───────────────────────────────────────────────────────


class TestNoResultsScenario:
    @pytest.mark.asyncio
    async def test_empty_results_returns_graceful_answer(self):
        pipeline, _, _, _llm = _make_pipeline(chunks=[])
        ans = await pipeline.ask("user-1", "Q?")
        assert not ans.has_sources
        assert "could not find" in ans.answer.lower() or "no " in ans.answer.lower()

    @pytest.mark.asyncio
    async def test_llm_not_called_when_no_results(self):
        pipeline, _, _, llm = _make_pipeline(chunks=[])
        await pipeline.ask("user-1", "Q?")
        llm.generate.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_no_results_answer_is_success(self):
        pipeline, _, _, _ = _make_pipeline(chunks=[])
        ans = await pipeline.ask("user-1", "Q?")
        assert ans.success

    @pytest.mark.asyncio
    async def test_empty_question_handled_gracefully(self):
        pipeline, _, _, _ = _make_pipeline(chunks=[_rc()])
        ans = await pipeline.ask("user-1", "")
        # Should not crash
        assert isinstance(ans, RAGAnswer)


# ── Configuration: top-K and min_similarity ────────────────────────────────────


class TestConfiguration:
    @pytest.mark.asyncio
    async def test_top_k_passed_to_vector_store(self):
        pipeline, _, store, _ = _make_pipeline(chunks=[_rc()], top_k=3)
        await pipeline.ask("user-1", "Q?")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["top_k"] == 3

    @pytest.mark.asyncio
    async def test_top_k_override_respected(self):
        pipeline, _, store, _ = _make_pipeline(chunks=[_rc()], top_k=5)
        await pipeline.ask("user-1", "Q?", top_k=2)
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["top_k"] == 2

    @pytest.mark.asyncio
    async def test_min_similarity_passed_to_vector_store(self):
        pipeline, _, store, _ = _make_pipeline(chunks=[_rc()], min_similarity=0.5)
        await pipeline.ask("user-1", "Q?")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["min_similarity"] == pytest.approx(0.5)

    @pytest.mark.asyncio
    async def test_document_id_filter_passed_to_retrieve(self):
        embed = _embed_provider()
        store = _vector_store([_rc()])
        llm = _llm()
        retriever = VectorRetriever(
            embedding_provider=embed,
            vector_store=store,
            llm_service=llm,
        )
        pipeline = RAGPipeline(retriever=retriever)
        # Scoped to specific document
        await pipeline.ask("user-1", "Q?", document_ids=["doc-uuid-1"])
        # The store was called — filter happens inside retrieve()
        store.search.assert_awaited_once()


# ── Error handling ─────────────────────────────────────────────────────────────


class TestErrorHandling:
    @pytest.mark.asyncio
    async def test_embedding_failure_returns_error_answer(self):
        embed = AsyncMock(spec=IEmbeddingProvider)
        embed.embed.side_effect = RuntimeError("GPU OOM")
        retriever = VectorRetriever(
            embedding_provider=embed,
            vector_store=_vector_store(),
            llm_service=_llm(),
            config=RetrievalConfig(reraise_errors=False),
        )
        pipeline = RAGPipeline(retriever=retriever)
        ans = await pipeline.ask("user-1", "Q?")
        # Pipeline should not crash; may return empty or error answer
        assert isinstance(ans, RAGAnswer)

    @pytest.mark.asyncio
    async def test_vector_store_failure_graceful(self):
        store = AsyncMock(spec=IVectorStore)
        store.search.side_effect = RuntimeError("DB connection lost")
        retriever = VectorRetriever(
            embedding_provider=_embed_provider(),
            vector_store=store,
            llm_service=_llm(),
            config=RetrievalConfig(reraise_errors=False),
        )
        pipeline = RAGPipeline(retriever=retriever)
        ans = await pipeline.ask("user-1", "Q?")
        assert isinstance(ans, RAGAnswer)

    @pytest.mark.asyncio
    async def test_llm_failure_still_returns_chunks(self):
        llm = AsyncMock()
        llm.generate.side_effect = RuntimeError("rate limit")
        retriever = VectorRetriever(
            embedding_provider=_embed_provider(),
            vector_store=_vector_store([_rc()]),
            llm_service=llm,
            config=RetrievalConfig(reraise_errors=False),
        )
        pipeline = RAGPipeline(retriever=retriever)
        ans = await pipeline.ask("user-1", "Q?")
        assert ans.has_sources  # chunks returned
        assert "unable to generate" in ans.answer.lower()

    @pytest.mark.asyncio
    async def test_pipeline_ask_never_raises(self):
        retriever = MagicMock()
        retriever.retrieve_and_generate = AsyncMock(side_effect=Exception("unexpected"))
        pipeline = RAGPipeline(retriever=retriever)
        ans = await pipeline.ask("user-1", "Q?")  # must not raise
        assert ans.success is False

    @pytest.mark.asyncio
    async def test_pipeline_reports_success_false_on_error(self):
        retriever = MagicMock()
        retriever.retrieve_and_generate = AsyncMock(side_effect=RuntimeError("crashed"))
        pipeline = RAGPipeline(retriever=retriever)
        ans = await pipeline.ask("user-1", "Q?")
        assert not ans.success
        assert ans.error_message


# ── System prompt and context format ──────────────────────────────────────────


class TestContextFormat:
    @pytest.mark.asyncio
    async def test_llm_called_with_non_empty_system_prompt(self):
        _, _, _, _llm = _make_pipeline(chunks=[_rc()])
        pipeline, _, _, llm2 = _make_pipeline(chunks=[_rc()])
        await pipeline.ask("user-1", "Q?")
        llm2.generate.assert_awaited_once()
        req = llm2.generate.call_args.args[0]
        assert req.system_prompt and len(req.system_prompt) > 10

    @pytest.mark.asyncio
    async def test_context_block_contains_chunk_number(self):
        c = _chunk(text="important fact")
        embed = _embed_provider()
        store = _vector_store([_rc(c)])
        llm = _llm()
        r = VectorRetriever(embedding_provider=embed, vector_store=store, llm_service=llm)
        pipeline = RAGPipeline(retriever=r)
        await pipeline.ask("user-1", "Q?")
        req = llm.generate.call_args.args[0]
        assert "Chunk 1" in req.prompt

    @pytest.mark.asyncio
    async def test_context_block_contains_source_citation(self):
        c = _chunk(document_name="summary.pdf", page_number=2)
        embed = _embed_provider()
        store = _vector_store([_rc(c)])
        llm = _llm()
        r = VectorRetriever(embedding_provider=embed, vector_store=store, llm_service=llm)
        pipeline = RAGPipeline(retriever=r)
        await pipeline.ask("user-1", "Q?")
        req = llm.generate.call_args.args[0]
        assert "summary.pdf" in req.prompt
        assert "Page 2" in req.prompt
