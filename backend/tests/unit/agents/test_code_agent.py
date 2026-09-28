# ============================================================
# tests/unit/agents/test_code_agent.py — Unit tests for CodeAgent
# ============================================================
"""Unit tests for CodeAgent (Phase 4)."""
from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.code_agent import (
    CODE_AGENT_NAME,
    VALID_ACTIONS,
    VALID_LANGUAGES,
    CodeAgent,
    _action_instruction,
    _build_prompt,
)
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentStartedEvent,
    AgentStatusChangedEvent,
    AgentTokenEvent,
)


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "u1",
        "input": "fun hello() = println(\"hi\")",
        "metadata": {"code_action": "explain", "language_id": "kotlin"},
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_execution(request: AgentRequest | None = None) -> AgentExecution:
    req = request or make_request()
    return AgentExecution(request=req, agent_name=CODE_AGENT_NAME)


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


def mock_session_and_orc(result_text: str = "## Explanation\nIt prints hi."):
    """Context manager stack that mocks DB session and AIOrchestrator.complete."""

    class _Stack:
        def __init__(self) -> None:
            self._patches: list[object] = []
            self._mocks: dict[str, object] = {}

        def __enter__(self) -> _Stack:
            mock_session = MagicMock()
            mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
            mock_session.__aexit__ = AsyncMock(return_value=None)
            p1 = patch("app.agents.code_agent.AsyncSessionLocal", return_value=mock_session)

            mock_orc_inst = AsyncMock()
            mock_orc_inst.complete = AsyncMock(return_value=result_text)
            p2 = patch("app.agents.code_agent.AIOrchestrator", return_value=mock_orc_inst)

            p3 = patch("app.agents.code_agent.InjectionDetector", None)

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
    assert CodeAgent().name == CODE_AGENT_NAME


def test_declares_code_analysis() -> None:
    assert AgentCapability.CODE_ANALYSIS in CodeAgent().capabilities


def test_can_handle_empty_capabilities() -> None:
    assert CodeAgent().can_handle(make_request()) is True


def test_can_handle_code_analysis_capability() -> None:
    req = make_request(capabilities=[AgentCapability.CODE_ANALYSIS])
    assert CodeAgent().can_handle(req) is True


def test_cannot_handle_speech_to_text() -> None:
    req = make_request(capabilities=[AgentCapability.SPEECH_TO_TEXT])
    assert CodeAgent().can_handle(req) is False


# ---------------------------------------------------------------------------
# All 6 actions
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("action", ["explain", "fix_bug", "generate_tests",
                                     "generate", "refactor", "review"])
@pytest.mark.asyncio
async def test_action_completes(action: str) -> None:
    agent = CodeAgent()
    req = make_request(metadata={"code_action": action, "language_id": "kotlin"})
    execution = make_execution(req)

    with mock_session_and_orc(f"Result for {action}"):
        events = await collect(agent.execute(req, execution))

    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.content == f"Result for {action}"
    assert completed.result.metadata.get("action") == action


# ---------------------------------------------------------------------------
# Streaming event sequence
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_streaming_event_sequence() -> None:
    agent = CodeAgent()
    req = make_request()
    execution = make_execution(req)

    with mock_session_and_orc("Explained!"):
        events = await collect(agent.execute(req, execution))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)
    assert any(isinstance(e, AgentTokenEvent) for e in events)
    assert isinstance(events[-1], AgentCompletedEvent)


# ---------------------------------------------------------------------------
# Error paths
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_db_unavailable() -> None:
    agent = CodeAgent()
    req = make_request()

    import app.agents.code_agent as _mod
    original = _mod.AsyncSessionLocal
    _mod.AsyncSessionLocal = None
    try:
        events = await collect(agent.execute(req, make_execution(req)))
    finally:
        _mod.AsyncSessionLocal = original

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert failed.result.error.code == "DB_UNAVAILABLE"


@pytest.mark.asyncio
async def test_llm_timeout_emits_failed() -> None:
    agent = CodeAgent()
    req = make_request()
    execution = make_execution(req)

    mock_session = MagicMock()
    mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
    mock_session.__aexit__ = AsyncMock(return_value=None)

    async def slow_complete(*args: object, **kwargs: object) -> str:
        await asyncio.sleep(100)
        return "never"

    mock_orc_inst = MagicMock()
    mock_orc_inst.complete = slow_complete

    with (
        patch("app.agents.code_agent.AsyncSessionLocal", return_value=mock_session),
        patch("app.agents.code_agent.AIOrchestrator", return_value=mock_orc_inst),
        patch("app.agents.code_agent.InjectionDetector", None),
        patch("app.agents.code_agent._ACTION_TIMEOUTS", {"explain": 0.01}),
    ):
        events = await collect(agent.execute(req, execution))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert "TIMEOUT" in failed.result.error.code


# ---------------------------------------------------------------------------
# Metadata in result
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_result_metadata_contains_language_id() -> None:
    agent = CodeAgent()
    req = make_request(metadata={"code_action": "explain", "language_id": "python"})
    execution = make_execution(req)

    with mock_session_and_orc("ok"):
        events = await collect(agent.execute(req, execution))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.metadata.get("language_id") == "python"


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------


def test_build_prompt_contains_code() -> None:
    prompt = _build_prompt("fun x() {}", "kotlin", "explain")
    assert "fun x() {}" in prompt
    assert "kotlin" in prompt.lower()


def test_all_actions_produce_non_empty_instruction() -> None:
    for action in VALID_ACTIONS:
        assert _action_instruction(action)


def test_valid_actions_and_languages_non_empty() -> None:
    assert len(VALID_ACTIONS) >= 6
    assert len(VALID_LANGUAGES) >= 6
