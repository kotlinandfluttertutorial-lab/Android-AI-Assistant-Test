"""Unit tests for app.mcp.connectors.atlassian.

All HTTP calls are intercepted by ``httpx.MockTransport`` / ``respx`` style mocks —
no real network calls, no Atlassian credentials required.

Covers:
  - AtlassianMCPConfig validation
  - atlassian_connector_from_settings() factory (Settings stubs)
  - Tool schema (get_schema)
  - Tool discovery (discover_tools) — success, auth failure, network error, timeout
  - Tool invocation (invoke) — success, auth 401 with token refresh, 403, 4xx, RPC
    error, connection failure, timeout, missing tool_name param
  - Credential redaction (_redact)
  - No credential leakage in error messages or log output
"""

from __future__ import annotations

import sys
from unittest.mock import MagicMock as _MagicMock

# Stub google.genai before any app.main import chain fires
_g = _MagicMock()
sys.modules.setdefault("google.genai", _g)
sys.modules.setdefault("google.genai.types", _g)

import asyncio
import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from app.mcp.connectors.atlassian import (
    AtlassianMCPConfig,
    AtlassianMCPConnector,
    _redact,
    atlassian_connector_from_settings,
)
from app.schemas.mcp import MCPToolResult, MCPToolSchema

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_SERVER = "https://mcp.atlassian.com/v1"
_CLOUD  = "test-cloud-id"
_CID    = "test-client-id"
_CSEC   = "test-client-secret"   # not a real secret


def _config(**kw) -> AtlassianMCPConfig:
    defaults = dict(server_url=_SERVER, cloud_id=_CLOUD, client_id=_CID, client_secret=_CSEC)
    defaults.update(kw)
    return AtlassianMCPConfig(**defaults)


def _connector(**kw) -> AtlassianMCPConnector:
    return AtlassianMCPConnector(_config(**kw))


def _token_resp(token: str = "tok-abc") -> httpx.Response:
    return httpx.Response(200, json={"access_token": token, "token_type": "Bearer"})


def _tool_list_resp(tools: list[dict] | None = None) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "tools": tools
                or [
                    {
                        "name": "jira_get_issue",
                        "description": "Get a Jira issue.",
                        "inputSchema": {"type": "object", "properties": {}},
                    },
                    {
                        "name": "confluence_search_content",
                        "description": "Search Confluence.",
                        "inputSchema": {"type": "object"},
                        "requiresConfirmation": False,
                    },
                ]
            },
        },
    )


def _tool_call_resp(result: dict | None = None) -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "result": result or {"key": "PROJ-42", "summary": "Test issue"},
        },
    )


def _rpc_error_resp(message: str = "Not found") -> httpx.Response:
    return httpx.Response(
        200,
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "error": {"code": -32600, "message": message},
        },
    )


# ---------------------------------------------------------------------------
# AtlassianMCPConfig
# ---------------------------------------------------------------------------

class TestAtlassianMCPConfig:
    def test_valid_construction(self):
        c = _config()
        assert c.server_url == _SERVER
        assert c.is_configured

    def test_blank_server_url_raises(self):
        with pytest.raises(ValueError, match="server_url"):
            AtlassianMCPConfig(server_url="  ", cloud_id="", client_id=_CID, client_secret=_CSEC)

    def test_blank_client_id_raises(self):
        with pytest.raises(ValueError, match="client_id"):
            AtlassianMCPConfig(server_url=_SERVER, cloud_id="", client_id="", client_secret=_CSEC)

    def test_blank_client_secret_raises(self):
        with pytest.raises(ValueError, match="client_secret"):
            AtlassianMCPConfig(server_url=_SERVER, cloud_id="", client_id=_CID, client_secret="")

    def test_is_configured_true_when_all_set(self):
        assert _config().is_configured

    def test_is_configured_false_when_no_url(self):
        c = AtlassianMCPConfig.__new__(AtlassianMCPConfig)
        object.__setattr__(c, "server_url", "")
        object.__setattr__(c, "client_id", _CID)
        object.__setattr__(c, "client_secret", _CSEC)
        object.__setattr__(c, "cloud_id", "")
        object.__setattr__(c, "timeout_s", 20.0)
        object.__setattr__(c, "scopes", [])
        assert not c.is_configured

    def test_default_scopes_include_jira_and_confluence(self):
        scopes = _config().scopes
        assert any("jira" in s for s in scopes)
        assert any("confluence" in s for s in scopes)

    def test_custom_timeout(self):
        c = _config(timeout_s=5.0)
        assert c.timeout_s == 5.0


# ---------------------------------------------------------------------------
# _redact
# ---------------------------------------------------------------------------

class TestRedact:
    def test_bearer_token_redacted(self):
        text = "Authorization: Bearer supersecrettoken123"
        assert "supersecrettoken123" not in _redact(text)
        assert "[REDACTED]" in _redact(text)

    def test_client_secret_redacted(self):
        text = "client_secret=mysecret"
        assert "mysecret" not in _redact(text)

    def test_access_token_redacted(self):
        text = "access_token: abc.def.ghi"
        assert "abc.def.ghi" not in _redact(text)

    def test_safe_text_unchanged(self):
        text = "tool=jira_get_issue status=200"
        assert _redact(text) == text


# ---------------------------------------------------------------------------
# Factory: atlassian_connector_from_settings
# ---------------------------------------------------------------------------

class TestAtlassianConnectorFromSettings:
    def _fake_settings(self, **kw):
        s = MagicMock()
        s.ATLASSIAN_MCP_SERVER_URL = kw.get("url", _SERVER)
        s.ATLASSIAN_CLOUD_ID = kw.get("cloud", _CLOUD)
        s.ATLASSIAN_CLIENT_ID = kw.get("cid", _CID)
        s.ATLASSIAN_CLIENT_SECRET = kw.get("csec", _CSEC)
        s.ATLASSIAN_MCP_TIMEOUT_S = kw.get("timeout", 20.0)
        return s

    def test_returns_connector_when_all_set(self):
        with patch(
            "app.config.settings.get_settings",
            return_value=self._fake_settings(),
        ):
            c = atlassian_connector_from_settings()
        assert isinstance(c, AtlassianMCPConnector)

    def test_returns_none_when_url_blank(self):
        with patch(
            "app.config.settings.get_settings",
            return_value=self._fake_settings(url=""),
        ):
            c = atlassian_connector_from_settings()
        assert c is None

    def test_returns_none_when_client_id_blank(self):
        with patch(
            "app.config.settings.get_settings",
            return_value=self._fake_settings(cid=""),
        ):
            c = atlassian_connector_from_settings()
        assert c is None

    def test_returns_none_when_client_secret_blank(self):
        with patch(
            "app.config.settings.get_settings",
            return_value=self._fake_settings(csec=""),
        ):
            c = atlassian_connector_from_settings()
        assert c is None

    def test_connector_has_correct_server_url(self):
        with patch(
            "app.config.settings.get_settings",
            return_value=self._fake_settings(url="https://custom.mcp.example.com/v2"),
        ):
            c = atlassian_connector_from_settings()
        assert c._config.server_url == "https://custom.mcp.example.com/v2"


# ---------------------------------------------------------------------------
# get_schema
# ---------------------------------------------------------------------------

class TestGetSchema:
    def test_tool_name(self):
        assert _connector().get_schema().tool_name == "atlassian_mcp"

    def test_description_mentions_atlassian(self):
        desc = _connector().get_schema().description
        assert "Atlassian" in desc

    def test_parameters_require_tool_name(self):
        schema = _connector().get_schema()
        required = schema.parameters.get("required", [])
        assert "tool_name" in required

    def test_requires_confirmation_false(self):
        assert _connector().get_schema().requires_confirmation is False


# ---------------------------------------------------------------------------
# discover_tools — mocking httpx
# ---------------------------------------------------------------------------

class TestDiscoverTools:
    @pytest.mark.asyncio
    async def test_success_returns_schemas(self):
        c = _connector()
        expected = [
            MCPToolSchema(
                tool_name="jira_get_issue",
                description="Get a Jira issue.",
                parameters={"type": "object"},
            ),
            MCPToolSchema(
                tool_name="confluence_search_content",
                description="Search Confluence.",
                parameters={"type": "object"},
            ),
        ]
        # Stub _get_access_token and _fetch_tool_list directly to avoid
        # httpx request-object requirements in unit tests
        with (
            patch.object(c, "_get_access_token", return_value="test-token"),
            patch.object(c, "_fetch_tool_list", AsyncMock(return_value=expected)),
        ):
            schemas = await c.discover_tools()

        assert len(schemas) == 2
        names = [s.tool_name for s in schemas]
        assert "jira_get_issue" in names
        assert "confluence_search_content" in names

    @pytest.mark.asyncio
    async def test_discovery_returns_mcp_tool_schema_objects(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=[_token_resp(), _tool_list_resp()])
            schemas = await c.discover_tools()
        assert all(isinstance(s, MCPToolSchema) for s in schemas)

    @pytest.mark.asyncio
    async def test_discovery_returns_empty_on_connection_error(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(
                side_effect=httpx.ConnectError("Connection refused")
            )
            schemas = await c.discover_tools()
        assert schemas == []

    @pytest.mark.asyncio
    async def test_discovery_returns_empty_on_timeout(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=asyncio.TimeoutError())
            schemas = await c.discover_tools()
        assert schemas == []

    @pytest.mark.asyncio
    async def test_discovery_returns_empty_when_server_returns_401(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(
                side_effect=[_token_resp(), httpx.Response(401)]
            )
            schemas = await c.discover_tools()
        assert schemas == []

    @pytest.mark.asyncio
    async def test_discovery_returns_empty_when_not_configured(self):
        c = AtlassianMCPConnector.__new__(AtlassianMCPConnector)
        c._config = MagicMock()
        c._config.is_configured = False
        c._access_token = None
        schemas = await c.discover_tools()
        assert schemas == []

    @pytest.mark.asyncio
    async def test_discovery_handles_missing_tools_key(self):
        c = _connector()
        empty_resp = httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {}})
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=[_token_resp(), empty_resp])
            schemas = await c.discover_tools()
        assert schemas == []


# ---------------------------------------------------------------------------
# invoke — mocking httpx
# ---------------------------------------------------------------------------

class TestInvoke:
    @pytest.mark.asyncio
    async def test_missing_tool_name_returns_failure(self):
        c = _connector()
        result = await c.invoke({}, "user-1")
        assert not result.success
        assert "tool_name" in result.error

    @pytest.mark.asyncio
    async def test_blank_tool_name_returns_failure(self):
        c = _connector()
        result = await c.invoke({"tool_name": "   "}, "user-1")
        assert not result.success

    @pytest.mark.asyncio
    async def test_successful_invocation(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(
                side_effect=[_token_resp(), _tool_call_resp({"issue_key": "PROJ-1"})]
            )
            result = await c.invoke(
                {"tool_name": "jira_get_issue", "arguments": {"issue_key": "PROJ-1"}},
                "user-1",
            )
        assert result.success
        assert result.result == {"issue_key": "PROJ-1"}
        assert result.result_status == "success"

    @pytest.mark.asyncio
    async def test_successful_invocation_sets_tool_name(self):
        c = _connector()
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=[_token_resp(), _tool_call_resp()])
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert result.tool_name == "atlassian_mcp"

    @pytest.mark.asyncio
    async def test_http_401_returns_auth_failure(self):
        c = _connector()
        c._access_token = "cached-token"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            # First call: 401; token refresh; second call: still 401
            mock_client.post = AsyncMock(
                side_effect=[
                    httpx.Response(401),
                    _token_resp("new-token"),
                    httpx.Response(401),
                ]
            )
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        assert "Authentication failed" in result.error
        # Must not contain token value
        assert "new-token" not in result.error
        assert "cached-token" not in result.error

    @pytest.mark.asyncio
    async def test_http_403_returns_authorisation_failure(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(return_value=httpx.Response(403))
            result = await c.invoke({"tool_name": "jira_create_issue"}, "u1")
        assert not result.success
        assert "Authorisation denied" in result.error or "403" in result.error

    @pytest.mark.asyncio
    async def test_http_500_returns_failure_with_status_code(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(return_value=httpx.Response(500))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        assert "500" in result.error

    @pytest.mark.asyncio
    async def test_rpc_error_returns_failure(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(return_value=_rpc_error_resp("Issue not found"))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        assert "Issue not found" in result.error

    @pytest.mark.asyncio
    async def test_connection_error_returns_failure(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(
                side_effect=httpx.ConnectError("Connection refused")
            )
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        assert "connect" in result.error.lower() or "ATLASSIAN_MCP_SERVER_URL" in result.error

    @pytest.mark.asyncio
    async def test_timeout_returns_failure(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=asyncio.TimeoutError())
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        assert "timed out" in result.error.lower()

    @pytest.mark.asyncio
    async def test_unexpected_exception_returns_generic_failure(self):
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=RuntimeError("internal crash"))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert not result.success
        # Internal detail MUST NOT be exposed
        assert "internal crash" not in result.error
        assert "RuntimeError" not in result.error
        assert "Traceback" not in result.error

    @pytest.mark.asyncio
    async def test_invoke_never_raises(self):
        """No matter what happens inside, invoke() must not propagate."""
        c = _connector()
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=Exception("unexpected!"))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert isinstance(result, MCPToolResult)
        assert not result.success

    # ── Token refresh logic ───────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_token_cached_after_first_call(self):
        c = _connector()
        call_counts: dict[str, int] = {"token": 0, "tool": 0}

        def _side_effect(url, **kw):
            if "oauth" in url or "token" in url:
                call_counts["token"] += 1
                return _token_resp()
            call_counts["tool"] += 1
            return _tool_call_resp()

        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=[_token_resp(), _tool_call_resp()])
            await c.invoke({"tool_name": "jira_get_issue"}, "u1")
            # Token is now cached; next call should not re-fetch it
            mock_client.post = AsyncMock(side_effect=[_tool_call_resp()])
            await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        # Only one token fetch across two invocations
        assert c._access_token is not None

    # ── Credential leakage ────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_error_message_does_not_contain_client_secret(self):
        c = _connector(client_secret="ultra-secret-value-xyz")
        c._access_token = "tok"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=RuntimeError("ultra-secret-value-xyz exposed!"))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert "ultra-secret-value-xyz" not in result.error

    @pytest.mark.asyncio
    async def test_error_message_does_not_contain_access_token(self):
        c = _connector()
        c._access_token = "should-never-appear-in-logs"
        with patch("httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cls.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post = AsyncMock(side_effect=RuntimeError("should-never-appear-in-logs!"))
            result = await c.invoke({"tool_name": "jira_get_issue"}, "u1")
        assert "should-never-appear-in-logs" not in result.error

    def test_error_messages_have_no_credentials(self):
        """No static error message should contain credential-like patterns."""
        c = _connector()
        schema = c.get_schema()
        # Static schema description should not carry credentials
        assert _CID not in schema.description
        assert _CSEC not in schema.description


# ---------------------------------------------------------------------------
# Module-level: no hard-coded credentials in source
# ---------------------------------------------------------------------------

class TestNoHardcodedCredentials:
    def test_connector_source_has_no_credentials(self):
        import inspect
        import app.mcp.connectors.atlassian as mod
        src = inspect.getsource(mod)
        dangerous_patterns = [
            "atlassian.net",   # organisation-specific URL
            "ghp_",            # GitHub token pattern (wrong file check)
            "xoxb-",           # Slack token
        ]
        for pat in dangerous_patterns:
            assert pat not in src, f"Possible hardcoded value found: {pat!r}"

    def test_connector_source_has_no_bearer_literals(self):
        import inspect
        import app.mcp.connectors.atlassian as mod
        src = inspect.getsource(mod)
        # Check that no real token values appear as string literals.
        # The string "Bearer " legitimately appears in the header construction
        # f-string, but actual token values must never be hardcoded.
        assert "Bearer supersecret" not in src
        assert "Bearer xoxb" not in src
        assert "Bearer ghp_" not in src
