# tests/unit/agents/test_tool_agent.py — Unit tests for ToolAgent
"""Unit tests for ToolAgent (Phase 5)."""
from __future__ import annotations
import json
from collections.abc import AsyncIterator
from unittest.mock import AsyncMock, MagicMock, patch
import pytest
from app.agents.tool_agent import TOOL_AGENT_NAME, ToolAgent
from app.agents.models import (AgentCapability, AgentCompletedEvent, AgentEvent,
    AgentExecution, AgentFailedEvent, AgentRequest, AgentStatus, AgentToolCompletedEvent,
    AgentToolConfirmationRequiredEvent, AgentToolFailedEvent, AgentToolStartedEvent)


def make_request(**kwargs) -> AgentRequest:
    d = {"user_id": "user-1", "input": "run tool",
         "metadata": {"tool_name": "github", "tool_params": "{}"}}
    d.update(kwargs)
    return AgentRequest(**d)

def make_exec(req=None) -> AgentExecution:
    r = req or make_request()
    return AgentExecution(request=r, agent_name=TOOL_AGENT_NAME)

async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]

def _mock_broker(success=True, result=None, error=None, requires_confirmation=False):
    from app.schemas.mcp import MCPToolResult
    mr = MCPToolResult(tool_name="t", success=success, result=result or {"output": "ok"},
                       error=error, result_status="success" if success else "error")
    connector = MagicMock(); connector.requires_confirmation = requires_confirmation
    broker = MagicMock()
    broker._registry = {"github": connector}
    broker.invoke = AsyncMock(return_value=mr)
    return broker

def mock_stack(success=True, result=None, error=None, requires_confirmation=False):
    broker = _mock_broker(success, result, error, requires_confirmation)
    class _S:
        def __enter__(self):
            mock_session = MagicMock()
            mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
            mock_session.__aexit__ = AsyncMock(return_value=None)
            self._patches = [
                patch("app.agents.tool_agent.AsyncSessionLocal", return_value=mock_session),
                patch("app.agents.tool_agent.MCPBroker", return_value=broker),
                patch("app.agents.tool_agent._get_mcp_broker_fn", None),
            ]
            for p in self._patches: p.start()
            return self
        def __exit__(self, *_): [p.stop() for p in self._patches]
    return _S()

# ── Capabilities ───────────────────────────────────────────────────────────────
def test_agent_name(): assert ToolAgent().name == TOOL_AGENT_NAME
def test_declares_tool_use(): assert AgentCapability.TOOL_USE in ToolAgent().capabilities

# ── Unauthenticated ────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_blank_user_id_emits_unauthenticated():
    """ToolAgent must reject blank userId even when services are available."""
    agent = ToolAgent()
    # user_id=" " (whitespace) passes Pydantic validation but ToolAgent strips it to blank
    req = make_request(user_id=" ", metadata={"tool_name": "github", "tool_params": "{}"})
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    with (patch("app.agents.tool_agent.AsyncSessionLocal", return_value=mock_session),
          patch("app.agents.tool_agent.MCPBroker", return_value=MagicMock()),
          patch("app.agents.tool_agent._get_mcp_broker_fn", None)):
        events = await collect(agent.execute(req, make_exec(req)))
    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None and "UNAUTHENTICATED" in failed.result.error.code

# ── Missing tool name ──────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_missing_tool_name_emits_missing():
    """ToolAgent must reject requests with no tool_name when services are available."""
    agent = ToolAgent()
    req = make_request(metadata={})  # no tool_name
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    with (patch("app.agents.tool_agent.AsyncSessionLocal", return_value=mock_session),
          patch("app.agents.tool_agent.MCPBroker", return_value=MagicMock()),
          patch("app.agents.tool_agent._get_mcp_broker_fn", None)):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentFailedEvent) and "MISSING_TOOL_NAME" in e.result.error.code for e in events)

# ── Successful tool call ───────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_successful_tool_call():
    agent = ToolAgent()
    req = make_request()
    with mock_stack(success=True, result={"answer": 42}):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentToolStartedEvent) for e in events)
    assert any(isinstance(e, AgentToolCompletedEvent) for e in events)
    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.status == AgentStatus.COMPLETED

# ── Invalid params JSON ────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_invalid_params_json_emits_failed():
    """ToolAgent must reject malformed tool_params JSON when services are available."""
    agent = ToolAgent()
    req = make_request(metadata={"tool_name": "github", "tool_params": "not-json"})
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    with (patch("app.agents.tool_agent.AsyncSessionLocal", return_value=mock_session),
          patch("app.agents.tool_agent.MCPBroker", return_value=MagicMock()),
          patch("app.agents.tool_agent._get_mcp_broker_fn", None)):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentFailedEvent) and "INVALID_PARAMS" in e.result.error.code for e in events)

# ── Confirmation gate ──────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_unconfirmed_write_tool_emits_confirmation_required():
    agent = ToolAgent()
    req = make_request()
    with mock_stack(requires_confirmation=True):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentToolConfirmationRequiredEvent) for e in events)
    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None and completed.result.status == AgentStatus.WAITING

@pytest.mark.asyncio
async def test_confirmed_write_tool_executes():
    agent = ToolAgent()
    req = make_request(metadata={"tool_name": "github", "tool_params": "{}", "confirmed": "true"})
    with mock_stack(requires_confirmation=True, success=True):
        events = await collect(agent.execute(req, make_exec(req)))
    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None and completed.result.status == AgentStatus.COMPLETED

# ── Tool returning error ───────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_tool_returning_error_emits_tool_failed():
    agent = ToolAgent()
    req = make_request()
    with mock_stack(success=False, error="permission denied"):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentToolFailedEvent) for e in events)
    assert any(isinstance(e, AgentFailedEvent) for e in events)

# ── Timeout ────────────────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_tool_timeout_emits_failed():
    import asyncio
    agent = ToolAgent()
    req = make_request()
    async def slow_invoke(**kw): await asyncio.sleep(100)
    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)
    broker = MagicMock(); broker._registry = {}; broker.invoke = slow_invoke
    with (patch("app.agents.tool_agent.AsyncSessionLocal", return_value=mock_session),
          patch("app.agents.tool_agent.MCPBroker", return_value=broker),
          patch("app.agents.tool_agent._get_mcp_broker_fn", None),
          patch("app.agents.tool_agent._DEFAULT_TOOL_TIMEOUT", 0.01)):
        events = await collect(agent.execute(req, make_exec(req)))
    assert any(isinstance(e, AgentFailedEvent) and "TIMEOUT" in e.result.error.code for e in events)

# ── Service unavailable ────────────────────────────────────────────────────────
@pytest.mark.asyncio
async def test_services_unavailable_emits_failed():
    agent = ToolAgent()
    req = make_request()
    import app.agents.tool_agent as mod
    orig_s = mod.AsyncSessionLocal; orig_m = mod.MCPBroker
    mod.AsyncSessionLocal = None; mod.MCPBroker = None
    try: events = await collect(agent.execute(req, make_exec(req)))
    finally: mod.AsyncSessionLocal = orig_s; mod.MCPBroker = orig_m
    assert any(isinstance(e, AgentFailedEvent) for e in events)
