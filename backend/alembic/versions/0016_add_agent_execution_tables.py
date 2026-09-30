"""Add agent execution, session, step, and tool execution tables.

Phase 10 — Production Hardening.

Creates four new tables to persist agent execution audit trails:

  agent_sessions    — one row per user "chat session" that may span multiple
                      agent executions. Ties together the conversation and the
                      sequence of executions triggered by that session.

  agent_executions  — one row per AgentOrchestrator.execute() call. Records
                      the agent name, mode, status, and wall-clock duration.
                      Tokens and error codes are stored; raw content is NOT
                      stored (privacy by design).

  agent_steps       — one row per step in a multi-agent plan (each handoff
                      creates a new step). Records the agent, decision type,
                      duration, and token counts for that step.

  tool_executions   — one row per MCP tool invocation. Records the tool name,
                      status, duration, and (on failure) the error code.
                      Tool output is NOT stored (may contain sensitive data).

Design decisions:
- Raw LLM tokens (prompt text, response text) are NEVER stored.
- All tables reference ``users.id`` and ``conversations.id`` via FK with
  ``ON DELETE CASCADE`` so user data deletion propagates automatically.
- All timestamps are ``TIMESTAMPTZ`` (UTC).
- ``status`` columns use VARCHAR(32) enum-style values to avoid Postgres enum
  migration fragility.

Revision ID: 0016_add_agent_execution_tables
Revises:     0015_make_chroma_id_nullable
"""

from __future__ import annotations

from typing import Union, Sequence

import sqlalchemy as sa
from alembic import op

# ── Revision chain ─────────────────────────────────────────────────────────────

revision: str = "0016_add_agent_execution_tables"
down_revision: Union[str, None] = "0015_make_chroma_id_nullable"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # ── agent_sessions ────────────────────────────────────────────────────────
    op.create_table(
        "agent_sessions",
        sa.Column(
            "id",
            sa.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("user_id", sa.UUID(as_uuid=True), nullable=False),
        sa.Column("conversation_id", sa.UUID(as_uuid=True), nullable=True),
        # agent_mode: "AUTO" | "CHAT" | "CODE" | "RESEARCH" | "DOCUMENT" | "IMAGE" | "VOICE" | "LOCAL"
        sa.Column("agent_mode", sa.String(32), nullable=False, server_default="AUTO"),
        # status: "ACTIVE" | "COMPLETED" | "FAILED" | "CANCELLED"
        sa.Column("status", sa.String(32), nullable=False, server_default="ACTIVE"),
        sa.Column(
            "started_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.Column("ended_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["conversation_id"], ["conversations.id"], ondelete="SET NULL"),
        comment="One row per agent interaction session. Does not store message content.",
    )
    op.create_index("ix_agent_sessions_user_id", "agent_sessions", ["user_id"])
    op.create_index("ix_agent_sessions_conversation_id", "agent_sessions", ["conversation_id"])
    op.create_index("ix_agent_sessions_started_at", "agent_sessions", ["started_at"])

    # ── agent_executions ──────────────────────────────────────────────────────
    op.create_table(
        "agent_executions",
        sa.Column(
            "id",
            sa.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("session_id", sa.UUID(as_uuid=True), nullable=True),
        sa.Column("user_id", sa.UUID(as_uuid=True), nullable=False),
        sa.Column("conversation_id", sa.UUID(as_uuid=True), nullable=True),
        # request_id: matches AgentRequest.requestId on the Android side
        sa.Column("request_id", sa.String(64), nullable=False),
        sa.Column("agent_name", sa.String(64), nullable=False),
        sa.Column("agent_mode", sa.String(32), nullable=False, server_default="AUTO"),
        # model: LLM provider identifier e.g. "gemini", "on_device"
        sa.Column("model", sa.String(64), nullable=True),
        # status: "STARTED" | "RUNNING" | "COMPLETED" | "PARTIAL" | "FAILED" | "CANCELLED"
        sa.Column("status", sa.String(32), nullable=False, server_default="STARTED"),
        sa.Column("duration_ms", sa.Integer, nullable=True),
        sa.Column("input_tokens", sa.Integer, nullable=True),
        sa.Column("output_tokens", sa.Integer, nullable=True),
        # error_code: machine-readable code e.g. "ROUTING_FAILED", "TIMEOUT"
        sa.Column("error_code", sa.String(64), nullable=True),
        # NOTE: raw prompt/response content is intentionally NOT stored here
        sa.Column("step_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("handoff_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("tool_call_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column(
            "started_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.Column("completed_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["session_id"], ["agent_sessions.id"], ondelete="SET NULL"),
        sa.ForeignKeyConstraint(["conversation_id"], ["conversations.id"], ondelete="SET NULL"),
        comment=(
            "One row per DefaultAgentOrchestrator.execute() call. "
            "Tokens counted but not content stored (privacy by design)."
        ),
    )
    op.create_index("ix_agent_executions_user_id", "agent_executions", ["user_id"])
    op.create_index("ix_agent_executions_session_id", "agent_executions", ["session_id"])
    op.create_index("ix_agent_executions_request_id", "agent_executions", ["request_id"])
    op.create_index("ix_agent_executions_agent_name", "agent_executions", ["agent_name"])
    op.create_index("ix_agent_executions_status", "agent_executions", ["status"])
    op.create_index("ix_agent_executions_started_at", "agent_executions", ["started_at"])

    # ── agent_steps ───────────────────────────────────────────────────────────
    op.create_table(
        "agent_steps",
        sa.Column(
            "id",
            sa.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("execution_id", sa.UUID(as_uuid=True), nullable=False),
        sa.Column("user_id", sa.UUID(as_uuid=True), nullable=False),
        sa.Column("step_index", sa.Integer, nullable=False),
        sa.Column("agent_name", sa.String(64), nullable=False),
        # decision_type: "Respond" | "CallTool" | "Retrieve" | "Wait" | "Finish"
        sa.Column("decision_type", sa.String(32), nullable=True),
        # status: "COMPLETED" | "FAILED" | "CANCELLED"
        sa.Column("status", sa.String(32), nullable=False, server_default="COMPLETED"),
        sa.Column("duration_ms", sa.Integer, nullable=True),
        sa.Column("tokens_used", sa.Integer, nullable=False, server_default="0"),
        # NOTE: outcome (tool output, retrieved chunks) is NOT stored
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.ForeignKeyConstraint(["execution_id"], ["agent_executions.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        comment=(
            "One row per reasoning/tool-call step within an agent execution. "
            "Step outcome content is NOT stored."
        ),
    )
    op.create_index("ix_agent_steps_execution_id", "agent_steps", ["execution_id"])
    op.create_index("ix_agent_steps_user_id", "agent_steps", ["user_id"])

    # ── tool_executions ───────────────────────────────────────────────────────
    op.create_table(
        "tool_executions",
        sa.Column(
            "id",
            sa.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("execution_id", sa.UUID(as_uuid=True), nullable=True),
        sa.Column("user_id", sa.UUID(as_uuid=True), nullable=False),
        sa.Column("tool_name", sa.String(64), nullable=False),
        sa.Column("agent_name", sa.String(64), nullable=True),
        # status: "COMPLETED" | "FAILED" | "TIMEOUT" | "CONFIRMATION_REQUIRED"
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("duration_ms", sa.Integer, nullable=True),
        # error_code: e.g. "TOOL_TIMEOUT", "PERMISSION_DENIED", "TOOL_NOT_FOUND"
        sa.Column("error_code", sa.String(64), nullable=True),
        # NOTE: tool parameters and output are NOT stored (may contain secrets/PII)
        sa.Column("requires_confirmation", sa.Boolean, nullable=False, server_default="false"),
        sa.Column("confirmed", sa.Boolean, nullable=False, server_default="false"),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            nullable=False,
            server_default=sa.text("NOW()"),
        ),
        sa.ForeignKeyConstraint(["execution_id"], ["agent_executions.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["user_id"], ["users.id"], ondelete="CASCADE"),
        comment=(
            "One row per MCP tool invocation. Tool parameters and output are NOT stored (security)."
        ),
    )
    op.create_index("ix_tool_executions_execution_id", "tool_executions", ["execution_id"])
    op.create_index("ix_tool_executions_user_id", "tool_executions", ["user_id"])
    op.create_index("ix_tool_executions_tool_name", "tool_executions", ["tool_name"])
    op.create_index("ix_tool_executions_status", "tool_executions", ["status"])
    op.create_index("ix_tool_executions_created_at", "tool_executions", ["created_at"])


def downgrade() -> None:
    op.drop_table("tool_executions")
    op.drop_table("agent_steps")
    op.drop_table("agent_executions")
    op.drop_table("agent_sessions")
