# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : dispatcher.py
# Purpose : ActionDispatcher — executes a single AgentDecision by
#           routing to the correct backend (LLM / RAG / MCP).
#
# Design rules:
#   - Depends on injected adapters; no direct construction of
#     LLMService, RAGPipeline, or MCPServer here.
#   - Returns ActionOutcome (never raises) — all errors are captured.
#   - Sensitive data (tokens, API keys) never appears in outcomes.
# ============================================================
"""ActionDispatcher — executes one AgentDecision against LLM/RAG/MCP."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

from app.agents.models import (
    AgentDecision,
    CallToolDecision,
    FinishDecision,
    RespondDecision,
    RetrieveDecision,
    WaitDecision,
)
from app.orchestration.config import OrchestrationConfig

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# ActionOutcome
# ---------------------------------------------------------------------------


@dataclass
class ActionOutcome:
    """Result of dispatching one :class:`~app.agents.models.AgentDecision`.

    Attributes:
        action_type:    The decision type that was dispatched.
        output:         Text result (LLM text, RAG answer, tool output, etc.).
        success:        False when the action raised an error.
        error_message:  Human-safe error; empty on success.
        tokens_used:    LLM tokens consumed (0 for RAG/MCP).
        is_final:       True when this action terminates the loop.
        citations:      RAG citations from a retrieve action.
        tool_record:    MCP tool call record from a call_tool action.
        duration_ms:    Wall-clock time for this action.
    """

    action_type: str
    output: str = ""
    success: bool = True
    error_message: str = ""
    tokens_used: int = 0
    is_final: bool = False
    citations: list[dict[str, Any]] = field(default_factory=list)
    tool_record: dict[str, Any] | None = None
    duration_ms: int = 0


# ---------------------------------------------------------------------------
# Adapter protocols — callers inject concrete implementations
# ---------------------------------------------------------------------------


@runtime_checkable
class LLMAdapter(Protocol):
    """Minimal async interface the dispatcher expects from an LLM client."""

    async def generate(
        self,
        prompt: str,
        *,
        system_prompt: str = "",
        user_id: str | None = None,
        max_tokens: int | None = None,
        temperature: float | None = None,
        rag_context: list[str] | None = None,
    ) -> str: ...


@runtime_checkable
class RAGAdapter(Protocol):
    """Minimal async interface the dispatcher expects from a RAG pipeline."""

    async def ask(
        self,
        user_id: str,
        question: str,
        top_k: int = 5,
        document_ids: list[str] | None = None,
    ) -> Any: ...  # returns RAGAnswer-like object with .answer and .sources


@runtime_checkable
class MCPAdapter(Protocol):
    """Minimal async interface the dispatcher expects from an MCP server."""

    async def execute(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
    ) -> Any: ...  # returns MCPToolResult-like object with .success/.result/.error


# ---------------------------------------------------------------------------
# ActionDispatcher
# ---------------------------------------------------------------------------


class ActionDispatcher:
    """Dispatches a single :class:`~app.agents.models.AgentDecision` to the
    appropriate backend adapter.

    Supported decision types:
    - ``respond``    → LLM generation
    - ``retrieve``   → RAG retrieval + generation
    - ``call_tool``  → MCP tool execution
    - ``wait``       → no-op (surface confirmation request to caller)
    - ``finish``     → marks the loop as complete

    All methods return :class:`ActionOutcome` and **never raise**.

    Usage::

        dispatcher = ActionDispatcher(
            llm=my_llm_adapter,
            rag=my_rag_pipeline,
            mcp=my_mcp_server,
            config=OrchestrationConfig(),
        )
        outcome = await dispatcher.dispatch(decision, user_id="u1")
    """

    def __init__(
        self,
        llm: LLMAdapter | None = None,
        rag: RAGAdapter | None = None,
        mcp: MCPAdapter | None = None,
        config: OrchestrationConfig | None = None,
    ) -> None:
        self._llm = llm
        self._rag = rag
        self._mcp = mcp
        self._config = config or OrchestrationConfig()

    async def dispatch(
        self,
        decision: AgentDecision,
        user_id: str,
        system_prompt: str = "",
        document_ids: list[str] | None = None,
    ) -> ActionOutcome:
        """Dispatch *decision* to the correct adapter.

        Args:
            decision:      The :class:`~app.agents.models.AgentDecision` to execute.
            user_id:       Authenticated user ID forwarded to adapters.
            system_prompt: Optional system / persona prompt for LLM calls.
            document_ids:  Optional document scope restriction for RAG calls.

        Returns:
            :class:`ActionOutcome` — never raises.
        """
        start_ms = time.monotonic() * 1000

        try:
            if isinstance(decision, RespondDecision):
                outcome = await self._dispatch_respond(decision, user_id, system_prompt)
            elif isinstance(decision, RetrieveDecision):
                outcome = await self._dispatch_retrieve(decision, user_id, document_ids)
            elif isinstance(decision, CallToolDecision):
                outcome = await self._dispatch_tool(decision, user_id)
            elif isinstance(decision, WaitDecision):
                outcome = self._dispatch_wait(decision)
            elif isinstance(decision, FinishDecision):
                outcome = self._dispatch_finish(decision)
            else:
                outcome = ActionOutcome(
                    action_type=getattr(decision, "type", "unknown"),
                    success=False,
                    error_message=f"Unknown decision type: {type(decision).__name__}",
                )
        except Exception as exc:
            # Belt-and-suspenders: each _dispatch_* also catches exceptions,
            # but this top-level guard ensures no propagation.
            action_type = getattr(decision, "type", "unknown")
            logger.error(
                "ActionDispatcher: unexpected error action=%r user=%r: %s",
                action_type, user_id, exc,
            )
            outcome = ActionOutcome(
                action_type=action_type,
                success=False,
                error_message="An unexpected error occurred during action execution.",
            )

        outcome.duration_ms = int(time.monotonic() * 1000 - start_ms)
        return outcome

    # ── Per-type handlers ──────────────────────────────────────────────────

    async def _dispatch_respond(
        self,
        decision: RespondDecision,
        user_id: str,
        system_prompt: str,
    ) -> ActionOutcome:
        """Forward a respond decision to the LLM adapter."""
        if self._llm is None:
            return ActionOutcome(
                action_type="respond",
                success=False,
                error_message="LLM adapter not configured.",
            )

        try:
            text = await self._llm.generate(
                decision.content,
                system_prompt=system_prompt,
                user_id=user_id,
                max_tokens=self._config.max_tokens_per_step,
                temperature=self._config.llm_temperature,
            )
            return ActionOutcome(
                action_type="respond",
                output=text,
                success=True,
                is_final=decision.is_final,
            )
        except Exception as exc:
            logger.warning("ActionDispatcher: LLM generate failed: %s", exc)
            return ActionOutcome(
                action_type="respond",
                success=False,
                error_message="LLM generation failed. Please try again.",
            )

    async def _dispatch_retrieve(
        self,
        decision: RetrieveDecision,
        user_id: str,
        document_ids: list[str] | None,
    ) -> ActionOutcome:
        """Forward a retrieve decision to the RAG adapter."""
        if not self._config.enable_rag:
            return ActionOutcome(
                action_type="retrieve",
                success=False,
                error_message="RAG retrieval is disabled in this configuration.",
            )

        if self._rag is None:
            return ActionOutcome(
                action_type="retrieve",
                success=False,
                error_message="RAG adapter not configured.",
            )

        try:
            scoped_ids = list(decision.document_ids) or document_ids or None
            rag_result = await self._rag.ask(
                user_id=user_id,
                question=decision.query,
                top_k=decision.top_k or self._config.rag_top_k,
                document_ids=scoped_ids,
            )
            answer = getattr(rag_result, "answer", "") or ""
            sources = getattr(rag_result, "sources", []) or []
            citations = [
                {k: v for k, v in (s.items() if isinstance(s, dict) else vars(s).items())}
                for s in sources
            ]
            return ActionOutcome(
                action_type="retrieve",
                output=answer,
                success=True,
                citations=citations,
            )
        except Exception as exc:
            logger.warning("ActionDispatcher: RAG retrieve failed: %s", exc)
            return ActionOutcome(
                action_type="retrieve",
                success=False,
                error_message="Document retrieval failed. Please try again.",
            )

    async def _dispatch_tool(
        self,
        decision: CallToolDecision,
        user_id: str,
    ) -> ActionOutcome:
        """Forward a call_tool decision to the MCP adapter."""
        if not self._config.enable_mcp:
            return ActionOutcome(
                action_type="call_tool",
                success=False,
                error_message="MCP tool execution is disabled in this configuration.",
            )

        if self._mcp is None:
            return ActionOutcome(
                action_type="call_tool",
                success=False,
                error_message="MCP adapter not configured.",
            )

        # Parse JSON parameters safely
        try:
            raw_params = json.loads(decision.parameters) if decision.parameters else {}
            if not isinstance(raw_params, dict):
                raw_params = {"value": raw_params}
        except json.JSONDecodeError:
            raw_params = {"raw": decision.parameters}

        tool_record: dict = {
            "tool_name": decision.tool_name,
            "input": decision.parameters,
            "output": None,
            "failed": False,
            "error_message": None,
        }

        try:
            mcp_result = await self._mcp.execute(
                tool_name=decision.tool_name,
                params=raw_params,
                user_id=user_id,
            )
            success = getattr(mcp_result, "success", False)
            result_data = getattr(mcp_result, "result", None)
            error_msg = getattr(mcp_result, "error", None) or ""
            output = json.dumps(result_data) if result_data is not None else ""

            tool_record["output"] = output
            tool_record["failed"] = not success
            if not success:
                tool_record["error_message"] = error_msg

            return ActionOutcome(
                action_type="call_tool",
                output=output,
                success=success,
                error_message=error_msg if not success else "",
                tool_record=tool_record,
            )
        except Exception as exc:
            logger.warning(
                "ActionDispatcher: MCP tool=%r failed: %s", decision.tool_name, exc
            )
            tool_record["failed"] = True
            tool_record["error_message"] = "Tool execution failed."
            return ActionOutcome(
                action_type="call_tool",
                success=False,
                error_message="Tool execution failed. Please try again.",
                tool_record=tool_record,
            )

    @staticmethod
    def _dispatch_wait(decision: WaitDecision) -> ActionOutcome:
        """Return a non-final outcome that surfaces the wait reason."""
        return ActionOutcome(
            action_type="wait",
            output=decision.reason,
            success=True,
            is_final=False,
        )

    @staticmethod
    def _dispatch_finish(decision: FinishDecision) -> ActionOutcome:
        """Mark the loop as finished."""
        return ActionOutcome(
            action_type="finish",
            output=decision.reason,
            success=True,
            is_final=True,
        )
