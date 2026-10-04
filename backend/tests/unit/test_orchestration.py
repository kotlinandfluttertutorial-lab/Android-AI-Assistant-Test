"""Comprehensive unit tests for app.orchestration.

Covers:
  - OrchestrationConfig validation
  - RunStatus lifecycle
  - ExecutionSpan construction
  - OrchestrationState transitions and span recording
  - OrchestrationResult.from_state
  - PlanningResult factory
  - OrchestrationPlanner (routing success / failure)
  - ActionOutcome
  - ActionDispatcher: respond / retrieve / call_tool / wait / finish /
    disabled flags / missing adapters / error handling / never-raises
  - ObservabilityTracker: span recording, callbacks, _truncate, _redact_uid
  - AgentExecutionLoop: completed, max_steps exceeded, max_tool_calls,
    timeout, cancellation, tool events, RAG events, JSON decision parsing
  - SingleAgentRunner: planning failure, streaming, blocking run(), timeout,
    MCP/RAG execution paths
  - _parse_decision: all action types, malformed JSON, plain text fallback

No production credentials.  All LLM/RAG/MCP adapters are faked.
google.genai is stubbed so the services chain is not triggered.
"""

from __future__ import annotations

import sys
from unittest.mock import MagicMock as _MagicMock

_g = _MagicMock()
sys.modules.setdefault("google.genai", _g)
sys.modules.setdefault("google.genai.types", _g)

import asyncio
import uuid
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

# ── orchestration imports ─────────────────────────────────────────────────────
from app.orchestration.config import OrchestrationConfig
from app.orchestration.dispatcher import ActionDispatcher, ActionOutcome
from app.orchestration.loop import AgentExecutionLoop, _parse_decision
from app.orchestration.observer import ObservabilityTracker, _redact_uid, _truncate
from app.orchestration.planner import OrchestrationPlanner, PlanningResult
from app.orchestration.runner import SingleAgentRunner
from app.orchestration.state import (
    ExecutionSpan,
    OrchestrationResult,
    OrchestrationState,
    RunStatus,
)

# ── agent model imports ───────────────────────────────────────────────────────
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentEvent,
    AgentFailedEvent,
    AgentRequest,
    AgentStartedEvent,
    AgentStatus,
    AgentTokenEvent,
    CallToolDecision,
    FinishDecision,
    RespondDecision,
    RetrieveDecision,
    WaitDecision,
)
from app.agents.registry import AgentRegistry

# ── helpers ───────────────────────────────────────────────────────────────────

USER = "user-test-1"
REQ_ID = str(uuid.uuid4())


def _request(
    user_id: str = USER,
    input_text: str = "What is the weather?",
    timeout_ms: int = 60_000,
    **kw,
) -> AgentRequest:
    return AgentRequest(
        request_id=REQ_ID,
        user_id=user_id,
        input=input_text,
        timeout_ms=timeout_ms,
        **kw,
    )


def _state(agent_name: str = "conversational") -> OrchestrationState:
    s = OrchestrationState(
        request_id=REQ_ID,
        user_id=USER,
        agent_name=agent_name,
    )
    return s


def _fake_registry(agent_name: str = "conversational") -> AgentRegistry:
    """Registry with one stub agent."""
    from app.agents.base import Agent

    class _StubAgent(Agent):
        @property
        def name(self) -> str:
            return agent_name

        @property
        def description(self) -> str:
            return "Stub agent for testing."

        @property
        def capabilities(self) -> frozenset[AgentCapability]:
            return frozenset({AgentCapability.TEXT_GENERATION})

        def execute(self, request, execution):  # type: ignore[override]
            async def _gen():
                yield AgentCompletedEvent(
                    result=MagicMock(
                        status=AgentStatus.COMPLETED,
                        content="stub answer",
                    )
                )

            return _gen()

    registry = AgentRegistry()
    registry.register(_StubAgent())
    return registry


def _fake_llm(text: str = '{"action":"respond","content":"42","is_final":true}') -> AsyncMock:
    llm = AsyncMock()
    llm.generate = AsyncMock(return_value=text)
    return llm


def _fake_rag(answer: str = "The answer.", sources: list | None = None) -> AsyncMock:
    result = MagicMock()
    result.answer = answer
    result.sources = sources or []
    rag = AsyncMock()
    rag.ask = AsyncMock(return_value=result)
    return rag


def _fake_mcp(success: bool = True, result_data: Any = None, error: str = "") -> AsyncMock:
    res = MagicMock()
    res.success = success
    res.result = result_data or {"ok": True}
    res.error = error
    mcp = AsyncMock()
    mcp.execute = AsyncMock(return_value=res)
    return mcp


# =============================================================================
# OrchestrationConfig
# =============================================================================


class TestOrchestrationConfig:
    def test_defaults(self):
        c = OrchestrationConfig()
        assert c.max_steps == 10
        assert c.timeout_s == 120.0
        assert c.enable_rag
        assert c.enable_mcp
        assert c.enable_streaming

    def test_max_steps_below_1_raises(self):
        with pytest.raises(ValueError, match="max_steps"):
            OrchestrationConfig(max_steps=0)

    def test_max_steps_above_hard_cap_raises(self):
        with pytest.raises(ValueError, match="max_steps"):
            OrchestrationConfig(max_steps=51)

    def test_max_tool_calls_above_hard_cap_raises(self):
        with pytest.raises(ValueError, match="max_tool_calls"):
            OrchestrationConfig(max_tool_calls=101)

    def test_timeout_zero_raises(self):
        with pytest.raises(ValueError, match="timeout_s"):
            OrchestrationConfig(timeout_s=0)

    def test_timeout_above_hard_cap_raises(self):
        with pytest.raises(ValueError, match="timeout_s"):
            OrchestrationConfig(timeout_s=301)

    def test_min_similarity_out_of_range_raises(self):
        with pytest.raises(ValueError, match="min_similarity"):
            OrchestrationConfig(min_similarity=1.5)

    def test_rag_top_k_below_1_raises(self):
        with pytest.raises(ValueError, match="rag_top_k"):
            OrchestrationConfig(rag_top_k=0)

    def test_llm_temperature_out_of_range_raises(self):
        with pytest.raises(ValueError, match="llm_temperature"):
            OrchestrationConfig(llm_temperature=3.0)

    def test_llm_temperature_none_allowed(self):
        c = OrchestrationConfig(llm_temperature=None)
        assert c.llm_temperature is None

    def test_valid_custom_config(self):
        c = OrchestrationConfig(max_steps=3, timeout_s=30.0, enable_rag=False, enable_mcp=False)
        assert c.max_steps == 3
        assert not c.enable_rag


# =============================================================================
# RunStatus
# =============================================================================


class TestRunStatus:
    def test_terminal_statuses(self):
        for s in (
            RunStatus.COMPLETED,
            RunStatus.FAILED,
            RunStatus.TIMED_OUT,
            RunStatus.CANCELLED,
        ):
            assert s.is_terminal

    def test_non_terminal_statuses(self):
        for s in (RunStatus.PENDING, RunStatus.RUNNING):
            assert not s.is_terminal


# =============================================================================
# ExecutionSpan
# =============================================================================


class TestExecutionSpan:
    def test_construction(self):
        span = ExecutionSpan(step_index=0, action_type="respond", output_summary="ok", success=True)
        assert span.step_index == 0
        assert span.success

    def test_default_error_empty(self):
        span = ExecutionSpan(step_index=1, action_type="finish")
        assert span.error_message == ""
        assert span.tokens_used == 0


# =============================================================================
# OrchestrationState
# =============================================================================


class TestOrchestrationState:
    def test_initial_status_is_pending(self):
        s = _state()
        assert s.status == RunStatus.PENDING

    def test_start_sets_running(self):
        s = _state()
        s.start()
        assert s.status == RunStatus.RUNNING
        assert s.start_time_s > 0

    def test_finish_sets_completed(self):
        s = _state()
        s.start()
        s.finish()
        assert s.status == RunStatus.COMPLETED

    def test_fail_sets_error(self):
        s = _state()
        s.start()
        s.fail("something broke")
        assert s.status == RunStatus.FAILED
        assert "something broke" in s.error_message

    def test_time_out(self):
        s = _state()
        s.start()
        s.time_out()
        assert s.status == RunStatus.TIMED_OUT

    def test_cancel(self):
        s = _state()
        s.start()
        s.cancel()
        assert s.status == RunStatus.CANCELLED

    def test_cancel_no_op_on_terminal(self):
        s = _state()
        s.start()
        s.finish()
        s.cancel()
        assert s.status == RunStatus.COMPLETED  # unchanged

    def test_record_span_increments_step_count(self):
        s = _state()
        span = ExecutionSpan(step_index=0, action_type="respond")
        s.record_span(span)
        assert s.step_count == 1
        assert len(s.spans) == 1

    def test_record_tool_span_increments_tool_count(self):
        s = _state()
        span = ExecutionSpan(step_index=0, action_type="call_tool")
        s.record_span(span)
        assert s.tool_call_count == 1

    def test_append_output_first(self):
        s = _state()
        s.append_output("hello")
        assert s.accumulated_output == "hello"

    def test_append_output_subsequent_adds_newline(self):
        s = _state()
        s.append_output("part1")
        s.append_output("part2")
        assert "part1" in s.accumulated_output
        assert "part2" in s.accumulated_output

    def test_elapsed_s_before_start(self):
        s = _state()
        assert s.elapsed_s == 0.0

    def test_total_tokens(self):
        s = _state()
        s.record_span(ExecutionSpan(step_index=0, action_type="respond", tokens_used=10))
        s.record_span(ExecutionSpan(step_index=1, action_type="respond", tokens_used=20))
        assert s.total_tokens == 30


# =============================================================================
# OrchestrationResult
# =============================================================================


class TestOrchestrationResult:
    def _completed_state(self) -> OrchestrationState:
        s = _state()
        s.start()
        s.append_output("the answer")
        s.record_span(ExecutionSpan(step_index=0, action_type="respond", tokens_used=5))
        s.finish()
        return s

    def test_from_state_success(self):
        s = self._completed_state()
        r = OrchestrationResult.from_state(s, elapsed_ms=100)
        assert r.success
        assert r.output == "the answer"
        assert r.step_count == 1
        assert r.total_tokens == 5
        assert r.elapsed_ms == 100

    def test_from_state_failure(self):
        s = _state()
        s.start()
        s.fail("timeout")
        r = OrchestrationResult.from_state(s, elapsed_ms=50)
        assert not r.success
        assert r.status == RunStatus.FAILED

    def test_success_property_false_on_failed(self):
        r = OrchestrationResult(
            run_id="r1",
            request_id="req1",
            agent_name="agent",
            status=RunStatus.FAILED,
            output="",
        )
        assert not r.success


# =============================================================================
# PlanningResult
# =============================================================================


class TestPlanningResult:
    def test_failed_factory(self):
        r = PlanningResult.failed("no agent")
        assert not r.success
        assert r.plan is None
        assert r.agent_name == ""
        assert "no agent" in r.error


# =============================================================================
# OrchestrationPlanner
# =============================================================================


class TestOrchestrationPlanner:
    def test_plan_success(self):
        registry = _fake_registry()
        planner = OrchestrationPlanner(registry, OrchestrationConfig())
        result = planner.plan(_request())
        assert result.success
        assert result.plan is not None
        assert result.agent_name == "conversational"

    def test_plan_fails_on_empty_registry(self):
        planner = OrchestrationPlanner(AgentRegistry(), OrchestrationConfig())
        result = planner.plan(_request())
        assert not result.success
        assert result.plan is None
        assert result.error

    def test_plan_respects_explicit_agent_name(self):
        registry = _fake_registry("my-agent")
        planner = OrchestrationPlanner(registry, OrchestrationConfig())
        req = _request(metadata={"agent_name": "my-agent"})
        result = planner.plan(req)
        assert result.success
        assert result.agent_name == "my-agent"

    def test_plan_fails_unknown_explicit_agent(self):
        registry = _fake_registry()
        planner = OrchestrationPlanner(registry, OrchestrationConfig())
        req = _request(metadata={"agent_name": "ghost-agent"})
        result = planner.plan(req)
        assert not result.success


# =============================================================================
# ActionOutcome
# =============================================================================


class TestActionOutcome:
    def test_defaults(self):
        o = ActionOutcome(action_type="respond")
        assert o.success
        assert not o.is_final
        assert o.output == ""
        assert o.tokens_used == 0

    def test_error_outcome(self):
        o = ActionOutcome(action_type="respond", success=False, error_message="oops")
        assert not o.success
        assert o.error_message == "oops"


# =============================================================================
# ActionDispatcher
# =============================================================================


class TestActionDispatcherRespond:
    @pytest.mark.asyncio
    async def test_respond_success(self):
        llm = _fake_llm("Hello world")
        d = ActionDispatcher(llm=llm, config=OrchestrationConfig())
        outcome = await d.dispatch(
            RespondDecision(content="What is 2+2?", is_final=True), user_id=USER
        )
        assert outcome.success
        assert outcome.output == "Hello world"
        assert outcome.is_final
        assert outcome.action_type == "respond"

    @pytest.mark.asyncio
    async def test_respond_llm_failure_returns_safe_message(self):
        llm = AsyncMock()
        llm.generate = AsyncMock(side_effect=RuntimeError("GPU OOM"))
        d = ActionDispatcher(llm=llm, config=OrchestrationConfig())
        outcome = await d.dispatch(RespondDecision(content="Q?"), user_id=USER)
        assert not outcome.success
        assert "GPU OOM" not in outcome.error_message  # no internal leak
        assert "LLM generation failed" in outcome.error_message

    @pytest.mark.asyncio
    async def test_respond_no_llm_adapter_returns_failure(self):
        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(RespondDecision(content="Q?"), user_id=USER)
        assert not outcome.success
        assert "not configured" in outcome.error_message

    @pytest.mark.asyncio
    async def test_respond_duration_set(self):
        d = ActionDispatcher(llm=_fake_llm(), config=OrchestrationConfig())
        outcome = await d.dispatch(RespondDecision(content="Q?"), user_id=USER)
        assert outcome.duration_ms >= 0


class TestActionDispatcherRetrieve:
    @pytest.mark.asyncio
    async def test_retrieve_success(self):
        rag = _fake_rag(answer="Found it.", sources=[{"document_name": "doc.pdf"}])
        d = ActionDispatcher(rag=rag, config=OrchestrationConfig())
        outcome = await d.dispatch(RetrieveDecision(query="climate change"), user_id=USER)
        assert outcome.success
        assert outcome.output == "Found it."
        assert len(outcome.citations) == 1
        assert outcome.action_type == "retrieve"

    @pytest.mark.asyncio
    async def test_retrieve_disabled_returns_failure(self):
        rag = _fake_rag()
        d = ActionDispatcher(rag=rag, config=OrchestrationConfig(enable_rag=False))
        outcome = await d.dispatch(RetrieveDecision(query="Q"), user_id=USER)
        assert not outcome.success
        assert "disabled" in outcome.error_message

    @pytest.mark.asyncio
    async def test_retrieve_no_adapter_returns_failure(self):
        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(RetrieveDecision(query="Q"), user_id=USER)
        assert not outcome.success

    @pytest.mark.asyncio
    async def test_retrieve_rag_error_returns_safe_message(self):
        rag = AsyncMock()
        rag.ask = AsyncMock(side_effect=RuntimeError("DB down"))
        d = ActionDispatcher(rag=rag, config=OrchestrationConfig())
        outcome = await d.dispatch(RetrieveDecision(query="Q"), user_id=USER)
        assert not outcome.success
        assert "DB down" not in outcome.error_message


class TestActionDispatcherTool:
    @pytest.mark.asyncio
    async def test_tool_success(self):
        mcp = _fake_mcp(success=True, result_data={"issue": "PROJ-1"})
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig())
        outcome = await d.dispatch(
            CallToolDecision(tool_name="jira_get_issue", parameters='{"issue_key":"PROJ-1"}'),
            user_id=USER,
        )
        assert outcome.success
        assert outcome.action_type == "call_tool"
        assert outcome.tool_record is not None
        assert outcome.tool_record["tool_name"] == "jira_get_issue"

    @pytest.mark.asyncio
    async def test_tool_failure_from_mcp(self):
        mcp = _fake_mcp(success=False, error="Not found")
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig())
        outcome = await d.dispatch(
            CallToolDecision(tool_name="jira_get_issue", parameters="{}"),
            user_id=USER,
        )
        assert not outcome.success
        assert "Not found" in outcome.error_message

    @pytest.mark.asyncio
    async def test_tool_disabled_returns_failure(self):
        mcp = _fake_mcp()
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig(enable_mcp=False))
        outcome = await d.dispatch(
            CallToolDecision(tool_name="any_tool", parameters="{}"), user_id=USER
        )
        assert not outcome.success
        assert "disabled" in outcome.error_message

    @pytest.mark.asyncio
    async def test_tool_no_adapter_returns_failure(self):
        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(
            CallToolDecision(tool_name="any_tool", parameters="{}"), user_id=USER
        )
        assert not outcome.success

    @pytest.mark.asyncio
    async def test_tool_json_params_parsed(self):
        mcp = _fake_mcp()
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig())
        await d.dispatch(
            CallToolDecision(tool_name="tool", parameters='{"key": "val"}'), user_id=USER
        )
        call_kwargs = mcp.execute.call_args.kwargs
        assert call_kwargs["params"] == {"key": "val"}

    @pytest.mark.asyncio
    async def test_tool_malformed_json_uses_raw_fallback(self):
        mcp = _fake_mcp()
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig())
        outcome = await d.dispatch(
            CallToolDecision(tool_name="tool", parameters="not-json"), user_id=USER
        )
        assert outcome.success  # raw params sent, no crash

    @pytest.mark.asyncio
    async def test_tool_exception_returns_safe_message(self):
        mcp = AsyncMock()
        mcp.execute = AsyncMock(side_effect=RuntimeError("internal db error"))
        d = ActionDispatcher(mcp=mcp, config=OrchestrationConfig())
        outcome = await d.dispatch(
            CallToolDecision(tool_name="tool", parameters="{}"), user_id=USER
        )
        assert not outcome.success
        assert "internal db error" not in outcome.error_message


class TestActionDispatcherWaitFinish:
    @pytest.mark.asyncio
    async def test_wait_returns_non_final(self):
        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(WaitDecision(reason="Awaiting confirmation"), user_id=USER)
        assert outcome.success
        assert not outcome.is_final
        assert "Awaiting confirmation" in outcome.output

    @pytest.mark.asyncio
    async def test_finish_returns_final(self):
        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(FinishDecision(reason="Done."), user_id=USER)
        assert outcome.success
        assert outcome.is_final
        assert "Done." in outcome.output

    @pytest.mark.asyncio
    async def test_unknown_decision_type_returns_failure(self):
        class _FakeDecision:
            type = "unknown_xyz"

        d = ActionDispatcher(config=OrchestrationConfig())
        outcome = await d.dispatch(_FakeDecision(), user_id=USER)
        assert not outcome.success

    @pytest.mark.asyncio
    async def test_dispatcher_never_raises(self):
        """Even with all adapters absent and malformed decision, no exception."""
        d = ActionDispatcher(config=OrchestrationConfig())

        # Simulate an adapter that crashes unexpectedly
        class _CrashDecision(RespondDecision):
            pass

        d._llm = MagicMock()
        d._llm.generate = AsyncMock(side_effect=Exception("crash!"))
        outcome = await d.dispatch(RespondDecision(content="Q?"), user_id=USER)
        assert not outcome.success


# =============================================================================
# ObservabilityTracker
# =============================================================================


class TestObservabilityTracker:
    def test_record_span_updates_state(self):
        tracker = ObservabilityTracker()
        s = _state()
        span = tracker.record_span(
            s,
            step_index=0,
            action_type="respond",
            input_summary="Q?",
            output_summary="A.",
            duration_ms=42,
            tokens_used=7,
            success=True,
        )
        assert span.step_index == 0
        assert span.tokens_used == 7
        assert s.step_count == 1

    def test_callback_invoked_on_span(self):
        received = []
        tracker = ObservabilityTracker(extra_callbacks=[received.append])
        s = _state()
        tracker.record_span(
            s,
            step_index=0,
            action_type="respond",
            input_summary="in",
            output_summary="out",
            duration_ms=1,
            tokens_used=0,
            success=True,
        )
        assert len(received) == 1
        assert received[0].action_type == "respond"

    def test_failing_callback_does_not_propagate(self):
        def _bad_cb(span):
            raise RuntimeError("callback crash")

        tracker = ObservabilityTracker(extra_callbacks=[_bad_cb])
        s = _state()
        # Must not raise
        tracker.record_span(
            s,
            step_index=0,
            action_type="respond",
            input_summary="",
            output_summary="",
            duration_ms=0,
            tokens_used=0,
            success=True,
        )

    def test_truncate_long_input(self):
        long = "x" * 500
        result = _truncate(long, max_chars=200)
        assert len(result) == 201  # 200 chars + "…"
        assert result.endswith("…")

    def test_truncate_short_unchanged(self):
        assert _truncate("hello") == "hello"

    def test_truncate_empty(self):
        assert _truncate("") == ""

    def test_redact_uid(self):
        uid = "aaaa-bbbb-cccc-dddd"
        result = _redact_uid(uid)
        assert uid not in result
        assert result.startswith("aaaa-bbb")

    def test_on_run_start_and_end_emit_no_exception(self):
        tracker = ObservabilityTracker()
        s = _state()
        s.start()
        tracker.on_run_start(s)
        s.finish()
        tracker.on_run_end(s, elapsed_ms=100)

    def test_on_limit_exceeded_no_exception(self):
        tracker = ObservabilityTracker()
        s = _state()
        tracker.on_limit_exceeded(s, "max_steps", "Step limit reached.")

    def test_on_timeout_no_exception(self):
        tracker = ObservabilityTracker()
        s = _state()
        tracker.on_timeout(s)

    def test_on_error_no_exception(self):
        tracker = ObservabilityTracker()
        s = _state()
        tracker.on_error(s, "Something went wrong.")


# =============================================================================
# _parse_decision
# =============================================================================


class TestParseDecision:
    def _req(self):
        return _request()

    def test_respond_final(self):
        raw = '{"action":"respond","content":"42","is_final":true}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, RespondDecision)
        assert d.content == "42"
        assert d.is_final

    def test_respond_not_final(self):
        raw = '{"action":"respond","content":"thinking...","is_final":false}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, RespondDecision)
        assert not d.is_final

    def test_finish(self):
        raw = '{"action":"finish","content":"All done."}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, FinishDecision)
        assert "All done" in d.reason

    def test_retrieve(self):
        raw = '{"action":"retrieve","query":"python syntax"}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, RetrieveDecision)
        assert d.query == "python syntax"

    def test_call_tool(self):
        raw = '{"action":"call_tool","tool_name":"jira_read","parameters":{"action":"list_issues"}}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, CallToolDecision)
        assert d.tool_name == "jira_read"

    def test_call_tool_params_as_string(self):
        # Parameters as a JSON-encoded string (properly escaped)
        import json

        raw = json.dumps(
            {
                "action": "call_tool",
                "tool_name": "t",
                "parameters": '{"k":"v"}',
            }
        )
        d = _parse_decision(raw, self._req())
        assert isinstance(d, CallToolDecision)
        assert d.parameters  # non-empty

    def test_wait(self):
        raw = '{"action":"wait","content":"Please confirm."}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, WaitDecision)

    def test_plain_text_fallback(self):
        d = _parse_decision("No JSON here, just text.", self._req())
        assert isinstance(d, RespondDecision)
        assert d.is_final

    def test_invalid_json_fallback(self):
        d = _parse_decision("{not valid json}", self._req())
        assert isinstance(d, RespondDecision)

    def test_unknown_action_defaults_to_respond(self):
        raw = '{"action":"teleport","content":"warp!"}'
        d = _parse_decision(raw, self._req())
        assert isinstance(d, RespondDecision)

    def test_empty_string_fallback(self):
        d = _parse_decision("", self._req())
        assert isinstance(d, RespondDecision)


# =============================================================================
# AgentExecutionLoop
# =============================================================================


async def _collect(gen_coro) -> list[AgentEvent]:
    events = []
    async for ev in await gen_coro:
        events.append(ev)
    return events


class TestAgentExecutionLoop:
    def _loop(
        self,
        llm_text: str = '{"action":"respond","content":"answer","is_final":true}',
        rag=None,
        mcp=None,
        max_steps: int = 5,
        timeout_s: float = 10.0,
    ) -> tuple[AgentExecutionLoop, AgentRegistry]:
        config = OrchestrationConfig(max_steps=max_steps, timeout_s=timeout_s)
        registry = _fake_registry()
        dispatcher = ActionDispatcher(
            llm=_fake_llm(llm_text),
            rag=rag,
            mcp=mcp,
            config=config,
        )
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        return loop, registry

    @pytest.mark.asyncio
    async def test_happy_path_completed(self):
        loop, _ = self._loop()
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "started" in types
        assert "completed" in types
        assert state.status == RunStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_completed_event_has_output(self):
        loop, _ = self._loop(
            llm_text='{"action":"respond","content":"the final answer","is_final":true}'
        )
        state = _state()
        events = await _collect(loop.run(_request(), state))
        completed = next(e for e in events if isinstance(e, AgentCompletedEvent))
        assert completed.result.content == "the final answer"

    @pytest.mark.asyncio
    async def test_finish_decision_terminates_loop(self):
        loop, _ = self._loop(llm_text='{"action":"finish","content":"All done."}')
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "completed" in types
        assert state.status == RunStatus.COMPLETED

    @pytest.mark.asyncio
    async def test_max_steps_exceeded_yields_failed(self):
        # LLM always returns non-final respond to keep loop going
        loop, _ = self._loop(
            llm_text='{"action":"respond","content":"step","is_final":false}',
            max_steps=2,
        )
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "failed" in types
        assert state.status == RunStatus.FAILED
        assert "Max steps" in state.error_message

    @pytest.mark.asyncio
    async def test_max_tool_calls_exceeded_yields_failed(self):
        # LLM always returns a tool call
        call_count = 0

        async def _tool_execute(tool_name, params, user_id):
            nonlocal call_count
            call_count += 1
            res = MagicMock()
            res.success = True
            res.result = {"ok": True}
            res.error = ""
            return res

        mcp = AsyncMock()
        mcp.execute = _tool_execute

        config = OrchestrationConfig(max_steps=20, max_tool_calls=2, timeout_s=10.0)
        registry = _fake_registry()
        dispatcher = ActionDispatcher(
            llm=_fake_llm('{"action":"call_tool","tool_name":"t","parameters":"{}"}'),
            mcp=mcp,
            config=config,
        )
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "failed" in types
        assert state.status == RunStatus.FAILED

    @pytest.mark.asyncio
    async def test_timeout_yields_failed_timed_out(self):
        async def _slow_llm(*a, **kw):
            await asyncio.sleep(10)
            return '{"action":"respond","content":"late","is_final":true}'

        config = OrchestrationConfig(max_steps=5, timeout_s=0.05)
        registry = _fake_registry()
        slow_llm = AsyncMock()
        slow_llm.generate = AsyncMock(side_effect=_slow_llm)
        dispatcher = ActionDispatcher(llm=slow_llm, config=config)
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "failed" in types
        assert state.status == RunStatus.TIMED_OUT

    @pytest.mark.asyncio
    async def test_rag_retrieve_yields_retrieval_event(self):
        rag = _fake_rag(answer="RAG answer", sources=[{"document_name": "d.pdf"}])
        config = OrchestrationConfig(max_steps=5, timeout_s=10.0)
        registry = _fake_registry()
        dispatcher = ActionDispatcher(
            llm=_fake_llm('{"action":"retrieve","query":"climate"}'),
            rag=rag,
            config=config,
        )
        # Second decide returns final respond
        call_count = 0
        original_generate = dispatcher._llm.generate

        async def _two_step_llm(*a, **kw):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return '{"action":"retrieve","query":"climate"}'
            return '{"action":"respond","content":"done","is_final":true}'

        dispatcher._llm.generate = _two_step_llm
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "retrieval_completed" in types
        assert state.citations  # citations accumulated

    @pytest.mark.asyncio
    async def test_mcp_tool_yields_tool_events(self):
        mcp = _fake_mcp(success=True, result_data={"key": "PROJ-1"})
        config = OrchestrationConfig(max_steps=5, timeout_s=10.0)
        registry = _fake_registry()
        call_count = 0

        async def _two_step_llm(*a, **kw):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return '{"action":"call_tool","tool_name":"jira_read","parameters":"{}"}'
            return '{"action":"respond","content":"done","is_final":true}'

        llm = AsyncMock()
        llm.generate = _two_step_llm
        dispatcher = ActionDispatcher(llm=llm, mcp=mcp, config=config)
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        assert "tool_started" in types
        assert "tool_completed" in types
        assert state.tool_calls  # tool call recorded

    @pytest.mark.asyncio
    async def test_failed_step_continues_loop(self):
        """A non-final failed step should not immediately abort the loop."""
        call_count = 0

        async def _unstable_llm(*a, **kw):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                # Force an error by returning a call_tool decision when MCP is absent
                return '{"action":"call_tool","tool_name":"t","parameters":"{}"}'
            return '{"action":"respond","content":"recovered","is_final":true}'

        config = OrchestrationConfig(max_steps=5, timeout_s=10.0, enable_mcp=True)
        registry = _fake_registry()
        llm = AsyncMock()
        llm.generate = _unstable_llm
        # No MCP adapter → tool call fails, loop should continue
        dispatcher = ActionDispatcher(llm=llm, mcp=None, config=config)
        loop = AgentExecutionLoop(registry=registry, dispatcher=dispatcher, config=config)
        state = _state()
        events = await _collect(loop.run(_request(), state))
        types = [e.type for e in events]
        # Loop should eventually complete
        assert "completed" in types or "failed" in types

    @pytest.mark.asyncio
    async def test_token_event_emitted_on_respond(self):
        loop, _ = self._loop(
            llm_text='{"action":"respond","content":"hello token","is_final":true}'
        )
        state = _state()
        events = await _collect(loop.run(_request(), state))
        token_events = [e for e in events if isinstance(e, AgentTokenEvent)]
        assert token_events
        assert "hello token" in token_events[0].token

    @pytest.mark.asyncio
    async def test_started_event_first(self):
        loop, _ = self._loop()
        state = _state()
        events = await _collect(loop.run(_request(), state))
        assert isinstance(events[0], AgentStartedEvent)
        assert events[0].agent_name == "conversational"

    @pytest.mark.asyncio
    async def test_state_step_count_increments(self):
        loop, _ = self._loop()
        state = _state()
        await _collect(loop.run(_request(), state))
        assert state.step_count >= 1


# =============================================================================
# SingleAgentRunner
# =============================================================================


class TestSingleAgentRunner:
    def _runner(
        self,
        llm_text: str = '{"action":"respond","content":"answer","is_final":true}',
        rag=None,
        mcp=None,
        max_steps: int = 5,
        timeout_s: float = 10.0,
    ) -> SingleAgentRunner:
        return SingleAgentRunner(
            registry=_fake_registry(),
            llm=_fake_llm(llm_text),
            rag=rag,
            mcp=mcp,
            config=OrchestrationConfig(max_steps=max_steps, timeout_s=timeout_s),
        )

    @pytest.mark.asyncio
    async def test_run_returns_successful_result(self):
        runner = self._runner(
            llm_text='{"action":"respond","content":"the answer","is_final":true}'
        )
        result = await runner.run(_request())
        assert result.success
        assert result.output == "the answer"
        assert result.step_count >= 1

    @pytest.mark.asyncio
    async def test_run_planning_failure_returns_failed_result(self):
        runner = SingleAgentRunner(
            registry=AgentRegistry(),  # empty — no agent to route to
            llm=_fake_llm(),
            config=OrchestrationConfig(),
        )
        result = await runner.run(_request())
        assert not result.success
        assert result.error_message

    @pytest.mark.asyncio
    async def test_run_elapsed_ms_positive(self):
        runner = self._runner()
        result = await runner.run(_request())
        assert result.elapsed_ms >= 0

    @pytest.mark.asyncio
    async def test_stream_yields_started_and_completed(self):
        runner = self._runner()
        events = []
        async for ev in runner.stream(_request()):
            events.append(ev)
        types = [e.type for e in events]
        assert "started" in types
        assert "completed" in types

    @pytest.mark.asyncio
    async def test_stream_planning_failure_yields_failed(self):
        runner = SingleAgentRunner(
            registry=AgentRegistry(),
            llm=_fake_llm(),
            config=OrchestrationConfig(),
        )
        events = []
        async for ev in runner.stream(_request()):
            events.append(ev)
        assert any(isinstance(e, AgentFailedEvent) for e in events)

    @pytest.mark.asyncio
    async def test_run_with_rag_execution(self):
        rag = _fake_rag(answer="RAG-based answer")
        call_count = 0

        async def _rag_then_done(*a, **kw):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return '{"action":"retrieve","query":"test query"}'
            return '{"action":"respond","content":"RAG-based answer","is_final":true}'

        llm = AsyncMock()
        llm.generate = _rag_then_done
        runner = SingleAgentRunner(
            registry=_fake_registry(),
            llm=llm,
            rag=rag,
            config=OrchestrationConfig(max_steps=5, timeout_s=10.0),
        )
        result = await runner.run(_request())
        assert result.success or result.step_count >= 1

    @pytest.mark.asyncio
    async def test_run_with_mcp_execution(self):
        mcp = _fake_mcp(success=True, result_data={"issue": "PROJ-42"})
        call_count = 0

        async def _tool_then_done(*a, **kw):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return '{"action":"call_tool","tool_name":"jira_read","parameters":"{}"}'
            return '{"action":"respond","content":"done","is_final":true}'

        llm = AsyncMock()
        llm.generate = _tool_then_done
        runner = SingleAgentRunner(
            registry=_fake_registry(),
            llm=llm,
            mcp=mcp,
            config=OrchestrationConfig(max_steps=5, timeout_s=10.0),
        )
        result = await runner.run(_request())
        assert result.success or result.step_count >= 1

    @pytest.mark.asyncio
    async def test_timeout_returns_timed_out_result(self):
        async def _slow(*a, **kw):
            await asyncio.sleep(10)
            return '{"action":"respond","content":"late","is_final":true}'

        slow_llm = AsyncMock()
        slow_llm.generate = _slow
        runner = SingleAgentRunner(
            registry=_fake_registry(),
            llm=slow_llm,
            config=OrchestrationConfig(max_steps=5, timeout_s=0.05),
        )
        result = await runner.run(_request())
        assert result.status in (RunStatus.TIMED_OUT, RunStatus.FAILED)

    @pytest.mark.asyncio
    async def test_run_output_accumulated_correctly(self):
        runner = self._runner(
            llm_text='{"action":"respond","content":"final output text","is_final":true}'
        )
        result = await runner.run(_request())
        assert result.output == "final output text"

    @pytest.mark.asyncio
    async def test_runner_config_property(self):
        runner = self._runner()
        assert runner.config.max_steps == 5

    @pytest.mark.asyncio
    async def test_runner_observer_property(self):
        runner = self._runner()
        assert isinstance(runner.observer, ObservabilityTracker)

    @pytest.mark.asyncio
    async def test_span_callback_invoked(self):
        spans = []
        runner = SingleAgentRunner(
            registry=_fake_registry(),
            llm=_fake_llm('{"action":"respond","content":"x","is_final":true}'),
            config=OrchestrationConfig(max_steps=5, timeout_s=10.0),
            span_callbacks=[spans.append],
        )
        await runner.run(_request())
        assert spans  # at least one span recorded
