"""Unit tests for versioned RAG APIs.

Covers:
  POST /api/v1/rag/search   — semantic retrieval without LLM
  POST /api/v1/rag/ask      — full RAG QA with answer and citations

All dependencies are mocked — no real embedding model, vector store,
LLM, or DB required. No production credentials needed.
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

# ── env vars before any app imports ──────────────────────────────────────────
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import sys
from unittest.mock import MagicMock as _MagicMock

# ── Stub out google.genai before app.main imports the services chain ──────────
_google_mock = _MagicMock()
sys.modules.setdefault("google.genai", _google_mock)
sys.modules.setdefault("google.genai.types", _google_mock)

from app.interfaces.core import DocumentChunk, RetrievedChunk, RetrievalResult
from app.main import app
from app.rag.pipeline import RAGAnswer
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ── Shared fixtures ───────────────────────────────────────────────────────────

_USER = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_DOC = uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")


def _token(user_id: uuid.UUID = _USER) -> TokenPayload:
    now = datetime.now(tz=timezone.utc)
    return TokenPayload(
        sub=str(user_id),
        role="user",
        jti=str(uuid.uuid4()),
        iat=now,
        exp=now.replace(year=now.year + 1),
    )


def _dc(
    chunk_id: str = "c1",
    text: str = "The revenue was $1.2B.",
    doc_name: str = "report.pdf",
    page: int | None = 3,
) -> DocumentChunk:
    return DocumentChunk(
        chunk_id=chunk_id,
        document_id=str(_DOC),
        document_name=doc_name,
        user_id=str(_USER),
        chunk_index=0,
        text=text,
        page_number=page,
        char_start=0,
        char_end=len(text),
    )


def _rc(chunk: DocumentChunk | None = None, similarity: float = 0.88) -> RetrievedChunk:
    return RetrievedChunk(
        chunk=chunk or _dc(),
        similarity=similarity,
        retrieval_path="chroma_ann",
    )


def _rag_answer(
    question: str = "What was the revenue?",
    answer: str = "Revenue was $1.2B.",
    chunks: list[RetrievedChunk] | None = None,
    sources: list[dict] | None = None,
) -> RAGAnswer:
    rc_list = chunks or [_rc()]
    src = sources or [
        {
            "document_id": str(_DOC),
            "document_name": "report.pdf",
            "page_number": 3,
            "chunk_index": 0,
            "excerpt": "The revenue was $1.2B.",
            "retrieval_path": "chroma_ann",
        }
    ]
    return RAGAnswer(
        question=question,
        answer=answer,
        sources=src,
        chunk_count=len(rc_list),
        has_sources=bool(rc_list),
        success=True,
        latency_ms=42.0,
        request_id="test-req-id",
    )


@pytest.fixture(autouse=True)
def auth():
    app.dependency_overrides[get_current_user] = lambda: _token()
    yield
    app.dependency_overrides.pop(get_current_user, None)


# ── POST /api/v1/rag/search ───────────────────────────────────────────────────


class TestV1RagSearch:
    @pytest.mark.asyncio
    async def test_search_returns_200(self):
        result = RetrievalResult(query="revenue", chunks=[_rc()], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/search",
                    json={"query": "revenue", "top_k": 5},
                )
        assert resp.status_code == 200

    @pytest.mark.asyncio
    async def test_search_response_schema(self):
        result = RetrievalResult(query="q", chunks=[_rc()], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        body = resp.json()
        assert "query" in body
        assert "sources" in body
        assert "total_sources" in body

    @pytest.mark.asyncio
    async def test_search_sources_contain_metadata(self):
        chunk = _dc(doc_name="annual.pdf", page=7)
        result = RetrievalResult(query="profit", chunks=[_rc(chunk, 0.91)], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "profit"})
        src = resp.json()["sources"][0]
        assert src["document_name"] == "annual.pdf"
        assert src["page_number"] == 7
        assert src["similarity"] == pytest.approx(0.91, abs=0.01)
        assert src["retrieval_path"] == "chroma_ann"

    @pytest.mark.asyncio
    async def test_search_total_sources_matches_list(self):
        chunks = [_rc(_dc(chunk_id=f"c{i}")) for i in range(3)]
        result = RetrievalResult(query="q", chunks=chunks, answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        body = resp.json()
        assert body["total_sources"] == 3
        assert len(body["sources"]) == 3

    @pytest.mark.asyncio
    async def test_search_empty_results_returns_200_not_404(self):
        result = RetrievalResult(query="nothingmatches", chunks=[], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "nothingmatches"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["total_sources"] == 0
        assert body["sources"] == []

    @pytest.mark.asyncio
    async def test_search_failure_returns_200_empty_gracefully(self):
        """Infrastructure failure must not return 5xx — returns empty sources."""
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            side_effect=RuntimeError("ChromaDB unreachable"),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["total_sources"] == 0
        # Internal error must NOT be exposed
        assert "ChromaDB unreachable" not in resp.text
        assert "Traceback" not in resp.text

    @pytest.mark.asyncio
    async def test_search_blank_query_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/search", json={"query": ""})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_search_missing_query_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/search", json={})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_search_top_k_above_20_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/search", json={"query": "q", "top_k": 99})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_search_top_k_zero_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/search", json={"query": "q", "top_k": 0})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_search_requires_auth(self):
        app.dependency_overrides.pop(get_current_user, None)
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        assert resp.status_code == 401
        # Restore fixture override
        app.dependency_overrides[get_current_user] = lambda: _token()

    @pytest.mark.asyncio
    async def test_search_document_ids_filter_accepted(self):
        result = RetrievalResult(query="q", chunks=[], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ) as mock_retrieve:
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                await client.post(
                    "/api/v1/rag/search",
                    json={"query": "q", "document_ids": [str(_DOC)]},
                )
        # Verify document_ids was passed through
        mock_retrieve.assert_awaited_once()
        kwargs = mock_retrieve.call_args.kwargs
        assert kwargs.get("document_ids") == [str(_DOC)]

    @pytest.mark.asyncio
    async def test_search_source_excerpt_truncated_to_200_chars(self):
        long_text = "word " * 100  # 500 chars
        chunk = _dc(text=long_text)
        result = RetrievalResult(query="q", chunks=[_rc(chunk)], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        excerpt = resp.json()["sources"][0]["excerpt"]
        assert len(excerpt) <= 200

    @pytest.mark.asyncio
    async def test_search_char_offset_source_when_no_page(self):
        chunk = _dc(page=None, doc_name="notes.md")
        chunk = DocumentChunk(
            chunk_id="c1",
            document_id=str(_DOC),
            document_name="notes.md",
            user_id=str(_USER),
            chunk_index=0,
            text="some text",
            page_number=None,
            char_start=10,
            char_end=80,
        )
        result = RetrievalResult(query="q", chunks=[_rc(chunk)], answer="", citations=[])
        with patch(
            "app.rag.retriever.VectorRetriever.retrieve",
            new_callable=AsyncMock,
            return_value=result,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post("/api/v1/rag/search", json={"query": "q"})
        src = resp.json()["sources"][0]
        assert src["page_number"] is None


# ── POST /api/v1/rag/ask ──────────────────────────────────────────────────────


class TestV1RagAsk:
    @pytest.mark.asyncio
    async def test_ask_returns_200(self):
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(ask=AsyncMock(return_value=_rag_answer())),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "What was revenue?"},
                )
        assert resp.status_code == 200

    @pytest.mark.asyncio
    async def test_ask_response_schema(self):
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(ask=AsyncMock(return_value=_rag_answer())),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "What was revenue?"},
                )
        body = resp.json()
        assert "question" in body
        assert "answer" in body
        assert "sources" in body
        assert "has_sources" in body

    @pytest.mark.asyncio
    async def test_ask_answer_populated(self):
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(
                ask=AsyncMock(return_value=_rag_answer(answer="Revenue was $1.2B."))
            ),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "Revenue?"},
                )
        assert resp.json()["answer"] == "Revenue was $1.2B."

    @pytest.mark.asyncio
    async def test_ask_sources_populated(self):
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(ask=AsyncMock(return_value=_rag_answer())),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "q?"},
                )
        body = resp.json()
        assert body["has_sources"] is True
        assert len(body["sources"]) == 1
        src = body["sources"][0]
        assert "document_name" in src
        assert "page_number" in src
        assert "excerpt" in src

    @pytest.mark.asyncio
    async def test_ask_no_results_returns_graceful_answer(self):
        no_result = RAGAnswer(
            question="Q?",
            answer="I could not find relevant information.",
            sources=[],
            chunk_count=0,
            has_sources=False,
            success=True,
            latency_ms=10.0,
            request_id="r1",
        )
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(ask=AsyncMock(return_value=no_result)),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "unknown topic?"},
                )
        assert resp.status_code == 200
        body = resp.json()
        assert body["has_sources"] is False
        assert len(body["sources"]) == 0
        assert "could not find" in body["answer"].lower() or body["answer"]

    @pytest.mark.asyncio
    async def test_ask_llm_failure_returns_200_not_500(self):
        """LLM failure inside pipeline is handled — never returns 5xx."""
        error_result = RAGAnswer(
            question="Q?",
            answer="Unable to generate answer due to a service error.",
            sources=[
                {
                    "document_id": str(_DOC),
                    "document_name": "report.pdf",
                    "page_number": 1,
                    "chunk_index": 0,
                    "excerpt": "context",
                    "retrieval_path": "chroma_ann",
                }
            ],
            chunk_count=1,
            has_sources=True,
            success=True,
            latency_ms=5.0,
            request_id="r2",
        )
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(ask=AsyncMock(return_value=error_result)),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "Q?"},
                )
        assert resp.status_code == 200
        # Chunks (sources) still returned even on LLM failure
        assert resp.json()["has_sources"] is True

    @pytest.mark.asyncio
    async def test_ask_pipeline_construction_failure_returns_200_safe(self):
        """If pipeline can't be built (e.g. bad config), return graceful 200."""
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            side_effect=RuntimeError("ChromaDB config missing"),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "Q?"},
                )
        assert resp.status_code == 200
        body = resp.json()
        # Must not expose internal error detail
        assert "ChromaDB config missing" not in resp.text
        assert "Traceback" not in resp.text
        assert body["has_sources"] is False

    @pytest.mark.asyncio
    async def test_ask_blank_question_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/ask", json={"question": ""})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_ask_missing_question_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/ask", json={})
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_ask_requires_auth(self):
        app.dependency_overrides.pop(get_current_user, None)
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/ask", json={"question": "q?"})
        assert resp.status_code == 401
        app.dependency_overrides[get_current_user] = lambda: _token()

    @pytest.mark.asyncio
    async def test_ask_document_ids_filter_passed_to_pipeline(self):
        pipeline_mock = MagicMock()
        pipeline_mock.ask = AsyncMock(return_value=_rag_answer())
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=pipeline_mock,
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "q?", "document_ids": [str(_DOC)]},
                )
        pipeline_mock.ask.assert_awaited_once()
        kwargs = pipeline_mock.ask.call_args.kwargs
        assert kwargs.get("document_ids") == [str(_DOC)]

    @pytest.mark.asyncio
    async def test_ask_question_preserved_in_response(self):
        with patch(
            "app.api.rag.router._get_rag_pipeline",
            return_value=MagicMock(
                ask=AsyncMock(return_value=_rag_answer(question="My specific question?"))
            ),
        ):
            async with AsyncClient(
                transport=ASGITransport(app=app), base_url="http://test"
            ) as client:
                resp = await client.post(
                    "/api/v1/rag/ask",
                    json={"question": "My specific question?"},
                )
        assert resp.json()["question"] == "My specific question?"

    @pytest.mark.asyncio
    async def test_ask_top_k_above_20_returns_422(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.post("/api/v1/rag/ask", json={"question": "q?", "top_k": 50})
        assert resp.status_code == 422


# ── OpenAPI schema checks ─────────────────────────────────────────────────────


class TestOpenAPISchema:
    @pytest.mark.asyncio
    async def test_openapi_includes_v1_documents_upload(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        assert resp.status_code == 200
        paths = resp.json()["paths"]
        assert "/api/v1/documents/upload" in paths

    @pytest.mark.asyncio
    async def test_openapi_includes_v1_documents_list(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        paths = resp.json()["paths"]
        assert "/api/v1/documents" in paths

    @pytest.mark.asyncio
    async def test_openapi_includes_v1_documents_detail(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        paths = resp.json()["paths"]
        assert "/api/v1/documents/{document_id}" in paths

    @pytest.mark.asyncio
    async def test_openapi_includes_v1_rag_search(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        paths = resp.json()["paths"]
        assert "/api/v1/rag/search" in paths

    @pytest.mark.asyncio
    async def test_openapi_includes_v1_rag_ask(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        paths = resp.json()["paths"]
        assert "/api/v1/rag/ask" in paths

    @pytest.mark.asyncio
    async def test_openapi_documents_upload_requires_multipart(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        spec = resp.json()
        upload_path = spec["paths"]["/api/v1/documents/upload"]["post"]
        # multipart/form-data encoding
        body = upload_path.get("requestBody", {})
        assert "multipart/form-data" in body.get("content", {})

    @pytest.mark.asyncio
    async def test_openapi_rag_ask_has_summary(self):
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/openapi.json")
        ask_op = resp.json()["paths"]["/api/v1/rag/ask"]["post"]
        assert "summary" in ask_op
        assert ask_op["summary"]
