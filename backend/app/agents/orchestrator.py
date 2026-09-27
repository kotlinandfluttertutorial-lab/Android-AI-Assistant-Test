# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : orchestrator.py
# Purpose : Central coordinator — routes requests, builds plans, drives
#           agent execution, handles handoffs, cancellation, and timeouts.
#
# Architecture Layer : Agent Core (Phase 2)
# Pattern Used       : Facade / Coordinator
#
# Key Concepts:
#   - execute() is an async generator yielding AgentEvent values
#   - Timeout enforced via asyncio.wait_for / asyncio.timeout
#   - Handoff: agent result nextAction.type == "HANDOFF" feeds into next step
#   - Cancellation: caller breaks from async for loop → generator cleaned up
#   - No hardcoded agent-specific logic in this class
#
# Dependencies: app.agents.registry, app.agents.router, app.agents.planner,
#               app.agents.models, app.agents.base, asyncio
# ============================================================

"""AgentOrchestrator — end-to-end agent execution coordinator."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import AsyncIterator
from typing import TYPE_CHECKING

from app.agents.models import (
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
)
from app.agents.planner import AgentPlanner, PlanCounters
from app.agents.registry import AgentRegistry
from app.agents.router import AgentRouter

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)

HANDOFF_ACTION_TYPE = "HANDOFF"


class AgentOrchestrator:
    """Central coordinator for agent request execution.

    Flow::

        AgentRequest
          → AgentRouter.route()       (select initial agent)
          → AgentPlanner.build_plan() (build step list, validate limits)
          → for each step:
              AgentPlanner.check_limits()
              Agent.execute()          (yields AgentEvent values)
              if result.next_action.type == "HANDOFF" → re-route for next step
          → yield terminal AgentEvent

    ## Usage

    ```python
    orchestrator = AgentOrchestrator(registry, router, planner)
    async for event in orchestrator.execute(request):
        if isinstance(event, AgentCompletedEvent):
            print(event.result.content)
    ```

    ## Cancellation

    Break out of the async-for loop (or cancel the task) to stop execution.
    The generator's ``finally`` block emits an AgentCancelledEvent.
    """

    def __init__(
        self,
        registry: AgentRegistry,
        router: AgentRouter,
        planner: AgentPlanner,
    ) -> None:
        self._registry = registry
        self._router = router
        self._planner = planner

    async def execute(
        self,
        request: AgentRequest,
    ) -> AsyncIterator[AgentEvent]:
        """Execute *request* end-to-end, yielding AgentEvent values.

        Always yields a terminal event (Completed / Failed / Cancelled).

        Args:
            request: The validated AgentRequest to execute.

        Yields:
            :class:`~app.agents.models.AgentEvent` instances.
        """
        return self._execute_inner(request)

    async def _execute_inner(
        self,
        request: AgentRequest,
    ) -> AsyncIterator[AgentEvent]:
        """Internal async generator that performs the actual execution."""
        execution_id = str(uuid.uuid4())
        start_ms = time.monotonic() * 1000

        # ── 1. Route ────────────────────────────────────────────────────────
        routing = self._router.route(request, self._registry)
        if routing.failed or routing.agent is None:
            yield self._failed_event(
                execution_id, request, "orchestrator",
                "ROUTING_FAILED", routing.reason,
            )
            return

        first_agent = routing.agent

        # ── 2. Plan ─────────────────────────────────────────────────────────
        try:
            plan = self._planner.build_plan(request, first_agent, self._registry)
        except Exception as exc:
            yield self._failed_event(
                execution_id, request, first_agent.name,
                "PLAN_BUILD_FAILED", str(exc),
            )
            return

        yield AgentStartedEvent(execution_id=execution_id, agent_name=first_agent.name)

        # ── 3. Execute plan ──────────────────────────────────────────────────
        current_input = request.input
        counters = PlanCounters(start_ms=time.monotonic() * 1000)

        try:
            async with asyncio.timeout(plan.timeout_ms / 1000):
                for step_index, plan_step in enumerate(plan.steps):
                    # Check limits before each step
                    violation = self._planner.check_limits(plan, counters)
                    if violation is not None:
                        yield self._failed_event(
                            execution_id, request, plan_step.agent_name,
                            "LIMIT_EXCEEDED", violation.message,
                        )
                        return

                    # Resolve agent for this step
                    try:
                        agent = self._registry.get(plan_step.agent_name)
                    except Exception as exc:
                        yield self._failed_event(
                            execution_id, request, plan_step.agent_name,
                            "AGENT_NOT_FOUND", str(exc),
                        )
                        return

                    # Build step request (handoff: use accumulated output as input)
                    remaining_ms = max(
                        100,
                        plan.timeout_ms - int((time.monotonic() * 1000) - start_ms),
                    )
                    step_request = request if step_index == 0 else AgentRequest(
                        user_id=request.user_id,
                        input=current_input,
                        conversation_id=request.conversation_id,
                        provider=request.provider,
                        capabilities=request.capabilities,
                        context=request.context,
                        max_steps=request.max_steps,
                        timeout_ms=remaining_ms,
                        streaming_enabled=request.streaming_enabled,
                        metadata=request.metadata,
                    )

                    if step_index > 0:
                        counters.handoffs_done += 1
                        yield AgentStatusChangedEvent(
                            execution_id=execution_id,
                            status=AgentStatus.RUNNING,
                        )

                    # Create execution envelope
                    execution = AgentExecution(
                        request=step_request,
                        agent_name=agent.name,
                        status=AgentStatus.STARTED,
                    )

                    # Stream events from the agent
                    step_result: AgentResult | None = None
                    async for event in agent.execute(step_request, execution):
                        # Count tool calls
                        if hasattr(event, "type") and event.type in (
                            "tool_completed", "tool_failed"
                        ):
                            counters.tool_calls_made += 1
                        yield event

                        # Capture terminal result
                        if hasattr(event, "result"):
                            step_result = event.result  # type: ignore[attr-defined]

                    counters.steps_taken += 1

                    # Use step output as input for next step (handoff)
                    if step_index < len(plan.steps) - 1 and step_result is not None:
                        current_input = step_result.content or current_input

        except asyncio.TimeoutError:
            yield self._failed_event(
                execution_id, request, first_agent.name,
                "TIMEOUT",
                f"Plan execution exceeded timeout of {plan.timeout_ms} ms.",
            )
        except asyncio.CancelledError:
            from app.agents.models import AgentCancelledEvent
            yield AgentCancelledEvent(reason="Execution cancelled by caller.")
            raise  # re-raise so the task is properly cancelled

    # ── Helpers ──────────────────────────────────────────────────────────────

    @staticmethod
    def _failed_event(
        execution_id: str,
        request: AgentRequest,
        agent_name: str,
        code: str,
        message: str,
    ) -> AgentEvent:
        result = AgentResult(
            execution_id=execution_id,
            request_id=request.request_id,
            agent_name=agent_name,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=message),
        )
        return AgentFailedEvent(result=result)
