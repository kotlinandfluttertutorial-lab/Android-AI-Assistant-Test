"""Single-agent orchestration layer.

Provides a controlled decision loop on top of the existing agent infrastructure
in ``app.agents``:

    Understand Request
          ↓
      Create Plan         ← OrchestrationPlanner
          ↓
     Select Action        ← AgentDecision dispatched by ActionDispatcher
          ↓
        Execute           ← LLM / RAG / MCP via injected adapters
          ↓
     Observe Result       ← ObservabilityTracker records span
          ↓
    Continue / Finish     ← AgentExecutionLoop drives the cycle

Public surface
--------------
OrchestrationConfig       – per-run limits and feature flags.
OrchestrationState        – mutable live state of one execution run.
OrchestrationResult       – immutable final outcome.
ExecutionSpan             – one observe record (action + outcome + timing).
OrchestrationPlanner      – wraps AgentPlanner; selects agent, builds plan.
ActionDispatcher          – dispatches a single AgentDecision to LLM/RAG/MCP.
AgentExecutionLoop        – drives the decide → execute → observe cycle.
ObservabilityTracker      – records spans, emits structured log events.
SingleAgentRunner         – façade: wire everything together and run.

Safety & memory
---------------
AgentSafetyGuard          – sanitises tool/RAG output, redacts sensitive args,
                            enforces user-authorisation and tool-permission checks.
ConversationMemoryBuffer  – in-process short-term ring buffer of recent turns.
ConversationTurn          – one turn stored in ConversationMemoryBuffer.
AgentMemoryAdapter        – async wrapper around MemoryService for long-term
                            memory storage and context-injection retrieval.
"""

from app.orchestration.config import OrchestrationConfig
from app.orchestration.dispatcher import ActionDispatcher
from app.orchestration.loop import AgentExecutionLoop
from app.orchestration.memory import (
    AgentMemoryAdapter,
    ConversationMemoryBuffer,
    ConversationTurn,
)
from app.orchestration.observer import ObservabilityTracker
from app.orchestration.planner import OrchestrationPlanner
from app.orchestration.runner import SingleAgentRunner
from app.orchestration.safety import AgentSafetyGuard
from app.orchestration.state import (
    ExecutionSpan,
    OrchestrationResult,
    OrchestrationState,
)

__all__ = [
    "ActionDispatcher",
    "AgentExecutionLoop",
    "AgentMemoryAdapter",
    "AgentSafetyGuard",
    "ConversationMemoryBuffer",
    "ConversationTurn",
    "ExecutionSpan",
    "ObservabilityTracker",
    "OrchestrationConfig",
    "OrchestrationPlanner",
    "OrchestrationResult",
    "OrchestrationState",
    "SingleAgentRunner",
]
