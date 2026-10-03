# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : config.py
# Purpose : OrchestrationConfig — per-execution limits and feature flags.
#
# Design rules:
#   - Zero infrastructure imports; pure Python dataclass.
#   - Validated at construction; no silent bad-defaults.
#   - All limits have hard caps mirroring the existing AgentPlanner
#     constants so the two layers agree on safety boundaries.
# ============================================================
"""OrchestrationConfig — validated configuration for one agent run."""

from __future__ import annotations

from dataclasses import dataclass, field

# Hard caps — kept in sync with app.agents.planner constants
_HARD_MAX_STEPS: int = 50
_HARD_MAX_TOOL_CALLS: int = 100
_HARD_MAX_TOKENS_PER_STEP: int = 8_192
_HARD_TIMEOUT_S: float = 300.0


@dataclass
class OrchestrationConfig:
    """Configuration for a single :class:`~app.orchestration.runner.SingleAgentRunner` run.

    Attributes:
        max_steps:         Maximum decide→act→observe cycles before aborting.
        max_tool_calls:    Maximum total MCP tool invocations across all steps.
        timeout_s:         Wall-clock execution timeout in seconds.
        max_tokens_per_step: Soft cap on LLM output tokens per step.
        enable_rag:        Whether RAG retrieval actions are allowed.
        enable_mcp:        Whether MCP tool-call actions are allowed.
        enable_streaming:  Whether token events are emitted during LLM calls.
        min_similarity:    Minimum RAG retrieval similarity threshold (0–1).
        rag_top_k:         Maximum chunks to retrieve per RAG action.
        llm_temperature:   Sampling temperature forwarded to LLM calls.
    """

    # ── Limits ────────────────────────────────────────────────────────────────
    max_steps: int = 10
    max_tool_calls: int = 20
    timeout_s: float = 120.0
    max_tokens_per_step: int = 2_048

    # ── Feature flags ─────────────────────────────────────────────────────────
    enable_rag: bool = True
    enable_mcp: bool = True
    enable_streaming: bool = True

    # ── RAG options ───────────────────────────────────────────────────────────
    min_similarity: float = 0.0
    rag_top_k: int = 5

    # ── LLM options ───────────────────────────────────────────────────────────
    llm_temperature: float | None = None

    def __post_init__(self) -> None:
        if not (1 <= self.max_steps <= _HARD_MAX_STEPS):
            raise ValueError(
                f"OrchestrationConfig.max_steps must be 1–{_HARD_MAX_STEPS}, got {self.max_steps}."
            )
        if not (0 <= self.max_tool_calls <= _HARD_MAX_TOOL_CALLS):
            raise ValueError(
                f"OrchestrationConfig.max_tool_calls must be 0–{_HARD_MAX_TOOL_CALLS}, "
                f"got {self.max_tool_calls}."
            )
        if not (0 < self.timeout_s <= _HARD_TIMEOUT_S):
            raise ValueError(
                f"OrchestrationConfig.timeout_s must be (0, {_HARD_TIMEOUT_S}], "
                f"got {self.timeout_s}."
            )
        if not (1 <= self.max_tokens_per_step <= _HARD_MAX_TOKENS_PER_STEP):
            raise ValueError(
                f"OrchestrationConfig.max_tokens_per_step must be 1–{_HARD_MAX_TOKENS_PER_STEP}, "
                f"got {self.max_tokens_per_step}."
            )
        if not (0.0 <= self.min_similarity <= 1.0):
            raise ValueError(
                f"OrchestrationConfig.min_similarity must be 0.0–1.0, "
                f"got {self.min_similarity}."
            )
        if self.rag_top_k < 1:
            raise ValueError(
                f"OrchestrationConfig.rag_top_k must be ≥ 1, got {self.rag_top_k}."
            )
        if self.llm_temperature is not None and not (0.0 <= self.llm_temperature <= 2.0):
            raise ValueError(
                f"OrchestrationConfig.llm_temperature must be 0.0–2.0, "
                f"got {self.llm_temperature}."
            )
