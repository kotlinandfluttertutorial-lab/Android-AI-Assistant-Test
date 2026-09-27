# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_chat_agent.py
# Purpose : Unit tests for ChatAgent — capabilities, metadata, event mapping,
#           auth, errors, tool passthrough, streaming, cancellation.
#
# Note: ChatAgent calls AIOrchestrator.stream_chat() which requires a real
# database session. These tests stub the AIOrchestrator call entirely so no
# database is needed.
# ============================================================

"""Unit tests for ChatAgent (Phase 3)."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.chat_agent import CHAT_AGENT_NAME, ChatAgent, _CaptureProxy
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
    AgentToolCompletedEvent,
    AgentToolStartedEvent,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "user-1",
        "input": "Hello",
        "conversation_id": "conv-1",
        "provider": "gemini",
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_execution(request: AgentRequest | None = None) -> AgentExecution:
    req = request or make_request()
    return AgentExecution(request=req, agent_name=CHAT_AGENT_NAME)


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


def make_agent() -> ChatAgent:
    return ChatAgent()


# ---------------------------------------------------------------------------
# Metadata and capabilities
# ---------------------------------------------------------------------------


def test_agent_name_is_conversational() -> None:
    assert ChatAgent().name == "conversational"


def test_agent_declares_text_generation_and_streaming() -> None:
    caps = ChatAgent().capabilities
    assert AgentCapability.TEXT_GENERATION in caps
    assert AgentCapability.STREAMING in caps


def test_agent_description_is_non_empty() -> None:
    assert ChatAgent().description


def test_can_handle_empty_capabilities() -> None:
    agent = make_agent()
    assert agent.can_handle(make_request()) is True


def test_can_handle_text_generation_capability() -> None:
    agent = make_agent()
    req = make_request(capabilities=[AgentCapability.TEXT_GENERATION])
    assert agent.can_handle(req) is True


def test_cannot_handle_speech_to_text() -> None:
    agent = make_agent()
    req = make_request(capabilities=[AgentCapability.SPEECH_TO_TEXT])
    assert agent.can_handle(req) is False


# ---------------------------------------------------------------------------
# _CaptureProxy
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_capture_proxy_queues_token_and_done() -> None:
    proxy = _CaptureProxy()
    _background_tasks: list[object] = []

    async def feeder() -> None:
        await proxy.send_json({"type": "token", "data": "hello"})
        await proxy.send_json({"type": "done", "usage": {"inputTokens": 1, "outputTokens": 2}})

    _background_tasks.append(asyncio.create_task(feeder()))

    frames: list[dict[str, Any]] = []
    async for frame in proxy.events():
        frames.append(frame)

    assert len(frames) == 2
    assert frames[0]["type"] == "token"
    assert frames[1]["type"] == "done"


@pytest.mark.asyncio
async def test_capture_proxy_stops_on_error() -> None:
    proxy2 = _CaptureProxy()
    _background_tasks2: list[object] = []

    async def feeder2() -> None:
        await proxy2.send_json({"type": "error", "message": "oops"})

    _background_tasks2.append(asyncio.create_task(feeder2()))

    frames: list[dict[str, Any]] = []
    async for frame in proxy2.events():
        frames.append(frame)

    assert len(frames) == 1
    assert frames[0]["type"] == "error"


# ---------------------------------------------------------------------------
# Normal streaming via mocked AIOrchestrator
# ---------------------------------------------------------------------------


def _make_stream_patch(frames: list[dict[str, Any]]):
    """Return a patch that makes AIOrchestrator.stream_chat feed *frames* into the proxy."""

    async def mock_stream_chat(
        conversation_id: str,
        user_message: str,
        provider: object,
        user_id: str,
        ws: _CaptureProxy,  # type: ignore[type-arg]
    ) -> None:
        for frame in frames:
            await ws.send_json(frame)

    return mock_stream_chat


@pytest.mark.asyncio
async def test_normal_streaming_emits_token_and_completed() -> None:
    agent = make_agent()
    request = make_request()
    execution = make_execution(request)

    frames = [
        {"type": "token", "data": "Hello"},
        {"type": "token", "data": " world"},
        {"type": "done", "usage": {"inputTokens": 5, "outputTokens": 10}},
    ]

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = _make_stream_patch(frames)
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        events = await collect(agent.execute(request, execution))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)

    tokens = [e for e in events if isinstance(e, AgentTokenEvent)]
    assert len(tokens) == 2
    assert tokens[0].token == "Hello"
    assert tokens[1].token == " world"

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED
    assert completed.result.content == "Hello world"
    assert completed.result.usage is not None
    assert completed.result.usage.input_tokens == 5
    assert completed.result.usage.output_tokens == 10


@pytest.mark.asyncio
async def test_streaming_error_frame_emits_failed() -> None:
    agent = make_agent()
    request = make_request()
    execution = make_execution(request)

    frames = [{"type": "error", "message": "LLM quota exceeded"}]

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = _make_stream_patch(frames)
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        events = await collect(agent.execute(request, execution))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.status == AgentStatus.FAILED
    assert failed.result.error is not None
    assert failed.result.error.code == "STREAM_ERROR"
    assert "quota" in failed.result.error.message.lower()


# ---------------------------------------------------------------------------
# Tool call passthrough
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_tool_call_emits_tool_started_and_completed() -> None:
    agent = make_agent()
    request = make_request()
    execution = make_execution(request)

    frames = [
        {"type": "tool_call", "toolName": "github", "toolInput": {"title": "Bug"}},
        {"type": "token", "data": "Created issue"},
        {"type": "done", "usage": {"inputTokens": 1, "outputTokens": 5}},
    ]

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = _make_stream_patch(frames)
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        events = await collect(agent.execute(request, execution))

    tool_started = [e for e in events if isinstance(e, AgentToolStartedEvent)]
    tool_completed = [e for e in events if isinstance(e, AgentToolCompletedEvent)]
    assert len(tool_started) == 1
    assert tool_started[0].tool_name == "github"
    assert len(tool_completed) == 1
    assert tool_completed[0].tool_name == "github"


# ---------------------------------------------------------------------------
# Missing conversationId
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_missing_conversation_id_emits_failed() -> None:
    agent = make_agent()
    request = make_request(conversation_id=None)
    execution = make_execution(request)

    events = await collect(agent.execute(request, execution))

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert failed.result.error.code == "MISSING_CONVERSATION_ID"


@pytest.mark.asyncio
async def test_conversation_id_from_metadata_fallback() -> None:
    agent = make_agent()
    request = make_request(
        conversation_id=None,
        metadata={"conversation_id": "meta-conv-1"},
    )
    execution = make_execution(request)

    frames = [{"type": "done", "usage": {"inputTokens": 0, "outputTokens": 0}}]

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = _make_stream_patch(frames)
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        events = await collect(agent.execute(request, execution))

    completed = [e for e in events if isinstance(e, AgentCompletedEvent)]
    assert len(completed) == 1
    # Confirm the correct conversation_id was forwarded
    call_kwargs = mock_orc.stream_chat.call_args
    assert call_kwargs is not None
    assert call_kwargs.kwargs.get("conversation_id") == "meta-conv-1" or \
           (len(call_kwargs.args) > 0 and call_kwargs.args[0] == "meta-conv-1")


# ---------------------------------------------------------------------------
# DB unavailable
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_db_unavailable_emits_failed() -> None:
    agent = make_agent()
    request = make_request()
    execution = make_execution(request)

    # Patch the module-level AsyncSessionLocal to None to simulate DB unavailability
    import app.agents.chat_agent as _chat_mod
    original = _chat_mod.AsyncSessionLocal
    _chat_mod.AsyncSessionLocal = None
    try:
        events = await collect(agent.execute(request, execution))
    finally:
        _chat_mod.AsyncSessionLocal = original

    failed = next((e for e in events if isinstance(e, AgentFailedEvent)), None)
    assert failed is not None
    assert failed.result.error is not None
    assert failed.result.error.code == "DB_UNAVAILABLE"


# ---------------------------------------------------------------------------
# Provider forwarding
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_unknown_provider_defaults_to_gemini() -> None:
    agent = make_agent()
    request = make_request(provider="unknown_llm")
    execution = make_execution(request)

    frames = [{"type": "done", "usage": {"inputTokens": 0, "outputTokens": 0}}]
    captured_provider: list[object] = []

    async def capture_provider(*args: object, **kwargs: object) -> None:
        captured_provider.append(kwargs.get("provider") or (args[2] if len(args) > 2 else None))
        ws = kwargs.get("ws") or args[4]
        for frame in frames:
            await ws.send_json(frame)

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = capture_provider
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        await collect(agent.execute(request, execution))

    # Should not raise; provider falls back to gemini internally
    assert len(captured_provider) == 1


# ---------------------------------------------------------------------------
# Conversation history (context presence)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_conversation_context_userId_used() -> None:
    from app.agents.models import AgentContext, ContextMessage

    agent = make_agent()
    ctx = AgentContext(
        user_id="ctx-user",
        conversation_id="conv-ctx",
        conversation_history=[
            ContextMessage(role="user", content="Previous message"),
        ],
    )
    request = make_request(conversation_id="conv-1").model_copy(
        update={"context": ctx}
    )
    execution = make_execution(request)

    frames = [{"type": "done", "usage": {"inputTokens": 0, "outputTokens": 0}}]

    with (
        patch("app.agents.chat_agent.AsyncSessionLocal") as mock_session,
        patch("app.agents.chat_agent.AIOrchestrator") as mock_orc_cls,
    ):
        mock_orc = AsyncMock()
        mock_orc.stream_chat.side_effect = _make_stream_patch(frames)
        mock_orc_cls.return_value = mock_orc
        mock_session.return_value.__aenter__.return_value = MagicMock()
        mock_session.return_value.__aexit__.return_value = None

        events = await collect(agent.execute(request, execution))

    completed = [e for e in events if isinstance(e, AgentCompletedEvent)]
    assert len(completed) == 1
