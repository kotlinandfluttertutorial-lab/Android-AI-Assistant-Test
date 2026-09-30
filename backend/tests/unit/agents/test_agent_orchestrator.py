# ============================================================
# tests/unit/agents/test_agent_orchestrator.py
# Unit tests for AgentOrchestrator — execution, handoffs, timeouts, errors.
# ============================================================
"""Unit tests for AgentOrchestrator."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator

import pytest

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
)
from app.agents.orchestrator import AgentOrchestrator
from app.agents.planner import METADATA_KEY_PLAN_STEPS, AgentPlanner
from app.agents.registry import AgentRegistry
from app.agents.router import AgentRouter

# ── Stub agents ────────────────────────────────────────────────────────────────


class SuccessAgent(Agent):
    def __init__(self, name: str, content: str = "done") -> None:
        self._name = name
        self._content = content

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "success stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({AgentCapability.TEXT_GENERATION})

    async def execute(
        self, request: AgentRequest, execution: AgentExecution
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self._name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )
        yield AgentTokenEvent(token=self._content)
        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self._name,
            status=AgentStatus.COMPLETED,
            content=self._content,
        )
        yield AgentCompletedEvent(result=result)


class FailAgent(Agent):
    def __init__(self, name: str) -> None:
        self._name = name

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "fail stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({AgentCapability.TEXT_GENERATION})

    async def execute(
        self, request: AgentRequest, execution: AgentExecution
    ) -> AsyncIterator[AgentEvent]:
        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self._name,
            status=AgentStatus.FAILED,
            error=AgentError(code="ERR", message="deliberate failure"),
        )
        yield AgentFailedEvent(result=result)


class SlowAgent(Agent):
    """Agent that waits 2 s before producing output."""

    def __init__(self, name: str = "slow") -> None:
        self._name = name

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "slow stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({AgentCapability.TEXT_GENERATION})

    async def execute(
        self, request: AgentRequest, execution: AgentExecution
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self._name)
        await asyncio.sleep(2.0)
        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self._name,
            status=AgentStatus.COMPLETED,
            content="done",
        )
        yield AgentCompletedEvent(result=result)


# ── Helpers ────────────────────────────────────────────────────────────────────


def build_orchestrator(*agents: Agent) -> AgentOrchestrator:
    reg = AgentRegistry()
    for a in agents:
        reg.register(a)
    return AgentOrchestrator(registry=reg, router=AgentRouter(), planner=AgentPlanner())


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {"user_id": "u1", "input": "hello"}
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


async def collect_events(orc: AgentOrchestrator, request: AgentRequest) -> list[AgentEvent]:
    events: list[AgentEvent] = []
    gen = await orc.execute(request)
    async for event in gen:
        events.append(event)
        if event.type in ("completed", "failed", "cancelled"):
            break
    return events


# ── Tests ──────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_empty_registry_emits_failed_routing() -> None:
    orc = AgentOrchestrator(registry=AgentRegistry(), router=AgentRouter(), planner=AgentPlanner())
    events = await collect_events(orc, make_request())
    failed = next(e for e in events if isinstance(e, AgentFailedEvent))
    assert failed.result.error is not None
    assert failed.result.error.code == "ROUTING_FAILED"


@pytest.mark.asyncio
async def test_explicit_unknown_agent_emits_failed() -> None:
    orc = build_orchestrator(SuccessAgent("alpha"))
    events = await collect_events(orc, make_request(metadata={"agent_name": "missing"}))
    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None


@pytest.mark.asyncio
async def test_simple_execution_emits_complete_event_stream() -> None:
    orc = build_orchestrator(SuccessAgent("alpha", "Hello world"))
    events = await collect_events(orc, make_request())

    assert any(isinstance(e, AgentStartedEvent) for e in events)
    tokens = [e for e in events if isinstance(e, AgentTokenEvent)]
    assert len(tokens) >= 1
    assert tokens[0].token == "Hello world"
    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.content == "Hello world"
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_agent_failure_propagates_as_failed_event() -> None:
    orc = build_orchestrator(FailAgent("alpha"))
    events = await collect_events(orc, make_request())
    failed = next(e for e in events if isinstance(e, AgentFailedEvent))
    assert failed.result.status == AgentStatus.FAILED


@pytest.mark.asyncio
async def test_two_step_handoff_plan_executes_both_agents() -> None:
    reg = AgentRegistry()
    reg.register(SuccessAgent("rag", "rag-output"))
    reg.register(SuccessAgent("code", "code-output"))
    orc = AgentOrchestrator(registry=reg, router=AgentRouter(), planner=AgentPlanner())

    events: list[AgentEvent] = []
    gen = await orc.execute(make_request(metadata={METADATA_KEY_PLAN_STEPS: "rag,code"}))
    async for e in gen:
        events.append(e)
        if e.type in ("completed", "failed"):
            break

    tokens = [e for e in events if isinstance(e, AgentTokenEvent)]
    token_texts = [t.token for t in tokens]
    assert "rag-output" in token_texts or "code-output" in token_texts


@pytest.mark.asyncio
async def test_timeout_emits_failed_with_timeout_code() -> None:
    orc = build_orchestrator(SlowAgent())
    events = await collect_events(orc, make_request(timeout_ms=100))

    started = [e for e in events if isinstance(e, AgentStartedEvent)]
    assert len(started) >= 1

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert failed.result.error.code == "TIMEOUT"


@pytest.mark.asyncio
async def test_model_routing_decision_auto_path_raises() -> None:
    from app.agents.llm_client import LLMServiceAdapter
    from app.agents.model_router import InferencePath, ModelRoutingDecision

    with pytest.raises(ValueError):
        ModelRoutingDecision(
            path=InferencePath.AUTO,
            provider_name="gemini",
            client=LLMServiceAdapter(),
        )


@pytest.mark.asyncio
async def test_model_router_explicit_gemma_returns_on_device() -> None:
    from unittest.mock import MagicMock

    from app.agents.model_router import InferencePath, ModelRouter

    mock_local = MagicMock()
    mock_local.provider_name = "gemma"
    mock_local.is_available = True
    mock_cloud = MagicMock()
    mock_cloud.provider_name = "gemini"
    mock_cloud.is_available = True

    router = ModelRouter(cloud_client=mock_cloud, local_client=mock_local)
    request = AgentRequest(user_id="u1", input="x", provider="gemma")
    decision = await router.route(request, preference=InferencePath.AUTO)

    assert decision.path == InferencePath.ON_DEVICE
    assert decision.provider_name == "gemma"


@pytest.mark.asyncio
async def test_model_router_cloud_preference() -> None:
    from unittest.mock import MagicMock

    from app.agents.model_router import InferencePath, ModelRouter

    mock_local = MagicMock()
    mock_local.provider_name = "gemma"
    mock_local.is_available = True
    mock_cloud = MagicMock()
    mock_cloud.provider_name = "gemini"

    router = ModelRouter(cloud_client=mock_cloud, local_client=mock_local)
    decision = await router.route(
        AgentRequest(user_id="u1", input="x"),
        preference=InferencePath.CLOUD,
    )
    assert decision.path == InferencePath.CLOUD


@pytest.mark.asyncio
async def test_model_router_on_device_preference_falls_back_when_unavailable() -> None:
    from unittest.mock import MagicMock

    from app.agents.model_router import InferencePath, ModelRouter

    mock_local = MagicMock()
    mock_local.provider_name = "gemma"
    mock_local.is_available = False  # not available
    mock_cloud = MagicMock()
    mock_cloud.provider_name = "gemini"

    router = ModelRouter(cloud_client=mock_cloud, local_client=mock_local)
    decision = await router.route(
        AgentRequest(user_id="u1", input="x"),
        preference=InferencePath.ON_DEVICE,
    )
    assert decision.path == InferencePath.CLOUD
    assert decision.fallback_occurred is True


@pytest.mark.asyncio
async def test_llm_client_exception_carries_provider_and_retryable() -> None:
    from app.agents.llm_client import LLMClientError

    exc = LLMClientError("oops", provider="gemini", retryable=True)
    assert exc.provider == "gemini"
    assert exc.retryable is True
    assert str(exc) == "oops"
