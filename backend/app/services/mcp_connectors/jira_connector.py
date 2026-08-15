# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : jira_connector.py
# Purpose : jira_connector — services/mcp_connectors module
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

"""Jira MCP Tool Connectors.

Provides two connectors:
- ``JiraReadConnector``  (tool_name="jira_read")  — read-only operations.
- ``JiraWriteConnector`` (tool_name="jira_write") — write operations (requires confirmation).

Read actions:  search_issues, get_issue, list_projects
Write actions: create_issue, update_issue, add_comment

Auth: Basic Auth (email + API token) via ``JIRA_USER_EMAIL`` + ``JIRA_API_TOKEN``.
Base URL: ``JIRA_BASE_URL`` setting (e.g. https://yourorg.atlassian.net).

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


def _auth() -> tuple[str, str]:
    s = get_settings()
    return (s.JIRA_USER_EMAIL, s.JIRA_API_TOKEN)


def _base_url() -> str:
    return get_settings().JIRA_BASE_URL.rstrip("/")


class JiraReadConnector(MCPToolConnector):
    """Read-only Jira connector.

    Supported actions (passed as ``params["action"]``):
    - ``search_issues``:  JQL search. Params: ``jql`` (str), ``max_results`` (int, opt).
    - ``get_issue``:      Get single issue. Params: ``issue_key`` (e.g. "PROJ-123").
    - ``list_projects``:  List all accessible projects. Params: none.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "jira_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="jira_read",
            description="Read from Jira: search issues with JQL, get issue details, list projects.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["search_issues", "get_issue", "list_projects"],
                        "description": "The read action to perform.",
                    },
                    "jql": {
                        "type": "string",
                        "description": "JQL query string (for search_issues).",
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max results (for search_issues). Default: 20.",
                    },
                    "issue_key": {
                        "type": "string",
                        "description": "Issue key, e.g. PROJ-123 (for get_issue).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        base = _base_url()
        auth = _auth()
        async with httpx.AsyncClient(auth=auth, timeout=15.0) as client:
            headers = {"Accept": "application/json"}

            if action == "search_issues":
                jql = params.get("jql", "")
                max_results = params.get("max_results", 20)
                resp = await client.get(
                    f"{base}/rest/api/3/search",
                    headers=headers,
                    params={"jql": jql, "maxResults": max_results},
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "get_issue":
                issue_key = params.get("issue_key", "")
                resp = await client.get(
                    f"{base}/rest/api/3/issue/{issue_key}", headers=headers
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "list_projects":
                resp = await client.get(f"{base}/rest/api/3/project", headers=headers)
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"projects": resp.json()},
                    result_status="success",
                )

            else:
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=False,
                    error=f"Unknown action: {action!r}",
                    result_status="error",
                )


class JiraWriteConnector(MCPToolConnector):
    """Write operations Jira connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``create_issue``:  Create a new issue. Params: ``project_key``, ``summary``,
                         ``description``, ``issue_type``.
    - ``update_issue``:  Update an issue's fields. Params: ``issue_key``, ``fields`` (dict).
    - ``add_comment``:   Add a comment to an issue. Params: ``issue_key``, ``body``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "jira_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="jira_write",
            description="Write to Jira: create issues, update fields, add comments.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["create_issue", "update_issue", "add_comment"],
                        "description": "The write action to perform.",
                    },
                    "project_key": {
                        "type": "string",
                        "description": "Project key (for create_issue).",
                    },
                    "summary": {
                        "type": "string",
                        "description": "Issue summary (for create_issue).",
                    },
                    "description": {
                        "type": "string",
                        "description": "Issue description (for create_issue).",
                    },
                    "issue_type": {
                        "type": "string",
                        "description": "Issue type name, e.g. 'Bug'. Default: 'Task'.",
                    },
                    "issue_key": {
                        "type": "string",
                        "description": "Issue key, e.g. PROJ-123.",
                    },
                    "fields": {
                        "type": "object",
                        "description": "Fields to update (for update_issue).",
                    },
                    "body": {
                        "type": "string",
                        "description": "Comment body text (for add_comment).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        base = _base_url()
        auth = _auth()
        async with httpx.AsyncClient(auth=auth, timeout=15.0) as client:
            headers = {"Accept": "application/json", "Content-Type": "application/json"}

            if action == "create_issue":
                payload = {
                    "fields": {
                        "project": {"key": params.get("project_key", "")},
                        "summary": params.get("summary", ""),
                        "description": {
                            "type": "doc",
                            "version": 1,
                            "content": [
                                {
                                    "type": "paragraph",
                                    "content": [
                                        {
                                            "type": "text",
                                            "text": params.get("description", ""),
                                        }
                                    ],
                                }
                            ],
                        },
                        "issuetype": {"name": params.get("issue_type", "Task")},
                    }
                }
                resp = await client.post(
                    f"{base}/rest/api/3/issue", headers=headers, json=payload
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "update_issue":
                issue_key = params.get("issue_key", "")
                fields = params.get("fields", {})
                resp = await client.put(
                    f"{base}/rest/api/3/issue/{issue_key}",
                    headers=headers,
                    json={"fields": fields},
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"updated": True, "issue_key": issue_key},
                    result_status="success",
                )

            elif action == "add_comment":
                issue_key = params.get("issue_key", "")
                payload = {
                    "body": {
                        "type": "doc",
                        "version": 1,
                        "content": [
                            {
                                "type": "paragraph",
                                "content": [
                                    {"type": "text", "text": params.get("body", "")}
                                ],
                            }
                        ],
                    }
                }
                resp = await client.post(
                    f"{base}/rest/api/3/issue/{issue_key}/comment",
                    headers=headers,
                    json=payload,
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
