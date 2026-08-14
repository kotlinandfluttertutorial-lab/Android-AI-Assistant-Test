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
│  - Discovery: list tool schemas     │
│  - Invocation: route to connector   │
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

Every connector implements this abstract class:

```python
class MCPToolConnector(ABC):

    @property
    @abstractmethod
    def tool_name(self) -> str:
        """Unique name used to route invocations (e.g., 'github_create_issue')"""
        ...

    @abstractmethod
    def get_schema(self) -> MCPToolSchema:
        """Return the JSON Schema for this tool's parameters."""
        ...

    @abstractmethod
    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        """Execute the tool and return a result or raise MCPToolError."""
        ...

    @property
    def requires_confirmation(self) -> bool:
        """Return True for write operations (email send, issue create, etc.)."""
        return False
```

`MCPToolResult` contains `status` (`"success"` / `"error"`), `data` (tool-specific response),
and `error_message` (set on failure).

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

    async def invoke(self, tool_name: str, params: dict, user_id: str) -> MCPToolResult:
        connector = self._registry.get(tool_name)
        if not connector:
            raise MCPToolNotFoundError(tool_name)
        result = await connector.invoke(params, user_id)
        await self._audit_log(tool_name, user_id, result)
        return result
```

Adding a new connector requires **only** registering it at startup. No existing code is modified
(Open/Closed Principle).

---

## Built-In Connectors

### GitHub Connector

**Tool names:** `github_list_repos`, `github_get_issue`, `github_create_issue` (write), `github_list_prs`

**Authentication:** GitHub personal access token stored in `api_keys` table (AES-256 encrypted)

**Write operations (require confirmation):**
- `github_create_issue` — creates an issue in a specified repository
- `github_create_pr` — creates a pull request

**Example invocation:**
```json
{
  "tool_name": "github_create_issue",
  "params": {
    "repo": "owner/repository",
    "title": "Bug: null pointer in login flow",
    "body": "Steps to reproduce: ...",
    "labels": ["bug", "p1"]
  }
}
```

---

### Gmail Connector

**Tool names:** `gmail_list_messages`, `gmail_get_message`, `gmail_send_email` (write), `gmail_create_draft` (write)

**Authentication:** Google OAuth2 token (per-user, stored encrypted)

**Write operations (require confirmation):**
- `gmail_send_email` — sends an email on behalf of the user
- `gmail_create_draft` — creates a draft

---

### Google Drive Connector

**Tool names:** `gdrive_list_files`, `gdrive_get_file_content`, `gdrive_create_document` (write), `gdrive_upload_file` (write)

**Authentication:** Google OAuth2 token (per-user)

**Read operations** are available without confirmation. Write operations require the confirmation dialog.

---

### Google Calendar Connector

**Tool names:** `gcal_list_events`, `gcal_get_event`, `gcal_create_event` (write), `gcal_delete_event` (write)

**Authentication:** Google OAuth2 token

**Integration with Productivity Suite:** `CalendarView` screen uses `gcal_list_events` to surface Google Calendar events alongside local `CalendarEvent` Room entities.

---

### Slack Connector

**Tool names:** `slack_list_channels`, `slack_get_messages`, `slack_send_message` (write), `slack_create_channel` (write)

**Authentication:** Slack OAuth2 bot token (workspace-scoped, admin-configured)

---

### Jira Connector

**Tool names:** `jira_list_issues`, `jira_get_issue`, `jira_create_issue` (write), `jira_update_issue` (write), `jira_add_comment` (write)

**Authentication:** Jira API token + base URL (per-user, stored encrypted)

---

### Notion Connector

**Tool names:** `notion_list_pages`, `notion_get_page`, `notion_create_page` (write), `notion_append_block` (write)

**Authentication:** Notion integration token (per-user, stored encrypted)

---

### Figma Connector

**Tool names:** `figma_list_files`, `figma_get_file`, `figma_get_component`, `figma_export_frame`

**Authentication:** Figma personal access token (per-user, stored encrypted)

**Note:** Figma connector is read-only; no write operations are currently supported.

---

## Confirmation Dialog (Android)

When the AI Orchestrator determines that a write MCP tool is needed, it emits a
`{"type":"tool_call","toolName":"...","toolInput":{...}}` WebSocket event before invoking.

The Android `ChatDetailViewModel` intercepts write-operation tool calls and displays a
confirmation dialog:

```kotlin
// feature-chat: ConfirmToolCallDialog
if (event is StreamEvent.ToolCall && toolRequiresConfirmation(event.toolName)) {
    showConfirmDialog(
        title = "Allow ${event.toolName}?",
        description = "The assistant wants to: ${describeAction(event)}",
        onConfirm = { viewModel.confirmToolCall(event) },
        onDeny = { viewModel.denyToolCall(event) }
    )
}
```

The backend waits for the confirmation response before invoking the connector. If denied, the
orchestrator informs the user and continues the conversation without the tool.

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
        "params_hash": sha256(str(params)).hexdigest(),  # params hashed, never plaintext
    }
)
```

Tool parameters are hashed before logging to avoid storing sensitive content (e.g., email bodies,
file contents) in the audit log.

---

## Adding a New Connector

1. Create a new file in `backend/app/services/mcp_connectors/`, e.g., `linear_connector.py`
2. Implement `MCPToolConnector`:

```python
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
                    "team_id": {"type": "string"},
                    "title": {"type": "string"},
                    "description": {"type": "string"},
                },
                "required": ["team_id", "title"],
            }
        )

    @property
    def requires_confirmation(self) -> bool:
        return True  # write operation

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        # call Linear API
        ...
```

3. Register in `backend/app/main.py`:

```python
mcp_broker.register(LinearConnector())
```

That's it. No existing files are modified.

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Tool not found | `MCPToolNotFoundError` → HTTP 404 to client |
| Tool invocation timeout | `MCPToolTimeoutError` → HTTP 502, user informed |
| External API error (4xx/5xx) | `MCPToolError` → structured error response, user informed without internal details |
| Write not confirmed | `MCPConfirmationRequired` → HTTP 403, dialog shown to user |
| Audit log write failure | Non-fatal; logged to error monitoring; invocation still completes |
