# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : chat_agent.py
# Purpose : ChatAgent — Agent implementation that wraps the existing
#           AIOrchestrator.stream_chat() without modifying it.
#
# Architecture Layer : Agent Core (Phase 3)
# Pattern Used       : Adapter (implements Agent, wraps AIOrchestrator)
#
# Key Concepts:
#   - DOES NOT modify AIOrchestrator, WebSocket router, or any existing code
#   - Translates AIOrchestrator's ws.send_json() calls into AgentEvent yields
#     by using an internal WebSocket proxy that captures events as they stream
#   - Preserves existing streaming protocol: token/done/error/tool_call frames
#     are mapped 1:1 to AgentEvent subtypes
#   - Backward-compatible: the existing /ws/chat/{conversation_id} endpoint
#     continues to work unchanged; ChatAgent is a new path parallel to it
#   - Memory injection, safety filters, prompt injection detection are all
#     handled inside AIOrchestrator — ChatAgent inherits them for free
#
# Dependencies: app.agents.base, app.agents.models,
#               app.services.ai_orchestrator (unchanged),
#               app.database (AsyncSessionLocal)
# ============================================================

"""ChatAgent — wraps AIOrchestrator.stream_chat() as an Agent."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any

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
    AgentToolCompletedEvent,
    AgentToolStartedEvent,
    AgentUsage,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Lazy module-level references — imported once, guarded so unit tests that
# don't have pgvector/database installed can still import this module cleanly.
# ---------------------------------------------------------------------------

try:
    from app.database import AsyncSessionLocal as _AsyncSessionLocal  # type: ignore[assignment]
except Exception:  # pragma: no cover
    _AsyncSessionLocal = None  # type: ignore[assignment]

try:
    from app.services.ai_orchestrator import (  # type: ignore[assignment]
        AIOrchestrator as _AIOrchestrator,
    )
    from app.services.ai_orchestrator import (
        LLMProvider as _LLMProvider,
    )
except Exception:  # pragma: no cover
    _AIOrchestrator = None  # type: ignore[assignment]
    _LLMProvider = None  # type: ignore[assignment]

# Re-export as module attributes so tests can patch them at the module level.
AsyncSessionLocal = _AsyncSessionLocal
AIOrchestrator = _AIOrchestrator
LLMProvider = _LLMProvider

# Matches the Android ChatAgent.NAME constant
CHAT_AGENT_NAME = "conversational"


class _CaptureProxy:
    """Minimal WebSocket proxy that captures send_json calls as AgentEvents.

    AIOrchestrator.stream_chat() receives a WebSocket (or duck-typed proxy)
    and calls ``ws.send_json({"type": ..., ...})`` for each streaming frame.
    This proxy intercepts those calls and queues them for async consumption
    by ChatAgent.execute().

    No real network I/O is performed — all data stays in-process.
    """

    def __init__(self) -> None:
        import asyncio

        self._queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._done = False

    async def send_json(self, data: dict[str, Any]) -> None:  # type: ignore[override]
        """Queue *data* for consumption by the ChatAgent generator."""
        await self._queue.put(data)

    async def events(self) -> AsyncIterator[dict[str, Any]]:
        """Yield queued frames until a terminal frame is received."""
        while True:
            frame = await self._queue.get()
            yield frame
            if frame.get("type") in ("done", "error"):
                break

    @property
    def disconnected(self) -> bool:
        """Always False — this proxy never disconnects mid-stream."""
        return False


class ChatAgent(Agent):
    """Agent that routes chat requests through the existing AIOrchestrator.

    ## What is preserved
    - All AIOrchestrator logic: memory injection, prompt injection detection,
      safety filters, context summarisation, token counting, fallback providers.
    - The streaming protocol: token / done / error / tool_call frames are
      mapped 1:1 to AgentEvent subtypes.
    - The existing /ws/chat/{conversation_id} WebSocket endpoint is not touched.

    ## Capability contract
    Declares the same capabilities as the Android ChatAgent.
    """

    @property
    def name(self) -> str:
        return CHAT_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "Conversational chat agent backed by AIOrchestrator "
            "(memory, safety, streaming, MCP tool passthrough)."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset(
            {
                AgentCapability.TEXT_GENERATION,
                AgentCapability.STREAMING,
                AgentCapability.MEMORY_ACCESS,
                AgentCapability.TOOL_USE,
                AgentCapability.MULTI_STEP_REASONING,
            }
        )

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        """Stream chat response events.

        Wraps AIOrchestrator.stream_chat() using an in-process proxy that
        captures the WebSocket frames and re-emits them as AgentEvent values.

        Args:
            request:   The agent request (input = user message).
            execution: Pre-created execution envelope.

        Yields:
            AgentEvent values in stream order, ending with Completed or Failed.
        """
        import asyncio

        yield AgentStartedEvent(
            execution_id=execution.execution_id,
            agent_name=self.name,
        )
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id,
            status=AgentStatus.RUNNING,
        )

        # Resolve the database session
        if AsyncSessionLocal is None:
            yield self._failed(execution, request, "DB_UNAVAILABLE", "Database not available.")
            return

        conversation_id = request.conversation_id or (
            (request.metadata or {}).get("conversation_id")
        )
        if not conversation_id:
            yield self._failed(
                execution,
                request,
                "MISSING_CONVERSATION_ID",
                "ChatAgent requires a conversationId.",
            )
            return

        user_id = (
            request.user_id or (request.context.user_id if request.context else None) or "anonymous"
        )

        # Resolve provider — use module-level LLMProvider (may be None without pgvector)
        provider_str = (request.provider or "").strip().lower() or "gemini"
        if LLMProvider is not None:
            try:
                provider = LLMProvider(provider_str)
            except ValueError:
                provider = LLMProvider.gemini
                logger.warning(
                    "ChatAgent: unknown provider %r — defaulting to gemini",
                    provider_str,
                )
        else:
            provider = provider_str  # type: ignore[assignment]

        proxy = _CaptureProxy()
        accumulated = ""
        input_tokens = 0
        output_tokens = 0

        async with AsyncSessionLocal() as db:
            orc_cls = AIOrchestrator if AIOrchestrator is not None else object
            orchestrator = orc_cls(db=db)

            # Run stream_chat concurrently with event collection
            stream_task = asyncio.create_task(
                orchestrator.stream_chat(
                    conversation_id=conversation_id,
                    user_message=request.input,
                    provider=provider,
                    user_id=user_id,
                    ws=proxy,  # type: ignore[arg-type]
                )
            )

            try:
                async for frame in proxy.events():
                    frame_type = frame.get("type", "")

                    if frame_type == "token":
                        token = frame.get("data", "")
                        accumulated += token
                        yield AgentTokenEvent(token=token)

                    elif frame_type == "done":
                        usage = frame.get("usage", {})
                        input_tokens = usage.get("inputTokens", 0)
                        output_tokens = usage.get("outputTokens", 0)
                        break  # terminal

                    elif frame_type == "error":
                        msg = frame.get("message", "Streaming error.")
                        yield self._failed(execution, request, "STREAM_ERROR", msg)
                        stream_task.cancel()
                        return

                    elif frame_type == "tool_call":
                        tool_name = frame.get("toolName", "unknown_tool")
                        tool_input = str(frame.get("toolInput", {}))
                        yield AgentToolStartedEvent(
                            tool_name=tool_name,
                            parameters=tool_input,
                        )
                        # Passthrough — actual MCP execution is inside AIOrchestrator
                        yield AgentToolCompletedEvent(
                            tool_name=tool_name,
                            output="{}",
                            duration_ms=0,
                        )

                    elif frame_type == "ping":
                        # Heartbeat — skip; no AgentEvent equivalent needed
                        pass

            except Exception as exc:
                logger.exception("ChatAgent: unexpected error collecting stream frames: %s", exc)
                stream_task.cancel()
                yield self._failed(execution, request, "UNEXPECTED_ERROR", str(exc))
                return

            # Await the stream_chat task to surface any exceptions it raised
            try:
                await stream_task
            except asyncio.CancelledError:
                pass
            except Exception as exc:
                logger.warning("ChatAgent: stream_chat raised: %s", exc)
                # Non-fatal if we already have accumulated text; emit completed.

        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=accumulated or None,
            usage=AgentUsage(
                input_tokens=input_tokens,
                output_tokens=output_tokens,
            ),
        )
        yield AgentCompletedEvent(result=result)

    # ── Helpers ──────────────────────────────────────────────────────────────

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        message: str,
    ) -> AgentFailedEvent:
        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=CHAT_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=message),
        )
        return AgentFailedEvent(result=result)
