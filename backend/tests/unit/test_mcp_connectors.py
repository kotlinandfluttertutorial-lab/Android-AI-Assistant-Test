"""Unit tests for MCP tool connectors.

Covers all eight connector services (GitHub, Gmail, Google Drive, Google Calendar,
Slack, Jira, Notion, Figma) across both read and write variants.

Tests:
1. get_schema() returns valid MCPToolSchema for every connector.
2. Read connectors have requires_confirmation = False.
3. Write connectors have requires_confirmation = True.
4. Figma (read-only) has requires_confirmation = False.
5. invoke() with mocked HTTP client returns MCPToolResult with success=True.
6. invoke() when API returns HTTP error returns MCPToolResult with success=False.
7. invoke() with unknown action returns MCPToolResult with success=False.
8. Error messages in MCPToolResult.error never expose raw exception internals.

Requirements: 8.2, 8.3, 8.5
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_connectors import (
    FigmaReadConnector,
    GCalReadConnector,
    GCalWriteConnector,
    GDriveReadConnector,
    GDriveWriteConnector,
    GitHubReadConnector,
    GitHubWriteConnector,
    GmailReadConnector,
    GmailWriteConnector,
    JiraReadConnector,
    JiraWriteConnector,
    NotionReadConnector,
    NotionWriteConnector,
    SlackReadConnector,
    SlackWriteConnector,
)

# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

USER_ID = "00000000-0000-0000-0000-000000000001"

# All read connectors — (connector_instance, expected tool_name)
READ_CONNECTORS = [
    (GitHubReadConnector(), "github_read"),
    (GmailReadConnector(), "gmail_read"),
    (GDriveReadConnector(), "gdrive_read"),
    (GCalReadConnector(), "gcal_read"),
    (SlackReadConnector(), "slack_read"),
    (JiraReadConnector(), "jira_read"),
    (NotionReadConnector(), "notion_read"),
    (FigmaReadConnector(), "figma_read"),
]

# All write connectors — (connector_instance, expected tool_name)
WRITE_CONNECTORS = [
    (GitHubWriteConnector(), "github_write"),
    (GmailWriteConnector(), "gmail_write"),
    (GDriveWriteConnector(), "gdrive_write"),
    (GCalWriteConnector(), "gcal_write"),
    (SlackWriteConnector(), "slack_write"),
    (JiraWriteConnector(), "jira_write"),
    (NotionWriteConnector(), "notion_write"),
]


def _make_http_response(json_data: dict, status_code: int = 200) -> MagicMock:
    """Return a mock httpx response with raise_for_status() support."""
    resp = MagicMock()
    resp.json.return_value = json_data
    resp.status_code = status_code
    if status_code >= 400:
        import httpx

        resp.raise_for_status.side_effect = httpx.HTTPStatusError(
            message=f"HTTP {status_code}",
            request=MagicMock(),
            response=MagicMock(status_code=status_code),
        )
    else:
        resp.raise_for_status.return_value = None
    return resp


# ---------------------------------------------------------------------------
# Test 1: get_schema() returns valid MCPToolSchema
# ---------------------------------------------------------------------------


class TestGetSchema:
    """Every connector must return a valid MCPToolSchema from get_schema()."""

    @pytest.mark.parametrize(
        "connector, expected_name", READ_CONNECTORS + WRITE_CONNECTORS
    )
    def test_schema_is_mcp_tool_schema(self, connector, expected_name) -> None:
        schema = connector.get_schema()
        assert isinstance(schema, MCPToolSchema)

    @pytest.mark.parametrize(
        "connector, expected_name", READ_CONNECTORS + WRITE_CONNECTORS
    )
    def test_schema_tool_name_matches_connector(self, connector, expected_name) -> None:
        schema = connector.get_schema()
        assert schema.tool_name == expected_name
        assert schema.tool_name == connector.tool_name

    @pytest.mark.parametrize(
        "connector, expected_name", READ_CONNECTORS + WRITE_CONNECTORS
    )
    def test_schema_has_non_empty_description(self, connector, expected_name) -> None:
        schema = connector.get_schema()
        assert schema.description
        assert len(schema.description) > 0

    @pytest.mark.parametrize(
        "connector, expected_name", READ_CONNECTORS + WRITE_CONNECTORS
    )
    def test_schema_has_parameters_dict(self, connector, expected_name) -> None:
        schema = connector.get_schema()
        assert isinstance(schema.parameters, dict)

    @pytest.mark.parametrize(
        "connector, expected_name", READ_CONNECTORS + WRITE_CONNECTORS
    )
    def test_schema_requires_confirmation_matches_connector_property(
        self, connector, expected_name
    ) -> None:
        schema = connector.get_schema()
        assert schema.requires_confirmation == connector.requires_confirmation


# ---------------------------------------------------------------------------
# Test 2 & 3: requires_confirmation flags
# ---------------------------------------------------------------------------


class TestRequiresConfirmation:
    """Read connectors must be False; write connectors must be True."""

    @pytest.mark.parametrize("connector, _", READ_CONNECTORS)
    def test_read_connectors_do_not_require_confirmation(self, connector, _) -> None:
        assert connector.requires_confirmation is False

    @pytest.mark.parametrize("connector, _", WRITE_CONNECTORS)
    def test_write_connectors_require_confirmation(self, connector, _) -> None:
        assert connector.requires_confirmation is True

    def test_figma_read_only_no_confirmation(self) -> None:
        """Figma is read-only; it must never require confirmation."""
        assert FigmaReadConnector().requires_confirmation is False

    def test_all_write_connector_schemas_have_requires_confirmation_true(self) -> None:
        for connector, _ in WRITE_CONNECTORS:
            schema = connector.get_schema()
            assert (
                schema.requires_confirmation is True
            ), f"{connector.tool_name} schema mismatch"


# ---------------------------------------------------------------------------
# Test 4: invoke() with mocked HTTP client — success paths
# ---------------------------------------------------------------------------


class TestInvokeSuccessGitHub:
    """GitHub connectors return MCPToolResult on success."""

    @pytest.mark.asyncio
    async def test_github_read_search_repos(self) -> None:
        connector = GitHubReadConnector()
        mock_resp = _make_http_response(
            {"total_count": 1, "items": [{"id": 1, "name": "test-repo"}]}
        )
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "search_repos", "query": "fastapi"}, USER_ID
            )

        assert isinstance(result, MCPToolResult)
        assert result.success is True
        assert result.tool_name == "github_read"
        assert result.result is not None

    @pytest.mark.asyncio
    async def test_github_read_list_issues(self) -> None:
        connector = GitHubReadConnector()
        mock_resp = _make_http_response([{"id": 1, "title": "Bug"}])
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "list_issues", "owner": "octocat", "repo": "hello"}, USER_ID
            )

        assert result.success is True
        assert "issues" in result.result  # type: ignore[operator]

    @pytest.mark.asyncio
    async def test_github_read_unknown_action(self) -> None:
        connector = GitHubReadConnector()
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch(
            "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "does_not_exist"}, USER_ID)

        assert result.success is False
        assert result.result_status == "error"

    @pytest.mark.asyncio
    async def test_github_write_create_issue(self) -> None:
        connector = GitHubWriteConnector()
        mock_resp = _make_http_response({"id": 42, "number": 1, "title": "New Bug"})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {
                    "action": "create_issue",
                    "owner": "octocat",
                    "repo": "hello",
                    "title": "New Bug",
                    "body": "desc",
                },
                USER_ID,
            )

        assert result.success is True
        assert result.tool_name == "github_write"


class TestInvokeSuccessSlack:
    """Slack connectors return MCPToolResult on success."""

    @pytest.mark.asyncio
    async def test_slack_read_list_channels(self) -> None:
        connector = SlackReadConnector()
        mock_resp = _make_http_response(
            {"ok": True, "channels": [{"id": "C123", "name": "general"}]}
        )
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.slack_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "list_channels"}, USER_ID)

        assert result.success is True
        assert "channels" in result.result  # type: ignore[operator]

    @pytest.mark.asyncio
    async def test_slack_read_api_error_response(self) -> None:
        """Slack returns ok=False without raising — connector should return success=False."""
        connector = SlackReadConnector()
        mock_resp = _make_http_response({"ok": False, "error": "channel_not_found"})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.slack_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "list_channels"}, USER_ID)

        assert result.success is False
        assert result.result_status == "error"

    @pytest.mark.asyncio
    async def test_slack_write_post_message(self) -> None:
        connector = SlackWriteConnector()
        mock_resp = _make_http_response({"ok": True, "ts": "1234567890.123456"})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.slack_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "post_message", "channel_id": "C123", "text": "Hello!"},
                USER_ID,
            )

        assert result.success is True
        assert result.result is not None

    @pytest.mark.asyncio
    async def test_slack_write_unknown_action(self) -> None:
        connector = SlackWriteConnector()
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch(
            "app.services.mcp_connectors.slack_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "noop"}, USER_ID)

        assert result.success is False


class TestInvokeSuccessJira:
    """Jira connectors return MCPToolResult on success."""

    @pytest.mark.asyncio
    async def test_jira_read_search_issues(self) -> None:
        connector = JiraReadConnector()
        mock_resp = _make_http_response(
            {"issues": [{"id": "10000", "key": "PROJ-1"}], "total": 1}
        )
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.jira_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "search_issues", "jql": "project=PROJ"}, USER_ID
            )

        assert result.success is True
        assert result.tool_name == "jira_read"

    @pytest.mark.asyncio
    async def test_jira_write_create_issue(self) -> None:
        connector = JiraWriteConnector()
        mock_resp = _make_http_response({"id": "10001", "key": "PROJ-2"})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.jira_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {
                    "action": "create_issue",
                    "project_key": "PROJ",
                    "summary": "New bug",
                    "description": "Details",
                },
                USER_ID,
            )

        assert result.success is True
        assert result.tool_name == "jira_write"

    @pytest.mark.asyncio
    async def test_jira_read_unknown_action(self) -> None:
        connector = JiraReadConnector()
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch(
            "app.services.mcp_connectors.jira_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "fly"}, USER_ID)

        assert result.success is False
        assert result.result_status == "error"


class TestInvokeSuccessNotion:
    """Notion connectors return MCPToolResult on success."""

    @pytest.mark.asyncio
    async def test_notion_read_search_pages(self) -> None:
        connector = NotionReadConnector()
        mock_resp = _make_http_response({"results": [{"id": "abc", "object": "page"}]})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.notion_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "search_pages", "query": "meeting notes"}, USER_ID
            )

        assert result.success is True
        assert result.tool_name == "notion_read"

    @pytest.mark.asyncio
    async def test_notion_write_create_page(self) -> None:
        connector = NotionWriteConnector()
        mock_resp = _make_http_response({"id": "page-id-123", "object": "page"})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.notion_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {
                    "action": "create_page",
                    "parent_id": "parent-123",
                    "title": "My Page",
                    "content": "Hello",
                },
                USER_ID,
            )

        assert result.success is True
        assert result.tool_name == "notion_write"


class TestInvokeSuccessFigma:
    """Figma connector returns MCPToolResult on success."""

    @pytest.mark.asyncio
    async def test_figma_get_file(self) -> None:
        connector = FigmaReadConnector()
        mock_resp = _make_http_response({"name": "My Design", "document": {}})
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with patch(
            "app.services.mcp_connectors.figma_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke(
                {"action": "get_file", "file_key": "abc123"}, USER_ID
            )

        assert result.success is True
        assert result.tool_name == "figma_read"

    @pytest.mark.asyncio
    async def test_figma_unknown_action(self) -> None:
        connector = FigmaReadConnector()
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch(
            "app.services.mcp_connectors.figma_connector.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await connector.invoke({"action": "create_component"}, USER_ID)

        assert result.success is False
        assert result.result_status == "error"


# ---------------------------------------------------------------------------
# Test 5: invoke() when API returns HTTP error — connector raises; broker wraps
# ---------------------------------------------------------------------------


class TestInvokeHttpError:
    """When the upstream API returns an HTTP error, the connector raises and the
    broker's exception handler produces a safe MCPToolResult error.
    We test that the connector itself raises (not silently swallows) on HTTP errors."""

    @pytest.mark.asyncio
    async def test_github_read_raises_on_http_error(self) -> None:
        import httpx

        connector = GitHubReadConnector()
        mock_resp = _make_http_response({}, status_code=401)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            pytest.raises(httpx.HTTPStatusError),
        ):
            await connector.invoke({"action": "search_repos", "query": "test"}, USER_ID)

    @pytest.mark.asyncio
    async def test_jira_read_raises_on_http_error(self) -> None:
        import httpx

        connector = JiraReadConnector()
        mock_resp = _make_http_response({}, status_code=403)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.jira_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            pytest.raises(httpx.HTTPStatusError),
        ):
            await connector.invoke({"action": "list_projects"}, USER_ID)

    @pytest.mark.asyncio
    async def test_notion_write_raises_on_http_error(self) -> None:
        import httpx

        connector = NotionWriteConnector()
        mock_resp = _make_http_response({}, status_code=400)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.notion_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            pytest.raises(httpx.HTTPStatusError),
        ):
            await connector.invoke(
                {
                    "action": "create_page",
                    "parent_id": "p",
                    "title": "t",
                    "content": "c",
                },
                USER_ID,
            )

    @pytest.mark.asyncio
    async def test_figma_raises_on_http_error(self) -> None:
        import httpx

        connector = FigmaReadConnector()
        mock_resp = _make_http_response({}, status_code=404)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.figma_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            pytest.raises(httpx.HTTPStatusError),
        ):
            await connector.invoke(
                {"action": "get_file", "file_key": "missing"}, USER_ID
            )


# ---------------------------------------------------------------------------
# Test 6: Error isolation — broker wraps connector exceptions safely
# ---------------------------------------------------------------------------


class TestBrokerErrorIsolation:
    """Verify the broker wraps connector exceptions so internal details are never
    exposed to the caller."""

    @pytest.mark.asyncio
    async def test_broker_wraps_github_http_error(self) -> None:
        from unittest.mock import AsyncMock, patch

        from sqlalchemy.ext.asyncio import AsyncSession

        from app.services.mcp_broker import MCPBroker

        mock_db = AsyncMock(spec=AsyncSession)
        mock_audit = MagicMock()
        mock_audit.log_mcp_invoke = AsyncMock(return_value=MagicMock())

        broker = MCPBroker(mock_db)
        broker.register(GitHubReadConnector())

        # Simulate the connector raising an HTTP error
        mock_resp = _make_http_response({}, status_code=500)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.github_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            patch("app.services.mcp_broker.AuditService", return_value=mock_audit),
        ):
            result = await broker.invoke(
                "github_read", {"action": "search_repos", "query": "x"}, USER_ID
            )

        assert result.success is False
        assert result.result_status == "error"
        # Safe message — must not contain raw HTTP error details
        assert result.error is not None
        assert "HTTPStatusError" not in (result.error or "")
        assert "500" not in (result.error or "")

    @pytest.mark.asyncio
    async def test_broker_wraps_slack_http_error(self) -> None:
        from sqlalchemy.ext.asyncio import AsyncSession

        from app.services.mcp_broker import MCPBroker

        mock_db = AsyncMock(spec=AsyncSession)
        mock_audit = MagicMock()
        mock_audit.log_mcp_invoke = AsyncMock(return_value=MagicMock())

        broker = MCPBroker(mock_db)
        broker.register(SlackReadConnector())

        mock_resp = _make_http_response({}, status_code=429)
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.get = AsyncMock(return_value=mock_resp)

        with (
            patch(
                "app.services.mcp_connectors.slack_connector.httpx.AsyncClient",
                return_value=mock_client,
            ),
            patch("app.services.mcp_broker.AuditService", return_value=mock_audit),
        ):
            result = await broker.invoke(
                "slack_read", {"action": "list_channels"}, USER_ID
            )

        assert result.success is False
        assert result.result_status == "error"
        assert result.error == "Tool invocation failed. Please try again later."


# ---------------------------------------------------------------------------
# Test 7: All connectors registered in the router's _get_broker
# ---------------------------------------------------------------------------


class TestRouterConnectorRegistration:
    """The router's _get_broker function must register all 15 connectors."""

    def test_all_connectors_registered(self) -> None:
        from unittest.mock import AsyncMock

        from app.api.mcp.router import _get_broker

        mock_db = AsyncMock()
        broker = _get_broker(db=mock_db)
        schemas = broker.discover()

        tool_names = {s.tool_name for s in schemas}
        expected = {
            "github_read",
            "github_write",
            "gmail_read",
            "gmail_write",
            "gdrive_read",
            "gdrive_write",
            "gcal_read",
            "gcal_write",
            "slack_read",
            "slack_write",
            "jira_read",
            "jira_write",
            "notion_read",
            "notion_write",
            "figma_read",
        }
        assert tool_names == expected

    def test_broker_has_fifteen_connectors(self) -> None:
        from unittest.mock import AsyncMock

        from app.api.mcp.router import _get_broker

        mock_db = AsyncMock()
        broker = _get_broker(db=mock_db)
        assert len(broker.discover()) == 15

    def test_read_connectors_in_broker_have_no_confirmation(self) -> None:
        from unittest.mock import AsyncMock

        from app.api.mcp.router import _get_broker

        mock_db = AsyncMock()
        broker = _get_broker(db=mock_db)
        schemas = broker.discover()

        read_schemas = [s for s in schemas if s.tool_name.endswith("_read")]
        assert all(s.requires_confirmation is False for s in read_schemas)

    def test_write_connectors_in_broker_require_confirmation(self) -> None:
        from unittest.mock import AsyncMock

        from app.api.mcp.router import _get_broker

        mock_db = AsyncMock()
        broker = _get_broker(db=mock_db)
        schemas = broker.discover()

        write_schemas = [s for s in schemas if s.tool_name.endswith("_write")]
        assert all(s.requires_confirmation is True for s in write_schemas)
