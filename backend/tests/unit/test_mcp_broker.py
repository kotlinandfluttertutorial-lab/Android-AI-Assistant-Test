"""Unit tests for app.services.mcp_broker — MCPBroker registry/dispatcher.

Covers:
1. register + discover: registering 2 connectors; discover() returns both schemas.
2. invoke success: connector returns success; AuditLog written with result_status="success".
3. invoke error (connector raises): AuditLog written with result_status="error";
   result is safe (no internal details).
4. invoke unknown tool: AuditLog written with result_status="error"; success=False.
5. confirmation_required: AuditLog written with result_status="confirmation_required";
   connector's invoke() is NOT called.
6. OCP test: two separate connector subclasses registered and usable without
   modifying MCPBroker code.
7. no silent invocations (Property 12): exactly one AuditLog.add() per invoke() call
   for every code path.

Requirements: 9.1, 9.8
"""

from __future__ import annotations

import uuid
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPBroker, MCPToolConnector

# ---------------------------------------------------------------------------
# Test doubles — concrete MCPToolConnector implementations
# ---------------------------------------------------------------------------

SAMPLE_USER_ID = str(uuid.uuid4())


class _EchoConnector(MCPToolConnector):
    """A simple echo connector that always succeeds."""

    @property
    def tool_name(self) -> str:
        return "echo"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="echo",
            description="Echoes the input back.",
            parameters={"message": {"type": "string"}},
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        return MCPToolResult(
            tool_name="echo",
            success=True,
            result={"echo": params.get("message", "")},
            result_status="success",
        )


class _WriteConnector(MCPToolConnector):
    """A write connector that requires user confirmation before execution."""

    @property
    def tool_name(self) -> str:
        return "write_tool"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="write_tool",
            description="Performs a write operation.",
            parameters={"data": {"type": "string"}},
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        # Should never be called when requires_confirmation=True
        return MCPToolResult(
            tool_name="write_tool",
            success=True,
            result={"written": True},
            result_status="success",
        )

    @property
    def requires_confirmation(self) -> bool:
        return True


class _BrokenConnector(MCPToolConnector):
    """A connector that always raises an exception on invoke."""

    @property
    def tool_name(self) -> str:
        return "broken_tool"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="broken_tool",
            description="Always fails.",
            parameters={},
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        raise RuntimeError("Internal connector failure with stack trace details")


class _AnotherSuccessConnector(MCPToolConnector):
    """A second success connector used for OCP test."""

    @property
    def tool_name(self) -> str:
        return "another_tool"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="another_tool",
            description="Another successful connector.",
            parameters={"value": {"type": "integer"}},
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        return MCPToolResult(
            tool_name="another_tool",
            success=True,
            result={"processed": params.get("value", 0)},
            result_status="success",
        )


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_db_and_audit_mock():
    """Return (mock_db, mock_audit_instance, MockAuditService patch context).

    The mock_db is an AsyncMock for AsyncSession.
    mock_audit_instance is the instance that AuditService(db) returns; its
    log_mcp_invoke is an AsyncMock so we can assert call counts.
    """
    mock_db = AsyncMock()
    mock_audit_instance = MagicMock()
    mock_audit_instance.log_mcp_invoke = AsyncMock(return_value=MagicMock())
    return mock_db, mock_audit_instance


# ---------------------------------------------------------------------------
# Test 1: register + discover
# ---------------------------------------------------------------------------


class TestRegisterAndDiscover:
    """Register multiple connectors; verify discover() returns all schemas."""

    def test_discover_returns_all_registered_schemas(self) -> None:
        mock_db = AsyncMock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())
        broker.register(_WriteConnector())

        schemas = broker.discover()

        assert len(schemas) == 2
        tool_names = {s.tool_name for s in schemas}
        assert tool_names == {"echo", "write_tool"}

    def test_discover_schema_contents(self) -> None:
        mock_db = AsyncMock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        schemas = broker.discover()

        assert schemas[0].tool_name == "echo"
        assert schemas[0].description == "Echoes the input back."
        assert schemas[0].requires_confirmation is False

    def test_discover_empty_when_no_connectors(self) -> None:
        mock_db = AsyncMock()
        broker = MCPBroker(mock_db)
        assert broker.discover() == []


# ---------------------------------------------------------------------------
# Test 2: invoke success
# ---------------------------------------------------------------------------


class TestInvokeSuccess:
    """Connector invoke() returns success; AuditLog written with result_status='success'."""

    @pytest.mark.asyncio
    async def test_invoke_success_returns_true(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="echo",
                params={"message": "hello"},
                user_id=SAMPLE_USER_ID,
            )

        assert result.success is True
        assert result.result_status == "success"
        assert result.tool_name == "echo"
        assert result.result == {"echo": "hello"}

    @pytest.mark.asyncio
    async def test_invoke_success_writes_audit_log(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke(
                tool_name="echo",
                params={"message": "hello"},
                user_id=SAMPLE_USER_ID,
            )

        mock_audit.log_mcp_invoke.assert_called_once()
        call_kwargs = mock_audit.log_mcp_invoke.call_args.kwargs
        assert call_kwargs["tool"] == "echo"
        assert call_kwargs["params_summary"]["result_status"] == "success"

    @pytest.mark.asyncio
    async def test_invoke_success_has_no_error_field(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="echo",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        assert result.error is None


# ---------------------------------------------------------------------------
# Test 3: invoke error (connector raises exception)
# ---------------------------------------------------------------------------


class TestInvokeConnectorRaises:
    """When the connector raises, broker returns error result; AuditLog written."""

    @pytest.mark.asyncio
    async def test_returns_failure_result(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_BrokenConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="broken_tool",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        assert result.success is False
        assert result.result_status == "error"

    @pytest.mark.asyncio
    async def test_error_message_does_not_expose_internals(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_BrokenConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="broken_tool",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        # Must NOT expose internal exception details
        assert result.error is not None
        assert "Internal connector failure" not in (result.error or "")
        assert "stack trace" not in (result.error or "").lower()

    @pytest.mark.asyncio
    async def test_audit_log_written_with_error_status(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_BrokenConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke(
                tool_name="broken_tool",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        mock_audit.log_mcp_invoke.assert_called_once()
        call_kwargs = mock_audit.log_mcp_invoke.call_args.kwargs
        assert call_kwargs["params_summary"]["result_status"] == "error"


# ---------------------------------------------------------------------------
# Test 4: invoke unknown tool
# ---------------------------------------------------------------------------


class TestInvokeUnknownTool:
    """Invoking an unregistered tool returns error and writes audit log."""

    @pytest.mark.asyncio
    async def test_returns_error_for_unknown_tool(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="nonexistent",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        assert result.success is False
        assert result.result_status == "error"
        assert result.tool_name == "nonexistent"

    @pytest.mark.asyncio
    async def test_audit_log_written_for_unknown_tool(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke(
                tool_name="nonexistent",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        mock_audit.log_mcp_invoke.assert_called_once()
        call_kwargs = mock_audit.log_mcp_invoke.call_args.kwargs
        assert call_kwargs["tool"] == "nonexistent"
        assert call_kwargs["params_summary"]["result_status"] == "error"


# ---------------------------------------------------------------------------
# Test 5: confirmation_required
# ---------------------------------------------------------------------------


class TestConfirmationRequired:
    """Connectors with requires_confirmation=True return confirmation_required."""

    @pytest.mark.asyncio
    async def test_returns_confirmation_required_status(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_WriteConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            result = await broker.invoke(
                tool_name="write_tool",
                params={"data": "sensitive"},
                user_id=SAMPLE_USER_ID,
            )

        assert result.result_status == "confirmation_required"
        assert result.success is False

    @pytest.mark.asyncio
    async def test_connector_invoke_not_called(self) -> None:
        """The connector's invoke() must NOT be called when requires_confirmation=True."""
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)

        # Use a spy connector so we can detect if invoke() was called
        write_connector = _WriteConnector()
        write_connector.invoke = AsyncMock(  # type: ignore[method-assign]
            return_value=MCPToolResult(
                tool_name="write_tool",
                success=True,
                result_status="success",
            )
        )
        broker.register(write_connector)

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke(
                tool_name="write_tool",
                params={"data": "sensitive"},
                user_id=SAMPLE_USER_ID,
            )

        write_connector.invoke.assert_not_called()

    @pytest.mark.asyncio
    async def test_audit_log_written_for_confirmation_path(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_WriteConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke(
                tool_name="write_tool",
                params={},
                user_id=SAMPLE_USER_ID,
            )

        mock_audit.log_mcp_invoke.assert_called_once()
        call_kwargs = mock_audit.log_mcp_invoke.call_args.kwargs
        assert call_kwargs["params_summary"]["result_status"] == "confirmation_required"


# ---------------------------------------------------------------------------
# Test 6: OCP — adding two separate connector subclasses
# ---------------------------------------------------------------------------


class TestOCP:
    """Open/Closed Principle: two separate connector subclasses work without
    modifying MCPBroker code."""

    @pytest.mark.asyncio
    async def test_two_connectors_registered_and_discoverable(self) -> None:
        mock_db = AsyncMock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())
        broker.register(_AnotherSuccessConnector())

        schemas = broker.discover()
        tool_names = {s.tool_name for s in schemas}

        assert "echo" in tool_names
        assert "another_tool" in tool_names

    @pytest.mark.asyncio
    async def test_both_connectors_invocable(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())
        broker.register(_AnotherSuccessConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            echo_result = await broker.invoke(
                tool_name="echo",
                params={"message": "ocp test"},
                user_id=SAMPLE_USER_ID,
            )
            another_result = await broker.invoke(
                tool_name="another_tool",
                params={"value": 42},
                user_id=SAMPLE_USER_ID,
            )

        assert echo_result.success is True
        assert another_result.success is True
        assert echo_result.result == {"echo": "ocp test"}
        assert another_result.result == {"processed": 42}

    def test_mcpbroker_source_not_modified(self) -> None:
        """Structural test: MCPBroker has no references to concrete connectors.

        This verifies that the broker dispatches purely through the abstract
        MCPToolConnector interface (OCP).
        """
        import inspect

        import app.services.mcp_broker as broker_module

        source = inspect.getsource(broker_module.MCPBroker)

        # The broker must not name any concrete connector class
        assert "_EchoConnector" not in source
        assert "_WriteConnector" not in source
        assert "_AnotherSuccessConnector" not in source
        assert "_BrokenConnector" not in source


# ---------------------------------------------------------------------------
# Test 7: Property 12 — no silent invocations
# ---------------------------------------------------------------------------


class TestNoSilentInvocations:
    """Property 12: every invoke() path writes exactly one AuditLog entry.

    Validates: Requirements 9.8
    """

    @pytest.mark.asyncio
    async def test_success_path_writes_exactly_one_audit_entry(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("echo", {}, SAMPLE_USER_ID)

        assert mock_audit.log_mcp_invoke.call_count == 1

    @pytest.mark.asyncio
    async def test_error_path_writes_exactly_one_audit_entry(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_BrokenConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("broken_tool", {}, SAMPLE_USER_ID)

        assert mock_audit.log_mcp_invoke.call_count == 1

    @pytest.mark.asyncio
    async def test_confirmation_path_writes_exactly_one_audit_entry(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_WriteConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("write_tool", {}, SAMPLE_USER_ID)

        assert mock_audit.log_mcp_invoke.call_count == 1

    @pytest.mark.asyncio
    async def test_unknown_tool_path_writes_exactly_one_audit_entry(self) -> None:
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("nonexistent_tool", {}, SAMPLE_USER_ID)

        assert mock_audit.log_mcp_invoke.call_count == 1

    @pytest.mark.asyncio
    async def test_audit_log_receives_user_id_as_uuid(self) -> None:
        """Audit log must always receive a UUID, not a raw string."""
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("echo", {}, SAMPLE_USER_ID)

        call_kwargs = mock_audit.log_mcp_invoke.call_args.kwargs
        assert isinstance(call_kwargs["user_id"], uuid.UUID)

    @pytest.mark.asyncio
    async def test_audit_log_written_even_with_invalid_user_id(self) -> None:
        """Even an unparseable user_id must still result in one audit entry."""
        mock_db, mock_audit = _make_db_and_audit_mock()
        broker = MCPBroker(mock_db)
        broker.register(_EchoConnector())

        with patch("app.services.mcp_broker.AuditService", return_value=mock_audit):
            await broker.invoke("echo", {}, user_id="not-a-valid-uuid")

        assert mock_audit.log_mcp_invoke.call_count == 1
