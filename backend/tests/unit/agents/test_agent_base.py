# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_base.py
# Purpose : Unit tests for Agent base class default can_handle() behaviour.
# ============================================================

"""Unit tests for Agent ABC — focusing on the default can_handle() logic."""

from collections.abc import AsyncIterator

import pytest

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentEvent,
    AgentExecution,
    AgentRequest,
)


class StubAgent(Agent):
    """Minimal concrete Agent for testing."""

    def __init__(self, capabilities: frozenset[AgentCapability]) -> None:
        self._capabilities = capabilities

    @property
    def name(self) -> str:
        return "stub"

    @property
    def description(self) -> str:
        return "Test stub agent."

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return self._capabilities

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        # Empty generator — not used in these tests
        return
        yield  # make this a generator


def make_request(*caps: AgentCapability) -> AgentRequest:
    return AgentRequest(user_id="u1", input="test", capabilities=list(caps))


# ---------------------------------------------------------------------------
# can_handle — default behaviour
# ---------------------------------------------------------------------------


def test_can_handle_true_when_no_capability_constraint() -> None:
    agent = StubAgent(frozenset({AgentCapability.TEXT_GENERATION}))
    request = AgentRequest(user_id="u1", input="hi")  # empty capabilities
    assert agent.can_handle(request) is True


def test_can_handle_true_when_all_required_capabilities_satisfied() -> None:
    agent = StubAgent(frozenset({
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
        AgentCapability.MEMORY_ACCESS,
    }))
    request = make_request(AgentCapability.TEXT_GENERATION, AgentCapability.STREAMING)
    assert agent.can_handle(request) is True


def test_can_handle_false_when_agent_lacks_required_capability() -> None:
    agent = StubAgent(frozenset({AgentCapability.TEXT_GENERATION}))
    request = make_request(AgentCapability.TEXT_GENERATION, AgentCapability.TOOL_USE)
    assert agent.can_handle(request) is False


def test_can_handle_false_when_agent_has_no_capabilities_and_request_requires_some() -> None:
    agent = StubAgent(frozenset())
    request = make_request(AgentCapability.CODE_ANALYSIS)
    assert agent.can_handle(request) is False


def test_can_handle_true_when_capabilities_match_exactly() -> None:
    caps = frozenset({AgentCapability.ON_DEVICE_INFERENCE, AgentCapability.TEXT_GENERATION})
    agent = StubAgent(caps)
    request = make_request(*caps)
    assert agent.can_handle(request) is True


def test_repr_includes_name_and_capabilities() -> None:
    agent = StubAgent(frozenset({AgentCapability.STREAMING}))
    r = repr(agent)
    assert "stub" in r
    assert "STREAMING" in r


# ---------------------------------------------------------------------------
# Abstract enforcement
# ---------------------------------------------------------------------------


def test_cannot_instantiate_base_agent_directly() -> None:
    with pytest.raises(TypeError):
        Agent()  # type: ignore[abstract]
