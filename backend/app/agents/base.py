# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : base.py
# Purpose : Provider-independent Agent interface every implementation must satisfy.
#
# Architecture Layer : Agent Core (Phase 1) — interface
# Pattern Used       : Abstract Base Class (ABC)
#
# Key Concepts:
#   - execute() is an async generator (AsyncIterator[AgentEvent]) matching the
#     project's existing streaming patterns (OkHttp WebSocket callbackFlow on
#     Android; async generator / async for on the backend)
#   - can_handle() is a pure predicate — no side effects, no I/O
#   - Default can_handle() mirrors the Kotlin Agent.canHandle() default exactly
#   - Implementations register with a future AgentRegistry; the orchestration
#     layer calls can_handle() and routes to the first match
#
# Design Decision:
#   An ABC (not a Protocol) is used so that concrete agents can call super()
#   for the default can_handle() implementation and so that isinstance() checks
#   work correctly.  A Protocol would also work but would lose the default method.
#
# Dependencies: app.agents.models, abc, collections.abc
# ============================================================

"""Agent base class (abstract interface).

Every concrete agent must subclass Agent and implement:
  - name       (property)
  - description (property)
  - capabilities (property)
  - execute()  (async generator)

can_handle() has a default implementation and may be overridden.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator

from app.agents.models import (
    AgentCapability,
    AgentEvent,
    AgentExecution,
    AgentRequest,
)


class Agent(ABC):
    """Abstract base class every Agent implementation must subclass.

    ## Implementing an Agent

    ```python
    class ConversationalAgent(Agent):

        @property
        def name(self) -> str:
            return "conversational"

        @property
        def description(self) -> str:
            return "General-purpose conversational AI agent."

        @property
        def capabilities(self) -> frozenset[AgentCapability]:
            return frozenset({
                AgentCapability.TEXT_GENERATION,
                AgentCapability.STREAMING,
                AgentCapability.MEMORY_ACCESS,
            })

        async def execute(
            self,
            request: AgentRequest,
            execution: AgentExecution,
        ) -> AsyncIterator[AgentEvent]:
            yield AgentStartedEvent(
                execution_id=execution.execution_id,
                agent_name=self.name,
            )
            # ... yield AgentEvent values ...
            yield AgentCompletedEvent(result=result)
    ```

    ## Threading / Async Model

    `execute` is an async generator.  Callers iterate it with `async for event in
    agent.execute(request, execution)`.  The generator should yield control between
    steps (e.g. `await asyncio.sleep(0)` or actual I/O awaits) so the event loop
    is not starved.

    ## Cancellation

    The caller cancels by calling `.aclose()` on the async generator (which Python
    does automatically when the `async for` loop is broken or an exception is
    raised in the loop body).  Agents must clean up resources in a `finally` block
    or an `async with` context manager.
    """

    # ── Abstract properties ─────────────────────────────────────────────────

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique, stable agent identifier.

        Convention: lowercase, hyphenated — e.g. "conversational", "code-analysis".
        Used in AgentResult.agent_name and routing logs.
        """

    @property
    @abstractmethod
    def description(self) -> str:
        """One-sentence description of what this agent does."""

    @property
    @abstractmethod
    def capabilities(self) -> frozenset[AgentCapability]:
        """Immutable set of capabilities this agent declares support for."""

    # ── Default can_handle ───────────────────────────────────────────────────

    def can_handle(self, request: AgentRequest) -> bool:
        """Return True if this agent can handle *request*.

        Default implementation: returns True when every capability required by
        the request is declared in ``self.capabilities``, or when the request
        has no capability constraints (empty list).

        Override to add richer logic (e.g. provider availability checks,
        context-size limits, feature flags).

        This must remain a pure, synchronous function — no I/O, no side effects.

        Args:
            request: The incoming AgentRequest to evaluate.

        Returns:
            True if this agent should handle the request.
        """
        if not request.capabilities:
            return True
        return all(cap in self.capabilities for cap in request.capabilities)

    # ── Abstract execute ─────────────────────────────────────────────────────

    @abstractmethod
    def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        """Execute *request* and yield AgentEvent values until completion.

        The generator always terminates with one of:
        - AgentCompletedEvent  — execution finished successfully
        - AgentFailedEvent     — unrecoverable error
        - AgentCancelledEvent  — cancelled (generator closed by caller)

        The *execution* parameter carries the pre-populated AgentExecution
        snapshot (with status STARTED).  The agent does NOT mutate it directly;
        it signals state changes via AgentStatusChangedEvent emissions.

        Args:
            request:   The validated AgentRequest to execute.
            execution: The pre-created AgentExecution context for this run.

        Yields:
            AgentEvent instances in emission order.
        """

    # ── Repr ────────────────────────────────────────────────────────────────

    def __repr__(self) -> str:
        caps = ", ".join(c.value for c in sorted(self.capabilities, key=lambda c: c.value))
        return f"<Agent name={self.name!r} capabilities=[{caps}]>"
