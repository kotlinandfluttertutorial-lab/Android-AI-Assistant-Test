# Skill: MCP Connector

## Purpose
Add a new Model Context Protocol (MCP) connector to the Android AI Assistant backend,
following the patterns already established by the eight existing connectors:
GitHub, Gmail, Google Calendar, Google Drive, Slack, Notion, Jira, and Figma.

## When to Use
- Integrating a new external service as an MCP tool (e.g. Linear, Confluence, Salesforce)
- Adding a new tool action to an existing connector (e.g. "create GitHub issue" alongside
  the existing "list issues")
- Debugging an MCP tool invocation that isn't appearing as a `StreamEvent.ToolCall`

---

## MCP Architecture

```
Android (ChatDetailViewModel)
    StreamEvent.ToolCall(toolName, toolInput)  ← surfaced from WebSocket stream
                ↑
Backend WebSocket handler
    AIOrchestrator.stream_chat()
        ↓ LLM decides to call a tool
    MCPBroker.invoke(tool_name, tool_input, user_id, conversation_id)
        ├── writes AuditLog entry  (ALWAYS — Property 12)
        ├── checks requires_confirmation flag
        └── delegates to MCPToolConnector.execute(tool_input)
                ↓
        Specific connector (e.g. GithubConnector)
                ↓
        External API (GitHub REST API)
                ↓
        Returns result string  →  injected back into LLM as a tool result message
```

---

## Backend Files

| File | Responsibility |
|---|---|
| `backend/app/services/mcp_broker.py` | Registry + dispatch + audit log |
| `backend/app/services/mcp_tool_connector.py` | `MCPToolConnector` abstract base class |
| `backend/app/services/connectors/github_connector.py` | GitHub implementation (reference) |
| `backend/app/services/connectors/gmail_connector.py` | Gmail implementation |
| `backend/app/services/connectors/gcal_connector.py` | Google Calendar |
| `backend/app/services/connectors/gdrive_connector.py` | Google Drive |
| `backend/app/services/connectors/slack_connector.py` | Slack |
| `backend/app/services/connectors/notion_connector.py` | Notion |
| `backend/app/services/connectors/jira_connector.py` | Jira |
| `backend/app/services/connectors/figma_connector.py` | Figma |
| `backend/app/api/mcp/router.py` | REST endpoints for listing/configuring connectors |
| `backend/app/models/audit_log.py` | `AuditLog` ORM model |

---

## Step 1 — Create the Connector Class

```python
# backend/app/services/connectors/linear_connector.py
from __future__ import annotations

import logging
from typing import Any

import httpx

from app.services.mcp_tool_connector import MCPToolConnector

logger = logging.getLogger(__name__)

# Tool name constants — must be unique across all connectors
TOOL_LIST_ISSUES  = "linear_list_issues"
TOOL_CREATE_ISSUE = "linear_create_issue"
TOOL_UPDATE_ISSUE = "linear_update_issue"

# Tool schemas define what the LLM must supply in tool_input.
# Follow JSON Schema (subset) conventions.
TOOL_SCHEMAS: dict[str, dict[str, Any]] = {
    TOOL_LIST_ISSUES: {
        "description": "List open issues from a Linear team.",
        "parameters": {
            "type": "object",
            "properties": {
                "team_key": {
                    "type": "string",
                    "description": "The Linear team identifier (e.g. 'ENG')."
                },
                "limit": {
                    "type": "integer",
                    "description": "Maximum issues to return (default 10, max 50).",
                    "default": 10,
                }
            },
            "required": ["team_key"],
        },
    },
    TOOL_CREATE_ISSUE: {
        "description": "Create a new issue in Linear.",
        "parameters": {
            "type": "object",
            "properties": {
                "team_key": {"type": "string"},
                "title":    {"type": "string"},
                "description": {"type": "string", "default": ""},
            },
            "required": ["team_key", "title"],
        },
        "requires_confirmation": True,  # write operation — user confirms before execution
    },
    TOOL_UPDATE_ISSUE: {
        "description": "Update an existing Linear issue.",
        "parameters": {
            "type": "object",
            "properties": {
                "issue_id": {"type": "string"},
                "status":   {"type": "string", "enum": ["todo", "in_progress", "done"]},
            },
            "required": ["issue_id"],
        },
        "requires_confirmation": True,
    },
}


class LinearConnector(MCPToolConnector):
    """MCP connector for Linear project management."""

    # The API key is read from the per-user integration settings stored in the DB.
    # Never read it from a global env var — users provide their own keys.
    def __init__(self, api_key: str) -> None:
        self._api_key = api_key
        self._base_url = "https://api.linear.app/graphql"

    # ── MCPToolConnector interface ───────────────────────────────────────────

    @property
    def tool_names(self) -> list[str]:
        return list(TOOL_SCHEMAS.keys())

    @property
    def tool_schemas(self) -> dict[str, dict[str, Any]]:
        return TOOL_SCHEMAS

    async def execute(self, tool_name: str, tool_input: dict[str, Any]) -> str:
        """Dispatch to the correct tool handler.

        Returns a plain-text string that the LLM receives as the tool result.
        Raise ValueError for invalid input; raise RuntimeError for API errors.
        """
        match tool_name:
            case "linear_list_issues":
                return await self._list_issues(tool_input)
            case "linear_create_issue":
                return await self._create_issue(tool_input)
            case "linear_update_issue":
                return await self._update_issue(tool_input)
            case _:
                raise ValueError(f"Unknown tool: {tool_name}")

    # ── Tool handlers ────────────────────────────────────────────────────────

    async def _list_issues(self, params: dict[str, Any]) -> str:
        team_key = params["team_key"]
        limit = min(int(params.get("limit", 10)), 50)

        query = """
            query ListIssues($teamKey: String!, $first: Int!) {
              issues(filter: { team: { key: { eq: $teamKey } } }, first: $first) {
                nodes { id title state { name } }
              }
            }
        """
        data = await self._graphql(query, {"teamKey": team_key, "first": limit})
        nodes = data["data"]["issues"]["nodes"]
        if not nodes:
            return f"No open issues found for team {team_key}."
        lines = [f"- [{n['id']}] {n['title']} ({n['state']['name']})" for n in nodes]
        return "\n".join(lines)

    async def _create_issue(self, params: dict[str, Any]) -> str:
        # ... GraphQL mutation ...
        return f"Issue created: {params['title']}"

    async def _update_issue(self, params: dict[str, Any]) -> str:
        # ... GraphQL mutation ...
        return f"Issue {params['issue_id']} updated."

    # ── HTTP helper ──────────────────────────────────────────────────────────

    async def _graphql(self, query: str, variables: dict[str, Any]) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(
                self._base_url,
                json={"query": query, "variables": variables},
                headers={
                    "Authorization": self._api_key,
                    "Content-Type": "application/json",
                },
            )
            resp.raise_for_status()
            return resp.json()
```

---

## Step 2 — Register with `MCPBroker`

Open `backend/app/services/mcp_broker.py` and register the new connector:

```python
from app.services.connectors.linear_connector import LinearConnector, TOOL_SCHEMAS as LINEAR_SCHEMAS

class MCPBroker:
    def __init__(self, db: AsyncSession, user_id: uuid.UUID) -> None:
        self._connectors: dict[str, MCPToolConnector] = {}
        self._db = db
        self._user_id = user_id

    async def _load_connectors(self) -> None:
        # Existing connectors ...
        # Load Linear connector if the user has an API key configured
        linear_key = await self._get_integration_key("linear")
        if linear_key:
            connector = LinearConnector(api_key=linear_key)
            for tool_name in connector.tool_names:
                self._connectors[tool_name] = connector
```

---

## Step 3 — Audit Log (mandatory)

`MCPBroker.invoke()` already writes one `AuditLog` entry per tool call (Property 12).
Verify this is present for your new connector:

```python
async def invoke(
    self, tool_name: str, tool_input: dict[str, Any],
    conversation_id: str,
) -> str:
    # AUDIT LOG — must happen before execution (even if execution fails)
    await self._write_audit_log(
        tool_name=tool_name,
        tool_input=tool_input,
        conversation_id=conversation_id,
    )

    connector = self._connectors.get(tool_name)
    if connector is None:
        return f"Tool '{tool_name}' is not available."

    schema = connector.tool_schemas.get(tool_name, {})
    if schema.get("requires_confirmation") and not self._user_confirmed:
        # Surface confirmation request to Android via StreamEvent.ToolCall
        return "__CONFIRMATION_REQUIRED__"

    return await connector.execute(tool_name, tool_input)
```

---

## Step 4 — Expose Tool Schemas to the LLM

`AIOrchestrator` passes all registered tool schemas to the LLM provider as part of
the tools/functions parameter. Verify your new tools appear:

```python
tools = broker.get_all_schemas()  # dict[str, schema]
# Passed to OpenAIClient, GeminiClient, etc. as the `tools` parameter
```

---

## Step 5 — Android: Handle `StreamEvent.ToolCall`

In `ChatDetailViewModel` (or any ViewModel using streaming):

```kotlin
is StreamEvent.ToolCall -> {
    _uiState.update { it.copy(activeToolName = event.toolName) }
    // Show "Using Linear..." indicator in the UI
    // No need to send anything back — the server handles the tool result
}
```

The tool result is automatically injected server-side; the LLM continues streaming
tokens after the tool completes. The `activeToolName` indicator clears on the next
`StreamEvent.Token`.

---

## Step 6 — Integration Key Storage

Per-user integration API keys must be stored encrypted in PostgreSQL, not as plain
strings. The `AES_ENCRYPTION_KEY` environment variable (AES-256) is used for
field-level encryption. Follow the pattern in the existing `UserIntegration` model.

Never log API keys. When loading a connector, log only whether a key is present:
```python
logger.info("Linear connector loaded for user %s: key_present=%s", user_id, bool(linear_key))
```

---

## Checklist

- [ ] Connector class extends `MCPToolConnector` abstract base
- [ ] All tool names are globally unique (prefix with service name, e.g. `linear_`)
- [ ] `requires_confirmation = True` on all write/mutation tools
- [ ] `execute()` dispatches to individual handlers; raises `ValueError` for unknown tools
- [ ] Registered in `MCPBroker._load_connectors()`
- [ ] `AuditLog` entry written by `MCPBroker.invoke()` (not in the connector itself)
- [ ] API key loaded per-user from encrypted DB field (not from env var)
- [ ] `httpx.AsyncClient` used (not `requests`) — all I/O must be async
- [ ] `timeout=` set on all HTTP calls (default: 15s)
- [ ] Android `StreamEvent.ToolCall` handled without crashing
- [ ] Tool schemas exposed to the LLM via `broker.get_all_schemas()`
- [ ] Integration test covers: key present → tool invoked → audit log written
