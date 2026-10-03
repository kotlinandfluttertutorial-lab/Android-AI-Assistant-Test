# ============================================================
# Android AI Assistant — Backend
# Module  : api/agent
# File    : router.py
# Purpose : Unified AI-assistant execution endpoints.
#
# Routes
# ------
#   POST /api/v1/agent/execute   — blocking; returns OrchestrationResult JSON
#   POST /api/v1/agent/stream    — Server-Sent Events; one event per AgentEvent
#   GET  /api/v1/agent/tools     — list available MCP tools
#
# Security pipeline (same as chat router)
# ----------------------------------------
# 1. JWT authentication — enforced at router level via get_current_user.
# 2. Prompt injection detection — rejects requests before any LLM call.
# 3. OrchestrationConfig limits — enforced by AgentExecutionLoop
#    (max_steps, max_tool_calls, timeout_s).
# 4. Safety guards — ActionDispatcher sanitises all tool/RAG output.
# 5. Sensitive args are never logged (AgentSafetyGuard.redact_sensitive_args).
#
# Design rules
# ------------
# - Direct chat (POST /chat/message, POST /api/v1/chat) is UNCHANGED.
# - This router adds a NEW path — it does not modify existing routes.
# - All errors surface structured JSON; no raw tracebacks returned.
# - Streaming uses text/event-stream (SSE) with one JSON object per line.
# ============================================================
"""Unified Agent execution router — Agent + RAG + MCP + LLM."""

from __future__ import annotations

import json
import logging
import uuid
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.factory import AgentServiceFactory, get_agent_service_factory
from app.agents.models import AgentRequest
from app.database import get_db
from app.mcp import MCPServer
from app.orchestration.state import RunStatus
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.safety_service import InjectionDetector, PromptInjectionError

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(
    prefix="/api/v1/agent",
    tags=["agent"],
    dependencies=[Depends(get_current_user)],
)

# ---------------------------------------------------------------------------
# Shared injection detector (stateless — one instance is fine)
# ---------------------------------------------------------------------------

_injection_detector = InjectionDetector()


def get_injection_detector() -> InjectionDetector:
    return _injection_detector


# ---------------------------------------------------------------------------
# Request / Response schemas
# ---------------------------------------------------------------------------


class AgentExecuteRequest(BaseModel):
    """Body for POST /api/v1/agent/execute and POST /api/v1/agent/stream.

    Attributes:
        message:         Natural-language instruction or question.
        conversation_id: Optional conversation UUID for continuity.
        document_ids:    Restrict RAG to these document UUIDs (empty = all docs).
        tools:           MCP tool names to make available (empty = all allowed).
        enable_rag:      Whether the agent may issue RAG retrieval actions.
        enable_mcp:      Whether the agent may call MCP tools.
        max_steps:       Override the default maximum agent steps (1–50).
        timeout_ms:      Wall-clock timeout for the entire run in milliseconds.
    """

    message: str = Field(min_length=1, description="User instruction or question.")
    conversation_id: str | None = Field(
        default=None, description="Existing conversation UUID."
    )
    document_ids: list[str] = Field(
        default_factory=list,
        description="Restrict RAG retrieval to these document UUIDs.",
    )
    tools: list[str] = Field(
        default_factory=list,
        description="MCP tool names to allow for this run.",
    )
    enable_rag: bool = Field(default=True, description="Allow RAG retrieval actions.")
    enable_mcp: bool = Field(default=True, description="Allow MCP tool calls.")
    max_steps: int | None = Field(
        default=None,
        ge=1,
        le=50,
        description="Override max agent reasoning steps (uses Settings default if omitted).",
    )
    timeout_ms: int | None = Field(
        default=None,
        gt=0,
        description="Override run timeout in ms (uses Settings default if omitted).",
    )


class SourceReference(BaseModel):
    """One RAG citation included in the response."""

    document_id: str
    document_name: str
    excerpt: str
    page_number: int | None = None
    score: float = 0.0


class ToolCallRecord(BaseModel):
    """Record of one MCP tool call made during the run."""

    tool_name: str
    input: str
    output: str | None = None
    failed: bool = False
    error_message: str | None = None


class ExecutionStepRecord(BaseModel):
    """Summary of one decide→act→observe cycle."""

    step_index: int
    action_type: str
    input_summary: str = ""
    output_summary: str = ""
    duration_ms: int = 0
    tokens_used: int = 0
    success: bool = True
    error_message: str = ""


class AgentExecuteResponse(BaseModel):
    """Response for POST /api/v1/agent/execute.

    Attributes:
        run_id:         Unique ID for this agent run.
        request_id:     Echo of the request_id from AgentRequest.
        agent_name:     Name of the agent that handled the request.
        status:         Final run status (COMPLETED | FAILED | TIMED_OUT | …).
        success:        True when status == COMPLETED.
        output:         Full generated answer / final response text.
        sources:        RAG citations included in the response.
        tool_calls:     MCP tool calls made during the run.
        steps:          Execution step summaries (one per reasoning cycle).
        total_tokens:   Total LLM tokens consumed.
        step_count:     Number of reasoning cycles executed.
        elapsed_ms:     Wall-clock time for the full run.
        error_message:  Human-safe error; empty on success.
    """

    run_id: str
    request_id: str
    agent_name: str
    status: str
    success: bool
    output: str
    sources: list[SourceReference] = Field(default_factory=list)
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    steps: list[ExecutionStepRecord] = Field(default_factory=list)
    total_tokens: int = 0
    step_count: int = 0
    elapsed_ms: int = 0
    error_message: str = ""

    @classmethod
    def from_orchestration_result(cls, result: Any) -> AgentExecuteResponse:
        """Convert an :class:`~app.orchestration.state.OrchestrationResult`."""
        sources = [
            SourceReference(
                document_id=s.get("document_id", ""),
                document_name=s.get("document_name", s.get("source", "")),
                excerpt=s.get("excerpt", ""),
                page_number=s.get("page_number"),
                score=float(s.get("relevance_score", s.get("score", 0.0))),
            )
            for s in (result.citations or [])
        ]
        tool_calls = [
            ToolCallRecord(
                tool_name=t.get("tool_name", ""),
                input=t.get("input", ""),
                output=t.get("output"),
                failed=bool(t.get("failed", False)),
                error_message=t.get("error_message"),
            )
            for t in (result.tool_calls or [])
        ]
        steps = [
            ExecutionStepRecord(
                step_index=sp.step_index,
                action_type=sp.action_type,
                input_summary=sp.input_summary,
                output_summary=sp.output_summary,
                duration_ms=sp.duration_ms,
                tokens_used=sp.tokens_used,
                success=sp.success,
                error_message=sp.error_message,
            )
            for sp in (result.spans or [])
        ]
        return cls(
            run_id=result.run_id,
            request_id=result.request_id,
            agent_name=result.agent_name,
            status=result.status.value if hasattr(result.status, "value") else str(result.status),
            success=getattr(result, "success", result.status == RunStatus.COMPLETED),
            output=result.output,
            sources=sources,
            tool_calls=tool_calls,
            steps=steps,
            total_tokens=result.total_tokens,
            step_count=result.step_count,
            elapsed_ms=result.elapsed_ms,
            error_message=result.error_message,
        )


class ToolInfo(BaseModel):
    """Descriptor for one available MCP tool."""

    name: str
    display_name: str = ""
    description: str = ""
    category: str = ""


class AgentToolsResponse(BaseModel):
    """Response for GET /api/v1/agent/tools."""

    tools: list[ToolInfo]
    count: int


# ---------------------------------------------------------------------------
# Shared helper: build AgentRequest from body + user
# ---------------------------------------------------------------------------


def _make_agent_request(
    body: AgentExecuteRequest,
    current_user: TokenPayload,
    request_id: str,
) -> AgentRequest:
    """Translate the HTTP request body into an :class:`~app.agents.models.AgentRequest`."""
    from app.agents.models import AgentCapability, AgentContext

    # Forward document_ids and tools via AgentContext
    context = AgentContext(
        user_id=current_user.sub,
        conversation_id=body.conversation_id,
        rag_document_ids=body.document_ids,
        available_tools=body.tools,
    )

    # Include RAG and tool-use capabilities so the router can match
    capabilities = [AgentCapability.TEXT_GENERATION]
    if body.enable_rag:
        capabilities.append(AgentCapability.DOCUMENT_RETRIEVAL)
    if body.enable_mcp:
        capabilities.append(AgentCapability.TOOL_USE)

    timeout_ms = body.timeout_ms or 120_000

    return AgentRequest(
        request_id=request_id,
        user_id=current_user.sub,
        input=body.message,
        conversation_id=body.conversation_id,
        context=context,
        capabilities=capabilities,
        streaming_enabled=False,
        timeout_ms=timeout_ms,
    )


# ---------------------------------------------------------------------------
# POST /api/v1/agent/execute   — blocking run
# ---------------------------------------------------------------------------


@router.post(
    "/execute",
    response_model=AgentExecuteResponse,
    summary="Execute the AI agent (blocking)",
    description=(
        "Run the unified AI assistant — Agent + RAG + MCP + LLM — and wait for "
        "the final result.  The agent decides autonomously which tools and "
        "sources to consult based on the user's message.\n\n"
        "**Example use case**: Compare Jira AI-123 with the MCP architecture "
        "document — agent calls Jira MCP, retrieves the architecture doc "
        "via RAG, then calls LLM to synthesise a comparison."
    ),
    status_code=status.HTTP_200_OK,
)
async def agent_execute(
    request: Request,
    body: AgentExecuteRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(get_injection_detector),
    factory: AgentServiceFactory = Depends(get_agent_service_factory),
) -> AgentExecuteResponse:
    """Run the unified AI agent and return the complete result.

    Pipeline:
    1. Injection detection — blocks prompt-injection payloads.
    2. Build AgentRequest with context (document_ids, tools).
    3. Assemble SingleAgentRunner (LLM + RAG + MCP) via AgentServiceFactory.
    4. Run to completion.
    5. Return AgentExecuteResponse with output, sources, tool_calls, steps.
    """
    request_id = uuid.uuid4().hex[:16]

    # ── 1. Injection detection ────────────────────────────────────────────
    try:
        await detector.check_input(text=body.message, user_id=current_user.sub, db=db)
    except PromptInjectionError:
        logger.warning(
            "Agent execute: prompt injection blocked user=%s request_id=%s",
            current_user.sub,
            request_id,
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
        )

    # ── 2. Build AgentRequest ─────────────────────────────────────────────
    agent_request = _make_agent_request(body, current_user, request_id)

    # ── 3. Assemble runner ────────────────────────────────────────────────
    timeout_s = (body.timeout_ms / 1000.0) if body.timeout_ms else None
    runner = factory.build_runner(
        db=db,
        enable_rag=body.enable_rag,
        enable_mcp=body.enable_mcp,
        max_steps=body.max_steps,
        timeout_s=timeout_s,
    )

    # ── 4. Execute ────────────────────────────────────────────────────────
    try:
        result = await runner.run(agent_request)
    except Exception:
        logger.exception(
            "Agent execute: unexpected error user=%s request_id=%s",
            current_user.sub,
            request_id,
        )
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail={"error": {"code": "AGENT_EXECUTION_ERROR"}},
        )

    # ── 5. Map to response ────────────────────────────────────────────────
    logger.info(
        "Agent execute complete: user=%s request_id=%s run_id=%s status=%s "
        "steps=%d tokens=%d elapsed_ms=%d",
        current_user.sub,
        request_id,
        result.run_id,
        result.status,
        result.step_count,
        result.total_tokens,
        result.elapsed_ms,
    )
    return AgentExecuteResponse.from_orchestration_result(result)


# ---------------------------------------------------------------------------
# POST /api/v1/agent/stream    — Server-Sent Events
# ---------------------------------------------------------------------------

_SSE_CONTENT_TYPE = "text/event-stream"
_SSE_HEADERS = {
    "Cache-Control": "no-cache",
    "X-Accel-Buffering": "no",   # disable nginx buffering for SSE
}


def _event(event_type: str, data: dict[str, Any]) -> str:
    """Format one SSE frame."""
    return f"event: {event_type}\ndata: {json.dumps(data)}\n\n"


@router.post(
    "/stream",
    summary="Execute the AI agent (Server-Sent Events)",
    description=(
        "Stream agent execution events in real time using Server-Sent Events "
        "(text/event-stream).  Each event is a JSON object with a ``type`` field.\n\n"
        "Event types:\n"
        "- ``started``           — run has begun\n"
        "- ``thinking``          — agent is reasoning\n"
        "- ``token``             — one LLM output token\n"
        "- ``tool_started``      — MCP tool call beginning\n"
        "- ``tool_completed``    — MCP tool call succeeded\n"
        "- ``tool_failed``       — MCP tool call failed\n"
        "- ``retrieval_completed`` — RAG retrieval complete\n"
        "- ``completed``         — final result (includes output, sources, tool_calls)\n"
        "- ``failed``            — agent failed (includes error)\n"
    ),
    status_code=status.HTTP_200_OK,
    response_class=StreamingResponse,
)
async def agent_stream(
    request: Request,
    body: AgentExecuteRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    detector: InjectionDetector = Depends(get_injection_detector),
    factory: AgentServiceFactory = Depends(get_agent_service_factory),
) -> StreamingResponse:
    """Stream agent events via Server-Sent Events.

    Clients connect and receive a sequence of JSON event frames until
    a ``completed`` or ``failed`` terminal event is emitted.
    """
    request_id = uuid.uuid4().hex[:16]

    # Injection detection (sync check before opening the stream)
    try:
        await detector.check_input(text=body.message, user_id=current_user.sub, db=db)
    except PromptInjectionError:
        logger.warning(
            "Agent stream: prompt injection blocked user=%s request_id=%s",
            current_user.sub,
            request_id,
        )
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"error": {"code": "PROMPT_INJECTION_DETECTED"}},
        )

    agent_request = _make_agent_request(body, current_user, request_id)
    # Enable streaming events so token events are emitted
    agent_request = agent_request.model_copy(update={"streaming_enabled": True})

    timeout_s = (body.timeout_ms / 1000.0) if body.timeout_ms else None
    runner = factory.build_runner(
        db=db,
        enable_rag=body.enable_rag,
        enable_mcp=body.enable_mcp,
        max_steps=body.max_steps,
        timeout_s=timeout_s,
    )

    async def _generate():
        """Async generator that converts AgentEvent objects to SSE frames."""
        from app.agents.models import (
            AgentCompletedEvent,
            AgentFailedEvent,
            AgentRetrievalCompletedEvent,
            AgentStartedEvent,
            AgentThinkingEvent,
            AgentTokenEvent,
            AgentToolCompletedEvent,
            AgentToolFailedEvent,
            AgentToolStartedEvent,
        )

        try:
            async for event in runner.stream(agent_request):
                if isinstance(event, AgentStartedEvent):
                    yield _event("started", {
                        "request_id": request_id,
                        "agent_name": getattr(event, "agent_name", ""),
                    })

                elif isinstance(event, AgentThinkingEvent):
                    yield _event("thinking", {
                        "text": getattr(event, "thinking_text", ""),
                    })

                elif isinstance(event, AgentTokenEvent):
                    yield _event("token", {
                        "token": getattr(event, "token", ""),
                    })

                elif isinstance(event, AgentToolStartedEvent):
                    yield _event("tool_started", {
                        "tool_name": getattr(event, "tool_name", ""),
                        "parameters": getattr(event, "parameters", ""),
                    })

                elif isinstance(event, AgentToolCompletedEvent):
                    yield _event("tool_completed", {
                        "tool_name": getattr(event, "tool_name", ""),
                        "output": getattr(event, "output", ""),
                    })

                elif isinstance(event, AgentToolFailedEvent):
                    yield _event("tool_failed", {
                        "tool_name": getattr(event, "tool_name", ""),
                        "error": getattr(event, "error_message", ""),
                    })

                elif isinstance(event, AgentRetrievalCompletedEvent):
                    yield _event("retrieval_completed", {
                        "chunk_count": getattr(event, "chunk_count", 0),
                        "has_sources": getattr(event, "has_sources", False),
                    })

                elif isinstance(event, AgentCompletedEvent):
                    result = event.result
                    response = AgentExecuteResponse.from_orchestration_result(
                        _OrchestrationResultAdapter(result, request_id)
                    )
                    yield _event("completed", response.model_dump())

                elif isinstance(event, AgentFailedEvent):
                    result = event.result
                    error = getattr(result, "error", None)
                    yield _event("failed", {
                        "request_id": request_id,
                        "error_code": error.code if error else "AGENT_FAILED",
                        "error_message": error.message if error else "Agent execution failed.",
                    })

        except Exception as exc:
            logger.exception(
                "Agent stream: error in event generator user=%s request_id=%s: %s",
                current_user.sub,
                request_id,
                exc,
            )
            yield _event("failed", {
                "request_id": request_id,
                "error_code": "STREAM_ERROR",
                "error_message": "An unexpected error occurred during streaming.",
            })

    return StreamingResponse(
        content=_generate(),
        media_type=_SSE_CONTENT_TYPE,
        headers=_SSE_HEADERS,
    )


# ---------------------------------------------------------------------------
# GET /api/v1/agent/tools   — discover available MCP tools
# ---------------------------------------------------------------------------


@router.get(
    "/tools",
    response_model=AgentToolsResponse,
    summary="List available MCP tools",
    description=(
        "Returns the list of MCP tools available for this deployment.  "
        "Tool availability depends on which connectors are registered "
        "(e.g. Atlassian is only listed when ATLASSIAN_CLIENT_ID is configured)."
    ),
)
async def list_tools(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    factory: AgentServiceFactory = Depends(get_agent_service_factory),
) -> AgentToolsResponse:
    """List available MCP tools for the authenticated user."""
    try:
        from app.config.settings import get_settings
        s = get_settings()

        server = MCPServer.create(db=db)

        if s.ATLASSIAN_CLIENT_ID and s.ATLASSIAN_CLIENT_SECRET:
            try:
                from app.mcp.connectors.atlassian import (
                    AtlassianMCPConfig,
                    AtlassianMCPConnector,
                )
                server.register(AtlassianMCPConnector(config=AtlassianMCPConfig(
                    server_url=s.ATLASSIAN_MCP_SERVER_URL,
                    client_id=s.ATLASSIAN_CLIENT_ID,
                    client_secret=s.ATLASSIAN_CLIENT_SECRET,
                    timeout_s=s.ATLASSIAN_MCP_TIMEOUT_S,
                )))
            except Exception:
                pass

        schemas = server.discover()
        tools = [
            ToolInfo(
                name=sc.name,
                display_name=getattr(sc, "display_name", sc.name),
                description=getattr(sc, "description", ""),
                category=getattr(sc, "category", ""),
            )
            for sc in schemas
        ]
        return AgentToolsResponse(tools=tools, count=len(tools))
    except Exception as exc:
        logger.warning("list_tools: failed to discover tools: %s", exc)
        return AgentToolsResponse(tools=[], count=0)


# ---------------------------------------------------------------------------
# Internal: adapter for AgentResult → OrchestrationResult-like shape
# (used in streaming completed event)
# ---------------------------------------------------------------------------


class _OrchestrationResultAdapter:
    """Duck-typed adapter: presents AgentResult as an OrchestrationResult.

    Used only inside the SSE completed-event handler so
    AgentExecuteResponse.from_orchestration_result() can be reused.
    """

    def __init__(self, agent_result: Any, request_id: str) -> None:
        import uuid as _uuid

        self.run_id = str(_uuid.uuid4())
        self.request_id = request_id
        self.agent_name = getattr(agent_result, "agent_name", "ai-assistant")
        self.status = RunStatus.COMPLETED if getattr(
            agent_result, "status", None
        ) and agent_result.status.is_success else RunStatus.FAILED
        self.output = getattr(agent_result, "content", "") or ""
        self.citations = [
            {
                "document_id": c.document_id,
                "document_name": c.document_name,
                "excerpt": c.excerpt,
                "page_number": c.page_number,
                "score": c.score,
            }
            for c in getattr(agent_result, "citations", [])
        ]
        self.tool_calls = [
            {
                "tool_name": t.tool_name,
                "input": t.input,
                "output": t.output,
                "failed": t.failed,
                "error_message": t.error_message,
            }
            for t in getattr(agent_result, "tool_calls", [])
        ]
        self.spans = []
        usage = getattr(agent_result, "usage", None)
        self.total_tokens = usage.total_tokens if usage else 0
        self.step_count = 0
        self.elapsed_ms = 0
        error = getattr(agent_result, "error", None)
        self.error_message = error.message if error else ""
