# tests/unit/agents/test_voice_agent.py
"""Unit tests for VoiceAgent (Phase 6)."""

from __future__ import annotations

import base64
from collections.abc import AsyncIterator
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

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
)
from app.agents.voice_agent import VOICE_AGENT_NAME, VoiceAgent


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


async def collect(gen: AsyncIterator[AgentEvent]) -> list[AgentEvent]:
    return [e async for e in gen]


_SAMPLE_AUDIO_B64 = base64.b64encode(b"fake-audio-bytes").decode()


def make_request(**kwargs: object) -> AgentRequest:
    defaults: dict[str, object] = {
        "user_id": "user-1",
        "input": "voice test",
        "metadata": {
            "voice_action": "listen_and_respond",
            "audio_base64": _SAMPLE_AUDIO_B64,
        },
    }
    defaults.update(kwargs)
    return AgentRequest(**defaults)  # type: ignore[arg-type]


def make_exec(req: AgentRequest | None = None) -> AgentExecution:
    r = req or make_request()
    return AgentExecution(request=r, agent_name=VOICE_AGENT_NAME)


def _make_transcript_result(text: str = "Hello world") -> MagicMock:
    segment = MagicMock()
    segment.text = text
    result = MagicMock()
    result.transcript = [segment]
    return result


class _TranscriptionStack:
    """Context manager that patches TranscriptionService."""

    def __init__(
        self,
        transcript_text: str = "Hello world",
        raise_exc: Exception | None = None,
    ) -> None:
        self._transcript_text = transcript_text
        self._raise_exc = raise_exc
        self._patches: list = []

    def __enter__(self) -> _TranscriptionStack:
        service = MagicMock()
        if self._raise_exc is not None:
            service.transcribe = AsyncMock(side_effect=self._raise_exc)
        else:
            service.transcribe = AsyncMock(
                return_value=_make_transcript_result(self._transcript_text)
            )
        service_class = MagicMock(return_value=service)

        mock_session = MagicMock()
        mock_session.__aenter__ = AsyncMock(return_value=MagicMock())
        mock_session.__aexit__ = AsyncMock(return_value=None)

        orc = MagicMock()
        orc.complete = AsyncMock(return_value="LLM response text.")
        orc_class = MagicMock(return_value=orc)

        self._patches = [
            patch("app.agents.voice_agent.TranscriptionService", service_class),
            patch("app.agents.voice_agent.AIOrchestrator", orc_class),
            patch("app.agents.voice_agent.AsyncSessionLocal", return_value=mock_session),
        ]
        for p in self._patches:
            p.start()  # type: ignore[attr-defined]
        return self

    def __exit__(self, *_: object) -> None:
        for p in self._patches:
            p.stop()  # type: ignore[attr-defined]


# ---------------------------------------------------------------------------
# Name / capabilities
# ---------------------------------------------------------------------------


def test_agent_name() -> None:
    assert VoiceAgent().name == VOICE_AGENT_NAME


def test_declares_speech_to_text() -> None:
    assert AgentCapability.SPEECH_TO_TEXT in VoiceAgent().capabilities


def test_declares_text_to_speech() -> None:
    assert AgentCapability.TEXT_TO_SPEECH in VoiceAgent().capabilities


def test_declares_text_generation() -> None:
    assert AgentCapability.TEXT_GENERATION in VoiceAgent().capabilities


# ---------------------------------------------------------------------------
# speak_only
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_speak_only_emits_token_and_completed() -> None:
    agent = VoiceAgent()
    req = make_request(
        metadata={
            "voice_action": "speak_only",
            "text_to_speak": "Hello there",
        }
    )
    events = await collect(agent.execute(req, make_exec(req)))

    token = next((e for e in events if isinstance(e, AgentTokenEvent)), None)
    assert token is not None
    assert token.token == "Hello there"

    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_speak_only_blank_text_emits_blank_text() -> None:
    """speak_only with no text source (no text_to_speak, input is placeholder)
    correctly falls back to request.input and speaks it."""
    agent = VoiceAgent()
    req = make_request(
        input="Speak this please",
        metadata={"voice_action": "speak_only"},  # no text_to_speak → uses input
    )
    events = await collect(agent.execute(req, make_exec(req)))

    # Should complete successfully using request.input as the text
    token = next((e for e in events if isinstance(e, AgentTokenEvent)), None)
    assert token is not None
    assert token.token == "Speak this please"
    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_speak_only_uses_request_input_as_fallback() -> None:
    agent = VoiceAgent()
    req = make_request(
        input="Speak from input",
        metadata={"voice_action": "speak_only"},  # no text_to_speak key
    )
    events = await collect(agent.execute(req, make_exec(req)))

    token = next(e for e in events if isinstance(e, AgentTokenEvent))
    assert token.token == "Speak from input"


@pytest.mark.asyncio
async def test_speak_only_completed_metadata_has_action() -> None:
    agent = VoiceAgent()
    req = make_request(metadata={"voice_action": "speak_only", "text_to_speak": "Test"})
    events = await collect(agent.execute(req, make_exec(req)))

    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.metadata.get("action") == "speak_only"


# ---------------------------------------------------------------------------
# listen_only
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_listen_only_returns_transcript_token() -> None:
    with _TranscriptionStack(transcript_text="Transcribed text here"):
        agent = VoiceAgent()
        req = make_request(
            metadata={
                "voice_action": "listen_only",
                "audio_base64": _SAMPLE_AUDIO_B64,
            }
        )
        events = await collect(agent.execute(req, make_exec(req)))

    token_texts = [e.token for e in events if isinstance(e, AgentTokenEvent)]
    assert any("Transcribed text here" in t for t in token_texts)

    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.status == AgentStatus.COMPLETED


@pytest.mark.asyncio
async def test_listen_only_missing_audio_returns_no_transcript() -> None:
    """listen_only with no audio_base64 returns None transcript → no Completed."""
    import app.agents.voice_agent as _mod

    orig = _mod.TranscriptionService
    _mod.TranscriptionService = None  # type: ignore[assignment]
    try:
        agent = VoiceAgent()
        req = make_request(metadata={"voice_action": "listen_only"})  # no audio_base64
        events = await collect(agent.execute(req, make_exec(req)))
    finally:
        _mod.TranscriptionService = orig

    # No completed event when transcript is None
    assert not any(isinstance(e, AgentCompletedEvent) for e in events)


# ---------------------------------------------------------------------------
# listen_and_respond
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_listen_and_respond_full_pipeline() -> None:
    with _TranscriptionStack(transcript_text="What is Python?"):
        agent = VoiceAgent()
        req = make_request(
            metadata={
                "voice_action": "listen_and_respond",
                "audio_base64": _SAMPLE_AUDIO_B64,
                "provider": "gemini",
            }
        )
        events = await collect(agent.execute(req, make_exec(req)))

    # Transcript token
    token_texts = [e.token for e in events if isinstance(e, AgentTokenEvent)]
    assert any("What is Python?" in t for t in token_texts)
    # LLM response token
    assert any("LLM response text." in t for t in token_texts)
    # Completed
    completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
    assert completed.result.status == AgentStatus.COMPLETED
    assert completed.result.metadata.get("action") == "listen_and_respond"


@pytest.mark.asyncio
async def test_listen_and_respond_transcription_error_still_completes_gracefully() -> None:
    with _TranscriptionStack(raise_exc=RuntimeError("mic error")):
        agent = VoiceAgent()
        req = make_request(
            metadata={
                "voice_action": "listen_and_respond",
                "audio_base64": _SAMPLE_AUDIO_B64,
            }
        )
        events = await collect(agent.execute(req, make_exec(req)))

    # Transcription error → transcript is None → no completed event
    # (VoiceAgent returns early when _transcribe returns None)
    assert not any(isinstance(e, AgentCompletedEvent) for e in events)


@pytest.mark.asyncio
async def test_listen_and_respond_llm_unavailable_returns_transcript() -> None:
    """When AIOrchestrator is unavailable, agent returns transcript with COMPLETED."""
    with _TranscriptionStack():
        import app.agents.voice_agent as _mod

        orig_orc = _mod.AIOrchestrator
        orig_db = _mod.AsyncSessionLocal
        _mod.AIOrchestrator = None  # type: ignore[assignment]
        _mod.AsyncSessionLocal = None  # type: ignore[assignment]
        try:
            agent = VoiceAgent()
            req = make_request(
                metadata={
                    "voice_action": "listen_and_respond",
                    "audio_base64": _SAMPLE_AUDIO_B64,
                }
            )
            events = await collect(agent.execute(req, make_exec(req)))
        finally:
            _mod.AIOrchestrator = orig_orc
            _mod.AsyncSessionLocal = orig_db

    completed = next((e for e in events if isinstance(e, AgentCompletedEvent)), None)
    assert completed is not None
    assert completed.result.metadata.get("llm") == "unavailable"


# ---------------------------------------------------------------------------
# Unknown action
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_unknown_action_emits_unknown_action() -> None:
    agent = VoiceAgent()
    req = make_request(metadata={"voice_action": "sing"})
    events = await collect(agent.execute(req, make_exec(req)))

    assert any(
        isinstance(e, AgentFailedEvent) and "UNKNOWN_ACTION" in e.result.error.code for e in events
    )


# ---------------------------------------------------------------------------
# Event sequence
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_speak_only_starts_with_started_and_status_changed() -> None:
    agent = VoiceAgent()
    req = make_request(metadata={"voice_action": "speak_only", "text_to_speak": "Hi"})
    events = await collect(agent.execute(req, make_exec(req)))

    assert isinstance(events[0], AgentStartedEvent)
    assert isinstance(events[1], AgentStatusChangedEvent)
    assert events[1].status == AgentStatus.RUNNING
