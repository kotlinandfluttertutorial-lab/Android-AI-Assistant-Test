"""AI DevOps Assistant API endpoints — Phase 13.

Endpoints
---------
POST /devops/chat          — conversational DevOps question answering
GET  /devops/tools         — list available DevOps tools
POST /devops/tools/{name}/invoke  — invoke a single tool directly

All endpoints require a valid JWT.

Phase 13 — AI DevOps Assistant
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/devops",
    tags=["devops-assistant"],
    dependencies=[Depends(get_current_user)],
)


# ── Schemas ───────────────────────────────────────────────────────────────────


class DevOpsChatRequest(BaseModel):
    question: str = Field(
        description="Natural language DevOps question.",
        min_length=1,
        max_length=2000,
    )
    provider: str | None = Field(
        default=None,
        description="LLM provider override: gemini|openai|claude. None = default.",
    )


class ToolCallSummary(BaseModel):
    tool_name: str
    params:    dict
    result:    dict


class DevOpsChatResponse(BaseModel):
    session_id:    str
    question:      str
    answer:        str
    citations:     list[str]
    tool_calls:    list[ToolCallSummary]
    rounds_used:   int
    llm_provider:  str


class DirectInvokeRequest(BaseModel):
    params: dict = Field(default_factory=dict)


# ── Endpoints ─────────────────────────────────────────────────────────────────


@router.post(
    "/chat",
    response_model=DevOpsChatResponse,
    summary="Ask the AI DevOps Assistant a question",
    description=(
        "Conversational DevOps question answering using a ReAct tool-calling loop.\n\n"
        "The assistant selects which tools to call based on the question, executes "
        "them against live operational data, and synthesises a grounded answer.\n\n"
        "**Example questions:**\n"
        "- 'Why did the API fail at 14:32?'\n"
        "- 'Show me recent critical incidents'\n"
        "- 'What is the likely root cause of the current errors?'\n"
        "- 'How do I restart the backend service?'\n"
        "- 'Have we seen this DB connection error before?'\n"
        "- 'Generate an incident report for the latest open incident'\n\n"
        "**AI Safety:** All tool calls are read-only except `create_incident` "
        "which requires confirmation. No automated production actions are taken."
    ),
)
async def devops_chat(
    body: DevOpsChatRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DevOpsChatResponse:
    """Run the ReAct DevOps assistant loop and return a grounded answer.

    Phase 13 — AI DevOps Assistant
    """
    from app.services.devops_assistant_service import DevOpsAssistantService

    logger.info(
        "devops/chat: user=%s question=%r",
        current_user.sub,
        body.question[:80],
    )

    try:
        service  = DevOpsAssistantService(db)
        response = await service.ask(
            question          = body.question,
            user_id           = current_user.sub,
            provider_override = body.provider,
        )

        if response.error:
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=response.error,
            )

        return DevOpsChatResponse(
            session_id   = response.session_id,
            question     = response.question,
            answer       = response.answer,
            citations    = response.citations,
            tool_calls   = [
                ToolCallSummary(
                    tool_name = tc.tool_name,
                    params    = tc.params,
                    result    = tc.result,
                )
                for tc in response.tool_calls
            ],
            rounds_used  = response.rounds_used,
            llm_provider = response.llm_provider,
        )

    except HTTPException:
        raise
    except Exception as exc:
        logger.error("devops/chat: unexpected error — %s", exc, exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="DevOps assistant failed unexpectedly. Check application logs.",
        ) from exc


@router.get(
    "/tools",
    response_model=list[MCPToolSchema],
    summary="List available DevOps tools",
    description="Returns the schema for all 7 DevOps MCP tools.",
)
async def list_devops_tools(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[MCPToolSchema]:
    """Return schemas for all registered DevOps tool connectors.

    Phase 13 — AI DevOps Assistant
    """
    from app.services.devops_assistant_service import DevOpsAssistantService

    service = DevOpsAssistantService(db)
    return service._broker.discover()


@router.post(
    "/tools/{tool_name}/invoke",
    response_model=MCPToolResult,
    summary="Invoke a single DevOps tool directly",
    description=(
        "Directly invoke a DevOps tool by name without the conversational loop. "
        "Useful for programmatic access or debugging individual tools."
    ),
)
async def invoke_devops_tool(
    tool_name: str,
    body: DirectInvokeRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> MCPToolResult:
    """Invoke a DevOps tool directly.

    Phase 13 — AI DevOps Assistant
    """
    from app.services.devops_assistant_service import DevOpsAssistantService

    service = DevOpsAssistantService(db)
    result  = await service._broker.invoke(
        tool_name  = tool_name,
        params     = body.params,
        user_id    = current_user.sub,
    )

    if not result.success and result.result_status == "error":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=result.error or f"Tool '{tool_name}' failed.",
        )

    return result
