# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : state.py
# Purpose : OrchestrationState (mutable, live tracking) and
#           OrchestrationResult / ExecutionSpan (immutable outcome).
#
# Design rules:
#   - OrchestrationState is the ONLY mutable object during a run.
#     Everything else is immutable.
#   - ExecutionSpan records one observe cycle.
#   - OrchestrationResult is the final snapshot forwarded to callers.
#   - No infrastructure imports — pure Python dataclasses.
# ============================================================
"""State tracking and final result types for the orchestration layer."""

from __future__ import annotations

import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

# ---------------------------------------------------------------------------
# RunStatus
# ---------------------------------------------------------------------------


class RunStatus(str, enum.Enum):
    """Lifecycle state of one :class:`OrchestrationState` run."""

    PENDING = "pending"       # created, not yet started
    RUNNING = "running"       # currently executing the loop
    COMPLETED = "completed"   # finished successfully
    FAILED = "failed"         # terminated with error
    TIMED_OUT = "timed_out"   # wall-clock timeout exceeded
    CANCELLED = "cancelled"   # cancelled by caller

    @property
    def is_terminal(self) -> bool:
        return self in (
            RunStatus.COMPLETED,
            RunStatus.FAILED,
            RunStatus.TIMED_OUT,
            RunStatus.CANCELLED,
        )


# ---------------------------------------------------------------------------
# ExecutionSpan — one observe record
# ---------------------------------------------------------------------------


@dataclass
class ExecutionSpan:
    """Immutable record of a single decide→act→observe cycle.

    Attributes:
        step_index:     Zero-based position within the run.
        action_type:    Decision type: ``"respond"``, ``"retrieve"``,
                        ``"call_tool"``, ``"wait"``, ``"finish"``.
        input_summary:  Short description of the action input (safe to log).
        output_summary: Short description of the result (safe to log).
        duration_ms:    Wall-clock time for this step in milliseconds.
        tokens_used:    LLM tokens consumed by this step (0 for MCP/RAG).
        success:        False when the action raised an error.
        error_message:  Human-safe error string; empty on success.
        metadata:       Arbitrary diagnostic key-value pairs.
    """

    step_index: int
    action_type: str
    input_summary: str = ""
    output_summary: str = ""
    duration_ms: int = 0
    tokens_used: int = 0
    success: bool = True
    error_message: str = ""
    metadata: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# OrchestrationState — mutable live state
# ---------------------------------------------------------------------------


class OrchestrationState:
    """Mutable runtime state for one orchestration run.

    A single instance is created at the start of
    :meth:`~app.orchestration.loop.AgentExecutionLoop.run` and threaded
    through the decision loop.  External code (e.g. observers) reads it
    to check progress without holding a lock — the loop is single-threaded
    per run.

    Attributes:
        run_id:          Unique identifier for this run.
        request_id:      Propagated from AgentRequest.request_id.
        user_id:         Propagated from AgentRequest.user_id.
        agent_name:      Name of the agent driving the loop.
        status:          Current :class:`RunStatus`.
        step_count:      Number of completed decide→act→observe cycles.
        tool_call_count: Total MCP tool calls made so far.
        spans:           Ordered list of completed :class:`ExecutionSpan` objects.
        accumulated_output: Full text accumulated from all ``respond`` decisions.
        citations:       RAG citations collected from ``retrieve`` decisions.
        tool_calls:      MCP tool call records from ``call_tool`` decisions.
        error_message:   Set when status is FAILED or TIMED_OUT.
        start_time_s:    ``time.monotonic()`` at run start.
    """

    def __init__(
        self,
        request_id: str,
        user_id: str,
        agent_name: str,
    ) -> None:
        self.run_id: str = str(uuid.uuid4())
        self.request_id: str = request_id
        self.user_id: str = user_id
        self.agent_name: str = agent_name
        self.status: RunStatus = RunStatus.PENDING
        self.step_count: int = 0
        self.tool_call_count: int = 0
        self.spans: list[ExecutionSpan] = []
        self.accumulated_output: str = ""
        self.citations: list[dict[str, Any]] = []
        self.tool_calls: list[dict[str, Any]] = []
        self.error_message: str = ""
        self.start_time_s: float = 0.0

    # ── Transitions ──────────────────────────────────────────────────────────

    def start(self) -> None:
        self.status = RunStatus.RUNNING
        self.start_time_s = time.monotonic()

    def finish(self) -> None:
        self.status = RunStatus.COMPLETED

    def fail(self, message: str) -> None:
        self.status = RunStatus.FAILED
        self.error_message = message

    def time_out(self) -> None:
        self.status = RunStatus.TIMED_OUT
        self.error_message = f"Execution timed out after step {self.step_count}."

    def cancel(self) -> None:
        if not self.status.is_terminal:
            self.status = RunStatus.CANCELLED

    # ── Span recording ────────────────────────────────────────────────────────

    def record_span(self, span: ExecutionSpan) -> None:
        self.spans.append(span)
        self.step_count += 1
        if span.action_type == "call_tool":
            self.tool_call_count += 1

    def append_output(self, text: str) -> None:
        self.accumulated_output = (
            self.accumulated_output + text
            if not self.accumulated_output
            else self.accumulated_output + "\n" + text
        )

    # ── Computed ──────────────────────────────────────────────────────────────

    @property
    def elapsed_s(self) -> float:
        if self.start_time_s == 0.0:
            return 0.0
        return time.monotonic() - self.start_time_s

    @property
    def total_tokens(self) -> int:
        return sum(s.tokens_used for s in self.spans)


# ---------------------------------------------------------------------------
# OrchestrationResult — immutable final snapshot
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class OrchestrationResult:
    """Immutable outcome of a completed orchestration run.

    Created by :meth:`~app.orchestration.loop.AgentExecutionLoop.run`
    from the final :class:`OrchestrationState`.

    Attributes:
        run_id:          Unique identifier for this run.
        request_id:      Propagated from the originating AgentRequest.
        agent_name:      Agent that drove the loop.
        status:          Terminal :class:`RunStatus`.
        output:          Final accumulated text answer.
        citations:       RAG source citations included in the answer.
        tool_calls:      MCP tool call records.
        spans:           All :class:`ExecutionSpan` objects from the run.
        total_tokens:    Aggregate LLM tokens consumed.
        step_count:      Total decide→act→observe cycles completed.
        elapsed_ms:      Wall-clock run duration in milliseconds.
        error_message:   Human-safe error text; empty on success.
    """

    run_id: str
    request_id: str
    agent_name: str
    status: RunStatus
    output: str
    citations: list[dict[str, Any]] = field(default_factory=list)
    tool_calls: list[dict[str, Any]] = field(default_factory=list)
    spans: list[ExecutionSpan] = field(default_factory=list)
    total_tokens: int = 0
    step_count: int = 0
    elapsed_ms: int = 0
    error_message: str = ""

    @property
    def success(self) -> bool:
        return self.status == RunStatus.COMPLETED

    @classmethod
    def from_state(cls, state: OrchestrationState, elapsed_ms: int) -> OrchestrationResult:
        """Build a result snapshot from a finished :class:`OrchestrationState`."""
        return cls(
            run_id=state.run_id,
            request_id=state.request_id,
            agent_name=state.agent_name,
            status=state.status,
            output=state.accumulated_output,
            citations=list(state.citations),
            tool_calls=list(state.tool_calls),
            spans=list(state.spans),
            total_tokens=state.total_tokens,
            step_count=state.step_count,
            elapsed_ms=elapsed_ms,
            error_message=state.error_message,
        )
