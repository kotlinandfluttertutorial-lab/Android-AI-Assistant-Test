"""Core domain interfaces for the Android AI Assistant backend.

All abstractions in this package follow the Dependency Inversion Principle:
high-level policy modules (services, agents, orchestrators) depend on these
interfaces, never on concrete infrastructure (ChromaDB, PostgreSQL, Redis,
sentence-transformers, MinIO, etc.).

Existing ABCs already live in their own modules — they are re-exported here
so consumers have a single stable import path:

    from app.interfaces import LLMProvider, Agent, MCPToolConnector

New ABCs introduced in this package fill gaps that were missing:
IPlanner, IAgentExecutor, IMemoryStore, IDocumentLoader,
IEmbeddingProvider, IVectorStore, IRetriever, IMCPClient, IToolExecutor.

Import note
-----------
Re-exports of existing ABCs (LLMProvider, Agent, MCPToolConnector) use
lazy ``__getattr__`` to avoid triggering infrastructure-heavy transitive
imports (SQLAlchemy models, pgvector, etc.) at package import time.
Callers that need those types should import them from their canonical
locations; the convenience re-exports here are opt-in.
"""

# ── New ABCs and value objects (no infra imports) ────────────────────────────
# Always import eagerly — these have zero infrastructure dependencies.
from app.interfaces.core import (  # noqa: F401
    IAgentExecutor,
    IDocumentLoader,
    IEmbeddingProvider,
    IMCPClient,
    IMemoryStore,
    IPlanner,
    IRetriever,
    IToolExecutor,
    IVectorStore,
    # Value objects
    DocumentChunk,
    DocumentContent,
    DocumentLoadError,
    EmbeddingError,
    EmbeddingVector,
    MemoryEntry,
    MemoryType,
    RetrievalResult,
    RetrievedChunk,
    StoredChunk,
    ToolExecutionRequest,
    ToolExecutionResult,
    UnsupportedFormatError,
)

# ── Lazy re-exports of existing ABCs ────────────────────────────────────────
# Deferred to avoid triggering infrastructure imports at package load time.
# Access via `from app.interfaces import LLMProvider` works normally.

def __getattr__(name: str):  # noqa: N807
    if name in ("LLMProvider", "LLMRequest", "LLMResponse", "LLMUsage"):
        from app.llm import base as _llm_base
        return getattr(_llm_base, name)
    if name == "Agent":
        from app.agents.base import Agent as _Agent
        return _Agent
    if name in (
        "AgentCapability", "AgentContext", "AgentDecision", "AgentEvent",
        "AgentExecution", "AgentRequest", "AgentResult", "AgentStatus",
    ):
        from app.agents import models as _agent_models
        return getattr(_agent_models, name)
    if name == "MCPToolConnector":
        # Import directly from the module file to bypass services/__init__.py
        # which chains through ai_orchestrator → llm_clients → google.genai
        import importlib.util, os
        _broker_path = os.path.join(
            os.path.dirname(__file__), "..", "services", "mcp_broker.py"
        )
        _spec = importlib.util.spec_from_file_location("_mcp_broker_direct", _broker_path)
        _mod = importlib.util.module_from_spec(_spec)  # type: ignore[arg-type]
        _spec.loader.exec_module(_mod)  # type: ignore[union-attr]
        return _mod.MCPToolConnector
    if name in ("MCPToolResult", "MCPToolSchema"):
        from app.schemas import mcp as _mcp_schemas
        return getattr(_mcp_schemas, name)
    raise AttributeError(f"module 'app.interfaces' has no attribute {name!r}")
