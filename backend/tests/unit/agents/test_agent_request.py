# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_request.py
# Purpose : Unit tests for AgentRequest construction and validation.
# ============================================================

"""Unit tests for AgentRequest."""

import pytest
from pydantic import ValidationError

from app.agents.models import AgentCapability, AgentRequest


def make_request(**kwargs: object) -> AgentRequest:
    defaults = {"user_id": "user-1", "input": "Hello"}
    defaults.update(kwargs)  # type: ignore[arg-type]
    return AgentRequest(**defaults)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


def test_minimal_request_constructs() -> None:
    req = make_request()
    assert req.user_id == "user-1"
    assert req.input == "Hello"
    assert req.request_id  # auto-generated UUID
    assert req.conversation_id is None
    assert req.max_steps == 10
    assert req.timeout_ms == 60_000
    assert req.streaming_enabled is True
    assert req.capabilities == []
    assert req.metadata == {}


def test_all_optional_fields() -> None:
    req = make_request(
        request_id="req-abc",
        conversation_id="conv-1",
        provider="gemini",
        capabilities=[AgentCapability.CODE_ANALYSIS],
        max_steps=5,
        timeout_ms=30_000,
        streaming_enabled=False,
        metadata={"screen": "CodeScreen"},
    )
    assert req.request_id == "req-abc"
    assert req.conversation_id == "conv-1"
    assert req.provider == "gemini"
    assert req.capabilities == [AgentCapability.CODE_ANALYSIS]
    assert req.max_steps == 5
    assert req.timeout_ms == 30_000
    assert req.streaming_enabled is False
    assert req.metadata["screen"] == "CodeScreen"


def test_two_requests_with_different_ids_are_not_equal() -> None:
    a = make_request(request_id="id-1")
    b = make_request(request_id="id-2")
    assert a != b


def test_request_is_immutable() -> None:
    req = make_request()
    with pytest.raises(ValidationError):
        req.input = "changed"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# Validation: input
# ---------------------------------------------------------------------------


def test_blank_input_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(input="   ")


def test_empty_input_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(input="")


# ---------------------------------------------------------------------------
# Validation: user_id
# ---------------------------------------------------------------------------


def test_blank_user_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(user_id="  ")


def test_empty_user_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(user_id="")


# ---------------------------------------------------------------------------
# Validation: max_steps
# ---------------------------------------------------------------------------


def test_max_steps_one_is_valid() -> None:
    req = make_request(max_steps=1)
    assert req.max_steps == 1


def test_max_steps_fifty_is_valid() -> None:
    req = make_request(max_steps=50)
    assert req.max_steps == 50


def test_max_steps_zero_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(max_steps=0)


def test_max_steps_51_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(max_steps=51)


# ---------------------------------------------------------------------------
# Validation: timeout_ms
# ---------------------------------------------------------------------------


def test_zero_timeout_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(timeout_ms=0)


def test_negative_timeout_raises() -> None:
    with pytest.raises(ValidationError):
        make_request(timeout_ms=-1)


def test_positive_timeout_valid() -> None:
    req = make_request(timeout_ms=1)
    assert req.timeout_ms == 1


# ---------------------------------------------------------------------------
# Serialisation round-trip
# ---------------------------------------------------------------------------


def test_json_roundtrip() -> None:
    req = make_request(
        provider="openai",
        capabilities=[AgentCapability.TEXT_GENERATION],
        metadata={"key": "val"},
    )
    data = req.model_dump(mode="json")
    reconstructed = AgentRequest(**data)
    assert reconstructed == req


def test_model_dump_contains_expected_keys() -> None:
    req = make_request()
    data = req.model_dump()
    for key in ("request_id", "user_id", "input", "max_steps", "timeout_ms"):
        assert key in data
