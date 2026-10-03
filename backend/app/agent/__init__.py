"""Agent integration module.

Provides the unified assembly layer that wires RAGPipeline, MCPServer,
LLMServiceAdapter, and SingleAgentRunner into a single execution unit.

Public surface
--------------
AgentServiceFactory       – per-request runner builder.
agent_service_factory     – module-level singleton.
get_agent_service_factory – FastAPI dependency.
"""

from app.agent.factory import (
    AgentServiceFactory,
    agent_service_factory,
    get_agent_service_factory,
)

__all__ = [
    "AgentServiceFactory",
    "agent_service_factory",
    "get_agent_service_factory",
]
