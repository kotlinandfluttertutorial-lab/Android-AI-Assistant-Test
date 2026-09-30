# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_result.py
# Purpose : Unit tests for AgentResult, supporting types, and AgentUsage.
# ============================================================

"""Unit tests for AgentResult and supporting value types."""

import pytest
from pydantic import ValidationError

from app.agents.models import (
    AgentCitation,
    AgentError,
    AgentResult,
    AgentStatus,
    AgentToolCall,
    AgentUsage,
)


def make_result(**kwargs: object) -> AgentResult:
    defaults: dict[str, object] = {
        "execution_id": "exec-1",
        "request_id": "req-1",
        "agent_name": "test-agent",
        "status": AgentStatus.COMPLETED,
        "content": "Done",
    }
    defaults.update(kwargs)
    return AgentResult(**defaults)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# Construction
# ---------------------------------------------------------------------------


def test_minimal_completed_result() -> None:
    r = make_result()
    assert r.status == AgentStatus.COMPLETED
    assert r.content == "Done"
    assert r.tool_calls == []
    assert r.citations == []
    assert r.attachments == []
    assert r.usage is None
    assert r.error is None
    assert r.next_action is None


def test_failed_result_with_error() -> None:
    r = make_result(
        status=AgentStatus.FAILED,
        content=None,
        error=AgentError(code="LLM_TIMEOUT", message="Timed out"),
    )
    assert r.status == AgentStatus.FAILED
    assert r.error is not None
    assert r.error.code == "LLM_TIMEOUT"


def test_partial_result_is_valid() -> None:
    r = make_result(status=AgentStatus.PARTIAL, content="Partial answer")
    assert r.status == AgentStatus.PARTIAL


def test_cancelled_result_without_content() -> None:
    r = make_result(status=AgentStatus.CANCELLED, content=None)
    assert r.status == AgentStatus.CANCELLED
    assert r.content is None


# ---------------------------------------------------------------------------
# Validation
# ---------------------------------------------------------------------------


def test_non_terminal_status_raises() -> None:
    with pytest.raises(ValidationError):
        make_result(status=AgentStatus.RUNNING)


def test_blank_execution_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_result(execution_id="  ")


def test_blank_request_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_result(request_id="")


def test_blank_agent_name_raises() -> None:
    with pytest.raises(ValidationError):
        make_result(agent_name=" ")


# ---------------------------------------------------------------------------
# Computed properties
# ---------------------------------------------------------------------------


def test_has_content_true() -> None:
    assert make_result(content="something").has_content is True


def test_has_content_false_when_none() -> None:
    assert make_result(status=AgentStatus.FAILED, content=None).has_content is False


def test_has_content_false_when_blank() -> None:
    assert make_result(content="   ").has_content is False


def test_has_citations_false_when_empty() -> None:
    assert make_result().has_citations is False


def test_has_citations_true() -> None:
    r = make_result(
        citations=[AgentCitation(document_id="d1", document_name="Doc A", excerpt="ex", score=0.9)]
    )
    assert r.has_citations is True


def test_has_tool_errors_false_all_succeeded() -> None:
    r = make_result(
        tool_calls=[AgentToolCall(tool_name="github", input="{}", output="ok", failed=False)]
    )
    assert r.has_tool_errors is False


def test_has_tool_errors_true_any_failed() -> None:
    r = make_result(
        status=AgentStatus.PARTIAL,
        tool_calls=[AgentToolCall(tool_name="slack", input="{}", failed=True, error_message="err")],
    )
    assert r.has_tool_errors is True


# ---------------------------------------------------------------------------
# AgentUsage
# ---------------------------------------------------------------------------


def test_agent_usage_total_defaults_to_sum() -> None:
    u = AgentUsage(input_tokens=100, output_tokens=250)
    assert u.total_tokens == 350


def test_agent_usage_total_can_be_overridden() -> None:
    u = AgentUsage(input_tokens=100, output_tokens=250, total_tokens=999)
    assert u.total_tokens == 999


def test_agent_usage_negative_tokens_raise() -> None:
    with pytest.raises(ValidationError):
        AgentUsage(input_tokens=-1, output_tokens=0)


# ---------------------------------------------------------------------------
# Serialisation
# ---------------------------------------------------------------------------


def test_result_json_roundtrip() -> None:
    r = make_result(citations=[AgentCitation(document_id="d1", document_name="Doc", excerpt="e")])
    data = r.model_dump(mode="json")
    reconstructed = AgentResult(**data)
    assert reconstructed == r
