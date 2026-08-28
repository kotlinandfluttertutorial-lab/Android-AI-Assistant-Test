# MCP Integration Guide
## Android AI Assistant — Enterprise Edition

---

## Overview

The **MCP Broker** implements the Model Context Protocol, enabling the AI Orchestrator to invoke
external service integrations on behalf of the user. The broker provides a unified registry,
tool discovery, invocation, and audit logging.

---

## MCP Broker Architecture

```
AI Orchestrator
    │
    ▼
┌─────────────────────────────────────┐
│            MCP Broker               │
│  - Tool registry (dict of connectors│
│  - Discovery: list all tool schemas  │
│  - Invocation: route to connector   │
│  - Timeout: 30-second hard cap      │
│  - Audit: log every invocation      │
└─────────────────────────────────────┘
    │
    ├── GitHubConnector
    ├── GmailConnector
    ├── GoogleDriveConnector
    ├── GoogleCalendarConnector
    ├── SlackConnector
    ├── JiraConnector
    ├── NotionConnector
    └── FigmaConnector
```

---

## MCPToolConnector Interface

Every connector implements this abstract class. **Adding a new connector requires only creating
a new file and registering it — no existing code is modified (Open/Closed Principle).**

```python
# services/mcp_broker.py
class MCPToolConnector(ABC):

    @property
    @abstractmethod
    def tool_name(self) -> str:
        """Unique routing name, e.g., 'github_create_issue'"""
        ...

    @abstractmethod
    def get_schema(self) -> MCPToolSchema:
        """JSON Schema for this tool's parameters."""
        ...

    @abstractmethod
    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        """Execute the tool and return a result or raise MCPToolError."""
        ...

    @property
    def requires_confirmation(self) -> bool:
        """Return True for write operations. Default: False."""
        return False
```

`MCPToolResult` contains:
- `status`: `"success"` or `"error"`
- `data`: tool-specific response object
- `error_message`: set on failure (user-safe message; internal details never exposed)

---

## MCP Broker Registry

```python
class MCPBroker:
    def __init__(self):
        self._registry: dict[str, MCPToolConnector] = {}

    def register(self, connector: MCPToolConnector) -> None:
        self._registry[connector.tool_name] = connector

    def discover(self) -> list[MCPToolSchema]:
        return [c.get_schema() for c in self._registry.values()]

    async def invoke(
        self, tool_name: str, params: dict, user_id: str
    ) -> MCPToolResult:
        connector = self._registry.get(tool_name)
        if not connector:
            raise MCPToolNotFoundError(tool_name)
        try:
            result = await asyncio.wait_for(
                connector.invoke(params, user_id), timeout=30.0
            )
        except asyncio.TimeoutError:
            raise MCPToolTimeoutError(tool_name)
        await self._audit_log(tool_name, user_id, result)
        return result
```

---

## Built-In Connectors

### GitHub Connector

**Tool names:** `github_list_repos`, `github_get_issue`, `github_create_issue` (write),
`github_list_prs`, `github_create_pr` (write)

**Authentication:** GitHub personal access token — stored encrypted in `api_keys` table

**Write operations (require confirmation):** `github_create_issue`, `github_create_pr`

**Example:**
```json
{
  "tool_name": "github_create_issue",
  "params": {
    "repo": "owner/repository",
    "title": "Bug: null pointer in login flow",
    "body": "Steps to reproduce: 1. Open app...",
    "labels": ["bug", "p1"]
  }
}
```

---

### Gmail Connector

**Tool names:** `gmail_list_messages`, `gmail_get_message`, `gmail_send_email` (write),
`gmail_create_draft` (write)

**Authentication:** Google OAuth2 token (per-user, stored encrypted)

---

### Google Drive Connector

**Tool names:** `gdrive_list_files`, `gdrive_get_file_content`, `gdrive_create_document` (write),
`gdrive_upload_file` (write)

**Authentication:** Google OAuth2 token (per-user)

---

### Google Calendar Connector

**Tool names:** `gcal_list_events`, `gcal_get_event`, `gcal_create_event` (write),
`gcal_delete_event` (write)

**Authentication:** Google OAuth2 token (per-user)

**Productivity Suite integration:** The `CalendarView` screen uses `gcal_list_events` to merge
Google Calendar events with local `CalendarEvent` Room entities. Local events take precedence
on title conflicts.

---

### Slack Connector

**Tool names:** `slack_list_channels`, `slack_get_messages`, `slack_send_message` (write),
`slack_create_channel` (write)

**Authentication:** Slack OAuth2 bot token (workspace-scoped, admin-configured)

---

### Jira Connector

**Tool names:** `jira_list_issues`, `jira_get_issue`, `jira_create_issue` (write),
`jira_update_issue` (write), `jira_add_comment` (write)

**Authentication:** Jira API token + base URL (per-user, stored encrypted)

---

### Notion Connector

**Tool names:** `notion_list_pages`, `notion_get_page`, `notion_create_page` (write),
`notion_append_block` (write)

**Authentication:** Notion integration token (per-user, stored encrypted)

---

### Figma Connector

**Tool names:** `figma_list_files`, `figma_get_file`, `figma_get_component`, `figma_export_frame`

**Authentication:** Figma personal access token (per-user, stored encrypted)

**Note:** Figma connector is read-only; no write operations are currently supported.

---

## Confirmation Dialog (Android)

When the AI Orchestrator needs a write-operation MCP tool, it emits a `tool_call` WebSocket
event before invoking:

```json
{ "type": "tool_call", "toolName": "github_create_issue", "toolInput": { ... } }
```

`ChatDetailViewModel` intercepts write operations and shows a confirmation dialog:

```kotlin
// feature-chat: ToolCallConfirmationDialog
if (event is StreamEvent.ToolCall && event.toolName in writeToolNames) {
    showConfirmDialog(
        title = "Allow ${humanReadableName(event.toolName)}?",
        description = describeAction(event),
        onConfirm = { viewModel.confirmToolCall(event) },
        onDeny = { viewModel.denyToolCall(event) }
    )
}
```

- **Confirmed:** Backend invokes the connector; result injected into the LLM context
- **Denied:** Backend does NOT invoke; AI Orchestrator informs user the action was not performed;
  conversation continues

---

## Audit Logging

Every MCP tool invocation is recorded in `audit_logs`:

```python
await audit_service.log(
    user_id=user_id,
    event_type="mcp_invoke",
    metadata={
        "tool_name": tool_name,
        "status": result.status,
        "params_hash": sha256(json.dumps(params, sort_keys=True)).hexdigest(),
    }
)
```

Tool parameters are hashed before logging to avoid storing sensitive content in the audit log.
Records retained for a minimum of 90 days.

---

## Adding a New Connector

1. Create `backend/app/services/mcp_connectors/linear_connector.py`:

```python
from app.services.mcp_broker import MCPToolConnector, MCPToolSchema, MCPToolResult

class LinearConnector(MCPToolConnector):

    @property
    def tool_name(self) -> str:
        return "linear_create_issue"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="linear_create_issue",
            description="Create a Linear issue",
            parameters={
                "type": "object",
                "properties": {
                    "team_id": { "type": "string", "description": "Linear team ID" },
                    "title":   { "type": "string" },
                    "description": { "type": "string" },
                },
                "required": ["team_id", "title"],
            }
        )

    @property
    def requires_confirmation(self) -> bool:
        return True  # write operation

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        # Call Linear API, return MCPToolResult
        ...
```

2. Register at startup in `backend/app/main.py`:

```python
mcp_broker.register(LinearConnector())
```

That's it — no existing files are modified.

---

## Error Handling

| Scenario | HTTP Status | Behaviour |
|----------|-------------|-----------|
| Tool not found | 404 | User informed via structured error |
| Tool invocation timeout (> 30 s) | 502 | Timeout error returned; user informed |
| External API error (4xx/5xx) | 502 | User-safe error message; internal details hidden |
| Write not confirmed by user | — | Tool NOT invoked; conversation continues with notice |
| Audit log write failure | — | Non-fatal; logged to error monitoring; invocation completes |
