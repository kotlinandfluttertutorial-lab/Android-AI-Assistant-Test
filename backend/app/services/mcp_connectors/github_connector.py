# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : github_connector.py
# Purpose : github_connector — services/mcp_connectors module
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

"""GitHub MCP Tool Connectors.

Provides two connectors:
- ``GitHubReadConnector``  (tool_name="github_read")  — read-only operations.
- ``GitHubWriteConnector`` (tool_name="github_write") — write operations (requires confirmation).

Read actions:  search_repos, list_issues, get_repo, list_prs, get_file
Write actions: create_issue, create_pr_comment, create_gist

Auth: Bearer token via ``GITHUB_TOKEN`` setting.
Base URL: https://api.github.com

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

_GITHUB_BASE = "https://api.github.com"


def _auth_headers() -> dict[str, str]:
    token = get_settings().GITHUB_TOKEN
    return {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}


class GitHubReadConnector(MCPToolConnector):
    """Read-only GitHub connector.

    Supported actions (passed as ``params["action"]``):
    - ``search_repos``: Search GitHub repositories. Params: ``query`` (str).
    - ``list_issues``:  List open issues in a repo. Params: ``owner``, ``repo``.
    - ``get_repo``:     Get repository metadata. Params: ``owner``, ``repo``.
    - ``list_prs``:     List open pull requests. Params: ``owner``, ``repo``.
    - ``get_file``:     Get file content from a repo. Params: ``owner``, ``repo``, ``path``.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "github_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="github_read",
            description="Read data from GitHub: search repos, list issues/PRs, get file content.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": [
                            "search_repos",
                            "list_issues",
                            "get_repo",
                            "list_prs",
                            "get_file",
                        ],
                        "description": "The read action to perform.",
                    },
                    "query": {
                        "type": "string",
                        "description": "Search query (for search_repos).",
                    },
                    "owner": {
                        "type": "string",
                        "description": "Repository owner/org name.",
                    },
                    "repo": {"type": "string", "description": "Repository name."},
                    "path": {
                        "type": "string",
                        "description": "File path within repo (for get_file).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(headers=_auth_headers(), timeout=15.0) as client:
            if action == "search_repos":
                query = params.get("query", "")
                resp = await client.get(
                    f"{_GITHUB_BASE}/search/repositories", params={"q": query}
                )
                resp.raise_for_status()
                data = resp.json()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={
                        "total_count": data.get("total_count", 0),
                        "items": data.get("items", [])[:10],
                    },
                    result_status="success",
                )

            elif action == "list_issues":
                owner, repo = params.get("owner", ""), params.get("repo", "")
                resp = await client.get(
                    f"{_GITHUB_BASE}/repos/{owner}/{repo}/issues",
                    params={"state": "open"},
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"issues": resp.json()},
                    result_status="success",
                )

            elif action == "get_repo":
                owner, repo = params.get("owner", ""), params.get("repo", "")
                resp = await client.get(f"{_GITHUB_BASE}/repos/{owner}/{repo}")
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "list_prs":
                owner, repo = params.get("owner", ""), params.get("repo", "")
                resp = await client.get(
                    f"{_GITHUB_BASE}/repos/{owner}/{repo}/pulls",
                    params={"state": "open"},
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result={"pull_requests": resp.json()},
                    result_status="success",
                )

            elif action == "get_file":
                owner, repo, path = (
                    params.get("owner", ""),
                    params.get("repo", ""),
                    params.get("path", ""),
                )
                resp = await client.get(
                    f"{_GITHUB_BASE}/repos/{owner}/{repo}/contents/{path}"
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


class GitHubWriteConnector(MCPToolConnector):
    """Write operations GitHub connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``create_issue``:       Create a new issue. Params: ``owner``, ``repo``, ``title``, ``body``.
    - ``create_pr_comment``:  Add comment to a PR. Params: ``owner``, ``repo``, ``pr_number``, ``body``.
    - ``create_gist``:        Create a gist. Params: ``description``, ``filename``, ``content``, ``public``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "github_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="github_write",
            description="Write to GitHub: create issues, PR comments, and gists.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["create_issue", "create_pr_comment", "create_gist"],
                        "description": "The write action to perform.",
                    },
                    "owner": {
                        "type": "string",
                        "description": "Repository owner/org name.",
                    },
                    "repo": {"type": "string", "description": "Repository name."},
                    "title": {
                        "type": "string",
                        "description": "Issue title (for create_issue).",
                    },
                    "body": {
                        "type": "string",
                        "description": "Issue/comment body text.",
                    },
                    "pr_number": {
                        "type": "integer",
                        "description": "Pull request number (for create_pr_comment).",
                    },
                    "description": {
                        "type": "string",
                        "description": "Gist description (for create_gist).",
                    },
                    "filename": {
                        "type": "string",
                        "description": "Gist filename (for create_gist).",
                    },
                    "content": {
                        "type": "string",
                        "description": "Gist file content (for create_gist).",
                    },
                    "public": {
                        "type": "boolean",
                        "description": "Whether the gist is public. Default: false.",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        async with httpx.AsyncClient(headers=_auth_headers(), timeout=15.0) as client:
            if action == "create_issue":
                owner, repo = params.get("owner", ""), params.get("repo", "")
                payload = {
                    "title": params.get("title", ""),
                    "body": params.get("body", ""),
                }
                resp = await client.post(
                    f"{_GITHUB_BASE}/repos/{owner}/{repo}/issues", json=payload
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "create_pr_comment":
                owner, repo = params.get("owner", ""), params.get("repo", "")
                pr_number = params.get("pr_number", 0)
                payload = {"body": params.get("body", "")}
                resp = await client.post(
                    f"{_GITHUB_BASE}/repos/{owner}/{repo}/issues/{pr_number}/comments",
                    json=payload,
                )
                resp.raise_for_status()
                return MCPToolResult(
                    tool_name=self.tool_name,
                    success=True,
                    result=resp.json(),
                    result_status="success",
                )

            elif action == "create_gist":
                filename = params.get("filename", "file.txt")
                payload = {
                    "description": params.get("description", ""),
                    "public": params.get("public", False),
                    "files": {filename: {"content": params.get("content", "")}},
                }
                resp = await client.post(f"{_GITHUB_BASE}/gists", json=payload)
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
