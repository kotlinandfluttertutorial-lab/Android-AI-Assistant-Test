# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : __init__.py
# Purpose : Agent core package — foundational types and interfaces.
#
# Architecture Layer : Agent Core (new layer, Phase 1)
#
# Public surface:
#   from app.agents import (
#       AgentCapability, AgentStatus,
#       AgentRequest, AgentContext, AgentResult,
#       AgentDecision, AgentExecution, AgentEvent,
#       Agent,
#   )
# ============================================================

from app.agents.base import Agent
from app.agents.models import (
    AgentAttachment,
    AgentCapability,
    AgentCitation,
    AgentContext,
    AgentDecision,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentNextAction,
    AgentRequest,
    AgentResult,
    AgentStatus,
    AgentStep,
    AgentToolCall,
    AgentUsage,
    ContextMemory,
    ContextMessage,
)

__all__ = [
    "Agent",
    "AgentAttachment",
    "AgentCapability",
    "AgentCitation",
    "AgentContext",
    "AgentDecision",
    "AgentError",
    "AgentEvent",
    "AgentExecution",
    "AgentNextAction",
    "AgentRequest",
    "AgentResult",
    "AgentStatus",
    "AgentStep",
    "AgentToolCall",
    "AgentUsage",
    "ContextMemory",
    "ContextMessage",
]
