# ============================================================
# Android AI Assistant — Backend
# Module  : mcp/connectors
# File    : atlassian.py
# Purpose : AtlassianMCPConnector — MCPToolConnector that wraps the
#           Atlassian Remote MCP Server (mcp.atlassian.com) so Jira
#           and Confluence tools can be discovered and executed through
#           the project's MCP infrastructure.
#
# Architecture:
#   External Atlassian MCP gateway
#           ↓  (HTTP + OAuth 2.0 Bearer)
#   AtlassianMCPConnector  (this file)
#           ↓
#   MCPBroker / MCPRegistry / MCPExecutor  (app.mcp)
#           ↓
#   Agent / API handler
#
# Design rules:
#   - Credentials are NEVER hard-coded.  They are read from
#     AtlassianMCPConfig which is populated from the application
#     Settings (environment variables / Secret Manager).
#   - Sensitive values (client_secret, access tokens) are NEVER
#     written to logs.  The connector logs tool names and HTTP
#     status codes only.
#   - All network I/O runs through httpx.AsyncClient with a
#     configurable timeout so the event loop is never blocked.
#   - Connection, authentication, timeout, and remote errors are
#     each caught and surfaced as MCPToolResult(success=False) with
#     a safe, human-readable message — no internal details exposed.
#   - Tool discovery calls the MCP server's /tools/list endpoint
#     and returns a list of MCPToolSchema objects suitable for the
#     existing registry.
# ============================================================
"""AtlassianMCPConnector — Atlassian Remote MCP Server integration."""

from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_DEFAULT_TIMEOUT_S: float = 20.0
_TOKEN_URL = "https://auth.atlassian.com/oauth/token"
_TOOL_NAME = "atlassian_mcp"

# Regex for patterns that must never appear in log output
_SENSITIVE_PATTERN = re.compile(
    r"(bearer\s+\S+|client_secret[=:]\s*\S+|access_token[=:]\s*\S+)",
    re.IGNORECASE,
)


def _redact(text: str) -> str:
    """Replace credential patterns in *text* with ``[REDACTED]``."""
    return _SENSITIVE_PATTERN.sub("[REDACTED]", text)


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


@dataclass
class AtlassianMCPConfig:
    """Runtime configuration for :class:`AtlassianMCPConnector`.

    Attributes:
        server_url:     Base URL of the Atlassian MCP gateway.
                        E.g. ``https://mcp.atlassian.com/v1``.
        cloud_id:       Atlassian Cloud site UUID (required for cloud-scoped calls).
        client_id:      OAuth 2.0 client ID from developer.atlassian.com.
        client_secret:  OAuth 2.0 client secret.  **Never log this value.**
        timeout_s:      Per-request HTTP timeout in seconds.
        scopes:         OAuth 2.0 scopes to request.  Defaults cover Jira + Confluence
                        read/write.

    Do not construct this from literals in source code.  Use
    :func:`atlassian_connector_from_settings` which reads all values from
    the application Settings (environment variables).
    """

    server_url: str
    cloud_id: str
    client_id: str
    client_secret: str
    timeout_s: float = _DEFAULT_TIMEOUT_S
    scopes: list[str] = field(default_factory=lambda: [
        "read:jira-work",
        "write:jira-work",
        "read:confluence-content.all",
        "write:confluence-content",
        "offline_access",
    ])

    def __post_init__(self) -> None:
        if not self.server_url.strip():
            raise ValueError("AtlassianMCPConfig.server_url must not be blank.")
        if not self.client_id.strip():
            raise ValueError("AtlassianMCPConfig.client_id must not be blank.")
        if not self.client_secret.strip():
            raise ValueError("AtlassianMCPConfig.client_secret must not be blank.")

    @property
    def is_configured(self) -> bool:
        """True when all required fields are non-blank."""
        return bool(self.server_url and self.client_id and self.client_secret)


# ---------------------------------------------------------------------------
# Connector
# ---------------------------------------------------------------------------


class AtlassianMCPConnector(MCPToolConnector):
    """MCPToolConnector that proxies tool calls to the Atlassian Remote MCP Server.

    A single connector instance represents the full Atlassian MCP integration.
    Individual Jira / Confluence tool names are passed in the ``tool_name``
    parameter of the ``params`` dict and forwarded to the remote server.

    ## Tool discovery
    Call :meth:`discover_tools` to query the remote MCP server and retrieve
    the list of available tools.  The results can be registered with
    :class:`~app.mcp.registry.MCPRegistry` so agents see them in discovery.

    ## Authentication
    The connector uses OAuth 2.0 client-credentials flow to obtain a Bearer
    token, then sends it in the ``Authorization`` header.  The access token is
    cached in memory for the duration of the connector's lifetime; the token is
    refreshed when the server returns HTTP 401.

    ## Error handling
    - HTTP 401/403: returned as ``MCPToolResult(success=False, error="Authentication failed …")``.
    - HTTP 4xx other: returned as ``MCPToolResult(success=False, error="…")``.
    - Network error / timeout: returned as ``MCPToolResult(success=False, …)``.
    - Unexpected exceptions: caught, logged (without credentials), returned as failure.

    ## Credentials
    All credentials come from :class:`AtlassianMCPConfig`.  The connector
    **never** logs ``client_secret``, ``client_id``, or Bearer tokens.

    Usage::

        config = atlassian_connector_from_settings()
        connector = AtlassianMCPConnector(config)

        # Register with MCPServer
        server.register(connector)

        # Or discover individual tool schemas
        schemas = await connector.discover_tools()
    """

    def __init__(self, config: AtlassianMCPConfig) -> None:
        self._config = config
        self._access_token: str | None = None

    # ── MCPToolConnector interface ────────────────────────────────────────────

    @property
    def tool_name(self) -> str:
        return _TOOL_NAME

    @property
    def requires_confirmation(self) -> bool:
        # Write operations on Atlassian tools require confirmation; the per-tool
        # schema returned by discover_tools() sets the flag individually.
        return False  # gateway-level flag; individual tools declare their own

    def get_schema(self) -> MCPToolSchema:
        """Return the gateway-level schema for the Atlassian MCP connector.

        Individual tool schemas are available via :meth:`discover_tools`.
        This schema exposes the proxy interface used when callers pass an
        arbitrary Atlassian tool name in ``params["tool_name"]``.
        """
        return MCPToolSchema(
            tool_name=_TOOL_NAME,
            description=(
                "Atlassian MCP gateway — proxies tool calls to Jira and Confluence "
                "via the Atlassian Remote MCP Server.  Pass ``tool_name`` and "
                "``arguments`` in params to forward an individual tool call."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "tool_name": {
                        "type": "string",
                        "description": (
                            "Name of the Atlassian MCP tool to invoke, e.g. "
                            "'jira_get_issue', 'confluence_search_content'."
                        ),
                    },
                    "arguments": {
                        "type": "object",
                        "description": "Tool-specific arguments forwarded verbatim.",
                    },
                },
                "required": ["tool_name"],
            },
            requires_confirmation=False,
        )

    async def invoke(
        self, params: dict[str, Any], user_id: str
    ) -> MCPToolResult:
        """Forward a tool invocation to the Atlassian MCP server.

        Args:
            params:   Must contain ``tool_name`` (str) and optionally
                      ``arguments`` (dict).
            user_id:  Authenticated user UUID (used for audit logging only).

        Returns:
            :class:`~app.schemas.mcp.MCPToolResult` — never raises.
        """
        remote_tool = params.get("tool_name", "").strip()
        if not remote_tool:
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error="params['tool_name'] is required and must not be blank.",
                result_status="error",
            )

        arguments: dict[str, Any] = params.get("arguments", {})

        try:
            token = await self._get_access_token()
            result = await self._call_tool(remote_tool, arguments, token)
            # Retry once on 401 (token may have expired)
            if not result.success and "401" in (result.error or ""):
                self._access_token = None
                token = await self._get_access_token()
                result = await self._call_tool(remote_tool, arguments, token)
            return result

        except asyncio.TimeoutError:
            logger.warning(
                "AtlassianMCP: timeout invoking tool=%r server=%r",
                remote_tool, self._config.server_url,
            )
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=(
                    f"Request to Atlassian MCP server timed out after "
                    f"{self._config.timeout_s:.0f}s."
                ),
                result_status="error",
            )
        except httpx.ConnectError:
            logger.warning(
                "AtlassianMCP: connection error server=%r tool=%r",
                self._config.server_url, remote_tool,
            )
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=(
                    "Could not connect to the Atlassian MCP server. "
                    "Check ATLASSIAN_MCP_SERVER_URL."
                ),
                result_status="error",
            )
        except Exception as exc:
            # Log only safe information — never the exception repr if it
            # might contain credential fragments.
            safe_msg = _redact(str(exc))
            logger.error(
                "AtlassianMCP: unexpected error tool=%r: %s",
                remote_tool, safe_msg,
            )
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error="An unexpected error occurred while calling the Atlassian MCP server.",
                result_status="error",
            )

    # ── Discovery ─────────────────────────────────────────────────────────────

    async def discover_tools(self) -> list[MCPToolSchema]:
        """Query the MCP server and return its tool list as MCPToolSchema objects.

        Returns an empty list when the server is unreachable or credentials
        are not configured, so callers degrade gracefully.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema` objects discovered
            from the remote server.
        """
        if not self._config.is_configured:
            logger.info(
                "AtlassianMCP: discovery skipped — ATLASSIAN_MCP_SERVER_URL not configured."
            )
            return []

        try:
            token = await self._get_access_token()
            return await self._fetch_tool_list(token)
        except asyncio.TimeoutError:
            logger.warning(
                "AtlassianMCP: timeout during tool discovery server=%r",
                self._config.server_url,
            )
            return []
        except httpx.ConnectError:
            logger.warning(
                "AtlassianMCP: connection error during discovery server=%r",
                self._config.server_url,
            )
            return []
        except Exception as exc:
            logger.warning(
                "AtlassianMCP: discovery failed: %s", _redact(str(exc))
            )
            return []

    # ── Private helpers ───────────────────────────────────────────────────────

    async def _get_access_token(self) -> str:
        """Return a cached or freshly-obtained OAuth 2.0 access token.

        Uses client-credentials flow.  The token is stored in ``self._access_token``
        and reused until a 401 triggers a refresh.

        Raises:
            httpx.HTTPStatusError: On non-2xx OAuth response.
            asyncio.TimeoutError: On timeout.
        """
        if self._access_token:
            return self._access_token

        payload = {
            "grant_type": "client_credentials",
            "client_id": self._config.client_id,
            "client_secret": self._config.client_secret,
            "scope": " ".join(self._config.scopes),
        }

        async with httpx.AsyncClient(timeout=self._config.timeout_s) as client:
            resp = await client.post(_TOKEN_URL, data=payload)
            # Never log the full response — it contains the access token
            if resp.status_code != 200:
                logger.warning(
                    "AtlassianMCP: token request failed status=%d", resp.status_code
                )
                resp.raise_for_status()

            data = resp.json()
            token = data.get("access_token", "")
            if not token:
                raise ValueError("Atlassian OAuth response missing access_token field.")
            self._access_token = token
            logger.debug("AtlassianMCP: access token obtained successfully.")
            return token

    async def _call_tool(
        self,
        remote_tool: str,
        arguments: dict[str, Any],
        token: str,
    ) -> MCPToolResult:
        """Send a JSON-RPC ``tools/call`` request to the MCP server.

        Args:
            remote_tool: Atlassian tool name (e.g. ``"jira_get_issue"``).
            arguments:   Tool arguments dict.
            token:       OAuth 2.0 Bearer token.

        Returns:
            :class:`~app.schemas.mcp.MCPToolResult`.
        """
        url = f"{self._config.server_url.rstrip('/')}/tools/call"
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self._config.cloud_id:
            headers["X-Atlassian-Cloud-Id"] = self._config.cloud_id

        body = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": remote_tool,
                "arguments": arguments,
            },
        }

        async with httpx.AsyncClient(timeout=self._config.timeout_s) as client:
            resp = await client.post(url, headers=headers, json=body)

        if resp.status_code == 401:
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=(
                    "Authentication failed: the Atlassian MCP server rejected the token. "
                    "Check ATLASSIAN_CLIENT_ID and ATLASSIAN_CLIENT_SECRET."
                ),
                result_status="error",
            )

        if resp.status_code == 403:
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=(
                    f"Authorisation denied for tool '{remote_tool}'. "
                    "Ensure the OAuth app has the required Atlassian scopes."
                ),
                result_status="error",
            )

        if resp.status_code >= 400:
            logger.warning(
                "AtlassianMCP: HTTP %d for tool=%r", resp.status_code, remote_tool
            )
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=f"Atlassian MCP server returned HTTP {resp.status_code}.",
                result_status="error",
            )

        data = resp.json()
        rpc_result = data.get("result", {})
        rpc_error = data.get("error")

        if rpc_error:
            safe_msg = _redact(str(rpc_error.get("message", "Unknown RPC error")))
            logger.warning("AtlassianMCP: RPC error tool=%r: %s", remote_tool, safe_msg)
            return MCPToolResult(
                tool_name=_TOOL_NAME,
                success=False,
                error=safe_msg,
                result_status="error",
            )

        logger.info("AtlassianMCP: tool=%r completed successfully.", remote_tool)
        return MCPToolResult(
            tool_name=_TOOL_NAME,
            success=True,
            result=rpc_result,
            result_status="success",
        )

    async def _fetch_tool_list(self, token: str) -> list[MCPToolSchema]:
        """Send a JSON-RPC ``tools/list`` request and parse the response.

        Args:
            token: OAuth 2.0 Bearer token.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema`.
        """
        url = f"{self._config.server_url.rstrip('/')}/tools/list"
        headers = {
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        if self._config.cloud_id:
            headers["X-Atlassian-Cloud-Id"] = self._config.cloud_id

        body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}

        async with httpx.AsyncClient(timeout=self._config.timeout_s) as client:
            resp = await client.post(url, headers=headers, json=body)

        if resp.status_code == 401:
            logger.warning("AtlassianMCP: 401 during discovery — credentials may be invalid.")
            return []

        resp.raise_for_status()
        data = resp.json()
        tools_raw: list[dict[str, Any]] = (
            data.get("result", {}).get("tools", [])
            if isinstance(data.get("result"), dict)
            else []
        )

        schemas: list[MCPToolSchema] = []
        for t in tools_raw:
            name = t.get("name", "")
            if not name:
                continue
            schemas.append(
                MCPToolSchema(
                    tool_name=name,
                    description=t.get("description", ""),
                    parameters=t.get("inputSchema") or t.get("parameters") or {},
                    requires_confirmation=t.get("requiresConfirmation", False),
                )
            )

        logger.info("AtlassianMCP: discovered %d tools from remote server.", len(schemas))
        return schemas


# ---------------------------------------------------------------------------
# Factory
# ---------------------------------------------------------------------------


def atlassian_connector_from_settings() -> AtlassianMCPConnector | None:
    """Build an :class:`AtlassianMCPConnector` from the application Settings.

    Reads ``ATLASSIAN_MCP_SERVER_URL``, ``ATLASSIAN_CLOUD_ID``,
    ``ATLASSIAN_CLIENT_ID``, ``ATLASSIAN_CLIENT_SECRET``, and
    ``ATLASSIAN_MCP_TIMEOUT_S`` from the environment via pydantic-settings.

    Returns:
        A configured :class:`AtlassianMCPConnector` when all required
        settings are present, or ``None`` when the server URL is blank
        (Atlassian MCP disabled).

    No credentials are ever read from source code — all values come
    from environment variables (or Secret Manager in production).
    """
    from app.config.settings import get_settings  # lazy import

    s = get_settings()
    server_url = s.ATLASSIAN_MCP_SERVER_URL.strip()

    if not server_url:
        logger.info(
            "AtlassianMCP: ATLASSIAN_MCP_SERVER_URL is not set — connector disabled."
        )
        return None

    try:
        config = AtlassianMCPConfig(
            server_url=server_url,
            cloud_id=s.ATLASSIAN_CLOUD_ID.strip(),
            client_id=s.ATLASSIAN_CLIENT_ID.strip(),
            client_secret=s.ATLASSIAN_CLIENT_SECRET.strip(),
            timeout_s=float(s.ATLASSIAN_MCP_TIMEOUT_S),
        )
    except ValueError as exc:
        # Log configuration error without leaking credential values
        logger.error(
            "AtlassianMCP: invalid configuration — %s. "
            "Check ATLASSIAN_CLIENT_ID and ATLASSIAN_CLIENT_SECRET environment variables.",
            exc,
        )
        return None

    return AtlassianMCPConnector(config)
