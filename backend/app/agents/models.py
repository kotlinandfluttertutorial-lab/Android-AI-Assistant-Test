# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : models.py
# Purpose : Strongly-typed Pydantic v2 models for the Agent Core.
#
# Architecture Layer : Agent Core (Phase 1) — data models
# Pattern Used       : Pydantic v2 BaseModel (immutable value objects)
#
# Key Concepts:
#   - All models are immutable (model_config frozen=True)
#   - Validated at construction; invalid values raise ValidationError
#   - JSON-serialisable via model.model_dump(mode="json")
#   - No ORM / database dependencies — pure domain types
#   - AgentStatus transitions are enforced by AgentExecution helpers
#
# Design Decision:
#   Pydantic v2 frozen models are used (not dataclasses) to stay consistent
#   with the existing schema patterns in app/schemas/.  This also gives
#   automatic JSON serialisation/deserialisation and field validation for
#   free, matching the project's established conventions.
#
# Dependencies: pydantic>=2.0, python>=3.11
# ============================================================

"""Agent Core — Pydantic v2 domain models.

These models mirror the Kotlin domain types defined in:
  domain/src/main/kotlin/com/aiassistant/domain/agent/

They are used by:
  - app/agents/base.py        (Agent interface return types)
  - app/api/agents/ (future)  (request/response schemas)
  - app/workers/agent_worker  (future)  (Celery task payloads)

Requirements: Agent Architecture Phase 1
"""

from __future__ import annotations

import enum
import uuid
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

# ---------------------------------------------------------------------------
# AgentStatus
# ---------------------------------------------------------------------------


class AgentStatus(enum.StrEnum):
    """Lifecycle state of a single agent execution.

    State machine (valid forward transitions):

        REQUESTED → STARTED → RUNNING ↔ WAITING
                                  ↓         ↓
                              COMPLETED  FAILED
                              PARTIAL
        CANCELLED reachable from REQUESTED / STARTED / RUNNING / WAITING
    """

    REQUESTED = "REQUESTED"
    STARTED = "STARTED"
    RUNNING = "RUNNING"
    WAITING = "WAITING"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"

    # ── Helpers ──────────────────────────────────────────────────────────────

    @property
    def is_terminal(self) -> bool:
        """True when no further transitions are possible."""
        return self in (
            AgentStatus.COMPLETED,
            AgentStatus.PARTIAL,
            AgentStatus.FAILED,
            AgentStatus.CANCELLED,
        )

    @property
    def is_success(self) -> bool:
        """True when the execution produced usable output."""
        return self in (AgentStatus.COMPLETED, AgentStatus.PARTIAL)

    def can_transition_to(self, next_status: AgentStatus) -> bool:
        """Return True if transitioning from this status to *next_status* is valid."""
        return next_status.value in _STATUS_TRANSITIONS.get(self.value, set())


# Module-level transition table (cannot live inside the enum body — Python
# treats class-level assignments in enums as additional members, not attributes).
_STATUS_TRANSITIONS: dict[str, set[str]] = {
    "REQUESTED": {"STARTED", "CANCELLED"},
    "STARTED":   {"RUNNING", "WAITING", "FAILED", "CANCELLED"},
    "RUNNING":   {"WAITING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED"},
    "WAITING":   {"RUNNING", "FAILED", "CANCELLED"},
    "COMPLETED": set(),
    "PARTIAL":   set(),
    "FAILED":    set(),
    "CANCELLED": set(),
}


# ---------------------------------------------------------------------------
# AgentCapability
# ---------------------------------------------------------------------------


class AgentCapability(enum.StrEnum):
    """Distinct capability an Agent may declare support for."""

    TEXT_GENERATION = "TEXT_GENERATION"
    STREAMING = "STREAMING"
    DOCUMENT_RETRIEVAL = "DOCUMENT_RETRIEVAL"
    CODE_ANALYSIS = "CODE_ANALYSIS"
    SPEECH_TO_TEXT = "SPEECH_TO_TEXT"
    TEXT_TO_SPEECH = "TEXT_TO_SPEECH"
    IMAGE_UNDERSTANDING = "IMAGE_UNDERSTANDING"
    TOOL_USE = "TOOL_USE"
    MEMORY_ACCESS = "MEMORY_ACCESS"
    ON_DEVICE_INFERENCE = "ON_DEVICE_INFERENCE"
    SEMANTIC_SEARCH = "SEMANTIC_SEARCH"
    MULTI_STEP_REASONING = "MULTI_STEP_REASONING"
    TRANSLATION = "TRANSLATION"
    DOCUMENT_GENERATION = "DOCUMENT_GENERATION"
    PRODUCTIVITY_MANAGEMENT = "PRODUCTIVITY_MANAGEMENT"


# ---------------------------------------------------------------------------
# AgentContext supporting types
# ---------------------------------------------------------------------------


class ContextMessage(BaseModel):
    """A single turn in the conversation history."""

    model_config = ConfigDict(frozen=True)

    role: str = Field(description="Message role: user | assistant | system | tool")
    content: str = Field(description="Text content of the message.")


class ContextMemory(BaseModel):
    """A long-term user memory entry retrieved for the current query."""

    model_config = ConfigDict(frozen=True)

    content: str = Field(description="Text of the memory.")
    relevance_score: float = Field(
        ge=0.0,
        le=1.0,
        description="Cosine similarity score against the current query.",
    )


# ---------------------------------------------------------------------------
# AgentContext
# ---------------------------------------------------------------------------


class AgentContext(BaseModel):
    """All ambient information available to an Agent during execution.

    Assembled by the orchestration layer before calling Agent.execute().
    Every field is optional so partial contexts can be built incrementally.
    """

    model_config = ConfigDict(frozen=True)

    user_id: str = Field(description="ID of the user on whose behalf the agent runs.")
    conversation_id: str | None = Field(
        default=None,
        description="Conversation this execution is attached to, if any.",
    )
    conversation_history: list[ContextMessage] = Field(
        default_factory=list,
        description="Recent messages (newest last).  Empty for standalone tasks.",
    )
    memories: list[ContextMemory] = Field(
        default_factory=list,
        description="Semantically relevant memories retrieved for this query.",
    )
    persona_system_prompt: str | None = Field(
        default=None,
        description="System prompt from the active Persona, if any.",
    )
    rag_document_ids: list[str] = Field(
        default_factory=list,
        description="Document IDs to restrict RAG retrieval to.  Empty = all docs.",
    )
    available_tools: list[str] = Field(
        default_factory=list,
        description="MCP tool names the agent may invoke.",
    )
    is_privacy_mode: bool = Field(
        default=False,
        description="When True the agent must not persist new memories.",
    )
    is_offline: bool = Field(
        default=False,
        description="When True the agent must not make outbound network calls.",
    )
    extra_context: dict[str, str] = Field(
        default_factory=dict,
        description="Arbitrary caller-specific context key-value pairs.",
    )

    @field_validator("user_id")
    @classmethod
    def user_id_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentContext.user_id must not be blank.")
        return v

    def with_memories(self, additional: list[ContextMemory]) -> AgentContext:
        """Return a copy with *additional* memories appended."""
        return self.model_copy(update={"memories": list(self.memories) + additional})

    def with_tools(self, tools: list[str]) -> AgentContext:
        """Return a copy with *tools* added (deduplicated)."""
        merged = list(dict.fromkeys(list(self.available_tools) + tools))
        return self.model_copy(update={"available_tools": merged})


# ---------------------------------------------------------------------------
# AgentRequest
# ---------------------------------------------------------------------------

_DEFAULT_MAX_STEPS: int = 10
_DEFAULT_TIMEOUT_MS: int = 60_000
_MAX_STEPS_LIMIT: int = 50


class AgentRequest(BaseModel):
    """Input contract for submitting work to an Agent.

    Validated at construction; invalid requests raise ValidationError
    before reaching any agent implementation.
    """

    model_config = ConfigDict(frozen=True, str_strip_whitespace=True)

    request_id: str = Field(
        default_factory=lambda: str(uuid.uuid4()),
        description="Unique identifier for this request.",
    )
    user_id: str = Field(description="ID of the user submitting the request.")
    input: str = Field(description="Natural-language instruction or query.")
    conversation_id: str | None = Field(
        default=None,
        description="Conversation to attach this execution to.",
    )
    provider: str | None = Field(
        default=None,
        description="LLM provider hint (e.g. 'gemini').  None = agent default.",
    )
    capabilities: list[AgentCapability] = Field(
        default_factory=list,
        description="Required capabilities.  Empty = no constraint.",
    )
    context: AgentContext | None = Field(
        default=None,
        description="Pre-assembled context injected by the orchestration layer.",
    )
    max_steps: int = Field(
        default=_DEFAULT_MAX_STEPS,
        ge=1,
        le=_MAX_STEPS_LIMIT,
        description=f"Maximum reasoning steps. Range 1–{_MAX_STEPS_LIMIT}.",
    )
    timeout_ms: int = Field(
        default=_DEFAULT_TIMEOUT_MS,
        gt=0,
        description="Wall-clock timeout in milliseconds for the entire execution.",
    )
    streaming_enabled: bool = Field(
        default=True,
        description="Whether the caller expects token-by-token streaming events.",
    )
    metadata: dict[str, str] = Field(
        default_factory=dict,
        description="Arbitrary key-value pairs for caller-specific context.",
    )

    @field_validator("user_id")
    @classmethod
    def user_id_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentRequest.user_id must not be blank.")
        return v

    @field_validator("input")
    @classmethod
    def input_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentRequest.input must not be blank.")
        return v


# ---------------------------------------------------------------------------
# AgentResult supporting types
# ---------------------------------------------------------------------------


class AgentToolCall(BaseModel):
    """Records a single MCP tool invocation made during agent execution."""

    model_config = ConfigDict(frozen=True)

    tool_name: str
    input: str = Field(description="JSON-serialised input parameters.")
    output: str | None = Field(default=None, description="JSON-serialised result.")
    failed: bool = False
    error_message: str | None = None
    duration_ms: int = 0


class AgentCitation(BaseModel):
    """A RAG source citation included in the agent's response."""

    model_config = ConfigDict(frozen=True)

    document_id: str
    document_name: str
    excerpt: str
    page_number: int | None = None
    score: float = Field(default=0.0, ge=0.0, le=1.0)


class AgentAttachment(BaseModel):
    """A binary or structured attachment produced by the agent."""

    model_config = ConfigDict(frozen=True)

    name: str
    mime_type: str
    data: str | None = Field(default=None, description="Base64-encoded content for small payloads.")
    uri: str | None = Field(default=None, description="URI for large payloads stored externally.")


class AgentUsage(BaseModel):
    """Token consumption breakdown for a single agent execution."""

    model_config = ConfigDict(frozen=True)

    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)
    estimated_cost_usd: float | None = None

    @model_validator(mode="before")
    @classmethod
    def set_total_tokens(cls, values: dict[str, Any]) -> dict[str, Any]:
        if "total_tokens" not in values:
            values["total_tokens"] = (
                values.get("input_tokens", 0) + values.get("output_tokens", 0)
            )
        return values


class AgentError(BaseModel):
    """Structured error detail when AgentResult.status is FAILED."""

    model_config = ConfigDict(frozen=True)

    code: str = Field(description="Machine-readable error code.")
    message: str = Field(description="Human-readable description safe to display.")
    details: str | None = Field(default=None, description="Diagnostic details (not shown to user).")


class AgentNextAction(BaseModel):
    """Suggested follow-up action the agent recommends surfacing to the user."""

    model_config = ConfigDict(frozen=True)

    type: str = Field(description="Action category (e.g. 'RETRY', 'CONFIRM_TOOL_CALL').")
    label: str = Field(description="Short button/chip label.")
    payload: str | None = None


# ---------------------------------------------------------------------------
# AgentResult
# ---------------------------------------------------------------------------


class AgentResult(BaseModel):
    """The complete output of a single AgentExecution.

    Only created with a terminal AgentStatus.
    """

    model_config = ConfigDict(frozen=True)

    execution_id: str
    request_id: str
    agent_name: str
    status: AgentStatus
    content: str | None = None
    tool_calls: list[AgentToolCall] = Field(default_factory=list)
    citations: list[AgentCitation] = Field(default_factory=list)
    attachments: list[AgentAttachment] = Field(default_factory=list)
    usage: AgentUsage | None = None
    error: AgentError | None = None
    next_action: AgentNextAction | None = None
    metadata: dict[str, str] = Field(default_factory=dict)
    completed_at: int = Field(
        default_factory=lambda: int(__import__("time").time() * 1000),
        description="Epoch-ms when this result was finalised.",
    )

    @field_validator("execution_id")
    @classmethod
    def execution_id_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentResult.execution_id must not be blank.")
        return v

    @field_validator("request_id")
    @classmethod
    def request_id_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentResult.request_id must not be blank.")
        return v

    @field_validator("agent_name")
    @classmethod
    def agent_name_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentResult.agent_name must not be blank.")
        return v

    @field_validator("status")
    @classmethod
    def status_must_be_terminal(cls, v: AgentStatus) -> AgentStatus:
        if not v.is_terminal:
            raise ValueError(
                f"AgentResult may only be created with a terminal status, got {v}."
            )
        return v

    # ── Computed helpers ──────────────────────────────────────────────────────

    @property
    def has_content(self) -> bool:
        return bool(self.content and self.content.strip())

    @property
    def has_citations(self) -> bool:
        return len(self.citations) > 0

    @property
    def has_tool_errors(self) -> bool:
        return any(tc.failed for tc in self.tool_calls)


# ---------------------------------------------------------------------------
# AgentDecision
# ---------------------------------------------------------------------------


class AgentDecision(BaseModel):
    """A single action the agent has decided to take on a reasoning step.

    Discriminated by the *type* field.  The execution loop dispatches on it.
    """

    model_config = ConfigDict(frozen=True)

    type: str = Field(description="Decision type discriminator.")

    @classmethod
    def respond(
        cls,
        content: str,
        *,
        is_final: bool = True,
        streaming: bool = False,
    ) -> RespondDecision:
        return RespondDecision(content=content, is_final=is_final, streaming=streaming)

    @classmethod
    def call_tool(
        cls,
        tool_name: str,
        parameters: str,
        *,
        requires_confirmation: bool = False,
        rationale: str | None = None,
    ) -> CallToolDecision:
        return CallToolDecision(
            tool_name=tool_name,
            parameters=parameters,
            requires_confirmation=requires_confirmation,
            rationale=rationale,
        )

    @classmethod
    def retrieve(
        cls,
        query: str,
        *,
        document_ids: list[str] | None = None,
        top_k: int = 5,
        min_score: float = 0.4,
    ) -> RetrieveDecision:
        return RetrieveDecision(
            query=query,
            document_ids=document_ids or [],
            top_k=top_k,
            min_score=min_score,
        )

    @classmethod
    def wait(
        cls,
        reason: str,
        *,
        wait_for_type: str = "user_confirmation",
        payload: str | None = None,
    ) -> WaitDecision:
        return WaitDecision(reason=reason, wait_for_type=wait_for_type, payload=payload)

    @classmethod
    def finish(cls, reason: str = "Task completed.") -> FinishDecision:
        return FinishDecision(reason=reason)


class RespondDecision(AgentDecision):
    type: str = "respond"
    content: str
    is_final: bool = True
    streaming: bool = False


class CallToolDecision(AgentDecision):
    type: str = "call_tool"
    tool_name: str
    parameters: str
    requires_confirmation: bool = False
    rationale: str | None = None


class RetrieveDecision(AgentDecision):
    type: str = "retrieve"
    query: str
    document_ids: list[str] = Field(default_factory=list)
    top_k: int = Field(default=5, ge=1)
    min_score: float = Field(default=0.4, ge=0.0, le=1.0)


class WaitDecision(AgentDecision):
    type: str = "wait"
    reason: str
    wait_for_type: str = "user_confirmation"
    payload: str | None = None


class FinishDecision(AgentDecision):
    type: str = "finish"
    reason: str = "Task completed."


# ---------------------------------------------------------------------------
# AgentStep
# ---------------------------------------------------------------------------


class AgentStep(BaseModel):
    """Record of a single reasoning or tool-call step within an AgentExecution."""

    model_config = ConfigDict(frozen=True)

    step_index: int = Field(ge=0)
    decision: AgentDecision
    outcome: str | None = None
    duration_ms: int = 0
    tokens_used: int = 0


# ---------------------------------------------------------------------------
# AgentExecution
# ---------------------------------------------------------------------------


class AgentExecution(BaseModel):
    """The complete runtime record of a single agent execution.

    Immutable — use with_status(), with_step(), with_result(), and cancel()
    to produce updated snapshots.
    """

    model_config = ConfigDict(frozen=True)

    execution_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    request: AgentRequest
    agent_name: str
    status: AgentStatus = AgentStatus.REQUESTED
    steps: list[AgentStep] = Field(default_factory=list)
    result: AgentResult | None = None
    created_at: int = Field(
        default_factory=lambda: int(__import__("time").time() * 1000)
    )
    updated_at: int = Field(
        default_factory=lambda: int(__import__("time").time() * 1000)
    )
    completed_at: int | None = None

    @field_validator("execution_id")
    @classmethod
    def execution_id_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentExecution.execution_id must not be blank.")
        return v

    @field_validator("agent_name")
    @classmethod
    def agent_name_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("AgentExecution.agent_name must not be blank.")
        return v

    # ── Transition helpers ──────────────────────────────────────────────────

    def with_status(self, new_status: AgentStatus) -> AgentExecution:
        """Return a copy with status updated to *new_status*.

        Raises ValueError for invalid forward transitions.
        """
        if not self.status.can_transition_to(new_status):
            raise ValueError(
                f"Invalid AgentStatus transition: {self.status} → {new_status} "
                f"for execution {self.execution_id}."
            )
        now = int(__import__("time").time() * 1000)
        return self.model_copy(update={
            "status": new_status,
            "updated_at": now,
            "completed_at": now if new_status.is_terminal else self.completed_at,
        })

    def with_step(self, step: AgentStep) -> AgentExecution:
        """Return a copy with *step* appended to steps."""
        now = int(__import__("time").time() * 1000)
        return self.model_copy(update={
            "steps": [*list(self.steps), step],
            "updated_at": now,
        })

    def with_result(self, result: AgentResult) -> AgentExecution:
        """Return a copy with *result* set and status updated to result.status."""
        return self.with_status(result.status).model_copy(update={"result": result})

    def cancel(self) -> AgentExecution:
        """Return a copy in CANCELLED state, or self if already terminal."""
        if self.status.is_terminal:
            return self
        return self.with_status(AgentStatus.CANCELLED)

    # ── Computed helpers ──────────────────────────────────────────────────────

    @property
    def is_terminal(self) -> bool:
        return self.status.is_terminal

    @property
    def is_success(self) -> bool:
        return self.status.is_success

    @property
    def step_count(self) -> int:
        return len(self.steps)

    @property
    def total_tokens(self) -> int:
        return self.result.usage.total_tokens if self.result and self.result.usage else 0


# ---------------------------------------------------------------------------
# AgentEvent
# ---------------------------------------------------------------------------


class AgentEvent(BaseModel):
    """A discrete event emitted by an Agent during execution.

    Discriminated by the *type* field.
    The stream always terminates with Completed, Failed, or Cancelled.
    """

    model_config = ConfigDict(frozen=True)

    type: str


class AgentStartedEvent(AgentEvent):
    type: str = "started"
    execution_id: str
    agent_name: str


class AgentStatusChangedEvent(AgentEvent):
    type: str = "status_changed"
    execution_id: str
    status: AgentStatus


class AgentTokenEvent(AgentEvent):
    type: str = "token"
    token: str


class AgentThinkingEvent(AgentEvent):
    type: str = "thinking"
    step_index: int
    thought: str


class AgentToolStartedEvent(AgentEvent):
    type: str = "tool_started"
    tool_name: str
    parameters: str


class AgentToolCompletedEvent(AgentEvent):
    type: str = "tool_completed"
    tool_name: str
    output: str
    duration_ms: int


class AgentToolFailedEvent(AgentEvent):
    type: str = "tool_failed"
    tool_name: str
    error_message: str


class AgentToolConfirmationRequiredEvent(AgentEvent):
    type: str = "tool_confirmation_required"
    tool_name: str
    parameters: str
    rationale: str | None = None


class AgentRetrievalCompletedEvent(AgentEvent):
    type: str = "retrieval_completed"
    query: str
    chunk_count: int


class AgentCompletedEvent(AgentEvent):
    type: str = "completed"
    result: AgentResult


class AgentFailedEvent(AgentEvent):
    type: str = "failed"
    result: AgentResult


class AgentCancelledEvent(AgentEvent):
    type: str = "cancelled"
    reason: str = "Cancelled by user."
