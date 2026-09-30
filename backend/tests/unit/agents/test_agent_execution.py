# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_execution.py
# Purpose : Unit tests for AgentExecution immutable state transitions.
# ============================================================

"""Unit tests for AgentExecution."""

import pytest
from pydantic import ValidationError

from app.agents.models import (
    AgentExecution,
    AgentRequest,
    AgentResult,
    AgentStatus,
    AgentStep,
    AgentUsage,
    FinishDecision,
    RespondDecision,
)


def make_request() -> AgentRequest:
    return AgentRequest(user_id="u1", input="Do something")


def make_execution(**kwargs: object) -> AgentExecution:
    defaults: dict[str, object] = {
        "request": make_request(),
        "agent_name": "test-agent",
    }
    defaults.update(kwargs)
    return AgentExecution(**defaults)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# Construction
# ---------------------------------------------------------------------------


def test_new_execution_is_requested() -> None:
    exec_ = make_execution()
    assert exec_.status == AgentStatus.REQUESTED
    assert exec_.steps == []
    assert exec_.result is None
    assert exec_.is_terminal is False
    assert exec_.is_success is False


def test_blank_agent_name_raises() -> None:
    with pytest.raises(ValidationError):
        make_execution(agent_name="  ")


def test_blank_execution_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_execution(execution_id=" ")


# ---------------------------------------------------------------------------
# with_status — valid transitions
# ---------------------------------------------------------------------------


def test_requested_to_started() -> None:
    exec_ = make_execution().with_status(AgentStatus.STARTED)
    assert exec_.status == AgentStatus.STARTED


def test_full_chain_requested_started_running_completed() -> None:
    exec_ = (
        make_execution()
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_status(AgentStatus.COMPLETED)
    )
    assert exec_.status == AgentStatus.COMPLETED
    assert exec_.is_terminal is True
    assert exec_.is_success is True


def test_terminal_transition_sets_completed_at() -> None:
    exec_ = (
        make_execution()
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_status(AgentStatus.COMPLETED)
    )
    assert exec_.completed_at is not None


def test_non_terminal_transition_does_not_set_completed_at() -> None:
    exec_ = make_execution().with_status(AgentStatus.STARTED)
    assert exec_.completed_at is None


# ---------------------------------------------------------------------------
# with_status — invalid transitions
# ---------------------------------------------------------------------------


def test_requested_to_running_raises() -> None:
    with pytest.raises(ValueError):
        make_execution().with_status(AgentStatus.RUNNING)


def test_from_terminal_raises() -> None:
    terminal = make_execution().with_status(AgentStatus.STARTED).with_status(AgentStatus.FAILED)
    with pytest.raises(ValueError):
        terminal.with_status(AgentStatus.RUNNING)


# ---------------------------------------------------------------------------
# with_step
# ---------------------------------------------------------------------------


def test_with_step_appends() -> None:
    step = AgentStep(
        step_index=0,
        decision=RespondDecision(content="hi"),
    )
    exec_ = (
        make_execution()
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_step(step)
    )
    assert exec_.step_count == 1
    assert exec_.steps[0].step_index == 0


def test_multiple_steps_accumulate() -> None:
    step0 = AgentStep(step_index=0, decision=FinishDecision())
    step1 = AgentStep(step_index=1, decision=FinishDecision())
    exec_ = (
        make_execution()
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_step(step0)
        .with_step(step1)
    )
    assert exec_.step_count == 2


# ---------------------------------------------------------------------------
# with_result
# ---------------------------------------------------------------------------


def test_with_result_sets_result_and_status() -> None:
    request = make_request()
    result = AgentResult(
        execution_id="e1",
        request_id=request.request_id,
        agent_name="test-agent",
        status=AgentStatus.COMPLETED,
        content="Answer",
    )
    exec_ = (
        make_execution(request=request)
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_result(result)
    )
    assert exec_.status == AgentStatus.COMPLETED
    assert exec_.result is not None
    assert exec_.result.content == "Answer"
    assert exec_.is_terminal is True
    assert exec_.is_success is True


# ---------------------------------------------------------------------------
# cancel
# ---------------------------------------------------------------------------


def test_cancel_from_running_moves_to_cancelled() -> None:
    exec_ = (
        make_execution().with_status(AgentStatus.STARTED).with_status(AgentStatus.RUNNING).cancel()
    )
    assert exec_.status == AgentStatus.CANCELLED
    assert exec_.is_terminal is True


def test_cancel_from_requested() -> None:
    exec_ = make_execution().cancel()
    assert exec_.status == AgentStatus.CANCELLED


def test_cancel_from_terminal_returns_unchanged() -> None:
    completed = (
        make_execution()
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_status(AgentStatus.COMPLETED)
    )
    after = completed.cancel()
    assert after.status == AgentStatus.COMPLETED


# ---------------------------------------------------------------------------
# total_tokens
# ---------------------------------------------------------------------------


def test_total_tokens_zero_when_no_result() -> None:
    assert make_execution().total_tokens == 0


def test_total_tokens_from_result() -> None:
    request = make_request()
    result = AgentResult(
        execution_id="e",
        request_id=request.request_id,
        agent_name="test-agent",
        status=AgentStatus.COMPLETED,
        usage=AgentUsage(input_tokens=50, output_tokens=100),
    )
    exec_ = (
        make_execution(request=request)
        .with_status(AgentStatus.STARTED)
        .with_status(AgentStatus.RUNNING)
        .with_result(result)
    )
    assert exec_.total_tokens == 150


# ---------------------------------------------------------------------------
# Immutability
# ---------------------------------------------------------------------------


def test_original_not_mutated_by_with_status() -> None:
    original = make_execution()
    original.with_status(AgentStatus.STARTED)
    assert original.status == AgentStatus.REQUESTED


def test_original_not_mutated_by_with_step() -> None:
    original = make_execution().with_status(AgentStatus.STARTED).with_status(AgentStatus.RUNNING)
    original.with_step(AgentStep(step_index=0, decision=FinishDecision()))
    assert original.step_count == 0
