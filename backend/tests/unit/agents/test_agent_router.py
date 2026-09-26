# ============================================================
# tests/unit/agents/test_agent_router.py
# Unit tests for AgentRouter deterministic routing logic.
# ============================================================
"""Unit tests for AgentRouter."""
from __future__ import annotations

from collections.abc import AsyncIterator

from app.agents.base import Agent
from app.agents.models import AgentCapability, AgentEvent, AgentExecution, AgentRequest
from app.agents.registry import AgentRegistry
from app.agents.router import (
    AGENT_NAME_CONVERSATIONAL,
    METADATA_KEY_AGENT_NAME,
    METADATA_KEY_ATTACHMENT_TYPE,
    AgentRouter,
)


class StubAgent(Agent):
    def __init__(self, name: str, *caps: AgentCapability, rejects_all: bool = False) -> None:
        self._name = name
        self._caps = frozenset(caps)
        self._rejects = rejects_all

    @property
    def name(self) -> str:
        return self._name

    @property
    def description(self) -> str:
        return "stub"

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return self._caps

    def can_handle(self, request: AgentRequest) -> bool:
        if self._rejects:
            return False
        return super().can_handle(request)

    async def execute(self, request: AgentRequest, execution: AgentExecution) -> AsyncIterator[AgentEvent]:
        return
        yield


def registry(*agents: Agent) -> AgentRegistry:
    reg = AgentRegistry()
    for a in agents:
        reg.register(a)
    return reg


def req(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {"user_id": "u1", "input": "hello"}
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


router = AgentRouter()


# ── Empty registry ────────────────────────────────────────────────────────────


def test_empty_registry_returns_no_agent_found() -> None:
    result = router.route(req(), AgentRegistry())
    assert result.failed is True


# ── Explicit agent name ───────────────────────────────────────────────────────


def test_explicit_agent_name_routes_to_that_agent() -> None:
    a = StubAgent("alpha")
    result = router.route(req(metadata={METADATA_KEY_AGENT_NAME: "alpha"}), registry(a))
    assert result.failed is False
    assert result.agent is a
    assert "explicit_name:alpha" in result.reason


def test_explicit_agent_name_not_registered_returns_no_agent_found() -> None:
    result = router.route(
        req(metadata={METADATA_KEY_AGENT_NAME: "missing"}),
        registry(StubAgent("alpha")),
    )
    assert result.failed is True


# ── Capability match ──────────────────────────────────────────────────────────


def test_capability_match_selects_correct_agent() -> None:
    code = StubAgent("code", AgentCapability.CODE_ANALYSIS)
    chat = StubAgent("chat", AgentCapability.TEXT_GENERATION)
    result = router.route(
        req(capabilities=[AgentCapability.CODE_ANALYSIS]),
        registry(chat, code),
    )
    assert result.agent is code
    assert "capability_match" in result.reason


def test_no_agent_with_required_capability_returns_no_agent_found() -> None:
    chat = StubAgent("chat", AgentCapability.TEXT_GENERATION)
    result = router.route(
        req(capabilities=[AgentCapability.TOOL_USE]),
        registry(chat),
    )
    assert result.failed is True


# ── Attachment routing ────────────────────────────────────────────────────────


def test_image_attachment_routes_to_image_understanding_agent() -> None:
    vision = StubAgent("vision", AgentCapability.IMAGE_UNDERSTANDING)
    chat = StubAgent("chat", AgentCapability.TEXT_GENERATION)
    result = router.route(
        req(metadata={METADATA_KEY_ATTACHMENT_TYPE: "image"}),
        registry(chat, vision),
    )
    assert result.agent is vision
    assert result.reason == "attachment_type:image"


# ── Conversation context ──────────────────────────────────────────────────────


def test_conversation_context_prefers_conversational_agent() -> None:
    conv = StubAgent(AGENT_NAME_CONVERSATIONAL, AgentCapability.TEXT_GENERATION)
    code = StubAgent("code", AgentCapability.CODE_ANALYSIS)
    result = router.route(
        req(conversation_id="conv-1"),
        registry(code, conv),
    )
    assert result.agent is conv
    assert result.reason == "conversation_context"


# ── First capable ─────────────────────────────────────────────────────────────


def test_first_capable_agent_selected_when_no_other_heuristic_matches() -> None:
    a = StubAgent("alpha", AgentCapability.TEXT_GENERATION)
    result = router.route(req(), registry(a))
    assert result.agent is a
    assert result.reason == "first_capable"


def test_no_capable_agent_returns_no_agent_found() -> None:
    picky = StubAgent("picky", rejects_all=True)
    result = router.route(req(), registry(picky))
    assert result.failed is True
