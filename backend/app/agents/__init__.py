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
from app.agents.image_agent import IMAGE_AGENT_NAME, ImageAgent
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
from app.agents.pdf_agent import PDF_AGENT_NAME, PdfAgent
from app.agents.planner import (
    AgentPlan,
    AgentPlanner,
    AgentPlanStep,
    LimitViolation,
    PlanCounters,
)
from app.agents.rag_agent import RAG_AGENT_NAME, RagAgent
from app.agents.registry import AgentNotFoundError, AgentRegistry
from app.agents.router import AgentRouter, RoutingOutcome
from app.agents.tool_agent import TOOL_AGENT_NAME, ToolAgent
from app.agents.voice_agent import VOICE_AGENT_NAME, VoiceAgent
from app.agents.web_agent import WEB_AGENT_NAME, WebAgent

__all__ = [
    "CHAT_AGENT_NAME",
    "CODE_AGENT_NAME",
    "IMAGE_AGENT_NAME",
    "PDF_AGENT_NAME",
    "RAG_AGENT_NAME",
    "TOOL_AGENT_NAME",
    "VOICE_AGENT_NAME",
    "WEB_AGENT_NAME",
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
    "ChatAgent",
    "CodeAgent",
    "ContextMemory",
    "ContextMessage",
    "FinishDecision",
    "ImageAgent",
    "InferencePath",
    "LLMClient",
    "LLMClientError",
    "LLMServiceAdapter",
    "LimitViolation",
    "LocalGemmaAdapter",
    "ModelRouter",
    "ModelRoutingDecision",
    "PdfAgent",
    "PlanCounters",
    "RagAgent",
    "RespondDecision",
    "RetrieveDecision",
    "RoutingOutcome",
    "ToolAgent",
    "VoiceAgent",
    "WaitDecision",
    "WebAgent",
]

