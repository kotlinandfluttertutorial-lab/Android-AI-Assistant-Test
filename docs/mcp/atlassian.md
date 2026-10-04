# Atlassian MCP Integration

> **Module:** `app.mcp.connectors.atlassian`  
> **Date:** 2026-10-01  
> **Status:** Production-ready

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture](#2-architecture)
3. [Environment Variables](#3-environment-variables)
4. [Obtaining Credentials](#4-obtaining-credentials)
5. [Quick Start](#5-quick-start)
6. [Tool Discovery](#6-tool-discovery)
7. [Jira Tool Execution](#7-jira-tool-execution)
8. [Confluence Tool Execution](#8-confluence-tool-execution)
9. [Error Handling Reference](#9-error-handling-reference)
10. [Security Notes](#10-security-notes)
11. [Local Development Setup](#11-local-development-setup)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Overview

The Atlassian MCP integration connects the AI Assistant to the **Atlassian Remote MCP Server** (`mcp.atlassian.com`), exposing Jira and Confluence operations as discoverable tools within the project's MCP infrastructure.

This is **distinct** from the lower-level `JiraReadConnector` / `JiraWriteConnector` in `app.services.mcp_connectors.jira_connector`, which call the Jira REST API directly. The Atlassian MCP connector instead communicates with Atlassian's official MCP gateway using the [Model Context Protocol](https://modelcontextprotocol.io/) JSON-RPC format, enabling tool discovery via `tools/list` and execution via `tools/call`.

### What it provides

- **Tool discovery** — queries `POST {ATLASSIAN_MCP_SERVER_URL}/tools/list` and surfaces every available tool (Jira, Confluence, etc.) to the `MCPRegistry`.
- **Tool execution** — forwards `tools/call` requests to the remote server, with OAuth 2.0 authentication, configurable timeout, and safe error handling.
- **Factory function** — `atlassian_connector_from_settings()` reads all configuration from environment variables; no credentials are ever in source code.

---

## 2. Architecture

```
AI Agent / API handler
        │
        ▼
    MCPServer.create(db)
        │  server.register(AtlassianMCPConnector(config))
        ▼
    MCPRegistry  ←  MCPBroker (audit log)
        │
        ▼
    MCPExecutor
      │  allowlist → validate → timeout → invoke → result check
        │
        ▼
AtlassianMCPConnector.invoke(params, user_id)
      │
      ├── OAuth 2.0 client-credentials token (cached)
      │
      ├── POST {ATLASSIAN_MCP_SERVER_URL}/tools/call   (JSON-RPC 2.0)
      │       Authorization: Bearer <access_token>
      │       X-Atlassian-Cloud-Id: {ATLASSIAN_CLOUD_ID}
      │
      └── MCPToolResult(success, result | error, result_status)
```

The connector is registered as a **single gateway tool** (`tool_name="atlassian_mcp"`) in the `MCPRegistry`. When invoked, it forwards the `tool_name` field in `params` to the remote server. Individual Jira/Confluence tool schemas discovered via `discover_tools()` can optionally be registered directly in the registry as well.

---

## 3. Environment Variables

All configuration is injected through the application Settings (pydantic-settings). Set these as environment variables or in your `.env.local` / Secret Manager:

| Variable | Required | Description |
|---|---|---|
| `ATLASSIAN_MCP_SERVER_URL` | **Yes** | Base URL of the Atlassian MCP gateway, e.g. `https://mcp.atlassian.com/v1`. Leave blank to disable the connector entirely. |
| `ATLASSIAN_CLOUD_ID` | Recommended | Cloud site UUID. Find it at `https://<your-site>.atlassian.net/_edge/tenant_info`. Sent as the `X-Atlassian-Cloud-Id` header. |
| `ATLASSIAN_CLIENT_ID` | **Yes** | OAuth 2.0 client ID from [developer.atlassian.com](https://developer.atlassian.com). |
| `ATLASSIAN_CLIENT_SECRET` | **Yes** | OAuth 2.0 client secret. **Never commit this value.** |
| `ATLASSIAN_MCP_TIMEOUT_S` | No | Per-request timeout in seconds. Default: `20`. Range: 1–120. |

### Adding to `.env.local`

```dotenv
# Atlassian MCP Server — obtain credentials from developer.atlassian.com
ATLASSIAN_MCP_SERVER_URL=https://mcp.atlassian.com/v1
ATLASSIAN_CLOUD_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
ATLASSIAN_CLIENT_ID=your-oauth-client-id
ATLASSIAN_CLIENT_SECRET=your-oauth-client-secret
ATLASSIAN_MCP_TIMEOUT_S=20
```

> **Important:** `.env.local` is listed in `.gitignore`. Never commit these values to source control.

---

## 4. Obtaining Credentials

### 4.1 Create an OAuth 2.0 App

1. Go to [developer.atlassian.com](https://developer.atlassian.com) → **My Apps**.
2. Click **Create app** → choose **OAuth 2.0 integration**.
3. Note the **Client ID** and generate a **Client Secret** — store these in your secret manager.

### 4.2 Configure Scopes

Add the following API scopes to your app (Permissions tab):

**Jira:**
- `read:jira-work`
- `write:jira-work`

**Confluence:**
- `read:confluence-content.all`
- `write:confluence-content`

**Token refresh:**
- `offline_access`

### 4.3 Find Your Cloud ID

```bash
curl https://<your-site>.atlassian.net/_edge/tenant_info | jq .cloudId
```

---

## 5. Quick Start

### 5.1 Auto-configure from Settings (production)

```python
from app.mcp.connectors import atlassian_connector_from_settings
from app.mcp.server import MCPServer

async def handle_request(db, user_id: str):
    server = MCPServer.create(db)

    # Build connector from environment variables — no credentials in code
    connector = atlassian_connector_from_settings()
    if connector:
        server.register(connector)

        # Optionally also register individual discovered tools
        schemas = await connector.discover_tools()
        # schemas contains MCPToolSchema objects for every Jira/Confluence tool

    result = await server.execute(
        tool_name="atlassian_mcp",
        params={
            "tool_name": "jira_get_issue",
            "arguments": {"issue_key": "PROJ-42"},
        },
        user_id=user_id,
    )
    if result.success:
        print(result.result)
```

### 5.2 Manual configuration (testing / custom deployments)

```python
from app.mcp.connectors.atlassian import AtlassianMCPConfig, AtlassianMCPConnector

config = AtlassianMCPConfig(
    server_url="https://mcp.atlassian.com/v1",
    cloud_id="your-cloud-id",
    client_id="your-client-id",       # read from env in production
    client_secret="your-client-secret", # read from env in production
    timeout_s=20.0,
)
connector = AtlassianMCPConnector(config)
```

---

## 6. Tool Discovery

`discover_tools()` calls `POST {ATLASSIAN_MCP_SERVER_URL}/tools/list` and parses the JSON-RPC response:

```python
schemas: list[MCPToolSchema] = await connector.discover_tools()
for schema in schemas:
    print(schema.tool_name, schema.description)
    # e.g.:  jira_get_issue        Get details of a Jira issue
    #        jira_search_issues    Search Jira issues with JQL
    #        confluence_get_page   Get a Confluence page
```

**When discovery returns an empty list:**
- The connector is not configured (`ATLASSIAN_MCP_SERVER_URL` is blank).
- The remote server is unreachable.
- Authentication failed (HTTP 401).
- All cases return `[]` gracefully — the registry simply has no Atlassian tools.

---

## 7. Jira Tool Execution

Pass `tool_name` and `arguments` inside the `params` dict. The connector proxies the call to the remote server unchanged.

### Get an issue

```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "jira_get_issue",
        "arguments": {"issue_key": "PROJ-42"},
    },
    user_id=user_id,
)
```

### Search with JQL

```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "jira_search_issues",
        "arguments": {
            "jql": "project = PROJ AND status = 'In Progress'",
            "maxResults": 10,
        },
    },
    user_id=user_id,
)
```

### Create an issue (requires confirmation)

Jira write operations surface `requires_confirmation=True` in their schema. The `MCPExecutor` enforces the confirmation gate automatically.

```python
# First call — returns confirmation_required
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "jira_create_issue",
        "arguments": {
            "project": {"key": "PROJ"},
            "summary": "New bug report",
            "issuetype": {"name": "Bug"},
        },
    },
    user_id=user_id,
)
# result.result_status == "confirmation_required"

# After user confirms — pass confirmed=True in the MCPExecutor call
```

---

## 8. Confluence Tool Execution

### Search content

```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "confluence_search_content",
        "arguments": {
            "query": "deployment runbook",
            "spaceKey": "OPS",
            "limit": 5,
        },
    },
    user_id=user_id,
)
```

### Get a page

```python
result = await server.execute(
    tool_name="atlassian_mcp",
    params={
        "tool_name": "confluence_get_page",
        "arguments": {"pageId": "123456789"},
    },
    user_id=user_id,
)
```

---

## 9. Error Handling Reference

All errors are returned as `MCPToolResult(success=False)` — the connector **never raises**. The `error` field contains a human-safe message.

| Scenario | `error` content | `result_status` |
|---|---|---|
| `ATLASSIAN_MCP_SERVER_URL` blank | *(connector returns `None` from factory)* | N/A |
| `params["tool_name"]` missing or blank | `"params['tool_name'] is required …"` | `"error"` |
| HTTP 401 (token rejected) | `"Authentication failed: the Atlassian MCP server rejected the token."` | `"error"` |
| HTTP 403 (scope missing) | `"Authorisation denied for tool '…'. Ensure the OAuth app has the required Atlassian scopes."` | `"error"` |
| HTTP 5xx | `"Atlassian MCP server returned HTTP 5xx."` | `"error"` |
| JSON-RPC `error` field | The safe `message` from the RPC error object | `"error"` |
| Connection error | `"Could not connect to the Atlassian MCP server. Check ATLASSIAN_MCP_SERVER_URL."` | `"error"` |
| Timeout | `"Request to Atlassian MCP server timed out after Ns."` | `"error"` |
| Unexpected exception | `"An unexpected error occurred while calling the Atlassian MCP server."` | `"error"` |

### Token refresh

On HTTP 401 the connector clears the cached token and fetches a fresh one before retrying **once**. If the second call also returns 401 the failure result is returned.

---

## 10. Security Notes

### Credentials

- `ATLASSIAN_CLIENT_SECRET` must **never** be committed to source control.
- All credential fields are stored in `pydantic-settings` Fields with `default=""` — they are only populated from environment variables or a secret manager.
- The `atlassian_connector_from_settings()` factory is the only production path; it explicitly refuses to create a connector when `client_id` or `client_secret` is blank.

### Log sanitisation

The connector uses `_redact(text)` on any exception message before writing to the log, replacing patterns like `Bearer <token>`, `client_secret=<value>`, and `access_token=<value>` with `[REDACTED]`.

Access tokens and client secrets are **never** written to log output at any log level.

### Audit trail

Every invocation flows through `MCPBroker.invoke()` which writes an `AuditLog` row to PostgreSQL — including `result_status`, the tool name, and the user ID. The `params` dict (which contains only `tool_name` and `arguments`) is summarised, not stored verbatim, so Confluence / Jira content is not persisted in the audit log.

### TLS

All communication with the Atlassian MCP server uses HTTPS. `httpx.AsyncClient` enforces TLS certificate verification by default — this is not disabled.

---

## 11. Local Development Setup

### Option A — Use `.env.local` with real credentials

1. Create a sandbox Atlassian site (free tier available).
2. Create an OAuth 2.0 app with the scopes listed in §4.2.
3. Add the variables to `.env.local` (see §3).
4. Start the backend: `docker compose up backend`

### Option B — Disable the connector (default)

Leave `ATLASSIAN_MCP_SERVER_URL` blank (the default). The factory returns `None` and no connector is registered. All other MCP tools work normally.

### Option C — Mock server for CI

Use the mock in the unit tests as a reference:

```python
from unittest.mock import patch, AsyncMock
from app.mcp.connectors.atlassian import AtlassianMCPConnector, AtlassianMCPConfig
from app.schemas.mcp import MCPToolSchema

config = AtlassianMCPConfig(
    server_url="https://mock.local/v1",
    cloud_id="mock-cloud",
    client_id="mock-id",
    client_secret="mock-secret",
)
connector = AtlassianMCPConnector(config)

with patch.object(connector, "_get_access_token", return_value="mock-token"), \
     patch.object(connector, "_fetch_tool_list", AsyncMock(return_value=[
         MCPToolSchema(tool_name="jira_get_issue", description="Mock", parameters={}),
     ])):
    schemas = await connector.discover_tools()
```

---

## 12. Troubleshooting

### Connector not registered / tools not appearing

**Symptom:** `server.list_allowed()` does not include `"atlassian_mcp"`.  
**Cause:** `ATLASSIAN_MCP_SERVER_URL` is blank or `atlassian_connector_from_settings()` returned `None`.  
**Fix:** Set all three required variables (`ATLASSIAN_MCP_SERVER_URL`, `ATLASSIAN_CLIENT_ID`, `ATLASSIAN_CLIENT_SECRET`) and restart the server.

### `"Authentication failed"` error

**Symptom:** Every invoke returns `"Authentication failed: the Atlassian MCP server rejected the token."`.  
**Cause:** The client credentials are invalid or the OAuth app is suspended.  
**Fix:**
1. Re-check `ATLASSIAN_CLIENT_ID` and `ATLASSIAN_CLIENT_SECRET`.
2. Confirm the app is active in [developer.atlassian.com](https://developer.atlassian.com).
3. Verify the token endpoint `https://auth.atlassian.com/oauth/token` is reachable from the backend container.

### `"Authorisation denied"` error

**Symptom:** Specific tools return 403.  
**Cause:** The OAuth app is missing required scopes.  
**Fix:** Add the missing scopes in the Atlassian developer console (§4.2) and re-generate the client secret if required.

### `"Could not connect"` error

**Symptom:** `"Could not connect to the Atlassian MCP server. Check ATLASSIAN_MCP_SERVER_URL."`.  
**Cause:** DNS resolution failure or firewall blocking outbound HTTPS.  
**Fix:** Verify `ATLASSIAN_MCP_SERVER_URL` and that the container can reach `mcp.atlassian.com:443`.

### Discovery returns empty list

This is not an error — it is graceful degradation. Check the application logs for a `WARNING  app.mcp.connectors.atlassian` entry that explains the specific reason (connection failure, 401, timeout).

### Timeout errors

**Fix:** Increase `ATLASSIAN_MCP_TIMEOUT_S` (default 20 s, max 120 s). Cloud Run cold starts and large Confluence search results can occasionally take 15–30 s on first call.
