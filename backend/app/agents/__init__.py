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
from app.agents.chat_agent import CHAT_AGENT_NAME, ChatAgent
from app.agents.code_agent import CODE_AGENT_NAME, CodeAgent
from app.agents.pdf_agent import PDF_AGENT_NAME, PdfAgent
from app.agents.rag_agent import RAG_AGENT_NAME, RagAgent
from app.agents.tool_agent import TOOL_AGENT_NAME, ToolAgent
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
    "Agent",
    "CHAT_AGENT_NAME",
    "ChatAgent",
    "CODE_AGENT_NAME",
    "CodeAgent",
    "PDF_AGENT_NAME",
    "PdfAgent",
    "RAG_AGENT_NAME",
    "RagAgent",
    "TOOL_AGENT_NAME",
    "ToolAgent",
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
    "AgentNotFoundError",
    "AgentOrchestrator",
    "AgentPlan",
    "AgentPlanStep",
    "AgentPlanner",
    "AgentRegistry",
    "AgentRequest",
    "AgentResult",
    "AgentRouter",
    "AgentStartedEvent",
    "AgentStatus",
    "AgentStatusChangedEvent",
    "AgentStep",
    "AgentToolCall",
    "AgentUsage",
    "CallToolDecision",
    "ContextMemory",
    "ContextMessage",
    "FinishDecision",
    "InferencePath",
    "LLMClient",
    "LLMClientError",
    "LLMServiceAdapter",
    "LimitViolation",
    "LocalGemmaAdapter",
    "ModelRouter",
    "ModelRoutingDecision",
    "PlanCounters",
    "RespondDecision",
    "RetrieveDecision",
    "RoutingOutcome",
    "WaitDecision",
]

