# MCP Tools Reference

> **Updated:** 2026-10-03

Complete reference for all registered MCP tools, their parameters, and usage examples.

---

## Table of Contents

1. [Demo tools (zero credentials)](#1-demo-tools-zero-credentials)
2. [Atlassian tools (Jira + Confluence)](#2-atlassian-tools-jira--confluence)
3. [Tool discovery API](#3-tool-discovery-api)
4. [Tool invocation API](#4-tool-invocation-api)
5. [Error codes reference](#5-error-codes-reference)

---

## 1. Demo tools (zero credentials)

The `DemoMCPConnector` (`app/mcp/connectors/demo.py`) provides four tools that work entirely in-process with no external API calls. Register automatically in development mode (`ENVIRONMENT=development`).

---

### `demo_echo`

Returns the input message unchanged. Use as a connectivity / liveness test.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `message` | string | Yes | Any text to echo back |

**Response:**
```json
{
  "echo": "hello world",
  "length": 11
}
```

**Example:**
```python
result = await server.execute("demo_echo", {"message": "ping"}, user_id)
assert result.success
assert result.result["echo"] == "ping"
```

---

### `demo_add`

Adds two numbers. Use to verify parameter parsing.

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `a` | number | Yes | First operand |
| `b` | number | Yes | Second operand |

**Response:**
```json
{"a": 3, "b": 4, "sum": 7.0}
```

---

### `demo_env_info`

Returns safe, non-secret runtime information. Use to verify backend configuration.

**Parameters:** None

**Response:**
```json
{
  "python_version": "3.12.10 ...",
  "platform": "Windows",
  "environment": "development",
  "features": {
    "rag_enabled": true,
    "mcp_enabled": true,
    "agent_enabled": true,
    "prometheus_enabled": true,
    "otel_enabled": false,
    "llm_fallback_enabled": true
  },
  "agent_limits": {
    "max_steps": 10,
    "max_tool_calls": 20,
    "timeout_seconds": 120.0
  }
}
```

---

### `demo_rag_ping`

Checks that ChromaDB is reachable from within the agent execution context.

**Parameters:** None

**Response (success):**
```json
{
  "status": "ok",
  "host": "chromadb",
  "port": 8000,
  "collection_count": 3
}
```

**Response (failure):**
```json
{
  "success": false,
  "error": "ChromaDB unreachable: Connection refused. Ensure the chromadb service is running and CHROMA_HOST/PORT are set."
}
```

---

## 2. Atlassian tools (Jira + Confluence)

Provided by `AtlassianMCPConnector` (`app/mcp/connectors/atlassian.py`). Requires `ATLASSIAN_CLIENT_ID`, `ATLASSIAN_CLIENT_SECRET`, and `ATLASSIAN_MCP_SERVER_URL` to be set.

All Atlassian tools share a single gateway tool name: **`atlassian_mcp`**. The specific Jira/Confluence action is passed as `params["tool_name"]`.

### Discovery

```python
schemas = await connector.discover_tools()
# Returns List[MCPToolSchema] for every Jira/Confluence tool
# e.g. jira_get_issue, jira_search_issues, jira_create_issue,
#      confluence_search_content, confluence_get_page, ...
```

---

### Jira: Get Issue

**Tool name:** `jira_get_issue` (passed as `params["tool_name"]`)

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `issue_key` | string | Yes | Jira issue key, e.g. `PROJ-42` |

**Example:**
```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "jira_get_issue",
        "arguments": {"issue_key": "AI-123"},
    },
    user_id=user_id,
)
# result.result contains the issue JSON from Jira
```

---

### Jira: Search Issues

**Tool name:** `jira_search_issues`

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `jql` | string | Yes | JQL query string |
| `maxResults` | integer | No | Maximum results (default 10) |
| `fields` | string[] | No | Fields to include in response |

**Example:**
```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "jira_search_issues",
        "arguments": {
            "jql": "project = AI AND status = 'In Progress'",
            "maxResults": 5,
        },
    },
    user_id=user_id,
)
```

---

### Jira: Create Issue

**Tool name:** `jira_create_issue`  
**Requires confirmation:** Yes (write operation)

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `project` | object | Yes | `{"key": "PROJ"}` |
| `summary` | string | Yes | Issue title |
| `issuetype` | object | Yes | `{"name": "Bug"}` or `{"name": "Story"}` |
| `description` | string | No | Issue description (Atlassian Document Format or plain text) |
| `assignee` | object | No | `{"accountId": "..."}` |

---

### Confluence: Search Content

**Tool name:** `confluence_search_content`

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `query` | string | Yes | Free-text search query |
| `spaceKey` | string | No | Restrict to this Confluence space |
| `limit` | integer | No | Max results (default 10) |

---

### Confluence: Get Page

**Tool name:** `confluence_get_page`

**Parameters:**

| Name | Type | Required | Description |
|---|---|---|---|
| `pageId` | string | Yes | Confluence page ID |

---

## 3. Tool discovery API

```
GET /api/v1/agent/tools
Authorization: Bearer <token>
```

**Response:**
```json
{
  "tools": [
    {
      "name": "demo_echo",
      "display_name": "Demo: Echo",
      "description": "Returns the input message unchanged.",
      "category": "demo"
    },
    {
      "name": "atlassian_mcp",
      "display_name": "Atlassian MCP",
      "description": "Gateway to Jira and Confluence via Atlassian Remote MCP Server.",
      "category": "productivity"
    }
  ],
  "count": 2
}
```

---

## 4. Tool invocation API

Tools are invoked indirectly through the agent execute endpoint. The agent decides which tools to call based on the user's message.

```
POST /api/v1/agent/execute
Authorization: Bearer <token>
Content-Type: application/json

{
  "message": "Get Jira issue AI-123 and summarise it.",
  "enable_mcp": true,
  "max_steps": 5
}
```

**Response includes tool calls:**
```json
{
  "status": "completed",
  "output": "Jira issue AI-123: 'Implement MCP infrastructure'. Status: In Progress...",
  "tool_calls": [
    {
      "tool_name": "atlassian_mcp",
      "input": "{\"tool_name\": \"jira_get_issue\", \"arguments\": {\"issue_key\": \"AI-123\"}}",
      "output": "{\"key\": \"AI-123\", \"summary\": \"Implement MCP infrastructure\", ...}",
      "failed": false,
      "error_message": null
    }
  ],
  "sources": [],
  "steps": [...]
}
```

---

## 5. Error codes reference

| Error string | Cause | Resolution |
|---|---|---|
| `"Tool '...' is not registered or not on the allowlist."` | Tool name not registered or blocked | Check `server.list_allowed()` and register the connector |
| `"Invocation timed out after N ms."` | Tool exceeded `timeout_ms` | Increase `MCPToolModel.timeout_ms` or `ATLASSIAN_MCP_TIMEOUT_S` |
| `"Tool invocation failed. Please try again."` | Unexpected exception in connector | Check application logs for `exc_type` field |
| `"Authentication failed: ..."` | OAuth token rejected by Atlassian | Check `ATLASSIAN_CLIENT_ID` / `ATLASSIAN_CLIENT_SECRET` |
| `"Authorisation denied for tool '...'"` | Missing OAuth scope | Add required Atlassian API scopes in developer console |
| `"Could not connect to the Atlassian MCP server."` | DNS/network failure | Check `ATLASSIAN_MCP_SERVER_URL` and outbound HTTPS connectivity |
| `"MCP adapter not configured."` | `enable_mcp=false` or no MCPServer provided | Set `enable_mcp=true` in request and ensure credentials are set |
