# ============================================================
# Android AI Assistant — Backend
# Module  : agent
# File    : factory.py
# Purpose : AgentServiceFactory — wires RAGPipeline, MCPServer,
#           LLMServiceAdapter, and SingleAgentRunner into one
#           per-request execution unit.
#
# Design rules:
#   - No credentials hardcoded; all config read from Settings.
#   - RAG, MCP, LLM components are individually optional so the
#     runner degrades gracefully when one is unavailable.
#   - One factory instance lives in the module; the FastAPI
#     dependency calls build_runner() per request so each run
#     gets a fresh MCPServer (which holds a per-request DB session).
#   - Atlassian connector is registered only when credentials are
#     present in Settings — never fails at startup if absent.
# ============================================================
"""AgentServiceFactory — assembles a fully-wired :class:`~app.orchestration.SingleAgentRunner`.

This module is the single point where all four pillars are connected:

```
AgentRequest
      │
      ▼
SingleAgentRunner
  ├── LLMServiceAdapter  → LLMService  → GeminiProvider / LocalGemmaProvider
  ├── RAGPipeline        → VectorRetriever → ChromaVectorStore
  │                        → SentenceTransformerEmbeddingProvider
  │                        → LLMService (for answer generation)
  └── MCPServer          → MCPRegistry
                           ├── AtlassianMCPConnector (Jira + Confluence)
                           └── … future connectors
      │
      ▼
OrchestrationResult  (output, citations, tool_calls, spans, …)
```

Usage::

    from app.agent.factory import AgentServiceFactory

    factory = AgentServiceFactory()
    runner = factory.build_runner(db=db, user_id=current_user.sub)
    result = await runner.run(request)
"""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.agents.registry import AgentRegistry
from app.config.settings import get_settings
from app.llm.adapter import LLMServiceAdapter, get_llm_adapter
from app.orchestration.config import OrchestrationConfig
from app.orchestration.runner import SingleAgentRunner

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Minimal "always-handles" agent for the orchestration loop
# ---------------------------------------------------------------------------
# The orchestration loop requires at least one agent in the registry so the
# router can select one.  This shim satisfies that contract without
# duplicating any business logic — the real work happens in ActionDispatcher
# (LLM/RAG/MCP). The agent simply acts as a named target for the router.

def _build_default_registry() -> AgentRegistry:
    """Return a registry with the built-in AI-assistant agent registered."""
    from app.agents.base import Agent
    from app.agents.models import AgentCapability, AgentEvent, AgentExecution, AgentRequest

    class _AIAssistantAgent(Agent):
        """General-purpose AI-assistant agent used by the unified execution flow."""

        @property
        def name(self) -> str:
            return "ai-assistant"

        @property
        def description(self) -> str:
            return (
                "Unified AI assistant — calls LLM, RAG, and MCP as needed "
                "to answer the user's request."
            )

        @property
        def capabilities(self) -> frozenset[AgentCapability]:
            return frozenset(
                {
                    AgentCapability.TEXT_GENERATION,
                    AgentCapability.STREAMING,
                    AgentCapability.DOCUMENT_RETRIEVAL,
                    AgentCapability.TOOL_USE,
                    AgentCapability.MULTI_STEP_REASONING,
                    AgentCapability.MEMORY_ACCESS,
                    AgentCapability.SEMANTIC_SEARCH,
                }
            )

        def execute(
            self,
            request: AgentRequest,
            execution: AgentExecution,
        ):
            # The orchestration loop (AgentExecutionLoop) drives execution;
            # Agent.execute() is not called directly in SingleAgentRunner.
            raise NotImplementedError("Execution is driven by AgentExecutionLoop.")

    registry = AgentRegistry()
    registry.register(_AIAssistantAgent())
    return registry


# ---------------------------------------------------------------------------
# RAG pipeline factory helper
# ---------------------------------------------------------------------------

def _build_rag_pipeline():
    """Return a ready-to-use :class:`~app.rag.RAGPipeline`, or ``None`` on failure.

    Failures are non-fatal: if embedding or ChromaDB are unavailable the
    agent run continues with RAG disabled.
    """
    try:
        from app.embedding import SentenceTransformerEmbeddingProvider
        from app.llm.service import get_llm_service
        from app.rag import RAGPipeline
        from app.rag.retriever import RetrievalConfig, VectorRetriever
        from app.vector import ChromaVectorStore

        retriever = VectorRetriever(
            embedding_provider=SentenceTransformerEmbeddingProvider(),
            vector_store=ChromaVectorStore(),
            llm_service=get_llm_service(),
            config=RetrievalConfig(top_k=5, min_similarity=0.0),
        )
        return RAGPipeline(retriever=retriever)
    except Exception as exc:
        logger.warning(
            "AgentServiceFactory: RAG pipeline unavailable (RAG disabled): %s", exc
        )
        return None


# ---------------------------------------------------------------------------
# MCP server factory helper
# ---------------------------------------------------------------------------

def _build_mcp_server(db: AsyncSession):
    """Return a wired :class:`~app.mcp.MCPServer` with available connectors registered.

    The Atlassian connector is registered only when ATLASSIAN_CLIENT_ID and
    ATLASSIAN_CLIENT_SECRET are present in Settings.  Other connectors can be
    added here in future without touching the router or tests.
    """
    try:
        from app.mcp import MCPServer

        settings = get_settings()

        # Determine the tool allowlist from Settings (None = allow all).
        allowed_tools: set[str] | None = None

        server = MCPServer.create(db=db, allowed_tools=allowed_tools, default_timeout_ms=30_000)

        # ── Atlassian (Jira + Confluence) ────────────────────────────────────
        if settings.ATLASSIAN_CLIENT_ID and settings.ATLASSIAN_CLIENT_SECRET:
            try:
                from app.mcp.connectors.atlassian import (
                    AtlassianMCPConfig,
                    AtlassianMCPConnector,
                )

                atlassian_config = AtlassianMCPConfig(
                    server_url=settings.ATLASSIAN_MCP_SERVER_URL,
                    client_id=settings.ATLASSIAN_CLIENT_ID,
                    client_secret=settings.ATLASSIAN_CLIENT_SECRET,
                    timeout_s=settings.ATLASSIAN_MCP_TIMEOUT_S,
                )
                server.register(AtlassianMCPConnector(config=atlassian_config))
                logger.debug("AgentServiceFactory: Atlassian MCP connector registered.")
            except Exception as exc:
                logger.warning(
                    "AgentServiceFactory: Atlassian connector failed to register: %s", exc
                )
        else:
            logger.debug(
                "AgentServiceFactory: Atlassian credentials absent — connector skipped."
            )

        return server
    except Exception as exc:
        logger.warning(
            "AgentServiceFactory: MCP server unavailable (MCP disabled): %s", exc
        )
        return None


# ---------------------------------------------------------------------------
# AgentServiceFactory
# ---------------------------------------------------------------------------

class AgentServiceFactory:
    """Assembles a :class:`~app.orchestration.SingleAgentRunner` per request.

    The factory holds stateless, re-usable objects (the agent registry and
    the LLM adapter).  Per-request stateful objects (MCPServer with its DB
    session) are built inside :meth:`build_runner`.

    A module-level singleton is exposed as :data:`agent_service_factory`.

    Usage (in a FastAPI router)::

        from app.agent.factory import agent_service_factory

        @router.post("/execute")
        async def execute(
            body: AgentExecuteRequest,
            db: AsyncSession = Depends(get_db),
            current_user: TokenPayload = Depends(get_current_user),
        ) -> AgentExecuteResponse:
            runner = agent_service_factory.build_runner(db=db)
            result = await runner.run(
                AgentRequest(user_id=current_user.sub, input=body.message)
            )
            return AgentExecuteResponse.from_result(result)
    """

    def __init__(self) -> None:
        self._registry: AgentRegistry = _build_default_registry()
        self._llm: LLMServiceAdapter = get_llm_adapter()
        # RAG pipeline is request-independent; build once and share.
        self._rag = _build_rag_pipeline()

    # ── Public API ────────────────────────────────────────────────────────────

    def build_runner(
        self,
        db: AsyncSession,
        *,
        enable_rag: bool = True,
        enable_mcp: bool = True,
        max_steps: int | None = None,
        max_tool_calls: int | None = None,
        timeout_s: float | None = None,
    ) -> SingleAgentRunner:
        """Build a ready-to-use :class:`~app.orchestration.SingleAgentRunner`.

        Args:
            db:             SQLAlchemy async session for the current request.
                            Forwarded to :class:`~app.mcp.MCPServer` for audit
                            logging.
            enable_rag:     Allow the agent to issue RAG retrieval actions.
            enable_mcp:     Allow the agent to call MCP tools.
            max_steps:      Override the default maximum agent steps from
                            ``Settings.MAX_AGENT_STEPS``.
            max_tool_calls: Override the default maximum tool calls from
                            ``Settings.MAX_AGENT_TOOL_CALLS``.
            timeout_s:      Override the default timeout from
                            ``Settings.AGENT_TIMEOUT_SECONDS``.

        Returns:
            A fully wired :class:`~app.orchestration.SingleAgentRunner`.
        """
        settings = get_settings()

        # Build OrchestrationConfig from settings + per-request overrides
        overrides: dict[str, Any] = {"enable_rag": enable_rag, "enable_mcp": enable_mcp}
        if max_steps is not None:
            overrides["max_steps"] = max_steps
        if max_tool_calls is not None:
            overrides["max_tool_calls"] = max_tool_calls
        if timeout_s is not None:
            overrides["timeout_s"] = timeout_s

        config = OrchestrationConfig.from_settings(settings, **overrides)

        # MCP server needs the DB session — built fresh per request
        mcp_server = _build_mcp_server(db) if enable_mcp else None

        return SingleAgentRunner(
            registry=self._registry,
            llm=self._llm,
            rag=self._rag if enable_rag else None,
            mcp=mcp_server,
            config=config,
        )

    @property
    def registry(self) -> AgentRegistry:
        """The shared agent registry."""
        return self._registry

    @property
    def llm_adapter(self) -> LLMServiceAdapter:
        """The shared LLM adapter."""
        return self._llm


# ---------------------------------------------------------------------------
# Module-level singleton
# ---------------------------------------------------------------------------

agent_service_factory = AgentServiceFactory()


def get_agent_service_factory() -> AgentServiceFactory:
    """FastAPI dependency returning the shared :class:`AgentServiceFactory`.

    Usage::

        from app.agent.factory import get_agent_service_factory

        @router.post("/execute")
        async def execute(
            factory: AgentServiceFactory = Depends(get_agent_service_factory),
        ): ...
    """
    return agent_service_factory
