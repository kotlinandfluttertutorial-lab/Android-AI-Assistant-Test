# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_decision.py
# Purpose : Unit tests for AgentDecision factory methods and variants.
# ============================================================

"""Unit tests for AgentDecision and its concrete subtypes."""

from app.agents.models import (
    AgentDecision,
    CallToolDecision,
    FinishDecision,
    RespondDecision,
    RetrieveDecision,
    WaitDecision,
)


def test_respond_factory_defaults() -> None:
    d = AgentDecision.respond("Hello")
    assert isinstance(d, RespondDecision)
    assert d.content == "Hello"
    assert d.is_final is True
    assert d.streaming is False
    assert d.type == "respond"


def test_respond_factory_all_fields() -> None:
    d = AgentDecision.respond("...", is_final=False, streaming=True)
    assert d.is_final is False
    assert d.streaming is True


def test_call_tool_factory_defaults() -> None:
    d = AgentDecision.call_tool("github", '{"title":"Bug"}')
    assert isinstance(d, CallToolDecision)
    assert d.tool_name == "github"
    assert d.parameters == '{"title":"Bug"}'
    assert d.requires_confirmation is False
    assert d.rationale is None
    assert d.type == "call_tool"


def test_call_tool_factory_with_confirmation() -> None:
    d = AgentDecision.call_tool(
        "gmail", "{}", requires_confirmation=True, rationale="Sending email"
    )
    assert d.requires_confirmation is True
    assert d.rationale == "Sending email"


def test_retrieve_factory_defaults() -> None:
    d = AgentDecision.retrieve("What is RAG?")
    assert isinstance(d, RetrieveDecision)
    assert d.query == "What is RAG?"
    assert d.document_ids == []
    assert d.top_k == 5
    assert d.min_score == 0.4
    assert d.type == "retrieve"


def test_retrieve_factory_custom_params() -> None:
    d = AgentDecision.retrieve("arch", document_ids=["doc-1"], top_k=3, min_score=0.6)
    assert d.document_ids == ["doc-1"]
    assert d.top_k == 3
    assert d.min_score == 0.6


def test_wait_factory_defaults() -> None:
    d = AgentDecision.wait("Awaiting approval")
    assert isinstance(d, WaitDecision)
    assert d.reason == "Awaiting approval"
    assert d.wait_for_type == "user_confirmation"
    assert d.payload is None
    assert d.type == "wait"


def test_finish_factory_defaults() -> None:
    d = AgentDecision.finish()
    assert isinstance(d, FinishDecision)
    assert d.reason == "Task completed."
    assert d.type == "finish"


def test_finish_custom_reason() -> None:
    d = AgentDecision.finish("Context limit reached")
    assert d.reason == "Context limit reached"


def test_decision_json_roundtrip() -> None:
    original = AgentDecision.respond("Hello", is_final=True)
    data = original.model_dump(mode="json")
    reconstructed = RespondDecision(**data)
    assert reconstructed == original
