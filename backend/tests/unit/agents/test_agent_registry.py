# ============================================================
# tests/unit/agents/test_agent_registry.py
# Unit tests for AgentRegistry.
# ============================================================
"""Unit tests for AgentRegistry."""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest

from app.agents.base import Agent
from app.agents.models import AgentCapability, AgentEvent, AgentExecution, AgentRequest
from app.agents.registry import AgentNotFoundError, AgentRegistry


def make_request() -> AgentRequest:
    return AgentRequest(user_id="u1", input="test")


class StubAgent(Agent):
    def __init__(self, name: str, *caps: AgentCapability) -> None:
        self._name = name
        self._caps = frozenset(caps)

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return self._caps

    async def execute(
        self, request: AgentRequest, execution: AgentExecution
    ) -> AsyncIterator[AgentEvent]:
        return
        yield


# ── Construction ──────────────────────────────────────────────────────────────


def test_empty_registry_is_empty() -> None:
    reg = AgentRegistry()
    assert reg.is_empty is True
    assert reg.size == 0


# ── register ─────────────────────────────────────────────────────────────────


def test_register_adds_agent() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("alpha"))
    assert reg.size == 1
    assert reg.is_empty is False


def test_register_replaces_same_name() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("alpha"))
    reg.register(StubAgent("alpha"))
    assert reg.size == 1


# ── get ───────────────────────────────────────────────────────────────────────


def test_get_returns_registered_agent() -> None:
    reg = AgentRegistry()
    a = StubAgent("alpha")
    reg.register(a)
    assert reg.get("alpha") is a


def test_get_raises_for_unknown_name() -> None:
    reg = AgentRegistry()
    with pytest.raises(AgentNotFoundError):
        reg.get("unknown")


def test_get_or_none_returns_none_for_unknown() -> None:
    reg = AgentRegistry()
    assert reg.get_or_none("unknown") is None


# ── unregister ────────────────────────────────────────────────────────────────


def test_unregister_removes_agent() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("alpha"))
    reg.unregister("alpha")
    assert reg.is_empty is True


def test_unregister_unknown_is_noop() -> None:
    reg = AgentRegistry()
    reg.unregister("does_not_exist")  # must not raise
    assert reg.is_empty is True


# ── list ──────────────────────────────────────────────────────────────────────


def test_list_returns_all_agents() -> None:
    reg = AgentRegistry()
    a, b = StubAgent("alpha"), StubAgent("beta")
    reg.register(a)
    reg.register(b)
    lst = reg.list()
    assert len(lst) == 2
    assert a in lst
    assert b in lst


# ── find_by_capability ────────────────────────────────────────────────────────


def test_find_by_capability_empty_set_returns_all() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("a", AgentCapability.TEXT_GENERATION))
    reg.register(StubAgent("b", AgentCapability.CODE_ANALYSIS))
    assert len(reg.find_by_capability(set())) == 2


def test_find_by_capability_filters_correctly() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("chat", AgentCapability.TEXT_GENERATION, AgentCapability.STREAMING))
    reg.register(StubAgent("code", AgentCapability.CODE_ANALYSIS))
    results = reg.find_by_capability({AgentCapability.TEXT_GENERATION})
    assert len(results) == 1
    assert results[0].name == "chat"


def test_find_by_capability_returns_empty_when_no_match() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("partial", AgentCapability.TEXT_GENERATION))
    assert reg.find_by_capability({AgentCapability.TEXT_GENERATION, AgentCapability.TOOL_USE}) == []


def test_find_by_capability_all_matching_returned() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("a", AgentCapability.STREAMING))
    reg.register(StubAgent("b", AgentCapability.STREAMING))
    reg.register(StubAgent("c", AgentCapability.CODE_ANALYSIS))
    assert len(reg.find_by_capability({AgentCapability.STREAMING})) == 2


# ── repr ──────────────────────────────────────────────────────────────────────


def test_repr_includes_agent_names() -> None:
    reg = AgentRegistry()
    reg.register(StubAgent("alpha"))
    assert "alpha" in repr(reg)
