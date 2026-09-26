# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : __init__.py
# Purpose : Agent core package — public re-export surface.
#
# Architecture Layer : Agent Core (Phase 1 + Phase 2)
# ============================================================

# Phase 1 — models + base interface
from app.agents.base import Agent
from app.agents.llm_client import (
    LLMClient,
    LLMClientError,
    LLMServiceAdapter,
    LocalGemmaAdapter,
)
from app.agents.model_router import InferencePath, ModelRouter, ModelRoutingDecision
from app.agents.models import (
    AgentAttachment,
    AgentCapability,
    AgentCitation,
    AgentCompletedEvent,
    AgentContext,
    AgentDecision,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentNextAction,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentStep,
    AgentToolCall,
    AgentUsage,
    CallToolDecision,
    ContextMemory,
    ContextMessage,
    FinishDecision,
    RespondDecision,
    RetrieveDecision,
    WaitDecision,
)
from app.agents.orchestrator import AgentOrchestrator
from app.agents.planner import (
    AgentPlan,
    AgentPlanner,
    AgentPlanStep,
    LimitViolation,
    PlanCounters,
)

# Phase 2 — registry, router, planner, orchestrator, llm_client, model_router
from app.agents.registry import AgentNotFoundError, AgentRegistry
from app.agents.router import AgentRouter, RoutingOutcome

__all__ = [
    # Phase 1
    "Agent",
    "AgentAttachment",
    "AgentCapability",
    "AgentCitation",
    "AgentCompletedEvent",
    "AgentContext",
    "AgentDecision",
    "AgentError",
    "AgentEvent",
    "AgentExecution",
    "AgentFailedEvent",
    "AgentNextAction",
    "AgentRequest",
    "AgentResult",
    "AgentStartedEvent",
    "AgentStatus",
    "AgentStatusChangedEvent",
    "AgentStep",
    "AgentToolCall",
    "AgentUsage",
    "CallToolDecision",
    "FinishDecision",
    "RespondDecision",
    "RetrieveDecision",
    "WaitDecision",
    "ContextMemory",
    "ContextMessage",
    # Phase 2
    "AgentNotFoundError",
    "AgentRegistry",
    "AgentRouter",
    "RoutingOutcome",
    "AgentPlan",
    "AgentPlanStep",
    "AgentPlanner",
    "LimitViolation",
    "PlanCounters",
    "AgentOrchestrator",
    "LLMClient",
    "LLMClientError",
    "LLMServiceAdapter",
    "LocalGemmaAdapter",
    "InferencePath",
    "ModelRouter",
    "ModelRoutingDecision",
]

