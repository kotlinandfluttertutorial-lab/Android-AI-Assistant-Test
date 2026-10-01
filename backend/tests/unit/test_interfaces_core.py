"""Unit tests for backend/app/interfaces/core.py.

Tests verify:
  1. Value object construction and validation (MemoryEntry, EmbeddingVector,
     DocumentContent, DocumentChunk, StoredChunk, RetrievedChunk,
     RetrievalResult, ToolExecutionRequest, ToolExecutionResult, MemoryType).
  2. Each ABC cannot be instantiated directly.
  3. Concrete minimal stubs implement each ABC contract correctly.
  4. Re-exports in __init__.py resolve without ImportError.
  5. No infrastructure imports leak into the interfaces module.

Requirements: domain interface isolation (DIP), value-object validation.
"""

from __future__ import annotations

import inspect
from collections.abc import AsyncIterator
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

# ---------------------------------------------------------------------------
# Module-level import — validates that core.py itself has no infra imports
# ---------------------------------------------------------------------------
from app.interfaces.core import (
    # ABCs
    IAgentExecutor,
    IDocumentLoader,
    IEmbeddingProvider,
    IMCPClient,
    IMemoryStore,
    IPlanner,
    IRetriever,
    IToolExecutor,
    IVectorStore,
    # Value objects
    DocumentChunk,
    DocumentContent,
    DocumentLoadError,
    EmbeddingError,
    EmbeddingVector,
    MemoryEntry,
    MemoryType,
    RetrievalResult,
    RetrievedChunk,
    StoredChunk,
    ToolExecutionRequest,
    ToolExecutionResult,
    UnsupportedFormatError,
)
from app.agents.models import AgentEvent, AgentExecution, AgentRequest
from app.schemas.mcp import MCPToolResult, MCPToolSchema


# ===========================================================================
# MemoryType
# ===========================================================================

class TestMemoryType:
    def test_has_all_expected_values(self):
        assert MemoryType.FACT.value == "fact"
        assert MemoryType.PREFERENCE.value == "preference"
        assert MemoryType.WRITING_STYLE.value == "writing_style"
        assert MemoryType.AGENT_STATE.value == "agent_state"

    def test_is_string_enum(self):
        assert isinstance(MemoryType.FACT, str)


# ===========================================================================
# MemoryEntry
# ===========================================================================

class TestMemoryEntry:
    def test_valid_construction(self):
        entry = MemoryEntry(user_id="u1", content="User prefers dark mode.")
        assert entry.user_id == "u1"
        assert entry.content == "User prefers dark mode."
        assert entry.memory_type == MemoryType.FACT
        assert entry.memory_id == ""
        assert entry.relevance_score == 0.0
        assert entry.metadata == {}

    def test_all_fields(self):
        entry = MemoryEntry(
            user_id="u1",
            content="Prefers concise responses.",
            memory_type=MemoryType.PREFERENCE,
            memory_id="mem-123",
            relevance_score=0.87,
            metadata={"source": "conversation-1"},
        )
        assert entry.memory_type == MemoryType.PREFERENCE
        assert entry.memory_id == "mem-123"
        assert entry.relevance_score == pytest.approx(0.87)

    def test_blank_user_id_raises(self):
        with pytest.raises(ValueError, match="user_id"):
            MemoryEntry(user_id="   ", content="some content")

    def test_blank_content_raises(self):
        with pytest.raises(ValueError, match="content"):
            MemoryEntry(user_id="u1", content="")

    def test_relevance_score_below_zero_raises(self):
        with pytest.raises(ValueError, match="relevance_score"):
            MemoryEntry(user_id="u1", content="text", relevance_score=-0.1)

    def test_relevance_score_above_one_raises(self):
        with pytest.raises(ValueError, match="relevance_score"):
            MemoryEntry(user_id="u1", content="text", relevance_score=1.01)

    def test_boundary_relevance_scores_allowed(self):
        e0 = MemoryEntry(user_id="u1", content="text", relevance_score=0.0)
        e1 = MemoryEntry(user_id="u1", content="text", relevance_score=1.0)
        assert e0.relevance_score == 0.0
        assert e1.relevance_score == 1.0


# ===========================================================================
# EmbeddingVector
# ===========================================================================

class TestEmbeddingVector:
    def test_valid_construction(self):
        ev = EmbeddingVector(values=[0.1, 0.2, 0.3])
        assert ev.dimensions == 3
        assert ev.model_name == ""

    def test_with_all_fields(self):
        ev = EmbeddingVector(
            values=[1.0, 0.0],
            source_text="hello",
            model_name="all-MiniLM-L6-v2",
        )
        assert ev.model_name == "all-MiniLM-L6-v2"
        assert ev.dimensions == 2

    def test_empty_values_raises(self):
        with pytest.raises(ValueError, match="must not be empty"):
            EmbeddingVector(values=[])

    def test_dimensions_property(self):
        ev = EmbeddingVector(values=[0.0] * 384)
        assert ev.dimensions == 384


# ===========================================================================
# DocumentContent
# ===========================================================================

class TestDocumentContent:
    def test_valid_construction(self):
        dc = DocumentContent(document_id="doc-1", text="Hello world")
        assert dc.document_id == "doc-1"
        assert dc.text == "Hello world"
        assert dc.page_count == 0

    def test_blank_document_id_raises(self):
        with pytest.raises(ValueError, match="document_id"):
            DocumentContent(document_id="", text="text")

    def test_all_fields(self):
        dc = DocumentContent(
            document_id="d1",
            text="content",
            page_count=5,
            mime_type="application/pdf",
            filename="report.pdf",
            extraction_metadata={"author": "Alice"},
        )
        assert dc.page_count == 5
        assert dc.extraction_metadata["author"] == "Alice"


# ===========================================================================
# DocumentChunk
# ===========================================================================

class TestDocumentChunk:
    def test_valid_construction(self):
        chunk = DocumentChunk(
            chunk_id="doc1_chunk_0",
            document_id="doc1",
            document_name="Report",
            user_id="u1",
            chunk_index=0,
            text="First chunk of text.",
        )
        assert chunk.chunk_id == "doc1_chunk_0"
        assert chunk.page_number is None

    def test_blank_chunk_id_raises(self):
        with pytest.raises(ValueError, match="chunk_id"):
            DocumentChunk(
                chunk_id="  ",
                document_id="d1",
                document_name="n",
                user_id="u1",
                chunk_index=0,
                text="text",
            )

    def test_blank_text_raises(self):
        with pytest.raises(ValueError, match="text"):
            DocumentChunk(
                chunk_id="c1",
                document_id="d1",
                document_name="n",
                user_id="u1",
                chunk_index=0,
                text="   ",
            )

    def test_frozen(self):
        chunk = DocumentChunk(
            chunk_id="c1",
            document_id="d1",
            document_name="n",
            user_id="u1",
            chunk_index=0,
            text="text",
        )
        with pytest.raises(Exception):
            chunk.chunk_id = "modified"  # type: ignore[misc]


# ===========================================================================
# StoredChunk
# ===========================================================================

class TestStoredChunk:
    def _make_chunk(self) -> DocumentChunk:
        return DocumentChunk(
            chunk_id="c1", document_id="d1", document_name="Doc",
            user_id="u1", chunk_index=0, text="some text",
        )

    def test_valid_construction(self):
        ev = EmbeddingVector(values=[0.1, 0.2])
        sc = StoredChunk(chunk=self._make_chunk(), embedding=ev)
        assert sc.chunk.chunk_id == "c1"
        assert sc.embedding.dimensions == 2


# ===========================================================================
# RetrievedChunk
# ===========================================================================

class TestRetrievedChunk:
    def _make_chunk(self) -> DocumentChunk:
        return DocumentChunk(
            chunk_id="c1", document_id="d1", document_name="Doc",
            user_id="u1", chunk_index=0, text="text",
        )

    def test_valid_construction(self):
        rc = RetrievedChunk(chunk=self._make_chunk(), similarity=0.9)
        assert rc.similarity == pytest.approx(0.9)
        assert rc.retrieval_path == "ann"

    def test_similarity_below_zero_raises(self):
        with pytest.raises(ValueError, match="similarity"):
            RetrievedChunk(chunk=self._make_chunk(), similarity=-0.01)

    def test_similarity_above_one_raises(self):
        with pytest.raises(ValueError, match="similarity"):
            RetrievedChunk(chunk=self._make_chunk(), similarity=1.001)

    def test_boundary_similarities_allowed(self):
        chunk = self._make_chunk()
        assert RetrievedChunk(chunk=chunk, similarity=0.0).similarity == 0.0
        assert RetrievedChunk(chunk=chunk, similarity=1.0).similarity == 1.0


# ===========================================================================
# RetrievalResult
# ===========================================================================

class TestRetrievalResult:
    def test_empty_result(self):
        r = RetrievalResult(query="what is X?")
        assert not r.has_results
        assert r.answer == ""
        assert r.citations == []

    def test_has_results(self):
        chunk = DocumentChunk(
            chunk_id="c1", document_id="d1", document_name="n",
            user_id="u1", chunk_index=0, text="answer text",
        )
        rc = RetrievedChunk(chunk=chunk, similarity=0.8)
        r = RetrievalResult(query="Q", chunks=[rc], answer="The answer.")
        assert r.has_results
        assert r.answer == "The answer."


# ===========================================================================
# ToolExecutionRequest
# ===========================================================================

class TestToolExecutionRequest:
    def test_valid_construction(self):
        req = ToolExecutionRequest(
            tool_name="calculator",
            args={"expression": "1 + 1"},
            user_id="u1",
        )
        assert req.tool_name == "calculator"
        assert req.timeout_ms == 0

    def test_blank_tool_name_raises(self):
        with pytest.raises(ValueError, match="tool_name"):
            ToolExecutionRequest(tool_name="  ", args={}, user_id="u1")

    def test_blank_user_id_raises(self):
        with pytest.raises(ValueError, match="user_id"):
            ToolExecutionRequest(tool_name="calc", args={}, user_id="")


# ===========================================================================
# ToolExecutionResult
# ===========================================================================

class TestToolExecutionResult:
    def test_successful_result(self):
        r = ToolExecutionResult(tool_name="calc", success=True, output="42")
        assert r.success
        assert not r.is_empty

    def test_failure_result(self):
        r = ToolExecutionResult(tool_name="calc", success=False, error="division by zero")
        assert not r.success
        assert r.is_empty  # output is blank


# ===========================================================================
# Error types
# ===========================================================================

class TestErrorTypes:
    def test_document_load_error_message(self):
        err = DocumentLoadError(stage="ocr", filename="scan.pdf", detail="low confidence")
        assert "ocr" in str(err)
        assert "scan.pdf" in str(err)
        assert "low confidence" in str(err)

    def test_unsupported_format_error(self):
        err = UnsupportedFormatError(filename="data.xlsx", mime_type="application/vnd.ms-excel")
        assert "data.xlsx" in str(err)
        assert "Unsupported format" in str(err)

    def test_embedding_error_attributes(self):
        from app.interfaces.core import EmbeddingError
        err = EmbeddingError("model crashed", model_name="all-MiniLM", retryable=True)
        assert err.model_name == "all-MiniLM"
        assert err.retryable is True


# ===========================================================================
# ABC instantiation is rejected
# ===========================================================================

class TestAbstractnessEnforced:
    """Verify that direct instantiation of each ABC raises TypeError."""

    @pytest.mark.parametrize("abc_class", [
        IPlanner,
        IAgentExecutor,
        IMemoryStore,
        IDocumentLoader,
        IEmbeddingProvider,
        IVectorStore,
        IRetriever,
        IMCPClient,
        IToolExecutor,
    ])
    def test_cannot_instantiate_abc(self, abc_class):
        with pytest.raises(TypeError, match="Can't instantiate abstract class"):
            abc_class()  # type: ignore[call-arg]


# ===========================================================================
# Minimal concrete stubs satisfy the ABCs
# ===========================================================================

class _MinimalMemoryStore(IMemoryStore):
    async def store(self, entry: MemoryEntry) -> str:
        return "mem-1"

    async def retrieve(self, user_id, query, top_k=5, memory_type=None):
        return []

    async def delete(self, user_id, memory_id):
        pass

    async def delete_all(self, user_id):
        pass


class _MinimalDocumentLoader(IDocumentLoader):
    async def load(self, file_bytes, filename, document_id, mime_type=""):
        return DocumentContent(document_id=document_id, text="extracted")

    def supports(self, mime_type, filename):
        return filename.endswith(".txt")

    def max_file_size_bytes(self):
        return 10 * 1024 * 1024  # 10 MB


class _MinimalEmbeddingProvider(IEmbeddingProvider):
    async def embed(self, text):
        return EmbeddingVector(values=[0.0] * 384, model_name="test-model")

    async def embed_batch(self, texts):
        return [EmbeddingVector(values=[0.0] * 384) for _ in texts]

    @property
    def model_name(self):
        return "test-model"

    @property
    def embedding_dimension(self):
        return 384


class _MinimalVectorStore(IVectorStore):
    async def upsert(self, chunk, embedding):
        pass

    async def search(self, user_id, query_embedding, top_k=5, min_similarity=0.0):
        return []

    async def delete_by_document(self, user_id, document_id):
        pass

    async def delete_all(self, user_id):
        pass

    async def count(self, user_id):
        return 0


class _MinimalRetriever(IRetriever):
    async def retrieve(self, user_id, query, top_k=5, document_ids=None):
        return RetrievalResult(query=query)

    async def retrieve_and_generate(self, user_id, query, top_k=5, document_ids=None):
        return RetrievalResult(query=query, answer="generated answer")


class _MinimalMCPClient(IMCPClient):
    def discover(self):
        return []

    async def invoke(self, tool_name, params, user_id):
        return MCPToolResult(tool_name=tool_name, success=True, result_status="success")

    def is_registered(self, tool_name):
        return False


class _MinimalToolExecutor(IToolExecutor):
    async def execute(self, request):
        return ToolExecutionResult(tool_name=request.tool_name, success=True, output="42")

    def is_available(self, tool_name):
        return tool_name == "calculator"

    def list_tools(self):
        return ["calculator"]


class _MinimalPlanner(IPlanner):
    def build_plan(self, request, agent_name, available_agent_names):
        return [agent_name]

    def max_steps(self):
        return 10

    def max_tool_calls(self):
        return 20

    def timeout_ms(self):
        return 120_000


class _MinimalAgentExecutor(IAgentExecutor):
    def execute(self, request, execution) -> AsyncIterator[AgentEvent]:
        async def _gen():
            return
            yield  # make it a generator

        return _gen()

    async def cancel(self, execution_id):
        pass


class TestConcreteStubsAreValid:
    """Each stub can be instantiated and its methods return correct types."""

    @pytest.mark.asyncio
    async def test_memory_store_store(self):
        store = _MinimalMemoryStore()
        entry = MemoryEntry(user_id="u1", content="text")
        result = await store.store(entry)
        assert isinstance(result, str)

    @pytest.mark.asyncio
    async def test_memory_store_retrieve_returns_list(self):
        store = _MinimalMemoryStore()
        result = await store.retrieve("u1", "query")
        assert isinstance(result, list)

    @pytest.mark.asyncio
    async def test_document_loader_load(self):
        loader = _MinimalDocumentLoader()
        doc = await loader.load(b"hello", "file.txt", "doc-1")
        assert isinstance(doc, DocumentContent)
        assert doc.document_id == "doc-1"

    def test_document_loader_supports(self):
        loader = _MinimalDocumentLoader()
        assert loader.supports("text/plain", "file.txt")
        assert not loader.supports("application/pdf", "doc.pdf")

    def test_document_loader_max_file_size(self):
        loader = _MinimalDocumentLoader()
        assert loader.max_file_size_bytes() > 0

    @pytest.mark.asyncio
    async def test_embedding_provider_embed(self):
        provider = _MinimalEmbeddingProvider()
        ev = await provider.embed("hello world")
        assert isinstance(ev, EmbeddingVector)
        assert ev.dimensions == 384

    @pytest.mark.asyncio
    async def test_embedding_provider_embed_batch(self):
        provider = _MinimalEmbeddingProvider()
        results = await provider.embed_batch(["a", "b", "c"])
        assert len(results) == 3
        assert all(isinstance(r, EmbeddingVector) for r in results)

    def test_embedding_provider_properties(self):
        provider = _MinimalEmbeddingProvider()
        assert provider.model_name == "test-model"
        assert provider.embedding_dimension == 384

    @pytest.mark.asyncio
    async def test_vector_store_upsert_and_count(self):
        store = _MinimalVectorStore()
        chunk = DocumentChunk(
            chunk_id="c1", document_id="d1", document_name="Doc",
            user_id="u1", chunk_index=0, text="text",
        )
        ev = EmbeddingVector(values=[0.1, 0.2])
        sc = StoredChunk(chunk=chunk, embedding=ev)
        await store.upsert(sc.chunk, sc.embedding)
        count = await store.count("u1")
        assert count == 0  # stub always returns 0

    @pytest.mark.asyncio
    async def test_vector_store_search_returns_list(self):
        store = _MinimalVectorStore()
        ev = EmbeddingVector(values=[0.1, 0.2, 0.3])
        results = await store.search("u1", ev, top_k=5)
        assert isinstance(results, list)

    @pytest.mark.asyncio
    async def test_retriever_retrieve(self):
        retriever = _MinimalRetriever()
        result = await retriever.retrieve("u1", "what is X?")
        assert isinstance(result, RetrievalResult)
        assert result.query == "what is X?"

    @pytest.mark.asyncio
    async def test_retriever_retrieve_and_generate(self):
        retriever = _MinimalRetriever()
        result = await retriever.retrieve_and_generate("u1", "summarize doc")
        assert result.answer == "generated answer"

    @pytest.mark.asyncio
    async def test_mcp_client_invoke(self):
        client = _MinimalMCPClient()
        result = await client.invoke("github", {}, "u1")
        assert isinstance(result, MCPToolResult)
        assert result.success

    def test_mcp_client_discover(self):
        client = _MinimalMCPClient()
        tools = client.discover()
        assert isinstance(tools, list)

    def test_mcp_client_is_registered(self):
        client = _MinimalMCPClient()
        assert not client.is_registered("github")

    @pytest.mark.asyncio
    async def test_tool_executor_execute(self):
        executor = _MinimalToolExecutor()
        req = ToolExecutionRequest(tool_name="calculator", args={"expression": "1+1"}, user_id="u1")
        result = await executor.execute(req)
        assert result.success
        assert result.tool_name == "calculator"

    def test_tool_executor_is_available(self):
        executor = _MinimalToolExecutor()
        assert executor.is_available("calculator")
        assert not executor.is_available("unknown_tool")

    def test_tool_executor_list_tools(self):
        executor = _MinimalToolExecutor()
        tools = executor.list_tools()
        assert "calculator" in tools

    def test_planner_build_plan(self):
        planner = _MinimalPlanner()
        request = AgentRequest(
            user_id="u1",
            input="search docs",
            request_id="r1",
        )
        plan = planner.build_plan(request, "rag_agent", ["rag_agent", "chat_agent"])
        assert plan == ["rag_agent"]
        assert planner.max_steps() == 10
        assert planner.max_tool_calls() == 20
        assert planner.timeout_ms() == 120_000

    @pytest.mark.asyncio
    async def test_agent_executor_cancel_is_no_op(self):
        executor = _MinimalAgentExecutor()
        await executor.cancel("exec-1")  # should not raise


# ===========================================================================
# Re-export integrity from __init__.py
# ===========================================================================

class TestInitReExports:
    """All symbols promised in __init__.py are importable."""

    def test_llm_provider_re_exported(self):
        from app.interfaces import LLMProvider  # noqa: F401
        assert LLMProvider is not None

    def test_agent_re_exported(self):
        from app.interfaces import Agent  # noqa: F401
        assert Agent is not None

    def test_mcp_tool_connector_re_exported(self):
        from app.interfaces import MCPToolConnector  # noqa: F401
        assert MCPToolConnector is not None

    def test_new_abcs_re_exported(self):
        from app.interfaces import (  # noqa: F401
            IAgentExecutor,
            IDocumentLoader,
            IEmbeddingProvider,
            IMCPClient,
            IMemoryStore,
            IPlanner,
            IRetriever,
            IToolExecutor,
            IVectorStore,
        )

    def test_value_objects_re_exported(self):
        from app.interfaces import (  # noqa: F401
            DocumentChunk,
            EmbeddingVector,
            MemoryEntry,
            MemoryType,
            RetrievalResult,
            RetrievedChunk,
            ToolExecutionRequest,
            ToolExecutionResult,
        )


# ===========================================================================
# Dependency isolation check
# ===========================================================================

class TestNoInfrastructureImports:
    """Verify that interfaces/core.py does not import any infra library."""

    FORBIDDEN_MODULES = {
        "chromadb",
        "sqlalchemy",
        "redis",
        "sentence_transformers",
        "minio",
        "pdfplumber",
        "google.generativeai",
        "openai",
        "anthropic",
        "celery",
        "firebase_admin",
    }

    def test_core_imports_are_clean(self):
        import app.interfaces.core as core_module

        source_file = inspect.getfile(core_module)
        with open(source_file) as f:
            # Strip comment and docstring lines — only actual import statements matter.
            import_lines = [
                line for line in f
                if line.strip().startswith("import ") or line.strip().startswith("from ")
            ]
        source_imports = "\n".join(import_lines)

        for forbidden in self.FORBIDDEN_MODULES:
            assert forbidden not in source_imports, (
                f"interfaces/core.py must not import {forbidden!r} "
                f"(Dependency Inversion Principle violation)."
            )
