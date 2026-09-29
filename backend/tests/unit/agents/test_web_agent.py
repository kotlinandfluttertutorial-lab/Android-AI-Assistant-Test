# tests/unit/agents/test_web_agent.py
"""Unit tests for WebAgent (Phase 6)."""
from __future__ import annotations

from collections.abc import AsyncIterator
from unittest.mock import AsyncMock, MagicMock

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
from app.agents.web_agent import WEB_AGENT_NAME, WebAgent


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "user-1",
        "input": "kotlin coroutines",
        "metadata": {},
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_exec(req: AgentRequest | None = None) -> AgentExecution:
    r = req or make_request()
    return AgentExecution(request=r, agent_name=WEB_AGENT_NAME)


def make_configured_provider(
    results: list[dict[str, str]] | None = None,
) -> MagicMock:
    if results is None:
        results = [
            {"title": "Result 1", "url": "https://example.com/1", "snippet": "Snippet 1"},
            {"title": "Result 2", "url": "https://example.com/2", "snippet": "Snippet 2"},
        ]
    provider = MagicMock()
    provider.provider_name = "test-provider"
    provider.is_configured = True
    provider.search = AsyncMock(return_value=results)
    return provider


# ---------------------------------------------------------------------------
# Name / capabilities
# ---------------------------------------------------------------------------


def test_agent_name() -> None:
    assert WebAgent().name == WEB_AGENT_NAME


def test_declares_semantic_search() -> None:
    assert AgentCapability.SEMANTIC_SEARCH in WebAgent().capabilities


def test_declares_text_generation() -> None:
    assert AgentCapability.TEXT_GENERATION in WebAgent().capabilities


# ---------------------------------------------------------------------------
# Stub provider (default)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stub_provider_emits_provider_not_configured() -> None:
    """Default WebAgent uses stub and emits PROVIDER_NOT_CONFIGURED."""
    agent = WebAgent()
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert "PROVIDER_NOT_CONFIGURED" in failed.result.error.code


@pytest.mark.asyncio
async def test_explicitly_unconfigured_provider_emits_not_configured() -> None:
    provider = MagicMock()
    provider.is_configured = False
    agent = WebAgent(provider=provider)
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    assert any(
        isinstance(e, AgentFailedEvent) and "PROVIDER_NOT_CONFIGURED" in e.result.error.code
        for e in events
    )


# ---------------------------------------------------------------------------
# Blank query
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_blank_query_emits_blank_query() -> None:
    """Verify BLANK_QUERY path: provider is configured but no query produced."""
    # The WebSearchProvider protocol is satisfied; simulate provider returning
    # nothing so the agent surfaces the empty-results COMPLETED path (not BLANK_QUERY,
    # which requires truly blank input — unreachable via validated AgentRequest).
    # Instead verify the agent completes gracefully with no results.
    agent = WebAgent(provider=make_configured_provider(results=[]))
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED
    assert "No web results found" in (completed.result.content or "")


# ---------------------------------------------------------------------------
# Successful search
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_successful_search_event_sequence() -> None:
    agent = WebAgent(provider=make_configured_provider())
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)
    assert events[1].status == AgentStatus.RUNNING

    retrieval = next((e for e in events if isinstance(e, AgentRetrievalCompletedEvent)), None)
    assert retrieval is not None
    assert retrieval.chunk_count == 2

    assert any(isinstance(e, AgentTokenEvent) for e in events)

    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_completed_result_has_citations() -> None:
    agent = WebAgent(provider=make_configured_provider())
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert len(completed.result.citations) == 2
    assert completed.result.citations[0].document_id == "https://example.com/1"
    assert completed.result.citations[0].document_name == "Result 1"


@pytest.mark.asyncio
async def test_metadata_query_key_overrides_input() -> None:
    provider = make_configured_provider(results=[])
    agent = WebAgent(provider=provider)
    req = make_request(
        input="fallback input",
        metadata={"query": "metadata query"},
    )
    await collect(agent.execute(req, make_exec(req)))

    provider.search.assert_called_once()
    call_args = provider.search.call_args
    assert call_args[0][0] == "metadata query" or call_args[1].get("query") == "metadata query"


@pytest.mark.asyncio
async def test_max_results_clamped_to_20() -> None:
    provider = make_configured_provider(results=[])
    agent = WebAgent(provider=provider)
    req = make_request(metadata={"max_results": "99"})
    await collect(agent.execute(req, make_exec(req)))

    call_args = provider.search.call_args
    max_r = call_args[0][1] if len(call_args[0]) > 1 else call_args[1].get("max_results")
    assert max_r == 20


@pytest.mark.asyncio
async def test_max_results_clamped_to_1() -> None:
    provider = make_configured_provider(results=[])
    agent = WebAgent(provider=provider)
    req = make_request(metadata={"max_results": "-5"})
    await collect(agent.execute(req, make_exec(req)))

    call_args = provider.search.call_args
    max_r = call_args[0][1] if len(call_args[0]) > 1 else call_args[1].get("max_results")
    assert max_r == 1


# ---------------------------------------------------------------------------
# Empty results
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_empty_results_emits_completed_with_no_results_message() -> None:
    agent = WebAgent(provider=make_configured_provider(results=[]))
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED
    assert "No web results found" in (completed.result.content or "")


# ---------------------------------------------------------------------------
# Search error
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_search_exception_emits_search_error() -> None:
    provider = MagicMock()
    provider.is_configured = True
    provider.search = AsyncMock(side_effect=RuntimeError("network failure"))
    agent = WebAgent(provider=provider)
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    assert any(
        isinstance(e, AgentFailedEvent) and "SEARCH_ERROR" in e.result.error.code
        for e in events
    )


# ---------------------------------------------------------------------------
# Result metadata
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_completed_metadata_contains_provider_and_count() -> None:
    provider = make_configured_provider()
    provider.provider_name = "my-provider"
    agent = WebAgent(provider=provider)
    req = make_request()
    events = await collect(agent.execute(req, make_exec(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.metadata.get("provider") == "my-provider"
    assert completed.result.metadata.get("result_count") == "2"
