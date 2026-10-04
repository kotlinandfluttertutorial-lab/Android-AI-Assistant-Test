# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : runner.py
# Purpose : SingleAgentRunner — top-level façade that wires all
#           orchestration components together and exposes a simple
#           run() entry point.
# ============================================================
"""SingleAgentRunner — single entry point for the orchestration layer."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator

from app.agents.models import AgentEvent, AgentRequest
from app.agents.registry import AgentRegistry
from app.orchestration.config import OrchestrationConfig
from app.orchestration.dispatcher import ActionDispatcher, LLMAdapter, MCPAdapter, RAGAdapter
from app.orchestration.loop import AgentExecutionLoop
from app.orchestration.observer import ObservabilityTracker, SpanCallback
from app.orchestration.planner import OrchestrationPlanner
from app.orchestration.state import OrchestrationResult, OrchestrationState

logger = logging.getLogger(__name__)


class SingleAgentRunner:
    """Wires all orchestration components and runs the single-agent loop.

    ## Architecture

    ```
    SingleAgentRunner
        │
        ├── OrchestrationPlanner  (route + plan)
        ├── ActionDispatcher      (LLM / RAG / MCP)
        ├── AgentExecutionLoop    (decide → act → observe)
        └── ObservabilityTracker  (structured logging)
    ```

    ## Usage

    ```python
    runner = SingleAgentRunner(
        registry=registry,
        llm=LLMServiceAdapter(),
        rag=my_rag_pipeline,       # optional
        mcp=my_mcp_server,         # optional
        config=OrchestrationConfig(max_steps=5, timeout_s=60),
    )

    # Streaming — iterate events in real time
    async for event in runner.stream(request):
        handle(event)

    # Blocking — wait for the final result
    result = await runner.run(request)
    print(result.output)
    ```
    """

    def __init__(
        self,
        registry: AgentRegistry,
        llm: LLMAdapter | None = None,
        rag: RAGAdapter | None = None,
        mcp: MCPAdapter | None = None,
        config: OrchestrationConfig | None = None,
        span_callbacks: list[SpanCallback] | None = None,
    ) -> None:
        """
        Args:
            registry:        Agent registry used for routing and planning.
            llm:             LLM adapter (required for respond/decide actions).
            rag:             RAG pipeline adapter (optional; disabled if None).
            mcp:             MCP server adapter (optional; disabled if None).
            config:          Per-run limits and feature flags.
            span_callbacks:  Optional callbacks invoked after each span.
        """
        self._registry = registry
        self._config = config or OrchestrationConfig()

        self._planner = OrchestrationPlanner(registry, self._config)

        self._dispatcher = ActionDispatcher(
            llm=llm,
            rag=rag,
            mcp=mcp,
            config=self._config,
        )

        self._observer = ObservabilityTracker(extra_callbacks=span_callbacks or [])

        self._loop = AgentExecutionLoop(
            registry=registry,
            dispatcher=self._dispatcher,
            observer=self._observer,
            config=self._config,
        )

    # ── Public API ────────────────────────────────────────────────────────────

    async def stream(
        self,
        request: AgentRequest,
    ) -> AsyncIterator[AgentEvent]:
        """Run the orchestration loop and yield AgentEvent values as they occur.

        Args:
            request: Validated :class:`~app.agents.models.AgentRequest`.

        Yields:
            :class:`~app.agents.models.AgentEvent` instances.
        """
        # ── Plan ──────────────────────────────────────────────────────────────
        planning = self._planner.plan(request)
        if not planning.success or planning.plan is None:
            import uuid as _uuid

            from app.agents.models import (
                AgentError,
                AgentFailedEvent,
                AgentResult,
                AgentStatus,
            )

            result = AgentResult(
                execution_id=str(_uuid.uuid4()),
                request_id=request.request_id,
                agent_name="orchestration",
                status=AgentStatus.FAILED,
                error=AgentError(
                    code="PLANNING_FAILED",
                    message=planning.error,
                ),
            )
            yield AgentFailedEvent(result=result)
            return

        # ── Create state ───────────────────────────────────────────────────────
        state = OrchestrationState(
            request_id=request.request_id,
            user_id=request.user_id,
            agent_name=planning.agent_name,
        )

        # ── Run loop ───────────────────────────────────────────────────────────
        async for event in await self._loop.run(request, state):
            yield event

    async def run(
        self,
        request: AgentRequest,
    ) -> OrchestrationResult:
        """Run the orchestration loop to completion and return the final result.

        Consumes all yielded events internally.  Use :meth:`stream` when
        real-time streaming to a WebSocket or UI is required.

        Args:
            request: Validated :class:`~app.agents.models.AgentRequest`.

        Returns:
            :class:`~app.orchestration.state.OrchestrationResult`.
        """
        import time

        start_ms = int(time.monotonic() * 1000)

        # Plan first so we have the agent name for the state object
        planning = self._planner.plan(request)
        if not planning.success or planning.plan is None:
            import uuid as _uuid

            from app.orchestration.state import RunStatus

            return OrchestrationResult(
                run_id=str(_uuid.uuid4()),
                request_id=request.request_id,
                agent_name="orchestration",
                status=RunStatus.FAILED,
                output="",
                error_message=planning.error,
            )

        state = OrchestrationState(
            request_id=request.request_id,
            user_id=request.user_id,
            agent_name=planning.agent_name,
        )

        async for _ in await self._loop.run(request, state):
            pass  # consume events; state is updated in-place

        elapsed_ms = int(time.monotonic() * 1000) - start_ms
        return OrchestrationResult.from_state(state, elapsed_ms)

    # ── Properties ────────────────────────────────────────────────────────────

    @property
    def config(self) -> OrchestrationConfig:
        return self._config

    @property
    def observer(self) -> ObservabilityTracker:
        return self._observer
