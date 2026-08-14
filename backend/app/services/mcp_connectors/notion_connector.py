# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : notion_connector.py
# Purpose : notion_connector — services/mcp_connectors module
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

"""Notion MCP Tool Connectors.

Provides two connectors:
- ``NotionReadConnector``  (tool_name="notion_read")  — read-only operations.
- ``NotionWriteConnector`` (tool_name="notion_write") — write operations (requires confirmation).

Read actions:  search_pages, get_page, list_databases
Write actions: create_page, update_page, append_block

Auth: Bearer token via ``NOTION_TOKEN`` setting.
Base URL: https://api.notion.com/v1

Requirements: 8.2, 8.3, 8.5
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

_NOTION_BASE = "https://api.notion.com/v1"
_NOTION_VERSION = "2022-06-28"


def _auth_headers() -> dict[str, str]:
    token = get_settings().NOTION_TOKEN
    return {
        "Authorization": f"Bearer {token}",
        "Notion-Version": _NOTION_VERSION,
        "Content-Type": "application/json",
    }


class NotionReadConnector(MCPToolConnector):
    """Read-only Notion connector.

    Supported actions (passed as ``params["action"]``):
    - ``search_pages``:    Search all pages/databases. Params: ``query`` (str).
    - ``get_page``:        Get a page by ID. Params: ``page_id``.
    - ``list_databases``:  List all databases. Params: none.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "notion_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="notion_read",
            description="Read from Notion: search pages, get page content, list databases.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["search_pages", "get_page", "list_databases"],
                        "description": "The read action to perform.",
                    },
                    "query": {
                        "type": "string",
                        "description": "Search query (for search_pages).",
                    },
                    "page_id": {
                        "type": "string",
                        "description": "Notion page UUID (for get_page).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(headers=_auth_headers(), timeout=15.0) as client:
            if action == "search_pages":
                query = params.get("query", "")
                resp = await client.post(
                    f"{_NOTION_BASE}/search", json={"query": query}
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "get_page":
                page_id = params.get("page_id", "")
                resp = await client.get(f"{_NOTION_BASE}/pages/{page_id}")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "list_databases":
                resp = await client.post(
                    f"{_NOTION_BASE}/search",
                    json={"filter": {"value": "database", "property": "object"}},
                )
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


class NotionWriteConnector(MCPToolConnector):
    """Write operations Notion connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``create_page``:    Create a new page. Params: ``parent_id``, ``title``, ``content``.
    - ``update_page``:    Update page properties. Params: ``page_id``, ``properties`` (dict).
    - ``append_block``:   Append content blocks. Params: ``block_id``, ``text``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "notion_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="notion_write",
            description="Write to Notion: create pages, update properties, append content blocks.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["create_page", "update_page", "append_block"],
                        "description": "The write action to perform.",
                    },
                    "parent_id": {
                        "type": "string",
                        "description": "Parent page or database UUID (for create_page).",
                    },
                    "title": {
                        "type": "string",
                        "description": "Page title (for create_page).",
                    },
                    "content": {
                        "type": "string",
                        "description": "Page text content (for create_page).",
                    },
                    "page_id": {
                        "type": "string",
                        "description": "Notion page UUID (for update_page).",
                    },
                    "properties": {
                        "type": "object",
                        "description": "Property map to update (for update_page).",
                    },
                    "block_id": {
                        "type": "string",
                        "description": "Block/page UUID to append content to.",
                    },
                    "text": {
                        "type": "string",
                        "description": "Text content to append (for append_block).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(headers=_auth_headers(), timeout=15.0) as client:
            if action == "create_page":
                parent_id = params.get("parent_id", "")
                payload = {
                    "parent": {"page_id": parent_id},
                    "properties": {
                        "title": {
                            "title": [
                                {
                                    "type": "text",
                                    "text": {"content": params.get("title", "")},
                                }
                            ]
                        }
                    },
                    "children": [
                        {
                            "object": "block",
                            "type": "paragraph",
                            "paragraph": {
                                "rich_text": [
                                    {
                                        "type": "text",
                                        "text": {"content": params.get("content", "")},
                                    }
                                ]
                            },
                        }
                    ],
                }
                resp = await client.post(f"{_NOTION_BASE}/pages", json=payload)
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "update_page":
                page_id = params.get("page_id", "")
                properties = params.get("properties", {})
                resp = await client.patch(
                    f"{_NOTION_BASE}/pages/{page_id}", json={"properties": properties}
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "append_block":
                block_id = params.get("block_id", "")
                text = params.get("text", "")
                payload = {
                    "children": [
                        {
                            "object": "block",
                            "type": "paragraph",
                            "paragraph": {
                                "rich_text": [
                                    {"type": "text", "text": {"content": text}}
                                ]
                            },
                        }
                    ]
                }
                resp = await client.patch(
                    f"{_NOTION_BASE}/blocks/{block_id}/children", json=payload
                )
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
