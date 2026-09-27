# tests/unit/agents/test_pdf_agent.py
"""Unit tests for PdfAgent (Phase 5)."""
from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from dataclasses import field as dc_field
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
from app.agents.pdf_agent import PDF_AGENT_NAME, PdfAgent

# ---------------------------------------------------------------------------
# Stubs
# ---------------------------------------------------------------------------


@dataclass
class _Chunk:
    content: str = "chunk text"
    document_name: str = "doc.pdf"
    document_id: str = "doc-1"
    page_number: int = 1


@dataclass
class _QueryResult:
    query: str = "test query"
    retrieved_chunks: list = dc_field(default_factory=lambda: [_Chunk()])
    context: str = "Document context here."


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "user-1",
        "input": "What is this about?",
        "metadata": {"pdf_action": "query", "document_id": "doc-1"},
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_exec(req: AgentRequest | None = None) -> AgentExecution:
    r = req or make_request()
    return AgentExecution(request=r, agent_name=PDF_AGENT_NAME)


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


def mock_stack(
    answer: str = "The answer.",
    chunks: list | None = None,
) -> object:
    """Context manager that mocks rag_service + AIOrchestrator."""
    qr = _QueryResult()
    if chunks is not None:
        qr.retrieved_chunks = chunks

    class _Stack:
        def __enter__(self) -> _Stack:
            mock_session = MagicMock()
            mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
            mock_session.__aexit__ = AsyncMock(return_value=None)
            mock_rag = MagicMock()
            mock_rag.query_documents = AsyncMock(return_value=qr)
            mock_orc = MagicMock()
            mock_orc.complete = AsyncMock(return_value=answer)
            self._patches = [
                patch("app.agents.pdf_agent.AsyncSessionLocal", return_value=mock_session),
                patch("app.agents.pdf_agent.rag_service", mock_rag),
                patch("app.agents.pdf_agent.AIOrchestrator", return_value=mock_orc),
            ]
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
    assert PdfAgent().name == PDF_AGENT_NAME


def test_declares_document_retrieval() -> None:
    assert AgentCapability.DOCUMENT_RETRIEVAL in PdfAgent().capabilities


# ---------------------------------------------------------------------------
# Query
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_query_event_sequence() -> None:
    agent = PdfAgent()
    req = make_request()
    with mock_stack("The answer."):
        events = await collect(agent.execute(req, make_exec(req)))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)
    assert any(isinstance(e, AgentRetrievalCompletedEvent) for e in events)
    token = next(e for e in events if isinstance(e, AgentTokenEvent))
    assert token.token == "The answer."
    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_query_missing_document_id_attempts_all_docs() -> None:
    """Without a document_id the agent searches all user docs — should not raise."""
    agent = PdfAgent()
    req = make_request(metadata={"pdf_action": "query"})
    with mock_stack("answer"):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, (AgentCompletedEvent, AgentFailedEvent)) for e in events)


@pytest.mark.asyncio
async def test_query_no_chunks_emits_failed() -> None:
    agent = PdfAgent()
    req = make_request()
    with mock_stack(chunks=[]):
        events = await collect(agent.execute(req, make_exec(req)))
    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert "NO_RELEVANT_CONTENT" in failed.result.error.code


@pytest.mark.asyncio
async def test_retrieval_error_emits_failed() -> None:
    agent = PdfAgent()
    req = make_request()
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    mock_rag = MagicMock()
    mock_rag.query_documents = AsyncMock(side_effect=RuntimeError("db down"))
    mock_orc = MagicMock()
    with (
        patch("app.agents.pdf_agent.AsyncSessionLocal", return_value=mock_session),
        patch("app.agents.pdf_agent.rag_service", mock_rag),
        patch("app.agents.pdf_agent.AIOrchestrator", return_value=mock_orc),
    ):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentFailedEvent) for e in events)


# ---------------------------------------------------------------------------
# Summarize
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_summarize_action_sends_summary_prompt() -> None:
    agent = PdfAgent()
    captured_queries: list[str] = []

    async def fake_query(**kw: object) -> _QueryResult:
        captured_queries.append(str(kw.get("query", "")))
        return _QueryResult(query=str(kw.get("query", "")))

    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    mock_rag = MagicMock()
    mock_rag.query_documents = AsyncMock(side_effect=fake_query)
    mock_orc = MagicMock()
    mock_orc.complete = AsyncMock(return_value="Summary here.")
    req = make_request(metadata={"pdf_action": "summarize", "document_id": "doc-1"})
    with (
        patch("app.agents.pdf_agent.AsyncSessionLocal", return_value=mock_session),
        patch("app.agents.pdf_agent.rag_service", mock_rag),
        patch("app.agents.pdf_agent.AIOrchestrator", return_value=mock_orc),
    ):
        await collect(agent.execute(req, make_exec(req)))
    assert any("summary" in q.lower() for q in captured_queries)


# ---------------------------------------------------------------------------
# Upload
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_upload_missing_file_bytes_emits_failed() -> None:
    agent = PdfAgent()
    req = make_request(metadata={"pdf_action": "upload"})

    import app.agents.pdf_agent as _mod

    orig_s = _mod.AsyncSessionLocal
    orig_r = _mod.rag_service
    _mod.AsyncSessionLocal = MagicMock()  # type: ignore[assignment]
    _mod.rag_service = MagicMock()  # type: ignore[assignment]
    try:
        events = await collect(agent.execute(req, make_exec(req)))
    finally:
        _mod.AsyncSessionLocal = orig_s
        _mod.rag_service = orig_r

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert "MISSING_FILE_BYTES" in failed.result.error.code


@pytest.mark.asyncio
async def test_upload_invalid_base64_is_handled_gracefully() -> None:
    """Invalid base64 input must not raise — either INVALID_BASE64 or gracefully fails."""
    agent = PdfAgent()
    req = make_request(metadata={"pdf_action": "upload", "file_bytes_b64": "not-valid!!!"})

    import app.agents.pdf_agent as _mod

    orig = _mod.AsyncSessionLocal
    _mod.AsyncSessionLocal = MagicMock()  # type: ignore[assignment]
    try:
        events = await collect(agent.execute(req, make_exec(req)))
    finally:
        _mod.AsyncSessionLocal = orig

    # Must produce events (no uncaught exception)
    assert isinstance(events, list)


# ---------------------------------------------------------------------------
# Service unavailable
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_services_unavailable_emits_failed() -> None:
    agent = PdfAgent()
    req = make_request()

    import app.agents.pdf_agent as _mod

    orig_s = _mod.AsyncSessionLocal
    orig_r = _mod.rag_service
    _mod.AsyncSessionLocal = None  # type: ignore[assignment]
    _mod.rag_service = None  # type: ignore[assignment]
    try:
        events = await collect(agent.execute(req, make_exec(req)))
    finally:
        _mod.AsyncSessionLocal = orig_s
        _mod.rag_service = orig_r

    assert any(isinstance(e, AgentFailedEvent) for e in events)


# ---------------------------------------------------------------------------
# Unknown action
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_unknown_action_emits_failed() -> None:
    """PdfAgent rejects unknown pdf_action even when services are available."""
    agent = PdfAgent()
    req = make_request(metadata={"pdf_action": "rotate", "document_id": "doc-1"})
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    with (
        patch("app.agents.pdf_agent.AsyncSessionLocal", return_value=mock_session),
        patch("app.agents.pdf_agent.rag_service", MagicMock()),
        patch("app.agents.pdf_agent.AIOrchestrator", return_value=MagicMock()),
    ):
        events = await collect(agent.execute(req, make_exec(req)))
    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert "UNKNOWN_ACTION" in failed.result.error.code
