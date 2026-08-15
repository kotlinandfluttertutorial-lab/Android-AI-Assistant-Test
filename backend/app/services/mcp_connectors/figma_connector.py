# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : figma_connector.py
# Purpose : figma_connector — services/mcp_connectors module
#
# Architecture Layer : MCP Connector
# Pattern Used       : MCPToolConnector Implementation
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Figma MCP Tool Connector.

Provides one read-only connector:
- ``FigmaReadConnector`` (tool_name="figma_read") — read-only operations.

The Figma API is read-only by design for this integration.

Read actions: get_file, list_projects, get_project_files, get_comments

Auth: Bearer token via ``FIGMA_ACCESS_TOKEN`` setting.
Base URL: https://api.figma.com/v1

Requirements: 8.2, 8.5
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

_FIGMA_BASE = "https://api.figma.com/v1"


def _auth_headers() -> dict[str, str]:
    token = get_settings().FIGMA_ACCESS_TOKEN
    return {"X-Figma-Token": token}


class FigmaReadConnector(MCPToolConnector):
    """Read-only Figma connector.

    Supported actions (passed as ``params["action"]``):
    - ``get_file``:          Get a Figma file. Params: ``file_key``.
    - ``list_projects``:     List projects in a team. Params: ``team_id``.
    - ``get_project_files``: Get files in a project. Params: ``project_id``.
    - ``get_comments``:      Get comments on a file. Params: ``file_key``.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "figma_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="figma_read",
            description=(
                "Read from Figma: get files, list projects, get project files,"
                " get comments."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": [
                            "get_file",
                            "list_projects",
                            "get_project_files",
                            "get_comments",
                        ],
                        "description": "The read action to perform.",
                    },
                    "file_key": {
                        "type": "string",
                        "description": "Figma file key (for get_file, get_comments).",
                    },
                    "team_id": {
                        "type": "string",
                        "description": "Figma team ID (for list_projects).",
                    },
                    "project_id": {
                        "type": "string",
                        "description": "Figma project ID (for get_project_files).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(headers=_auth_headers(), timeout=15.0) as client:
            if action == "get_file":
                file_key = params.get("file_key", "")
                resp = await client.get(f"{_FIGMA_BASE}/files/{file_key}")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "list_projects":
                team_id = params.get("team_id", "")
                resp = await client.get(f"{_FIGMA_BASE}/teams/{team_id}/projects")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "get_project_files":
                project_id = params.get("project_id", "")
                resp = await client.get(f"{_FIGMA_BASE}/projects/{project_id}/files")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "get_comments":
                file_key = params.get("file_key", "")
                resp = await client.get(f"{_FIGMA_BASE}/files/{file_key}/comments")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            else:
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=False,
                    error=f"Unknown action: {action!r}",
                    result_status="error",
                )
