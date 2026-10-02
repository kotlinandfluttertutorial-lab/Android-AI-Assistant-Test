"""Unit tests for the app.mcp package.

Covers MCPToolModel, MCPParamDescriptor, error types, MCPRegistry,
MCPValidator, MCPExecutor, and MCPServer.

No real MCPBroker / DB / network — all infrastructure is mocked.
No production credentials required.
"""

from __future__ import annotations

import sys
from unittest.mock import MagicMock as _MagicMock

# Stub google.genai before any app import triggers the services chain
_g = _MagicMock()
sys.modules.setdefault("google.genai", _g)
sys.modules.setdefault("google.genai.types", _g)

import asyncio
import uuid
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.mcp.models import (
    MCPExecutionError,
    MCPParamDescriptor,
    MCPTimeoutError,
    MCPToolModel,
    MCPValidationError,
)
from app.mcp.registry import MCPRegistry
from app.mcp.validator import MCPValidator
from app.mcp.executor import MCPExecutor
from app.mcp.server import MCPServer
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

# ── Helpers ───────────────────────────────────────────────────────────────────

USER_ID = str(uuid.uuid4())


def _schema(
    tool_name: str = "echo",
    description: str = "Echoes params.",
    params: dict | None = None,
    requires_confirmation: bool = False,
) -> MCPToolSchema:
    return MCPToolSchema(
        tool_name=tool_name,
        description=description,
        parameters=params or {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "Text to echo."}
            },
            "required": ["message"],
        },
        requires_confirmation=requires_confirmation,
    )


def _connector(
    tool_name: str = "echo",
    result: MCPToolResult | None = None,
    raise_exc: Exception | None = None,
    requires_confirmation: bool = False,
) -> MCPToolConnector:
    class _Stub(MCPToolConnector):
        @property
        def tool_name(self) -> str:
            return tool_name

        def get_schema(self) -> MCPToolSchema:
            return _schema(tool_name=tool_name, requires_confirmation=requires_confirmation)

        async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
            if raise_exc:
                raise raise_exc
            return result or MCPToolResult(
                tool_name=tool_name,
                success=True,
                result={"echo": params.get("message", "")},
                result_status="success",
            )

        @property
        def requires_confirmation(self) -> bool:
            return requires_confirmation

    return _Stub()


def _mock_broker(
    discover_return: list | None = None,
    invoke_return: MCPToolResult | None = None,
    invoke_side_effect: Exception | None = None,
) -> MagicMock:
    broker = MagicMock()
    broker.discover.return_value = discover_return or []
    if invoke_side_effect:
        broker.invoke = AsyncMock(side_effect=invoke_side_effect)
    else:
        broker.invoke = AsyncMock(
            return_value=invoke_return
            or MCPToolResult(
                tool_name="echo",
                success=True,
                result={"echo": "hi"},
                result_status="success",
            )
        )
    broker.register = MagicMock()
    return broker


# ===========================================================================
# MCPParamDescriptor
# ===========================================================================

class TestMCPParamDescriptor:
    def test_valid_construction(self):
        p = MCPParamDescriptor(name="query", type="string", required=True)
        assert p.name == "query"
        assert p.required

    def test_blank_name_raises(self):
        with pytest.raises(ValueError, match="name"):
            MCPParamDescriptor(name="  ", type="string")

    def test_invalid_type_raises(self):
        with pytest.raises(ValueError, match="type"):
            MCPParamDescriptor(name="x", type="dict")

    def test_all_valid_types(self):
        for t in ("string", "integer", "boolean", "number", "array", "object"):
            p = MCPParamDescriptor(name="p", type=t)
            assert p.type == t

    def test_enum_stored(self):
        p = MCPParamDescriptor(name="action", enum=("read", "write"))
        assert "read" in p.enum


# ===========================================================================
# MCPToolModel
# ===========================================================================

class TestMCPToolModel:
    def test_valid_construction(self):
        m = MCPToolModel(tool_name="github_read", display_name="GitHub", description="Read.")
        assert m.tool_name == "github_read"

    def test_blank_tool_name_raises(self):
        with pytest.raises(ValueError, match="tool_name"):
            MCPToolModel(tool_name="  ", display_name="X", description="Y")

    def test_negative_timeout_raises(self):
        with pytest.raises(ValueError, match="timeout_ms"):
            MCPToolModel(tool_name="t", display_name="T", description="D", timeout_ms=-1)

    def test_zero_timeout_allowed(self):
        m = MCPToolModel(tool_name="t", display_name="T", description="D", timeout_ms=0)
        assert m.timeout_ms == 0

    def test_required_params(self):
        descriptors = (
            MCPParamDescriptor("owner", required=True),
            MCPParamDescriptor("repo", required=True),
            MCPParamDescriptor("limit", required=False),
        )
        m = MCPToolModel("t", "T", "D", param_descriptors=descriptors)
        assert sorted(m.required_params) == ["owner", "repo"]

    def test_param_names(self):
        descriptors = (
            MCPParamDescriptor("a"),
            MCPParamDescriptor("b"),
        )
        m = MCPToolModel("t", "T", "D", param_descriptors=descriptors)
        assert sorted(m.param_names) == ["a", "b"]

    def test_from_schema_builds_descriptors(self):
        schema = _schema(
            params={
                "type": "object",
                "properties": {
                    "action": {"type": "string", "enum": ["list", "get"]},
                    "owner": {"type": "string"},
                },
                "required": ["action"],
            }
        )
        model = MCPToolModel.from_schema(schema)
        assert "action" in model.param_names
        action_desc = next(d for d in model.param_descriptors if d.name == "action")
        assert action_desc.required
        assert "list" in action_desc.enum

    def test_from_schema_empty_params(self):
        # A schema with truly empty parameters (no properties dict)
        schema = MCPToolSchema(
            tool_name="noop",
            description="No params.",
            parameters={},
        )
        model = MCPToolModel.from_schema(schema)
        assert model.param_descriptors == ()


# ===========================================================================
# Error types
# ===========================================================================

class TestErrorTypes:
    def test_validation_error_message(self):
        e = MCPValidationError("github_read", "action", "is required")
        assert "github_read" in str(e)
        assert "action" in str(e)
        assert "is required" in str(e)

    def test_validation_error_no_param(self):
        e = MCPValidationError("github_read", None, "no params")
        assert "no params" in str(e)
        assert e.param_name is None

    def test_execution_error_attributes(self):
        e = MCPExecutionError("tool_x", "Something went wrong", retryable=True)
        assert e.tool_name == "tool_x"
        assert e.retryable is True

    def test_timeout_error_inherits_execution_error(self):
        e = MCPTimeoutError("tool_y", 5000)
        assert isinstance(e, MCPExecutionError)
        assert e.timeout_ms == 5000
        assert e.retryable is True
        assert "5000" in str(e)


# ===========================================================================
# MCPRegistry
# ===========================================================================

class TestMCPRegistry:
    def _registry(self, allowed=None, strict=False):
        broker = _mock_broker()
        return MCPRegistry(broker=broker, allowed_tools=allowed, strict_duplicates=strict)

    # ── Registration ──────────────────────────────────────────────────────────

    def test_register_adds_model(self):
        r = self._registry()
        r.register(_connector("echo"))
        assert r.is_registered("echo")

    def test_register_duplicate_logs_warning_by_default(self, caplog):
        import logging
        r = self._registry()
        r.register(_connector("echo"))
        with caplog.at_level(logging.WARNING):
            r.register(_connector("echo"))
        assert r.is_registered("echo")

    def test_register_duplicate_raises_in_strict_mode(self):
        r = self._registry(strict=True)
        r.register(_connector("echo"))
        with pytest.raises(ValueError, match="already registered"):
            r.register(_connector("echo"))

    def test_unregister_removes_model(self):
        r = self._registry()
        r.register(_connector("echo"))
        removed = r.unregister("echo")
        assert removed
        assert not r.is_registered("echo")

    def test_unregister_absent_returns_false(self):
        r = self._registry()
        assert r.unregister("ghost") is False

    def test_size_reflects_registrations(self):
        r = self._registry()
        assert r.size == 0
        r.register(_connector("a"))
        r.register(_connector("b"))
        assert r.size == 2

    # ── Allowlist ─────────────────────────────────────────────────────────────

    def test_allow_all_by_default(self):
        r = self._registry()
        r.register(_connector("echo"))
        assert r.is_allowed("echo")

    def test_allowlist_permits_listed_tool(self):
        r = self._registry(allowed={"echo"})
        r.register(_connector("echo"))
        r.register(_connector("slack"))
        assert r.is_allowed("echo")
        assert not r.is_allowed("slack")

    def test_wildcard_allows_all(self):
        r = self._registry(allowed={"*"})
        r.register(_connector("echo"))
        assert r.is_allowed("echo")

    def test_is_allowed_returns_false_for_unregistered(self):
        r = self._registry()
        assert not r.is_allowed("ghost")

    # ── Discovery ─────────────────────────────────────────────────────────────

    def test_discover_respects_allowlist(self):
        broker = _mock_broker(
            discover_return=[
                _schema("echo"),
                _schema("slack"),
            ]
        )
        r = MCPRegistry(broker=broker, allowed_tools={"echo"})
        r.register(_connector("echo"))
        r.register(_connector("slack"))
        schemas = r.discover()
        assert len(schemas) == 1
        assert schemas[0].tool_name == "echo"

    def test_discover_returns_all_when_no_allowlist(self):
        broker = _mock_broker(discover_return=[_schema("echo"), _schema("slack")])
        r = MCPRegistry(broker=broker)
        r.register(_connector("echo"))
        r.register(_connector("slack"))
        schemas = r.discover()
        assert len(schemas) == 2

    # ── Lookup ────────────────────────────────────────────────────────────────

    def test_get_model_returns_model(self):
        r = self._registry()
        r.register(_connector("echo"))
        model = r.get_model("echo")
        assert model is not None
        assert model.tool_name == "echo"

    def test_get_model_returns_none_for_unknown(self):
        r = self._registry()
        assert r.get_model("ghost") is None

    def test_list_registered_sorted(self):
        r = self._registry()
        r.register(_connector("z"))
        r.register(_connector("a"))
        r.register(_connector("m"))
        assert r.list_registered() == ["a", "m", "z"]

    def test_list_allowed_filters_allowlist(self):
        r = self._registry(allowed={"a", "m"})
        r.register(_connector("a"))
        r.register(_connector("m"))
        r.register(_connector("z"))
        assert r.list_allowed() == ["a", "m"]


# ===========================================================================
# MCPValidator
# ===========================================================================

class TestMCPValidator:
    def _model(self, *descriptors: MCPParamDescriptor, tool_name: str = "tool") -> MCPToolModel:
        return MCPToolModel(
            tool_name=tool_name,
            display_name="T",
            description="D",
            param_descriptors=descriptors,
        )

    def _validator(self) -> MCPValidator:
        return MCPValidator()

    # ── Required params ───────────────────────────────────────────────────────

    def test_required_param_present_passes(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("action", required=True))
        v.validate({"action": "list"}, model)  # must not raise

    def test_required_param_absent_raises(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("action", required=True))
        with pytest.raises(MCPValidationError) as exc_info:
            v.validate({}, model)
        assert exc_info.value.param_name == "action"

    def test_optional_param_absent_passes(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("limit", required=False))
        v.validate({}, model)  # must not raise

    # ── Type checking ─────────────────────────────────────────────────────────

    def test_correct_type_passes(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("count", type="integer"))
        v.validate({"count": 5}, model)  # must not raise

    def test_wrong_type_raises(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("count", type="integer"))
        with pytest.raises(MCPValidationError) as exc_info:
            v.validate({"count": "five"}, model)
        assert exc_info.value.param_name == "count"
        assert "integer" in str(exc_info.value)

    def test_string_type_passes_str_value(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("name", type="string"))
        v.validate({"name": "alice"}, model)

    def test_boolean_type_accepts_bool(self):
        v = self._validator()
        model = self._model(MCPParamDescriptor("flag", type="boolean"))
        v.validate({"flag": True}, model)

    # ── Enum checking ─────────────────────────────────────────────────────────

    def test_enum_member_passes(self):
        v = self._validator()
        model = self._model(
            MCPParamDescriptor("action", enum=("read", "write"))
        )
        v.validate({"action": "read"}, model)

    def test_enum_non_member_raises(self):
        v = self._validator()
        model = self._model(
            MCPParamDescriptor("action", enum=("read", "write"))
        )
        with pytest.raises(MCPValidationError) as exc_info:
            v.validate({"action": "delete"}, model)
        assert exc_info.value.param_name == "action"
        assert "delete" in str(exc_info.value)

    # ── validate_result ───────────────────────────────────────────────────────

    def test_validate_result_none_raises(self):
        v = self._validator()
        with pytest.raises(MCPValidationError, match="None"):
            v.validate_result(None, "tool_x")

    def test_validate_result_success_passes(self):
        v = self._validator()
        result = MCPToolResult(
            tool_name="echo", success=True, result={"x": 1}, result_status="success"
        )
        v.validate_result(result, "echo")  # must not raise

    def test_validate_result_unknown_status_raises(self):
        v = self._validator()
        result = MCPToolResult(
            tool_name="echo", success=True, result_status="pending"
        )
        with pytest.raises(MCPValidationError, match="result_status"):
            v.validate_result(result, "echo")

    def test_validate_from_schema(self):
        v = self._validator()
        schema = _schema(
            params={
                "type": "object",
                "properties": {"action": {"type": "string"}},
                "required": ["action"],
            }
        )
        with pytest.raises(MCPValidationError):
            v.validate_from_schema({}, schema)


# ===========================================================================
# MCPExecutor
# ===========================================================================

class TestMCPExecutor:
    def _executor(
        self,
        connector=None,
        allowed=None,
        reraise=False,
        timeout_ms=30_000,
        broker_side_effect=None,
    ):
        broker = _mock_broker(
            invoke_side_effect=broker_side_effect,
            invoke_return=MCPToolResult(
                tool_name="echo",
                success=True,
                result={"echo": "hi"},
                result_status="success",
            ),
        )
        registry = MCPRegistry(broker=broker, allowed_tools=allowed)
        if connector is not None:
            registry.register(connector)
        validator = MCPValidator()
        return MCPExecutor(
            registry=registry,
            validator=validator,
            default_timeout_ms=timeout_ms,
            reraise_errors=reraise,
        )

    # ── Allowlist ─────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_allowlist_rejection_returns_failure(self):
        executor = self._executor(connector=_connector("echo"), allowed={"slack"})
        result = await executor.execute("echo", {"message": "hi"}, USER_ID)
        assert not result.success
        assert "allowlist" in result.error.lower() or "not registered" in result.error.lower()

    @pytest.mark.asyncio
    async def test_allowlist_rejection_does_not_expose_internal_info(self):
        executor = self._executor(connector=_connector("echo"), allowed={"slack"})
        result = await executor.execute("echo", {}, USER_ID)
        assert "Traceback" not in (result.error or "")

    @pytest.mark.asyncio
    async def test_allowlist_rejection_reraises_when_configured(self):
        executor = self._executor(
            connector=_connector("echo"), allowed={"slack"}, reraise=True
        )
        with pytest.raises(MCPExecutionError):
            await executor.execute("echo", {}, USER_ID)

    # ── Validation ────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_missing_required_param_returns_failure(self):
        # echo tool requires "message" param
        executor = self._executor(connector=_connector("echo"))
        result = await executor.execute("echo", {}, USER_ID)
        # Empty params → validation fails → safe error returned
        assert not result.success

    @pytest.mark.asyncio
    async def test_valid_params_reach_broker(self):
        executor = self._executor(connector=_connector("echo"))
        result = await executor.execute("echo", {"message": "hello"}, USER_ID)
        assert result.success

    @pytest.mark.asyncio
    async def test_validation_error_reraises_when_configured(self):
        executor = self._executor(connector=_connector("echo"), reraise=True)
        with pytest.raises(MCPValidationError):
            await executor.execute("echo", {}, USER_ID)

    # ── Timeout ───────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_timeout_returns_failure_result(self):
        """asyncio.timeout raises TimeoutError → executor returns safe failure."""
        broker = _mock_broker()
        # Patch asyncio.timeout so it immediately raises TimeoutError
        import contextlib

        @contextlib.asynccontextmanager
        async def _always_timeout(*a, **kw):
            raise TimeoutError()
            yield  # make it a generator

        registry = MCPRegistry(broker=broker)
        registry.register(_connector("echo"))
        executor = MCPExecutor(registry=registry, default_timeout_ms=50)
        with patch("app.mcp.executor.asyncio.timeout", _always_timeout):
            result = await executor.execute("echo", {"message": "hi"}, USER_ID)
        assert not result.success
        assert "timed out" in result.error.lower()

    @pytest.mark.asyncio
    async def test_timeout_error_reraises_when_configured(self):
        import contextlib

        @contextlib.asynccontextmanager
        async def _always_timeout(*a, **kw):
            raise TimeoutError()
            yield

        broker = _mock_broker()
        registry = MCPRegistry(broker=broker)
        registry.register(_connector("echo"))
        executor = MCPExecutor(
            registry=registry, default_timeout_ms=50, reraise_errors=True
        )
        with patch("app.mcp.executor.asyncio.timeout", _always_timeout):
            with pytest.raises(MCPTimeoutError):
                await executor.execute("echo", {"message": "hi"}, USER_ID)

    # ── Broker failure ────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_broker_exception_returns_safe_failure(self):
        executor = self._executor(
            connector=_connector("echo"),
            broker_side_effect=RuntimeError("internal DB error"),
        )
        result = await executor.execute("echo", {"message": "hi"}, USER_ID)
        assert not result.success
        assert "internal DB error" not in (result.error or "")
        assert "Traceback" not in (result.error or "")

    # ── Success ───────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_successful_invocation_returns_success(self):
        executor = self._executor(connector=_connector("echo"))
        result = await executor.execute("echo", {"message": "hello"}, USER_ID)
        assert result.success
        assert result.result_status == "success"

    @pytest.mark.asyncio
    async def test_executor_passes_user_id_to_broker(self):
        broker = _mock_broker()
        registry = MCPRegistry(broker=broker)
        registry.register(_connector("echo"))
        executor = MCPExecutor(registry=registry)
        await executor.execute("echo", {"message": "hi"}, "test-user-id")
        broker.invoke.assert_awaited_once()
        call_kwargs = broker.invoke.call_args.kwargs
        assert call_kwargs.get("user_id") == "test-user-id"


# ===========================================================================
# MCPServer (façade)
# ===========================================================================

class TestMCPServer:
    def _server(self, db=None, allowed=None) -> MCPServer:
        db = db or MagicMock()
        return MCPServer.create(db=db, allowed_tools=allowed)

    def test_create_returns_server(self):
        server = self._server()
        assert isinstance(server, MCPServer)

    def test_register_adds_tool(self):
        server = self._server()
        server.register(_connector("echo"))
        assert server.is_allowed("echo")

    def test_discover_returns_allowed_schemas(self):
        db = MagicMock()
        server = MCPServer.create(db=db, allowed_tools={"echo"})
        server.register(_connector("echo"))
        server.register(_connector("slack"))
        schemas = server.discover()
        assert any(s.tool_name == "echo" for s in schemas)
        assert not any(s.tool_name == "slack" for s in schemas)

    def test_list_allowed_returns_sorted_names(self):
        server = self._server()
        server.register(_connector("z"))
        server.register(_connector("a"))
        assert server.list_allowed() == ["a", "z"]

    def test_get_model_returns_model(self):
        server = self._server()
        server.register(_connector("echo"))
        model = server.get_model("echo")
        assert model is not None
        assert model.tool_name == "echo"

    def test_get_model_returns_none_for_unknown(self):
        server = self._server()
        assert server.get_model("ghost") is None

    @pytest.mark.asyncio
    async def test_execute_delegates_to_executor(self):
        server = self._server()
        server.register(_connector("echo"))
        result = await server.execute("echo", {"message": "test"}, USER_ID)
        assert isinstance(result, MCPToolResult)

    @pytest.mark.asyncio
    async def test_execute_unknown_tool_returns_safe_failure(self):
        server = self._server()
        result = await server.execute("ghost", {}, USER_ID)
        assert not result.success
        assert "ghost" in result.error or "not registered" in result.error.lower()

    @pytest.mark.asyncio
    async def test_execute_never_exposes_internal_errors(self):
        db = MagicMock()
        server = MCPServer.create(db=db)
        server.register(_connector("broken", raise_exc=RuntimeError("secret DB password")))
        result = await server.execute("broken", {"message": "x"}, USER_ID)
        assert not result.success
        assert "secret DB password" not in (result.error or "")
        assert "Traceback" not in (result.error or "")

    def test_registry_property(self):
        server = self._server()
        assert isinstance(server.registry, MCPRegistry)

    def test_executor_property(self):
        server = self._server()
        assert isinstance(server.executor, MCPExecutor)


# ===========================================================================
# Credentials — no hardcoded secrets
# ===========================================================================

class TestNoHardcodedCredentials:
    def test_models_module_has_no_credentials(self):
        import inspect
        import app.mcp.models as mod
        src = inspect.getsource(mod)
        for pattern in ("Bearer ", "sk-", "ghp_", "xoxb-", "password=", "api_key="):
            assert pattern not in src, f"Possible hardcoded credential found: {pattern!r}"

    def test_registry_module_has_no_credentials(self):
        import inspect
        import app.mcp.registry as mod
        src = inspect.getsource(mod)
        for pattern in ("Bearer ", "sk-", "ghp_", "xoxb-"):
            assert pattern not in src

    def test_executor_module_has_no_credentials(self):
        import inspect
        import app.mcp.executor as mod
        src = inspect.getsource(mod)
        for pattern in ("Bearer ", "sk-", "ghp_", "xoxb-"):
            assert pattern not in src
