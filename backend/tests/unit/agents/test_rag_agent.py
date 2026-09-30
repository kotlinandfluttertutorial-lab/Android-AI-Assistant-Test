# ============================================================
# tests/unit/agents/test_rag_agent.py — Unit tests for RagAgent
# ============================================================
"""Unit tests for RagAgent (Phase 4)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentRetrievalCompletedEvent,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
)
from app.agents.rag_agent import RAG_AGENT_NAME, RagAgent

# ---------------------------------------------------------------------------
# Minimal stub for RAGService QueryResult
# ---------------------------------------------------------------------------


@dataclass
class _StubChunk:
    content: str = "chunk text"
    document_name: str = "doc.pdf"
    document_id: str = "doc-uuid-1"
    page_number: int = 1
    citation_type: str = "page"
    char_offset_start: int | None = None
    char_offset_end: int | None = None


@dataclass
class _StubQueryResult:
    query: str = "test query"
    retrieved_chunks: list[_StubChunk] = field(default_factory=lambda: [_StubChunk()])
    context: str = "Document context here."


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "u1",
        "input": "What is the architecture?",
        "metadata": {"agent_name": "rag", "document_ids": "doc-uuid-1"},
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_execution(request: AgentRequest | None = None) -> AgentExecution:
    req = request or make_request()
    return AgentExecution(request=req, agent_name=RAG_AGENT_NAME)


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


def mock_rag_and_orc(query_result: object = None, answer: str = "The answer.") -> object:
    """Context manager stack that mocks rag_service and AIOrchestrator."""
    qr = query_result or _StubQueryResult()

    class _Stack:
        def __init__(self) -> None:
            self._patches: list[object] = []

        def __enter__(self) -> _Stack:
            mock_session = MagicMock()
            mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
            mock_session.__aexit__ = AsyncMock(return_value=None)

            mock_rag = MagicMock()
            mock_rag.query_documents = AsyncMock(return_value=qr)

            mock_orc_inst = MagicMock()
            mock_orc_inst.complete = AsyncMock(return_value=answer)

            p1 = patch("app.agents.rag_agent.AsyncSessionLocal", return_value=mock_session)
            p2 = patch("app.agents.rag_agent.rag_service", mock_rag)
            p3 = patch("app.agents.rag_agent.AIOrchestrator", return_value=mock_orc_inst)

            self._patches = [p1, p2, p3]
            for p in self._patches:
                p.start()  # type: ignore[attr-defined]
            return self

        def __exit__(self, *_: object) -> None:
            for p in self._patches:
                p.stop()  # type: ignore[attr-defined]

    return _Stack()


# ---------------------------------------------------------------------------
# Metadata / capabilities
# ---------------------------------------------------------------------------


def test_agent_name() -> None:
    assert RagAgent().name == RAG_AGENT_NAME


def test_declares_document_retrieval() -> None:
    assert AgentCapability.DOCUMENT_RETRIEVAL in RagAgent().capabilities


def test_can_handle_empty_capabilities() -> None:
    assert RagAgent().can_handle(make_request()) is True


def test_can_handle_document_retrieval_capability() -> None:
    req = make_request(capabilities=[AgentCapability.DOCUMENT_RETRIEVAL])
    assert RagAgent().can_handle(req) is True


def test_cannot_handle_code_analysis() -> None:
    req = make_request(capabilities=[AgentCapability.CODE_ANALYSIS])
    assert RagAgent().can_handle(req) is False


# ---------------------------------------------------------------------------
# Successful retrieval
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_successful_query_event_sequence() -> None:
    agent = RagAgent()
    req = make_request()
    execution = make_execution(req)

    with mock_rag_and_orc(answer="The architecture is layered."):
        events = await collect(agent.execute(req, execution))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)
    retrieval = next((e for e in events if isinstance(e, AgentRetrievalCompletedEvent)), None)
    assert retrieval is not None
    token = next((e for e in events if isinstance(e, AgentTokenEvent)), None)
    assert token is not None
    assert token.token == "The architecture is layered."
    assert isinstance(events[-1], AgentCompletedEvent)


@pytest.mark.asyncio
async def test_completed_result_has_answer_as_content() -> None:
    agent = RagAgent()
    req = make_request()

    with mock_rag_and_orc(answer="MVVM layered."):
        events = await collect(agent.execute(req, make_execution(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.content == "MVVM layered."
    assert completed.result.status == AgentStatus.COMPLETED


# ---------------------------------------------------------------------------
# Citations
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_result_contains_citations() -> None:
    agent = RagAgent()
    req = make_request()

    with mock_rag_and_orc():
        events = await collect(agent.execute(req, make_execution(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert len(completed.result.citations) > 0
    assert completed.result.citations[0].document_id == "doc-uuid-1"
    assert completed.result.citations[0].document_name == "doc.pdf"


# ---------------------------------------------------------------------------
# Missing documents
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_chunks_returned_emits_failed() -> None:
    agent = RagAgent()
    req = make_request()
    empty_result = _StubQueryResult(retrieved_chunks=[])

    with mock_rag_and_orc(query_result=empty_result):
        events = await collect(agent.execute(req, make_execution(req)))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert "NO_RELEVANT_CONTENT" in failed.result.error.code


# ---------------------------------------------------------------------------
# Service unavailable
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_services_unavailable_emits_failed() -> None:
    agent = RagAgent()
    req = make_request()

    import app.agents.rag_agent as _mod

    orig_session = _mod.AsyncSessionLocal
    orig_rag = _mod.rag_service
    _mod.AsyncSessionLocal = None
    _mod.rag_service = None
    try:
        events = await collect(agent.execute(req, make_execution(req)))
    finally:
        _mod.AsyncSessionLocal = orig_session
        _mod.rag_service = orig_rag

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert "SERVICE_UNAVAILABLE" in failed.result.error.code


# ---------------------------------------------------------------------------
# Retrieval error
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_retrieval_exception_emits_failed() -> None:
    agent = RagAgent()
    req = make_request()
    execution = make_execution(req)

    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    mock_rag = MagicMock()
    mock_rag.query_documents = AsyncMock(side_effect=RuntimeError("DB down"))
    mock_orc_cls = MagicMock(return_value=MagicMock())

    with (
        patch("app.agents.rag_agent.AsyncSessionLocal", return_value=mock_session),
        patch("app.agents.rag_agent.rag_service", mock_rag),
        patch("app.agents.rag_agent.AIOrchestrator", mock_orc_cls),
    ):
        events = await collect(agent.execute(req, execution))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert "RETRIEVAL_ERROR" in failed.result.error.code


# ---------------------------------------------------------------------------
# rag_context in metadata
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_result_metadata_contains_rag_context() -> None:
    agent = RagAgent()
    req = make_request()

    with mock_rag_and_orc():
        events = await collect(agent.execute(req, make_execution(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert "rag_context" in completed.result.metadata
    assert "chunk_count" in completed.result.metadata
