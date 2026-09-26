# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : planner.py
# Purpose : Builds and validates multi-step execution plans with hard limits.
#
# Architecture Layer : Agent Core (Phase 2)
# Pattern Used       : Value object + planner
#
# Key Concepts:
#   - Plans are immutable dataclasses validated at construction
#   - Hard limits prevent runaway autonomous behavior
#   - DefaultAgentPlanner is deterministic — no LLM calls
#   - Multi-step plans declared via metadata["plan_steps"]
#
# Dependencies: app.agents.registry, app.agents.models
# ============================================================

"""AgentPlanner — builds and validates multi-step execution plans."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from app.agents.models import AgentRequest

if TYPE_CHECKING:
    from app.agents.base import Agent
    from app.agents.registry import AgentRegistry

logger = logging.getLogger(__name__)

# Metadata key used to declare a multi-step plan
METADATA_KEY_PLAN_STEPS = "plan_steps"

# Hard limits — plans exceeding these are rejected at construction
HARD_LIMIT_MAX_STEPS: int = 50
HARD_LIMIT_MAX_HANDOFFS: int = 10
HARD_LIMIT_MAX_TOOL_CALLS: int = 100

# Defaults
DEFAULT_MAX_STEPS: int = 10
DEFAULT_MAX_HANDOFFS: int = 3
DEFAULT_MAX_TOOL_CALLS: int = 20
DEFAULT_TIMEOUT_MS: int = 120_000


@dataclass(frozen=True)
class AgentPlanStep:
    """A single step in an :class:`AgentPlan`."""

    agent_name: str
    input_transform: str = ""

    def __post_init__(self) -> None:
        if not self.agent_name.strip():
            raise ValueError("AgentPlanStep.agent_name must not be blank.")


@dataclass(frozen=True)
class AgentPlan:
    """Immutable validated execution plan."""

    steps: list[AgentPlanStep]
    request_id: str
    max_steps: int = DEFAULT_MAX_STEPS
    max_handoffs: int = DEFAULT_MAX_HANDOFFS
    max_tool_calls: int = DEFAULT_MAX_TOOL_CALLS
    timeout_ms: int = DEFAULT_TIMEOUT_MS

    def __post_init__(self) -> None:
        if not self.steps:
            raise ValueError("AgentPlan must contain at least one step.")
        if not self.request_id.strip():
            raise ValueError("AgentPlan.request_id must not be blank.")
        if not (1 <= self.max_steps <= HARD_LIMIT_MAX_STEPS):
            raise ValueError(
                f"AgentPlan.max_steps must be in 1..{HARD_LIMIT_MAX_STEPS}, was {self.max_steps}."
            )
        if not (0 <= self.max_handoffs <= HARD_LIMIT_MAX_HANDOFFS):
            raise ValueError(
                f"AgentPlan.max_handoffs must be in 0..{HARD_LIMIT_MAX_HANDOFFS}, "
                f"was {self.max_handoffs}."
            )
        if not (0 <= self.max_tool_calls <= HARD_LIMIT_MAX_TOOL_CALLS):
            raise ValueError(
                f"AgentPlan.max_tool_calls must be in 0..{HARD_LIMIT_MAX_TOOL_CALLS}, "
                f"was {self.max_tool_calls}."
            )
        if self.timeout_ms <= 0:
            raise ValueError(f"AgentPlan.timeout_ms must be positive, was {self.timeout_ms}.")
        required_handoffs = len(self.steps) - 1
        if required_handoffs > self.max_handoffs:
            raise ValueError(
                f"AgentPlan has {len(self.steps)} steps requiring {required_handoffs} handoffs, "
                f"but max_handoffs={self.max_handoffs}."
            )

    @property
    def is_multi_step(self) -> bool:
        return len(self.steps) > 1

    @property
    def handoff_count(self) -> int:
        return max(len(self.steps) - 1, 0)


@dataclass
class PlanCounters:
    """Mutable execution counters checked against plan limits."""

    steps_taken: int = 0
    handoffs_done: int = 0
    tool_calls_made: int = 0
    start_ms: float = field(default_factory=lambda: time.monotonic() * 1000)

    @property
    def elapsed_ms(self) -> float:
        return time.monotonic() * 1000 - self.start_ms


@dataclass(frozen=True)
class LimitViolation:
    """Describes a runtime limit violation."""

    kind: str          # "max_steps" | "max_handoffs" | "max_tool_calls" | "timeout"
    current: int | float
    limit: int | float
    message: str


class AgentPlanner:
    """Builds AgentPlans and checks runtime limits.

    Deterministic — no LLM calls.
    """

    def build_plan(
        self,
        request: AgentRequest,
        selected_agent: Agent,
        registry: AgentRegistry,
    ) -> AgentPlan:
        """Build a validated plan for *request*.

        Multi-step: if ``request.metadata["plan_steps"]`` is a
        comma-separated list of agent names, build a chained plan.
        Otherwise build a single-step plan targeting *selected_agent*.

        Args:
            request:        The request to plan for.
            selected_agent: Agent chosen by AgentRouter for the first step.
            registry:       Used to validate multi-step agent names.

        Returns:
            Validated :class:`AgentPlan`.

        Raises:
            AgentNotFoundError:  A named step agent is not registered.
            ValueError:          Plan violates hard limits.
        """
        metadata = request.metadata or {}
        plan_steps_hint = metadata.get(METADATA_KEY_PLAN_STEPS, "").strip()

        if plan_steps_hint:
            agent_names = [n.strip() for n in plan_steps_hint.split(",") if n.strip()]
            if not agent_names:
                raise ValueError(
                    f"metadata['{METADATA_KEY_PLAN_STEPS}'] must contain at least one agent name."
                )
            # Validate all names exist
            for name in agent_names:
                registry.get(name)  # raises AgentNotFoundError if missing

            steps = [AgentPlanStep(agent_name=n) for n in agent_names]
            return AgentPlan(
                steps=steps,
                request_id=request.request_id,
                max_steps=request.max_steps,
                max_handoffs=len(agent_names) - 1,
                max_tool_calls=DEFAULT_MAX_TOOL_CALLS,
                timeout_ms=request.timeout_ms,
            )

        # Single-step plan
        return AgentPlan(
            steps=[AgentPlanStep(agent_name=selected_agent.name)],
            request_id=request.request_id,
            max_steps=request.max_steps,
            max_handoffs=0,
            max_tool_calls=DEFAULT_MAX_TOOL_CALLS,
            timeout_ms=request.timeout_ms,
        )

    def check_limits(
        self,
        plan: AgentPlan,
        counters: PlanCounters,
    ) -> LimitViolation | None:
        """Return the first limit violation for the *next* step, or None.

        Called by AgentOrchestrator before executing each step.
        """
        if counters.steps_taken >= plan.max_steps:
            return LimitViolation(
                kind="max_steps",
                current=counters.steps_taken,
                limit=plan.max_steps,
                message=f"Max steps ({plan.max_steps}) exceeded (taken={counters.steps_taken}).",
            )
        if counters.handoffs_done > plan.max_handoffs:
            return LimitViolation(
                kind="max_handoffs",
                current=counters.handoffs_done,
                limit=plan.max_handoffs,
                message=(
                    f"Max handoffs ({plan.max_handoffs}) exceeded "
                    f"(done={counters.handoffs_done})."
                ),
            )
        if counters.tool_calls_made >= plan.max_tool_calls:
            return LimitViolation(
                kind="max_tool_calls",
                current=counters.tool_calls_made,
                limit=plan.max_tool_calls,
                message=(
                    f"Max tool calls ({plan.max_tool_calls}) exceeded "
                    f"(made={counters.tool_calls_made})."
                ),
            )
        elapsed = counters.elapsed_ms
        if elapsed >= plan.timeout_ms:
            return LimitViolation(
                kind="timeout",
                current=round(elapsed),
                limit=plan.timeout_ms,
                message=f"Timeout ({plan.timeout_ms} ms) exceeded (elapsed={round(elapsed)} ms).",
            )
        return None
