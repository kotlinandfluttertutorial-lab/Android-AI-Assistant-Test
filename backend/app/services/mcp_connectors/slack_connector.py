# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : slack_connector.py
# Purpose : slack_connector — services/mcp_connectors module
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

"""Slack MCP Tool Connectors.

Provides two connectors:
- ``SlackReadConnector``  (tool_name="slack_read")  — read-only operations.
- ``SlackWriteConnector`` (tool_name="slack_write") — write operations (requires confirmation).

Read actions:  list_channels, get_channel_history, search_messages
Write actions: send_message, post_message

Auth: Bot token via ``SLACK_BOT_TOKEN`` setting.
Base URL: https://slack.com/api

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

_SLACK_BASE = "https://slack.com/api"


def _auth_headers() -> dict[str, str]:
    token = get_settings().SLACK_BOT_TOKEN
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


class SlackReadConnector(MCPToolConnector):
    """Read-only Slack connector.

    Supported actions (passed as ``params["action"]``):
    - ``list_channels``:    List public channels. Params: none required.
    - ``get_channel_history``: Get messages from a channel. Params: ``channel_id``, ``limit`` (opt).
    - ``search_messages``:  Search all messages. Params: ``query``.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "slack_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="slack_read",
            description="Read from Slack: list channels, get message history, search messages.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": [
                            "list_channels",
                            "get_channel_history",
                            "search_messages",
                        ],
                        "description": "The read action to perform.",
                    },
                    "channel_id": {
                        "type": "string",
                        "description": "Slack channel ID (e.g. C01234567).",
                    },
                    "query": {
                        "type": "string",
                        "description": "Search query (for search_messages).",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "Max messages to return. Default: 20.",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(timeout=15.0) as client:
            headers = _auth_headers()

            if action == "list_channels":
                resp = await client.get(
                    f"{_SLACK_BASE}/conversations.list", headers=headers
                )
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return MCPToolResult(
                        tool_name=self.tool_name,
                        success=False,
                        error="Slack API error: " + data.get("error", "unknown"),
                        result_status="error",
                    )
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"channels": data.get("channels", [])},
                    result_status="success",
                )

            elif action == "get_channel_history":
                channel_id = params.get("channel_id", "")
                limit = params.get("limit", 20)
                resp = await client.get(
                    f"{_SLACK_BASE}/conversations.history",
                    headers=headers,
                    params={"channel": channel_id, "limit": limit},
                )
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return MCPToolResult(
                        tool_name=self.tool_name,
                        success=False,
                        error="Slack API error: " + data.get("error", "unknown"),
                        result_status="error",
                    )
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"messages": data.get("messages", [])},
                    result_status="success",
                )

            elif action == "search_messages":
                query = params.get("query", "")
                resp = await client.get(
                    f"{_SLACK_BASE}/search.messages",
                    headers=headers,
                    params={"query": query},
                )
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return MCPToolResult(
                        tool_name=self.tool_name,
                        success=False,
                        error="Slack API error: " + data.get("error", "unknown"),
                        result_status="error",
                    )
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"messages": data.get("messages", {})},
                    result_status="success",
                )

            else:
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=False,
                    error=f"Unknown action: {action!r}",
                    result_status="error",
                )


class SlackWriteConnector(MCPToolConnector):
    """Write operations Slack connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``send_message``:  Send a DM to a user. Params: ``user_id``, ``text``.
    - ``post_message``:  Post to a channel. Params: ``channel_id``, ``text``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "slack_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="slack_write",
            description="Write to Slack: send DMs or post messages to channels.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["send_message", "post_message"],
                        "description": "The write action to perform.",
                    },
                    "user_id": {
                        "type": "string",
                        "description": "Slack user ID to DM (for send_message).",
                    },
                    "channel_id": {
                        "type": "string",
                        "description": "Slack channel ID (for post_message).",
                    },
                    "text": {"type": "string", "description": "Message text to send."},
                },
                "required": ["action", "text"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(timeout=15.0) as client:
            headers = _auth_headers()

            if action == "send_message":
                payload = {
                    "channel": params.get("user_id", ""),
                    "text": params.get("text", ""),
                }
                resp = await client.post(
                    f"{_SLACK_BASE}/chat.postMessage", headers=headers, json=payload
                )
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return MCPToolResult(
                        tool_name=self.tool_name,
                        success=False,
                        error="Slack API error: " + data.get("error", "unknown"),
                        result_status="error",
                    )
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"ts": data.get("ts")},
                    result_status="success",
                )

            elif action == "post_message":
                payload = {
                    "channel": params.get("channel_id", ""),
                    "text": params.get("text", ""),
                }
                resp = await client.post(
                    f"{_SLACK_BASE}/chat.postMessage", headers=headers, json=payload
                )
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return MCPToolResult(
                        tool_name=self.tool_name,
                        success=False,
                        error="Slack API error: " + data.get("error", "unknown"),
                        result_status="error",
                    )
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"ts": data.get("ts")},
                    result_status="success",
                )

            else:
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=False,
                    error=f"Unknown action: {action!r}",
                    result_status="error",
                )
