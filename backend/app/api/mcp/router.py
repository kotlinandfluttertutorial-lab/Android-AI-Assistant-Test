# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/mcp
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the mcp domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""MCP router — /tools/* endpoints.

Provides two endpoints:

- ``GET  /tools/``                     — discover all registered MCP tools.
- ``POST /tools/{tool_name}/invoke``   — invoke a registered MCP tool.

All endpoints require JWT authentication via the ``get_current_user``
dependency.  The ``MCPBroker`` is constructed per-request using a fresh
database session, keeping transaction scope tight.

Requirements: 9.1
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.mcp import MCPInvokeRequest, MCPToolResult, MCPToolSchema
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.mcp_broker import MCPBroker
from app.services.mcp_connectors import (
    FigmaReadConnector,
    GCalReadConnector,
    GCalWriteConnector,
    GDriveReadConnector,
    GDriveWriteConnector,
    GitHubReadConnector,
    GitHubWriteConnector,
    GmailReadConnector,
    GmailWriteConnector,
    JiraReadConnector,
    JiraWriteConnector,
    NotionReadConnector,
    NotionWriteConnector,
    SlackReadConnector,
    SlackWriteConnector,
)

router = APIRouter(
    prefix="/tools",
    tags=["mcp"],
    dependencies=[Depends(get_current_user)],
)


def _get_broker(db: AsyncSession = Depends(get_db)) -> MCPBroker:
    """Dependency that creates an ``MCPBroker`` bound to the current DB session.

    Registers all fifteen MCP tool connectors (read and write variants for each
    of the eight services: GitHub, Gmail, Google Drive, Google Calendar, Slack,
    Jira, Notion, and read-only Figma).

    Returns:
        An :class:`~app.services.mcp_broker.MCPBroker` instance.

    Requirements: 8.2, 8.3, 8.5
    """
    broker = MCPBroker(db)
    # GitHub
    broker.register(GitHubReadConnector())
    broker.register(GitHubWriteConnector())
    # Gmail
    broker.register(GmailReadConnector())
    broker.register(GmailWriteConnector())
    # Google Drive
    broker.register(GDriveReadConnector())
    broker.register(GDriveWriteConnector())
    # Google Calendar
    broker.register(GCalReadConnector())
    broker.register(GCalWriteConnector())
    # Slack
    broker.register(SlackReadConnector())
    broker.register(SlackWriteConnector())
    # Jira
    broker.register(JiraReadConnector())
    broker.register(JiraWriteConnector())
    # Notion
    broker.register(NotionReadConnector())
    broker.register(NotionWriteConnector())
    # Figma (read-only)
    broker.register(FigmaReadConnector())
    return broker


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/", response_model=list[MCPToolSchema])
async def list_tools(
    broker: MCPBroker = Depends(_get_broker),
) -> list[MCPToolSchema]:
    """Return schemas for all registered MCP tool connectors.

    Returns:
        List of :class:`~app.schemas.mcp.MCPToolSchema` objects.

    Requirements: 9.1
    """
    return broker.discover()


@router.post("/{tool_name}/invoke", response_model=MCPToolResult)
async def invoke_tool(
    tool_name: str,
    body: MCPInvokeRequest,
    request: Request,
    current_user: TokenPayload = Depends(get_current_user),
    broker: MCPBroker = Depends(_get_broker),
) -> MCPToolResult:
    """Invoke a registered MCP tool by name.

    The broker guarantees that an audit log entry is written for every
    invocation attempt (Property 12).

    Args:
        tool_name:    URL path parameter — the tool to invoke.
        body:         JSON body containing ``params`` dict.
        request:      FastAPI request object (used to extract client IP and
                      User-Agent for audit logging).
        current_user: Validated JWT payload injected by ``get_current_user``.
        broker:       ``MCPBroker`` instance injected via dependency.

    Returns:
        An :class:`~app.schemas.mcp.MCPToolResult`.

    Requirements: 9.1, 9.8
    """
    ip_address = request.client.host if request.client else ""
    user_agent = request.headers.get("user-agent", "")

    return await broker.invoke(
        tool_name=tool_name,
        params=body.params,
        user_id=current_user.sub,
        ip_address=ip_address,
        user_agent=user_agent,
    )
