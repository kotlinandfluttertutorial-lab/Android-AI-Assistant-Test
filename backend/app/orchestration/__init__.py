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
OrchestrationConfig     – per-run limits and feature flags.
OrchestrationState      – mutable live state of one execution run.
OrchestrationResult     – immutable final outcome.
ExecutionSpan           – one observe record (action + outcome + timing).
OrchestrationPlanner    – wraps AgentPlanner; selects agent, builds plan.
ActionDispatcher        – dispatches a single AgentDecision to LLM/RAG/MCP.
AgentExecutionLoop      – drives the decide → execute → observe cycle.
ObservabilityTracker    – records spans, emits structured log events.
SingleAgentRunner       – façade: wire everything together and run.
"""

from app.orchestration.config import OrchestrationConfig  # noqa: F401
from app.orchestration.state import ExecutionSpan, OrchestrationResult, OrchestrationState  # noqa: F401
from app.orchestration.planner import OrchestrationPlanner  # noqa: F401
from app.orchestration.dispatcher import ActionDispatcher  # noqa: F401
from app.orchestration.loop import AgentExecutionLoop  # noqa: F401
from app.orchestration.observer import ObservabilityTracker  # noqa: F401
from app.orchestration.runner import SingleAgentRunner  # noqa: F401

__all__ = [
    "ActionDispatcher",
    "AgentExecutionLoop",
    "ExecutionSpan",
    "ObservabilityConfig",
    "ObservabilityTracker",
    "OrchestrationConfig",
    "OrchestrationPlanner",
    "OrchestrationResult",
    "OrchestrationState",
    "SingleAgentRunner",
]
