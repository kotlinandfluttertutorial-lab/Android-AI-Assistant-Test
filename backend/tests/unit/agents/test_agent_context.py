# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_context.py
# Purpose : Unit tests for AgentContext construction and helper methods.
# ============================================================

"""Unit tests for AgentContext."""

import pytest
from pydantic import ValidationError

from app.agents.models import AgentContext, ContextMemory, ContextMessage


def make_context(**kwargs: object) -> AgentContext:
    defaults: dict[str, object] = {"user_id": "u1"}
    defaults.update(kwargs)
    return AgentContext(**defaults)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# Construction
# ---------------------------------------------------------------------------


def test_minimal_context_defaults() -> None:
    ctx = make_context()
    assert ctx.user_id == "u1"
    assert ctx.conversation_id is None
    assert ctx.conversation_history == []
    assert ctx.memories == []
    assert ctx.persona_system_prompt is None
    assert ctx.rag_document_ids == []
    assert ctx.available_tools == []
    assert ctx.is_privacy_mode is False
    assert ctx.is_offline is False
    assert ctx.extra_context == {}


def test_blank_user_id_raises() -> None:
    with pytest.raises(ValidationError):
        make_context(user_id="  ")


def test_context_is_immutable() -> None:
    ctx = make_context()
    with pytest.raises(ValidationError):
        ctx.user_id = "other"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# with_memories
# ---------------------------------------------------------------------------


def test_with_memories_appends() -> None:
    initial = make_context(memories=[ContextMemory(content="first", relevance_score=0.9)])
    updated = initial.with_memories([ContextMemory(content="second", relevance_score=0.8)])
    assert len(updated.memories) == 2
    assert updated.memories[0].content == "first"
    assert updated.memories[1].content == "second"


def test_with_memories_does_not_mutate_original() -> None:
    ctx = make_context()
    ctx.with_memories([ContextMemory(content="x", relevance_score=1.0)])
    assert ctx.memories == []


def test_with_memories_on_empty() -> None:
    ctx = make_context()
    updated = ctx.with_memories([ContextMemory(content="fact", relevance_score=0.7)])
    assert len(updated.memories) == 1
    assert updated.memories[0].content == "fact"


# ---------------------------------------------------------------------------
# with_tools
# ---------------------------------------------------------------------------


def test_with_tools_adds_tools() -> None:
    ctx = make_context(available_tools=["github"])
    updated = ctx.with_tools(["github", "gmail"])
    assert len(updated.available_tools) == 2
    assert "github" in updated.available_tools
    assert "gmail" in updated.available_tools


def test_with_tools_deduplicates() -> None:
    ctx = make_context(available_tools=["github"])
    updated = ctx.with_tools(["github"])
    assert updated.available_tools == ["github"]


def test_with_tools_does_not_mutate_original() -> None:
    ctx = make_context()
    ctx.with_tools(["slack"])
    assert ctx.available_tools == []


# ---------------------------------------------------------------------------
# ContextMemory validation
# ---------------------------------------------------------------------------


def test_context_memory_score_out_of_range_raises() -> None:
    with pytest.raises(ValidationError):
        ContextMemory(content="x", relevance_score=1.1)

    with pytest.raises(ValidationError):
        ContextMemory(content="x", relevance_score=-0.1)


def test_context_message_stores_role_and_content() -> None:
    msg = ContextMessage(role="user", content="Hello")
    assert msg.role == "user"
    assert msg.content == "Hello"


# ---------------------------------------------------------------------------
# Serialisation round-trip
# ---------------------------------------------------------------------------


def test_json_roundtrip() -> None:
    ctx = make_context(
        conversation_id="conv-1",
        memories=[ContextMemory(content="pref", relevance_score=0.9)],
        available_tools=["github"],
    )
    data = ctx.model_dump(mode="json")
    reconstructed = AgentContext(**data)
    assert reconstructed == ctx
