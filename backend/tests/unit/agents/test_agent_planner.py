# ============================================================
# tests/unit/agents/test_agent_planner.py
# Unit tests for AgentPlanner — plan construction and limit checks.
# ============================================================
"""Unit tests for AgentPlanner."""
from __future__ import annotations

import time
from collections.abc import AsyncIterator

import pytest

from app.agents.base import Agent
from app.agents.models import AgentCapability, AgentEvent, AgentExecution, AgentRequest
from app.agents.planner import (
    METADATA_KEY_PLAN_STEPS,
    AgentPlan,
    AgentPlanner,
    AgentPlanStep,
    PlanCounters,
)
from app.agents.registry import AgentNotFoundError, AgentRegistry


class StubAgent(Agent):
    def __init__(self, name: str) -> None:
        self._name = name

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset()

    async def execute(self, request: AgentRequest, execution: AgentExecution) -> AsyncIterator[AgentEvent]:
        return
        yield


def registry(*names: str) -> AgentRegistry:
    reg = AgentRegistry()
    for n in names:
        reg.register(StubAgent(n))
    return reg


def req(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {"user_id": "u1", "input": "test"}
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


planner = AgentPlanner()


# ── Single-step plan ──────────────────────────────────────────────────────────


def test_single_step_plan_built_for_simple_request() -> None:
    agent = StubAgent("conversational")
    reg = registry("conversational")
    plan = planner.build_plan(req(), agent, reg)

    assert plan.steps == [AgentPlanStep(agent_name="conversational")]
    assert plan.is_multi_step is False
    assert plan.handoff_count == 0
    assert plan.max_handoffs == 0


def test_single_step_inherits_max_steps_and_timeout() -> None:
    agent = StubAgent("alpha")
    reg = registry("alpha")
    plan = planner.build_plan(req(max_steps=5, timeout_ms=30_000), agent, reg)
    assert plan.max_steps == 5
    assert plan.timeout_ms == 30_000


# ── Multi-step plan ───────────────────────────────────────────────────────────


def test_multi_step_plan_from_metadata() -> None:
    reg = registry("rag", "code")
    agent = StubAgent("rag")
    plan = planner.build_plan(req(metadata={METADATA_KEY_PLAN_STEPS: "rag,code"}), agent, reg)

    assert plan.steps == [AgentPlanStep("rag"), AgentPlanStep("code")]
    assert plan.is_multi_step is True
    assert plan.handoff_count == 1


def test_multi_step_fails_when_agent_missing() -> None:
    reg = registry("rag")
    agent = StubAgent("rag")
    with pytest.raises(AgentNotFoundError):
        planner.build_plan(req(metadata={METADATA_KEY_PLAN_STEPS: "rag,missing"}), agent, reg)


def test_three_step_plan_pdf_rag_code() -> None:
    reg = registry("pdf", "rag", "code")
    agent = StubAgent("pdf")
    plan = planner.build_plan(
        req(metadata={METADATA_KEY_PLAN_STEPS: "pdf,rag,code"}), agent, reg
    )
    assert [s.agent_name for s in plan.steps] == ["pdf", "rag", "code"]
    assert plan.handoff_count == 2


# ── AgentPlan validation ──────────────────────────────────────────────────────


def test_plan_no_steps_raises() -> None:
    with pytest.raises(ValueError):
        AgentPlan(steps=[], request_id="r1")


def test_plan_max_steps_zero_raises() -> None:
    with pytest.raises(ValueError):
        AgentPlan(steps=[AgentPlanStep("a")], request_id="r1", max_steps=0)


def test_plan_more_steps_than_max_handoffs_raises() -> None:
    with pytest.raises(ValueError):
        AgentPlan(
            steps=[AgentPlanStep("a"), AgentPlanStep("b"), AgentPlanStep("c")],
            request_id="r1",
            max_handoffs=1,  # 2 required
        )


def test_plan_timeout_zero_raises() -> None:
    with pytest.raises(ValueError):
        AgentPlan(steps=[AgentPlanStep("a")], request_id="r1", timeout_ms=0)


# ── check_limits ──────────────────────────────────────────────────────────────


def test_check_limits_returns_none_within_budget() -> None:
    plan = AgentPlan(
        steps=[AgentPlanStep("a")],
        request_id="r1",
        max_steps=10,
        max_handoffs=3,
        max_tool_calls=20,
        timeout_ms=60_000,
    )
    counters = PlanCounters(steps_taken=5, handoffs_done=1, tool_calls_made=5)
    assert planner.check_limits(plan, counters) is None


def test_check_limits_detects_max_steps_exceeded() -> None:
    plan = AgentPlan(steps=[AgentPlanStep("a")], request_id="r1", max_steps=3)
    counters = PlanCounters(steps_taken=3)
    v = planner.check_limits(plan, counters)
    assert v is not None
    assert v.kind == "max_steps"


def test_check_limits_detects_max_tool_calls_exceeded() -> None:
    plan = AgentPlan(steps=[AgentPlanStep("a")], request_id="r1", max_tool_calls=5)
    counters = PlanCounters(tool_calls_made=5)
    v = planner.check_limits(plan, counters)
    assert v is not None
    assert v.kind == "max_tool_calls"


def test_check_limits_detects_timeout_exceeded() -> None:
    plan = AgentPlan(steps=[AgentPlanStep("a")], request_id="r1", timeout_ms=1)
    # Set start_ms far in the past so elapsed is huge
    counters = PlanCounters(start_ms=time.monotonic() * 1000 - 5_000)
    v = planner.check_limits(plan, counters)
    assert v is not None
    assert v.kind == "timeout"
