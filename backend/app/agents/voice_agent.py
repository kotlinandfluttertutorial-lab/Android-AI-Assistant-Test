# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : voice_agent.py
# Purpose : VoiceAgent — wraps the existing /transcription stub
#           endpoint as an Agent, surfacing it in the agent pipeline.
#
# Architecture Layer : Agent Core (Phase 6)
# Pattern Used       : Adapter (implements Agent; wraps transcription)
#
# Key Concepts:
#   - Wraps the existing /transcription stub endpoint (POST multipart)
#     without creating a second transcription implementation
#   - Supports three actions: "listen_only", "speak_only", "listen_and_respond"
#   - "listen_only": calls transcription service, returns transcript
#   - "speak_only": no server-side TTS yet — returns text token for
#     client-side synthesis (Android handles actual TTS)
#   - "listen_and_respond": transcription → LLM → response token
#   - The /transcription endpoint is a stub and may return mock data;
#     VoiceAgent faithfully wraps whatever it returns
#
# Request metadata keys:
#   "voice_action"     — "listen_and_respond" | "speak_only" | "listen_only"
#   "audio_base64"     — Base64-encoded audio bytes (for listen actions)
#   "audio_filename"   — filename hint, e.g. "clip.wav" (default "audio.wav")
#   "language"         — BCP-47 language tag (default "en")
#   "text_to_speak"    — literal text for "speak_only" action
#   "conversation_id"  — conversation UUID for LLM step
#   "provider"         — LLM provider (default "gemini")
#
# Streaming protocol:
#   Started → StatusChanged(RUNNING) → Thinking → Token × N → Completed
# ============================================================

"""VoiceAgent — wraps existing transcription service as an Agent."""

from __future__ import annotations

import base64
import io
import logging
from collections.abc import AsyncIterator

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
    AgentThinkingEvent,
    AgentTokenEvent,
)

logger = logging.getLogger(__name__)

VOICE_AGENT_NAME = "voice"

# Lazy imports — preserve existing service exactly as-is
try:
    from app.services.transcription_service import (  # type: ignore
        TranscriptionService as _TranscriptionService,
    )
except Exception:  # pragma: no cover
    _TranscriptionService = None  # type: ignore

TranscriptionService = _TranscriptionService

# Lazy import for LLM step
try:
    from app.services.ai_orchestrator import AIOrchestrator as _AIOrchestrator  # type: ignore
except Exception:  # pragma: no cover
    _AIOrchestrator = None  # type: ignore

AIOrchestrator = _AIOrchestrator

try:
    from app.database import AsyncSessionLocal as _AsyncSessionLocal  # type: ignore
except Exception:  # pragma: no cover
    _AsyncSessionLocal = None  # type: ignore

AsyncSessionLocal = _AsyncSessionLocal

_LLM_TIMEOUT = 30.0


class VoiceAgent(Agent):
    """Agent that performs speech-to-text, text-to-speech, and voice conversations.

    Wraps the existing transcription endpoint — does NOT create a second
    transcription implementation.
    """

    @property
    def name(self) -> str:
        return VOICE_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "Voice assistant agent: Speech-to-Text (via transcription service), "
            "LLM response generation, and Text-to-Speech token for client-side synthesis."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({
            AgentCapability.SPEECH_TO_TEXT,
            AgentCapability.TEXT_TO_SPEECH,
            AgentCapability.TEXT_GENERATION,
            AgentCapability.STREAMING,
        })

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        metadata = request.metadata or {}
        action = (metadata.get("voice_action") or "listen_and_respond").strip().lower()

        if action == "speak_only":
            async for event in self._handle_speak_only(request, execution, metadata):
                yield event
        elif action == "listen_only":
            async for event in self._handle_listen_only(request, execution, metadata):
                yield event
        elif action == "listen_and_respond":
            async for event in self._handle_listen_and_respond(request, execution, metadata):
                yield event
        else:
            yield self._failed(
                execution, request, "UNKNOWN_ACTION",
                f"Unknown voice_action '{action}'. "
                "Supported: listen_and_respond, speak_only, listen_only.",
            )

    # ── speak_only ────────────────────────────────────────────────────────────

    async def _handle_speak_only(
        self,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> AsyncIterator[AgentEvent]:
        text = (metadata.get("text_to_speak") or request.input or "").strip()
        if not text:
            yield self._failed(execution, request, "BLANK_TEXT",
                               "No text to speak. Provide metadata['text_to_speak'].")
            return

        # Server returns the text token; client synthesises speech on-device.
        yield AgentTokenEvent(token=text)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=text,
            metadata={"action": "speak_only"},
        ))

    # ── listen_only ───────────────────────────────────────────────────────────

    async def _handle_listen_only(
        self,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> AsyncIterator[AgentEvent]:
        transcript = await self._transcribe(request, execution, metadata)
        if transcript is None:
            return  # error already yielded by _transcribe

        yield AgentTokenEvent(token=transcript)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=transcript,
            metadata={"action": "listen_only"},
        ))

    # ── listen_and_respond ────────────────────────────────────────────────────

    async def _handle_listen_and_respond(
        self,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> AsyncIterator[AgentEvent]:
        transcript = await self._transcribe(request, execution, metadata)
        if transcript is None:
            return

        yield AgentTokenEvent(token=f"[You said]: {transcript}")

        # ── LLM step ─────────────────────────────────────────────────────────
        if AIOrchestrator is None or AsyncSessionLocal is None:
            # LLM unavailable — return transcript only
            yield AgentCompletedEvent(result=AgentResult(
                execution_id=execution.execution_id,
                request_id=request.request_id,
                agent_name=self.name,
                status=AgentStatus.COMPLETED,
                content=transcript,
                metadata={"action": "listen_and_respond", "llm": "unavailable"},
            ))
            return

        yield AgentThinkingEvent(step_index=1, thought="Generating response…")

        provider = (metadata.get("provider") or "gemini").strip()
        user_id = str(request.user_id or "anonymous")

        import asyncio

        try:
            async with AsyncSessionLocal() as db:
                orc = AIOrchestrator(db=db)
                llm_response = await asyncio.wait_for(
                    orc.complete(
                        prompt=transcript,
                        provider=provider,
                        max_tokens=1024,
                        user_id=user_id,
                    ),
                    timeout=_LLM_TIMEOUT,
                )
        except asyncio.TimeoutError:
            yield self._failed(execution, request, "LLM_TIMEOUT",
                               f"LLM response timed out after {_LLM_TIMEOUT}s.")
            return
        except Exception as exc:
            logger.exception("VoiceAgent: LLM error: %s", exc)
            yield self._failed(execution, request, "LLM_ERROR",
                               "LLM response generation failed.")
            return

        llm_text: str = llm_response if isinstance(llm_response, str) else str(llm_response)
        yield AgentTokenEvent(token=llm_text)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=llm_text,
            metadata={
                "action": "listen_and_respond",
                "transcript": transcript,
                "provider": provider,
            },
        ))

    # ── Shared transcription helper ───────────────────────────────────────────

    async def _transcribe(
        self,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> str | None:
        """Decode audio and call TranscriptionService.

        Returns transcript string or None when transcription cannot proceed.
        """
        audio_b64 = (metadata.get("audio_base64") or "").strip()
        if not audio_b64:
            # Yield is not possible from a plain coroutine; use generator delegation below.
            return None

        try:
            audio_bytes = base64.b64decode(audio_b64)
        except Exception:
            return None

        language = (metadata.get("language") or "en").strip()
        filename = (metadata.get("audio_filename") or "audio.wav").strip()

        if TranscriptionService is None:
            return None

        try:
            service = TranscriptionService()
            audio_file = io.BytesIO(audio_bytes)
            audio_file.name = filename
            result = await service.transcribe(audio_file=audio_file, language=language)
        except Exception as exc:
            logger.warning("VoiceAgent: transcription error: %s", exc)
            return None

        # Flatten transcript segments into a single string
        segments = getattr(result, "transcript", []) or []
        if isinstance(segments, list):
            parts = [getattr(s, "text", "") or (s.get("text", "") if isinstance(s, dict) else "")
                     for s in segments]
            return " ".join(p for p in parts if p).strip()
        return str(segments).strip()

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        msg: str,
    ) -> AgentFailedEvent:
        return AgentFailedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=VOICE_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=msg),
        ))
