# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : loop.py
# Purpose : AgentExecutionLoop — drives the
#           Understand→Plan→Select→Execute→Observe→Continue/Finish
#           cycle for a single agent.
#
# The loop calls the agent's decide() method (or a lightweight proxy)
# at each step to get the next AgentDecision, dispatches it via
# ActionDispatcher, records the span via ObservabilityTracker, and
# updates OrchestrationState.  It terminates when:
#   - The agent returns a FinishDecision or RespondDecision(is_final=True)
#   - max_steps is reached (FAILED)
#   - max_tool_calls is reached (FAILED)
#   - asyncio.timeout fires (TIMED_OUT)
#   - An unrecoverable error occurs (FAILED)
# ============================================================
"""AgentExecutionLoop — controlled decide→act→observe cycle."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator

from app.agents.models import (
    AgentDecision,
    AgentEvent,
    AgentRequest,
    AgentRetrievalCompletedEvent,
    AgentStatus,
    AgentThinkingEvent,
    AgentTokenEvent,
    AgentToolCompletedEvent,
    AgentToolFailedEvent,
    AgentToolStartedEvent,
    CallToolDecision,
    FinishDecision,
    RespondDecision,
    RetrieveDecision,
    WaitDecision,
)
from app.agents.planner import AgentPlanner, PlanCounters
from app.agents.registry import AgentRegistry
from app.orchestration.config import OrchestrationConfig
from app.orchestration.dispatcher import ActionDispatcher, ActionOutcome
from app.orchestration.observer import ObservabilityTracker
from app.orchestration.state import (
    OrchestrationState,
    RunStatus,
)

logger = logging.getLogger(__name__)


class AgentExecutionLoop:
    """Drives the controlled decide→act→observe cycle for a single agent.

    The loop is an **async generator** that yields
    :class:`~app.agents.models.AgentEvent` values in real time, allowing
    callers to stream progress to the UI while the run is in flight.

    The loop always terminates by yielding a terminal event
    (``AgentCompletedEvent`` or ``AgentFailedEvent``).

    ## Decision routing

    At each step the loop asks the agent for its next decision by calling
    the LLM adapter with an assembled prompt.  The LLM response is parsed
    into an :class:`~app.agents.models.AgentDecision` using a lightweight
    JSON extraction heuristic.  The decision is dispatched:

    - ``respond``   → LLM generation (``ActionDispatcher._dispatch_respond``)
    - ``retrieve``  → RAG pipeline   (``ActionDispatcher._dispatch_retrieve``)
    - ``call_tool`` → MCP execution  (``ActionDispatcher._dispatch_tool``)
    - ``wait``      → surface confirmation request; loop pauses
    - ``finish``    → loop terminates immediately

    ## State tracking

    :class:`~app.orchestration.state.OrchestrationState` is updated after
    every step and is observable by the caller through the yielded events.

    ## Timeout

    ``asyncio.timeout(config.timeout_s)`` wraps the entire loop body so a
    wall-clock timeout always terminates the generator.

    Usage::

        loop = AgentExecutionLoop(
            registry=registry,
            dispatcher=dispatcher,
            observer=tracker,
            config=config,
        )
        async for event in loop.run(request, state):
            process(event)
        result = state  # inspect final state
    """

    def __init__(
        self,
        registry: AgentRegistry,
        dispatcher: ActionDispatcher,
        observer: ObservabilityTracker | None = None,
        config: OrchestrationConfig | None = None,
    ) -> None:
        self._registry = registry
        self._dispatcher = dispatcher
        self._observer = observer or ObservabilityTracker()
        self._config = config or OrchestrationConfig()
        self._planner_helper = AgentPlanner()

    async def run(
        self,
        request: AgentRequest,
        state: OrchestrationState,
    ) -> AsyncIterator[AgentEvent]:
        """Execute the orchestration loop, yielding AgentEvent values.

        Args:
            request: Validated :class:`~app.agents.models.AgentRequest`.
            state:   Live :class:`~app.orchestration.state.OrchestrationState`
                     (caller must have set agent_name before calling).

        Yields:
            :class:`~app.agents.models.AgentEvent` instances as the loop progresses.
        """
        return self._run_inner(request, state)

    async def _run_inner(
        self,
        request: AgentRequest,
        state: OrchestrationState,
    ) -> AsyncIterator[AgentEvent]:
        """Internal async generator body."""
        from app.agents.models import (
            AgentCompletedEvent,
            AgentError,
            AgentFailedEvent,
            AgentResult,
            AgentStartedEvent,
            AgentStatusChangedEvent,
        )

        state.start()
        self._observer.on_run_start(state)

        yield AgentStartedEvent(
            execution_id=state.run_id,
            agent_name=state.agent_name,
        )

        PlanCounters(start_ms=time.monotonic() * 1000)
        # Accumulated context from previous steps (used to build prompts)
        prior_context: str = request.input

        try:
            async with asyncio.timeout(self._config.timeout_s):
                while True:
                    # ── Limit checks ─────────────────────────────────────────
                    if state.step_count >= self._config.max_steps:
                        state.fail(f"Max steps ({self._config.max_steps}) reached.")
                        self._observer.on_limit_exceeded(state, "max_steps", state.error_message)
                        break

                    if state.tool_call_count >= self._config.max_tool_calls:
                        state.fail(f"Max tool calls ({self._config.max_tool_calls}) reached.")
                        self._observer.on_limit_exceeded(
                            state, "max_tool_calls", state.error_message
                        )
                        break

                    # ── Get next decision ─────────────────────────────────────
                    yield AgentStatusChangedEvent(
                        execution_id=state.run_id,
                        status=AgentStatus.RUNNING,
                    )

                    decision, think_text = await self._decide(
                        request=request,
                        prior_context=prior_context,
                        state=state,
                    )

                    if think_text:
                        yield AgentThinkingEvent(
                            step_index=state.step_count,
                            thought=think_text,
                        )

                    # ── Pre-execute events ────────────────────────────────────
                    if isinstance(decision, CallToolDecision):
                        yield AgentToolStartedEvent(
                            tool_name=decision.tool_name,
                            parameters=decision.parameters,
                        )

                    # ── Execute action ────────────────────────────────────────
                    _step_start_ms = int(time.monotonic() * 1000)

                    # For RespondDecision the content IS the answer — use it
                    # directly rather than re-calling the LLM.  All other
                    # decision types (retrieve, call_tool, wait, finish) are
                    # dispatched to the appropriate adapter.
                    if isinstance(decision, RespondDecision):
                        outcome = ActionOutcome(
                            action_type="respond",
                            output=decision.content,
                            success=True,
                            is_final=decision.is_final,
                        )
                    elif isinstance(decision, FinishDecision):
                        outcome = ActionOutcome(
                            action_type="finish",
                            output=decision.reason,
                            success=True,
                            is_final=True,
                        )
                    else:
                        outcome = await self._dispatcher.dispatch(
                            decision=decision,
                            user_id=request.user_id,
                            system_prompt=_build_system_prompt(request),
                            document_ids=_rag_doc_ids(request),
                        )
                    step_end_ms = int(time.monotonic() * 1000)  # noqa: F841

                    # ── Post-execute events ───────────────────────────────────
                    if isinstance(decision, CallToolDecision):
                        if outcome.success:
                            yield AgentToolCompletedEvent(
                                tool_name=decision.tool_name,
                                output=outcome.output,
                                duration_ms=outcome.duration_ms,
                            )
                        else:
                            yield AgentToolFailedEvent(
                                tool_name=decision.tool_name,
                                error_message=outcome.error_message,
                            )

                    if isinstance(decision, RetrieveDecision):
                        yield AgentRetrievalCompletedEvent(
                            query=decision.query,
                            chunk_count=len(outcome.citations),
                        )

                    if isinstance(decision, RespondDecision) and outcome.success:
                        # Emit individual token event (streaming simulation)
                        yield AgentTokenEvent(token=outcome.output)

                    # ── Record span ───────────────────────────────────────────
                    self._observer.record_span(
                        state,
                        step_index=state.step_count,
                        action_type=outcome.action_type,
                        input_summary=_input_summary(decision),
                        output_summary=outcome.output[:200] if outcome.output else "",
                        duration_ms=outcome.duration_ms,
                        tokens_used=outcome.tokens_used,
                        success=outcome.success,
                        error_message=outcome.error_message,
                    )

                    # ── Accumulate outputs ────────────────────────────────────
                    if outcome.success and outcome.output:
                        state.append_output(outcome.output)
                        prior_context = outcome.output  # feed into next decide

                    if outcome.citations:
                        state.citations.extend(outcome.citations)

                    if outcome.tool_record:
                        state.tool_calls.append(outcome.tool_record)

                    # ── Termination check ─────────────────────────────────────
                    if isinstance(decision, FinishDecision):
                        state.finish()
                        break

                    if isinstance(decision, RespondDecision) and decision.is_final:
                        state.finish()
                        break

                    if not outcome.success:
                        # Non-fatal: log and continue unless it's a hard error
                        # (hard errors are caught by the outer except)
                        logger.debug(
                            "AgentExecutionLoop: step %d failed (%s); continuing.",
                            state.step_count - 1,
                            outcome.error_message,
                        )
                        # Inject error notice into context so the next decide
                        # can react to it
                        prior_context = (
                            f"[Previous step failed: {outcome.error_message}] "
                            f"Original input: {prior_context}"
                        )

        except asyncio.TimeoutError:
            state.time_out()
            self._observer.on_timeout(state)

        except asyncio.CancelledError:
            state.cancel()
            raise  # re-raise so the task is properly cancelled

        except Exception as exc:
            msg = f"Unexpected error in orchestration loop: {exc}"
            state.fail(msg)
            self._observer.on_error(state, msg)
            logger.exception("AgentExecutionLoop: unhandled error run_id=%s", state.run_id)

        # ── Final events ──────────────────────────────────────────────────────
        elapsed_ms = int(state.elapsed_s * 1000)
        self._observer.on_run_end(state, elapsed_ms)

        if state.status == RunStatus.COMPLETED:
            result = AgentResult(
                execution_id=state.run_id,
                request_id=state.request_id,
                agent_name=state.agent_name,
                status=AgentStatus.COMPLETED,
                content=state.accumulated_output,
                citations=[_dict_to_citation(c) for c in state.citations],
                metadata={"step_count": str(state.step_count)},
            )
            yield AgentCompletedEvent(result=result)
        else:
            error_code = {
                RunStatus.TIMED_OUT: "TIMEOUT",
                RunStatus.CANCELLED: "CANCELLED",
                RunStatus.FAILED: "EXECUTION_FAILED",
            }.get(state.status, "UNKNOWN")
            result = AgentResult(
                execution_id=state.run_id,
                request_id=state.request_id,
                agent_name=state.agent_name,
                status=AgentStatus.FAILED,
                error=AgentError(
                    code=error_code,
                    message=state.error_message or "Orchestration loop terminated unexpectedly.",
                ),
            )
            yield AgentFailedEvent(result=result)

    # ── Decision generation ───────────────────────────────────────────────────

    async def _decide(
        self,
        request: AgentRequest,
        prior_context: str,
        state: OrchestrationState,
    ) -> tuple[AgentDecision, str]:
        """Ask the LLM adapter for the next decision.

        Returns a (decision, thinking_text) tuple.
        On LLM failure, falls back to a FinishDecision.
        """
        # Build a reasoning prompt that describes the task and prior context
        prompt = _build_decision_prompt(request, prior_context, state)

        # Call the LLM adapter directly (bypass the dispatcher's short-circuit
        # for RespondDecision so the prompt actually reaches the model).
        llm = self._dispatcher._llm
        if llm is None:
            logger.warning("AgentExecutionLoop: no LLM adapter — finishing immediately.")
            return FinishDecision(reason="No LLM adapter configured."), ""

        try:
            raw_text = await llm.generate(
                prompt,
                system_prompt=_DECISION_SYSTEM_PROMPT,
                user_id=request.user_id,
            )
        except Exception as exc:
            logger.warning(
                "AgentExecutionLoop: _decide LLM failed step=%d; finishing: %s",
                state.step_count,
                exc,
            )
            return FinishDecision(reason=f"LLM unavailable: {exc}"), ""

        return _parse_decision(raw_text, request), ""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_DECISION_SYSTEM_PROMPT = """You are a single-purpose AI agent. At each step:
1. Analyze the task and any context from prior steps.
2. Choose EXACTLY ONE action. Respond with a JSON object:
   {"action": "respond"|"retrieve"|"call_tool"|"finish",
    "content": "...",     // for respond: the answer
    "query": "...",       // for retrieve: the search query
    "tool_name": "...",   // for call_tool: tool identifier
    "parameters": "...",  // for call_tool: JSON-encoded params
    "is_final": true|false // for respond: whether this completes the task
   }
3. Keep responses concise and accurate.
"""


def _build_decision_prompt(
    request: AgentRequest,
    prior_context: str,
    state: OrchestrationState,
) -> str:
    parts = [f"Task: {request.input}"]
    if prior_context and prior_context != request.input:
        parts.append(f"Prior step result:\n{prior_context}")
    if state.step_count > 0:
        parts.append(f"Steps completed so far: {state.step_count}")
    parts.append("What is your next action? Respond with the JSON schema above.")
    return "\n\n".join(parts)


def _parse_decision(llm_output: str, request: AgentRequest) -> AgentDecision:
    """Parse LLM JSON output into an AgentDecision.

    Falls back to RespondDecision(is_final=True) when JSON is absent or invalid.
    """
    import json as _json

    # Try to parse the full output as JSON first (handles nested structures)
    data: dict | None = None
    stripped = llm_output.strip()
    if stripped.startswith("{"):
        try:
            data = _json.loads(stripped)
        except _json.JSONDecodeError:
            pass

    if data is None:
        # Fallback: extract from first { to last } to cover partial outputs
        first_brace = llm_output.find("{")
        last_brace = llm_output.rfind("}")
        if first_brace != -1 and last_brace > first_brace:
            try:
                data = _json.loads(llm_output[first_brace : last_brace + 1])
            except _json.JSONDecodeError:
                pass

    if data is None:
        return RespondDecision(content=stripped or llm_output, is_final=True)

    action = data.get("action", "respond")

    if action == "finish":
        return FinishDecision(reason=data.get("content", "Task completed."))

    if action == "retrieve":
        return RetrieveDecision(
            query=data.get("query", request.input),
            top_k=data.get("top_k", 5),
        )

    if action == "call_tool":
        import json as _j

        params = data.get("parameters", "{}")
        if isinstance(params, dict):
            params = _j.dumps(params)
        elif not isinstance(params, str):
            params = _j.dumps(params)
        return CallToolDecision(
            tool_name=data.get("tool_name", ""),
            parameters=params,
        )

    if action == "wait":
        return WaitDecision(reason=data.get("content", "Waiting for input."))

    # Default: respond
    return RespondDecision(
        content=data.get("content", llm_output.strip()),
        is_final=data.get("is_final", True),
    )


def _build_system_prompt(request: AgentRequest) -> str:
    if request.context and request.context.persona_system_prompt:
        return request.context.persona_system_prompt
    return ""


def _rag_doc_ids(request: AgentRequest) -> list[str] | None:
    if request.context and request.context.rag_document_ids:
        return list(request.context.rag_document_ids)
    return None


def _input_summary(decision: AgentDecision) -> str:
    if isinstance(decision, RespondDecision):
        return decision.content[:200]
    if isinstance(decision, RetrieveDecision):
        return f"query={decision.query[:100]}"
    if isinstance(decision, CallToolDecision):
        return f"tool={decision.tool_name}"
    if isinstance(decision, WaitDecision):
        return f"wait={decision.reason[:100]}"
    if isinstance(decision, FinishDecision):
        return f"finish={decision.reason[:100]}"
    return str(decision)[:200]


def _dict_to_citation(c: dict) -> AgentCitation:  # noqa: F821
    from app.agents.models import AgentCitation

    return AgentCitation(
        document_id=c.get("document_id", ""),
        document_name=c.get("document_name", ""),
        excerpt=c.get("excerpt", ""),
        page_number=c.get("page_number"),
        score=c.get("similarity", 0.0),
    )
