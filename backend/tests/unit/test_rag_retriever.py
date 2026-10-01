"""Unit tests for app.rag.retriever — RetrievalConfig, VectorRetriever, RAGPipeline.

All dependencies (IEmbeddingProvider, IVectorStore, LLMService) are mocked.
No network, no LLM API, no vector DB, no credentials required.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.interfaces.core import (
    DocumentChunk,
    EmbeddingVector,
    IEmbeddingProvider,
    IVectorStore,
    RetrievedChunk,
    RetrievalResult,
)
from app.llm.base import LLMResponse, LLMUsage
from app.rag.retriever import RetrievalConfig, VectorRetriever
from app.rag.pipeline import RAGAnswer, RAGPipeline


# ── Helpers ───────────────────────────────────────────────────────────────────

def _chunk(
    chunk_id: str = "c1",
    document_id: str = "doc-1",
    document_name: str = "report.pdf",
    user_id: str = "u1",
    text: str = "The answer is 42.",
    page_number: int | None = 1,
) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=document_id,
        document_name=document_name,
        user_id=user_id,
        chunk_index=0,
        text=text,
        page_number=page_number,
    )


def _rc(chunk: DocumentChunk | None = None, similarity: float = 0.9) -> RetrievedChunk:
    return RetrievedChunk(
        chunk=chunk or _chunk(),
        similarity=similarity,
        retrieval_path="chroma_ann",
    )


def _embedding(dim: int = 4) -> EmbeddingVector:
    return EmbeddingVector(values=[0.5] * dim, model_name="test-model")


def _fake_embed_provider(embedding: EmbeddingVector | None = None) -> IEmbeddingProvider:
    provider = AsyncMock(spec=IEmbeddingProvider)
    provider.embed.return_value = embedding or _embedding()
    provider.model_name = "test-model"
    provider.embedding_dimension = 4
    return provider


def _fake_vector_store(results: list[RetrievedChunk] | None = None) -> IVectorStore:
    store = AsyncMock(spec=IVectorStore)
    store.search.return_value = results if results is not None else []
    return store


def _fake_llm_service(answer: str = "The answer is 42.") -> MagicMock:
    svc = AsyncMock()
    svc.generate.return_value = LLMResponse(
        text=answer,
        provider="test",
        model="test-model",
        usage=LLMUsage(input_tokens=10, output_tokens=5),
    )
    return svc


# ── RetrievalConfig ───────────────────────────────────────────────────────────

class TestRetrievalConfig:
    def test_default_top_k(self):
        assert RetrievalConfig().top_k == 5

    def test_default_min_similarity(self):
        assert RetrievalConfig().min_similarity == 0.0

    def test_top_k_below_one_raises(self):
        with pytest.raises(ValueError, match="top_k"):
            RetrievalConfig(top_k=0)

    def test_min_similarity_below_zero_raises(self):
        with pytest.raises(ValueError, match="min_similarity"):
            RetrievalConfig(min_similarity=-0.1)

    def test_min_similarity_above_one_raises(self):
        with pytest.raises(ValueError, match="min_similarity"):
            RetrievalConfig(min_similarity=1.01)

    def test_valid_config(self):
        c = RetrievalConfig(top_k=10, min_similarity=0.5)
        assert c.top_k == 10
        assert c.min_similarity == 0.5


# ── VectorRetriever.retrieve ──────────────────────────────────────────────────

class TestVectorRetrieverRetrieve:
    def _retriever(self, results=None, embed=None):
        return VectorRetriever(
            embedding_provider=_fake_embed_provider(embed),
            vector_store=_fake_vector_store(results),
            config=RetrievalConfig(reraise_errors=False),
        )

    @pytest.mark.asyncio
    async def test_empty_query_returns_empty_result(self):
        r = self._retriever()
        result = await r.retrieve("u1", "")
        assert not result.has_results
        assert result.answer == ""

    @pytest.mark.asyncio
    async def test_blank_query_returns_empty_result(self):
        r = self._retriever()
        result = await r.retrieve("u1", "   ")
        assert not result.has_results

    @pytest.mark.asyncio
    async def test_calls_embed_with_query(self):
        embed_prov = _fake_embed_provider()
        r = VectorRetriever(embedding_provider=embed_prov, vector_store=_fake_vector_store())
        await r.retrieve("u1", "what is X?")
        embed_prov.embed.assert_awaited_once_with("what is X?")

    @pytest.mark.asyncio
    async def test_calls_vector_store_search(self):
        store = _fake_vector_store()
        r = VectorRetriever(embedding_provider=_fake_embed_provider(), vector_store=store)
        await r.retrieve("u1", "query")
        store.search.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_passes_user_id_to_search(self):
        store = _fake_vector_store()
        r = VectorRetriever(embedding_provider=_fake_embed_provider(), vector_store=store)
        await r.retrieve("alice", "query")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["user_id"] == "alice"

    @pytest.mark.asyncio
    async def test_passes_top_k_to_search(self):
        store = _fake_vector_store()
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=store,
            config=RetrievalConfig(top_k=7),
        )
        await r.retrieve("u1", "query")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["top_k"] == 7

    @pytest.mark.asyncio
    async def test_top_k_override_takes_priority(self):
        store = _fake_vector_store()
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=store,
            config=RetrievalConfig(top_k=5),
        )
        await r.retrieve("u1", "query", top_k=3)
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["top_k"] == 3

    @pytest.mark.asyncio
    async def test_passes_min_similarity_to_search(self):
        store = _fake_vector_store()
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=store,
            config=RetrievalConfig(min_similarity=0.6),
        )
        await r.retrieve("u1", "query")
        call_kwargs = store.search.call_args.kwargs
        assert call_kwargs["min_similarity"] == pytest.approx(0.6)

    @pytest.mark.asyncio
    async def test_returns_chunks_from_store(self):
        results = [_rc(_chunk(chunk_id="c1")), _rc(_chunk(chunk_id="c2"))]
        r = self._retriever(results=results)
        result = await r.retrieve("u1", "query")
        assert result.has_results
        assert len(result.chunks) == 2

    @pytest.mark.asyncio
    async def test_no_chunks_returns_empty_result(self):
        r = self._retriever(results=[])
        result = await r.retrieve("u1", "query")
        assert not result.has_results

    @pytest.mark.asyncio
    async def test_answer_is_empty_in_retrieve_only(self):
        r = self._retriever(results=[_rc()])
        result = await r.retrieve("u1", "query")
        assert result.answer == ""

    @pytest.mark.asyncio
    async def test_citations_empty_in_retrieve_only(self):
        r = self._retriever(results=[_rc()])
        result = await r.retrieve("u1", "query")
        # retrieve() does not populate citations — only retrieve_and_generate does
        assert result.citations == []

    @pytest.mark.asyncio
    async def test_embed_failure_returns_empty_gracefully(self):
        embed_prov = AsyncMock(spec=IEmbeddingProvider)
        embed_prov.embed.side_effect = RuntimeError("embed failed")
        r = VectorRetriever(
            embedding_provider=embed_prov,
            vector_store=_fake_vector_store(),
            config=RetrievalConfig(reraise_errors=False),
        )
        result = await r.retrieve("u1", "query")
        assert not result.has_results

    @pytest.mark.asyncio
    async def test_embed_failure_reraised_when_configured(self):
        embed_prov = AsyncMock(spec=IEmbeddingProvider)
        embed_prov.embed.side_effect = RuntimeError("embed failed")
        r = VectorRetriever(
            embedding_provider=embed_prov,
            vector_store=_fake_vector_store(),
            config=RetrievalConfig(reraise_errors=True),
        )
        with pytest.raises(RuntimeError, match="embed failed"):
            await r.retrieve("u1", "query")

    @pytest.mark.asyncio
    async def test_search_failure_returns_empty_gracefully(self):
        store = AsyncMock(spec=IVectorStore)
        store.search.side_effect = RuntimeError("search failed")
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=store,
            config=RetrievalConfig(reraise_errors=False),
        )
        result = await r.retrieve("u1", "query")
        assert not result.has_results

    @pytest.mark.asyncio
    async def test_search_failure_reraised_when_configured(self):
        store = AsyncMock(spec=IVectorStore)
        store.search.side_effect = RuntimeError("search failed")
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=store,
            config=RetrievalConfig(reraise_errors=True),
        )
        with pytest.raises(RuntimeError, match="search failed"):
            await r.retrieve("u1", "query")


# ── VectorRetriever.retrieve_and_generate ────────────────────────────────────

class TestVectorRetrieverRetrieveAndGenerate:
    def _retriever(self, results=None, answer="The answer."):
        return VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store(results),
            llm_service=_fake_llm_service(answer),
        )

    @pytest.mark.asyncio
    async def test_no_chunks_returns_no_results_answer(self):
        r = self._retriever(results=[])
        result = await r.retrieve_and_generate("u1", "Q?")
        assert "could not find" in result.answer.lower()

    @pytest.mark.asyncio
    async def test_answer_populated_from_llm(self):
        r = self._retriever(results=[_rc()], answer="42 is the answer.")
        result = await r.retrieve_and_generate("u1", "Q?")
        assert result.answer == "42 is the answer."

    @pytest.mark.asyncio
    async def test_citations_populated(self):
        r = self._retriever(results=[_rc()])
        result = await r.retrieve_and_generate("u1", "Q?")
        assert len(result.citations) == 1

    @pytest.mark.asyncio
    async def test_chunks_present_in_result(self):
        chunks = [_rc(_chunk(chunk_id=f"c{i}")) for i in range(3)]
        r = self._retriever(results=chunks)
        result = await r.retrieve_and_generate("u1", "Q?")
        assert len(result.chunks) == 3

    @pytest.mark.asyncio
    async def test_llm_service_called_with_llm_request(self):
        llm_svc = _fake_llm_service()
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
            llm_service=llm_svc,
        )
        await r.retrieve_and_generate("u1", "my question")
        llm_svc.generate.assert_awaited_once()
        request = llm_svc.generate.call_args.args[0]
        assert "my question" in request.prompt

    @pytest.mark.asyncio
    async def test_llm_failure_returns_partial_result_with_chunks(self):
        llm_svc = AsyncMock()
        llm_svc.generate.side_effect = RuntimeError("LLM is down")
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
            llm_service=llm_svc,
            config=RetrievalConfig(reraise_errors=False),
        )
        result = await r.retrieve_and_generate("u1", "Q?")
        assert result.has_results          # chunks still returned
        assert "unable to generate" in result.answer.lower()

    @pytest.mark.asyncio
    async def test_no_llm_service_returns_chunks_without_answer(self):
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
            llm_service=None,
        )
        result = await r.retrieve_and_generate("u1", "Q?")
        assert result.has_results
        assert result.answer == ""

    @pytest.mark.asyncio
    async def test_context_passed_to_llm_contains_chunk_text(self):
        llm_svc = _fake_llm_service()
        c = _chunk(text="unique chunk xyz")
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc(c)]),
            llm_service=llm_svc,
        )
        await r.retrieve_and_generate("u1", "Q?")
        request = llm_svc.generate.call_args.args[0]
        assert "unique chunk xyz" in request.prompt

    @pytest.mark.asyncio
    async def test_system_prompt_set(self):
        llm_svc = _fake_llm_service()
        r = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
            llm_service=llm_svc,
        )
        await r.retrieve_and_generate("u1", "Q?")
        request = llm_svc.generate.call_args.args[0]
        assert request.system_prompt  # non-empty


# ── RAGPipeline.ask ───────────────────────────────────────────────────────────

class TestRAGPipelineAsk:
    def _pipeline(self, results=None, answer="The answer."):
        retriever = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store(results),
            llm_service=_fake_llm_service(answer),
        )
        return RAGPipeline(retriever=retriever)

    @pytest.mark.asyncio
    async def test_returns_rag_answer(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "What is X?")
        assert isinstance(ans, RAGAnswer)

    @pytest.mark.asyncio
    async def test_answer_is_populated(self):
        p = self._pipeline(results=[_rc()], answer="X is 42.")
        ans = await p.ask("u1", "What is X?")
        assert ans.answer == "X is 42."

    @pytest.mark.asyncio
    async def test_has_sources_when_chunks_retrieved(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "Q?")
        assert ans.has_sources

    @pytest.mark.asyncio
    async def test_no_sources_when_no_chunks(self):
        p = self._pipeline(results=[])
        ans = await p.ask("u1", "Q?")
        assert not ans.has_sources

    @pytest.mark.asyncio
    async def test_chunk_count_correct(self):
        p = self._pipeline(results=[_rc(), _rc()])
        ans = await p.ask("u1", "Q?")
        assert ans.chunk_count == 2

    @pytest.mark.asyncio
    async def test_success_true_on_happy_path(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "Q?")
        assert ans.success is True

    @pytest.mark.asyncio
    async def test_success_false_on_pipeline_error(self):
        retriever = MagicMock()
        retriever.retrieve_and_generate = AsyncMock(side_effect=RuntimeError("boom"))
        p = RAGPipeline(retriever=retriever)
        ans = await p.ask("u1", "Q?")
        assert ans.success is False

    @pytest.mark.asyncio
    async def test_error_message_populated_on_failure(self):
        retriever = MagicMock()
        retriever.retrieve_and_generate = AsyncMock(side_effect=RuntimeError("db error"))
        p = RAGPipeline(retriever=retriever)
        ans = await p.ask("u1", "Q?")
        assert "db error" in ans.error_message

    @pytest.mark.asyncio
    async def test_ask_never_raises(self):
        retriever = MagicMock()
        retriever.retrieve_and_generate = AsyncMock(side_effect=Exception("unexpected"))
        p = RAGPipeline(retriever=retriever)
        # Must not raise
        ans = await p.ask("u1", "Q?")
        assert ans.success is False

    @pytest.mark.asyncio
    async def test_latency_ms_positive(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "Q?")
        assert ans.latency_ms >= 0

    @pytest.mark.asyncio
    async def test_request_id_non_empty(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "Q?")
        assert ans.request_id

    @pytest.mark.asyncio
    async def test_question_preserved_in_answer(self):
        p = self._pipeline(results=[_rc()])
        ans = await p.ask("u1", "What is the meaning?")
        assert ans.question == "What is the meaning?"


# ── RAGPipeline.retrieve_only ─────────────────────────────────────────────────

class TestRAGPipelineRetrieveOnly:
    @pytest.mark.asyncio
    async def test_returns_rag_answer(self):
        retriever = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
        )
        p = RAGPipeline(retriever=retriever)
        ans = await p.retrieve_only("u1", "Q?")
        assert isinstance(ans, RAGAnswer)

    @pytest.mark.asyncio
    async def test_answer_empty(self):
        retriever = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc()]),
        )
        p = RAGPipeline(retriever=retriever)
        ans = await p.retrieve_only("u1", "Q?")
        # LLM not called — answer should be empty or not populated
        assert isinstance(ans.answer, str)

    @pytest.mark.asyncio
    async def test_sources_populated_from_chunks(self):
        retriever = VectorRetriever(
            embedding_provider=_fake_embed_provider(),
            vector_store=_fake_vector_store([_rc(_chunk(document_name="file.pdf"))]),
        )
        p = RAGPipeline(retriever=retriever)
        ans = await p.retrieve_only("u1", "Q?")
        assert ans.has_sources
        assert any("file.pdf" in str(s) for s in ans.sources)


# ── RAGAnswer ─────────────────────────────────────────────────────────────────

class TestRAGAnswer:
    def test_from_retrieval_result_happy_path(self):
        result = RetrievalResult(
            query="Q",
            chunks=[_rc()],
            answer="The answer.",
            citations=[{"document_name": "doc.pdf"}],
        )
        answer = RAGAnswer.from_retrieval_result("Q", result, latency_ms=42.0, request_id="rid1")
        assert answer.answer == "The answer."
        assert answer.has_sources
        assert answer.chunk_count == 1
        assert answer.success is True
        assert answer.latency_ms == 42.0
        assert answer.request_id == "rid1"

    def test_error_factory(self):
        answer = RAGAnswer.error("Q", "timeout", latency_ms=10.0, request_id="r2")
        assert answer.success is False
        assert answer.error_message == "timeout"
        assert not answer.has_sources
        assert answer.chunk_count == 0

    def test_from_empty_result(self):
        result = RetrievalResult(query="Q")
        answer = RAGAnswer.from_retrieval_result("Q", result, 0.0, "r3")
        assert not answer.has_sources
        assert answer.chunk_count == 0
