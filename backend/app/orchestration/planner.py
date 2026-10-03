# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : planner.py
# Purpose : OrchestrationPlanner — understands the request and builds a
#           validated single-agent plan using the existing AgentPlanner
#           and AgentRouter without modifying them.
# ============================================================
"""OrchestrationPlanner — wraps AgentRouter + AgentPlanner for single-agent runs."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from app.agents.models import AgentRequest
from app.agents.planner import AgentPlan, AgentPlanner
from app.agents.registry import AgentRegistry
from app.agents.router import AgentRouter, RoutingOutcome
from app.orchestration.config import OrchestrationConfig

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PlanningResult:
    """Output of :class:`OrchestrationPlanner.plan`.

    Attributes:
        plan:       The built :class:`~app.agents.planner.AgentPlan`.
        agent_name: Name of the selected agent.
        success:    False when planning failed.
        error:      Human-safe error message; empty on success.
    """

    plan: AgentPlan | None
    agent_name: str
    success: bool
    error: str = ""

    @classmethod
    def failed(cls, reason: str) -> "PlanningResult":
        return cls(plan=None, agent_name="", success=False, error=reason)


class OrchestrationPlanner:
    """Understands an AgentRequest and builds a validated single-agent plan.

    Wraps the existing :class:`~app.agents.router.AgentRouter` and
    :class:`~app.agents.planner.AgentPlanner` without modifying them.

    For the single-agent orchestration layer, planning always produces a
    **single-step plan** targeting the agent selected by the router.
    Multi-step plans remain the responsibility of
    :class:`~app.agents.orchestrator.AgentOrchestrator`.

    Usage::

        planner = OrchestrationPlanner(registry, config)
        result = planner.plan(request)
        if result.success:
            print(f"Agent: {result.agent_name}, plan: {result.plan}")
    """

    def __init__(
        self,
        registry: AgentRegistry,
        config: OrchestrationConfig | None = None,
    ) -> None:
        self._registry = registry
        self._config = config or OrchestrationConfig()
        self._router = AgentRouter()
        self._planner = AgentPlanner()

    def plan(self, request: AgentRequest) -> PlanningResult:
        """Select an agent and build a validated single-step plan.

        Args:
            request: Validated :class:`~app.agents.models.AgentRequest`.

        Returns:
            :class:`PlanningResult` — never raises.
        """
        # ── 1. Route ─────────────────────────────────────────────────────────
        routing: RoutingOutcome = self._router.route(request, self._registry)
        if routing.failed or routing.agent is None:
            logger.warning(
                "OrchestrationPlanner: routing failed request_id=%s reason=%r",
                request.request_id,
                routing.reason,
            )
            return PlanningResult.failed(f"No suitable agent: {routing.reason}")

        agent = routing.agent
        logger.info(
            "OrchestrationPlanner: routed request_id=%s → agent=%r reason=%r",
            request.request_id,
            agent.name,
            routing.reason,
        )

        # ── 2. Build plan ─────────────────────────────────────────────────────
        # Override timeout from config
        effective_request = request.model_copy(
            update={"timeout_ms": int(self._config.timeout_s * 1000)}
        )
        try:
            plan = self._planner.build_plan(effective_request, agent, self._registry)
        except Exception as exc:
            logger.warning(
                "OrchestrationPlanner: plan build failed request_id=%s: %s",
                request.request_id,
                exc,
            )
            return PlanningResult.failed(f"Plan build failed: {exc}")

        logger.debug(
            "OrchestrationPlanner: plan built agent=%r steps=%d timeout_ms=%d",
            agent.name,
            len(plan.steps),
            plan.timeout_ms,
        )
        return PlanningResult(plan=plan, agent_name=agent.name, success=True)
