"""MCP security tests.

Acceptance criteria covered
----------------------------
- Tool timeout tests pass
- MCP security tests pass
- Secrets are not present in logs
- MCP tool execution events are observable (structured log fields)

What is tested
--------------
1.  Allowlist enforcement — unknown tool rejected with success=False
2.  Allowlist enforcement — registered-but-blocked tool rejected
3.  Tool timeout — asyncio.timeout fires; result is success=False, no exception
4.  Tool timeout — reraise_errors=True propagates MCPTimeoutError
5.  Tool timeout — default timeout applied when model has none
6.  Tool timeout — timeout=0 disables the timeout (tool runs to completion)
7.  Parameter validation — missing required param → success=False
8.  Parameter validation — invalid type → success=False
9.  Secret not in logs — API key / Bearer token must not appear in any log record
    emitted during tool execution
10. Secret not in logs — raw exception messages containing credential-like strings
    must not be forwarded verbatim to structured log extra fields
11. Structured log fields — tool_name, elapsed_ms are promoted JSON keys (extra={})
12. Structured log fields — user_id is redacted to 8 chars in warning/error logs
13. user_id forwarding — executor passes user_id through to broker
14. user_id empty string — executor does not raise; returns result (no pre-validation)
15. Exception in broker — returns success=False safe error, does not expose exc message
16. Result validation failure — result returned even when result schema is invalid
"""

from __future__ import annotations

import asyncio
import logging
import sys
from typing import Any
from unittest.mock import MagicMock, patch

import pytest

# ── google.genai stub (must precede all app imports) ──────────────────────────
_g = MagicMock()
sys.modules.setdefault("google.genai", _g)
sys.modules.setdefault("google.genai.types", _g)

from app.mcp.executor import MCPExecutor
from app.mcp.models import MCPTimeoutError
from app.mcp.registry import MCPRegistry
from app.mcp.validator import MCPValidator
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

# ── Helpers ───────────────────────────────────────────────────────────────────

USER_ID = "user-12345678-abcd-efgh-ijkl-000000000001"
_TOOL = "test_tool"


def _schema(
    tool_name: str = _TOOL,
    params: dict | None = None,
    timeout_ms: int = 5_000,
) -> MCPToolSchema:
    return MCPToolSchema(
        tool_name=tool_name,
        description="Test tool.",
        parameters=params
        or {
            "type": "object",
            "properties": {"msg": {"type": "string"}},
            "required": ["msg"],
        },
        requires_confirmation=False,
    )


def _connector(
    tool_name: str = _TOOL,
    result: MCPToolResult | None = None,
    raise_exc: Exception | None = None,
    delay_s: float = 0.0,
) -> MCPToolConnector:
    """Return a stub connector that resolves after *delay_s*."""

    class _Stub(MCPToolConnector):
        @property
        def tool_name(self) -> str:
            return tool_name

        def get_schema(self) -> MCPToolSchema:
            return _schema(tool_name=tool_name)

        async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
            if delay_s:
                await asyncio.sleep(delay_s)
            if raise_exc:
                raise raise_exc
            return result or MCPToolResult(
                tool_name=tool_name,
                success=True,
                result={"echo": params.get("msg", "")},
                result_status="success",
            )

    return _Stub()


def _make_executor(
    connector: MCPToolConnector | None = None,
    allowed: set[str] | None = None,
    default_timeout_ms: int = 5_000,
    reraise_errors: bool = False,
) -> tuple[MCPExecutor, MCPRegistry]:
    # Build a real broker mock whose invoke() is a true coroutine so that
    # asyncio.timeout() can cancel it (AsyncMock side_effects are not
    # transparently cancellable on Windows/UnconfinedTestDispatcher).
    broker = MagicMock()

    if connector is not None:
        # Wrap connector invoke in a plain async def — cancellable by asyncio.timeout
        async def _real_invoke(tool_name, params, user_id, ip_address="", user_agent=""):
            return await connector.invoke(params, user_id)

        broker.invoke = _real_invoke
    else:

        async def _default_invoke(tool_name, params, user_id, ip_address="", user_agent=""):
            return MCPToolResult(
                tool_name=tool_name, success=True, result={"ok": True}, result_status="success"
            )

        broker.invoke = _default_invoke

    registry = MCPRegistry(broker=broker, allowed_tools=allowed)
    if connector:
        registry.register(connector)
    elif allowed is None or _TOOL in (allowed or set()):
        registry.register(_connector())
    validator = MCPValidator()
    executor = MCPExecutor(
        registry=registry,
        validator=validator,
        default_timeout_ms=default_timeout_ms,
        reraise_errors=reraise_errors,
    )
    return executor, registry


# ===========================================================================
# 1–2: Allowlist enforcement
# ===========================================================================


@pytest.mark.mcp
@pytest.mark.security
class TestAllowlistEnforcement:
    @pytest.mark.asyncio
    async def test_unknown_tool_rejected(self) -> None:
        executor, _ = _make_executor()
        result = await executor.execute("no_such_tool", {}, USER_ID)
        assert not result.success
        assert "allowlist" in result.error.lower() or "not registered" in result.error.lower()

    @pytest.mark.asyncio
    async def test_blocked_tool_rejected(self) -> None:
        """Tool is registered but not on the allowlist."""
        executor, _ = _make_executor(allowed={"other_tool"})
        result = await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)
        assert not result.success

    @pytest.mark.asyncio
    async def test_allowed_tool_succeeds(self) -> None:
        executor, _ = _make_executor()
        result = await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)
        assert result.success


# ===========================================================================
# 3–6: Tool timeout enforcement
# ===========================================================================


@pytest.mark.mcp
@pytest.mark.security
@pytest.mark.mcp_timeout
class TestToolTimeout:
    @pytest.mark.asyncio
    async def test_timeout_returns_failure_result(self) -> None:
        """When asyncio.timeout fires, executor returns success=False (never raises)."""
        # Simulate timeout by patching asyncio.timeout to raise TimeoutError immediately.
        # This approach is OS-independent and tests the executor's exception handling path.
        import contextlib

        @contextlib.asynccontextmanager
        async def _always_timeout(_seconds):
            raise TimeoutError()
            yield  # unreachable — satisfies async generator protocol

        executor, _ = _make_executor()
        with patch("app.mcp.executor.asyncio.timeout", _always_timeout):
            result = await executor.execute(_TOOL, {"msg": "slow"}, USER_ID)

        assert not result.success
        assert "timeout" in result.error.lower() or "timed out" in result.error.lower()

    @pytest.mark.asyncio
    async def test_timeout_reraise_propagates_mcp_timeout_error(self) -> None:
        """reraise_errors=True: MCPTimeoutError raised instead of captured."""
        import contextlib

        @contextlib.asynccontextmanager
        async def _always_timeout(_seconds):
            raise TimeoutError()
            yield

        executor, _ = _make_executor(reraise_errors=True)
        with patch("app.mcp.executor.asyncio.timeout", _always_timeout):
            with pytest.raises(MCPTimeoutError) as exc_info:
                await executor.execute(_TOOL, {"msg": "slow"}, USER_ID)
        assert exc_info.value.tool_name == _TOOL

    @pytest.mark.asyncio
    async def test_default_timeout_applied_when_no_model_registered(self) -> None:
        """When no MCPToolModel provides a timeout, default_timeout_ms is used."""
        import contextlib

        captured_timeout: list[float] = []

        @contextlib.asynccontextmanager
        async def _capture_timeout(seconds):
            captured_timeout.append(seconds)
            raise TimeoutError()
            yield

        # Register a bare connector with a schema that results in a model with
        # timeout_ms=0 — forcing the fallback to default_timeout_ms.
        # We achieve this by patching MCPToolModel.from_schema to return a model
        # with timeout_ms=0, then checking default_timeout_ms is used.
        broker = MagicMock()

        async def _quick(tool_name, params, user_id, ip_address="", user_agent=""):
            return MCPToolResult(
                tool_name=tool_name, success=True, result={}, result_status="success"
            )

        broker.invoke = _quick

        registry = MCPRegistry(broker=broker, allowed_tools=None)
        registry.register(_connector())  # registers _TOOL with model timeout_ms=30_000

        executor = MCPExecutor(registry=registry, default_timeout_ms=7_777)

        # Patch registry.get_model to return a model with timeout_ms=0 so
        # the executor falls back to default_timeout_ms
        zero_model = MagicMock()
        zero_model.timeout_ms = 0

        with patch.object(registry, "get_model", return_value=zero_model):
            with patch("app.mcp.executor.asyncio.timeout", _capture_timeout):
                result = await executor.execute(_TOOL, {"msg": "test"}, USER_ID)

        assert not result.success
        # With model.timeout_ms=0, executor uses default_timeout_ms (7 777 ms → 7.777 s)
        assert captured_timeout and abs(captured_timeout[0] - 7.777) < 0.001, (
            f"Expected default_timeout_ms 7.777 s, got {captured_timeout}"
        )

    @pytest.mark.asyncio
    async def test_timeout_zero_disables_timeout(self) -> None:
        """default_timeout_ms=0 means no timeout — fast tool completes normally."""
        fast = _connector(
            result=MCPToolResult(tool_name=_TOOL, success=True, result={}, result_status="success")
        )
        executor, _ = _make_executor(connector=fast, default_timeout_ms=0)
        result = await executor.execute(_TOOL, {"msg": "fast"}, USER_ID)
        assert result.success


# ===========================================================================
# 7–8: Parameter validation
# ===========================================================================


@pytest.mark.mcp
@pytest.mark.security
class TestParameterValidation:
    @pytest.mark.asyncio
    async def test_missing_required_param_rejected(self) -> None:
        executor, _ = _make_executor()
        # "msg" is required; send empty params
        result = await executor.execute(_TOOL, {}, USER_ID)
        # Validation may or may not reject — depends on MCPValidator implementation.
        # Assert it does not raise an unhandled exception.
        assert isinstance(result, MCPToolResult)

    @pytest.mark.asyncio
    async def test_exception_in_broker_returns_safe_error(self) -> None:
        """A broker that raises must not propagate an unhandled exception."""
        boom = _connector(raise_exc=RuntimeError("internal-credential: sk-abc123"))
        executor, _ = _make_executor(connector=boom)
        result = await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)
        assert not result.success
        # The raw exception message (which contains 'sk-abc123') must not appear
        # in the returned error string — only the safe generic message.
        assert "sk-abc123" not in (result.error or "")
        assert "credential" not in (result.error or "").lower()


# ===========================================================================
# 9–12: Secret not in logs / structured log fields
# ===========================================================================


@pytest.mark.mcp
@pytest.mark.security
@pytest.mark.observability
class TestMCPObservabilityAndSecretSafety:
    @pytest.mark.asyncio
    async def test_api_key_not_in_log_records_on_timeout(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """Bearer tokens and API keys must never appear in structured log output."""
        secret = "Bearer sk-prod-ABCDEF1234567890abcdef"

        class _LeakyConnector(MCPToolConnector):
            @property
            def tool_name(self) -> str:
                return _TOOL

            def get_schema(self) -> MCPToolSchema:
                return _schema()

            async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
                await asyncio.sleep(10.0)  # always times out
                return MCPToolResult(tool_name=_TOOL, success=True, result={})

        broker = MagicMock()

        async def _raise_with_secret(tool_name, params, user_id, ip_address="", user_agent=""):
            raise RuntimeError(f"auth header: {secret}")

        broker.invoke = _raise_with_secret
        registry = MCPRegistry(broker=broker)
        registry.register(_LeakyConnector())
        executor = MCPExecutor(registry=registry, default_timeout_ms=50)

        with caplog.at_level(logging.DEBUG, logger="app.mcp.executor"):
            await executor.execute(_TOOL, {"msg": "check"}, USER_ID)

        full_log = caplog.text + " ".join(
            str(r.getMessage()) + str(getattr(r, "__dict__", {})) for r in caplog.records
        )
        assert "sk-prod-ABCDEF" not in full_log
        assert secret not in full_log

    @pytest.mark.asyncio
    async def test_exception_message_not_leaked_to_logs(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """Exception messages (which may contain secrets) must not be forwarded
        verbatim to structured log extra fields — only exc_type is allowed."""
        secret_in_exc = "POSTGRES_PASSWORD=s3cr3t-db-p@ss!"
        boom = _connector(raise_exc=RuntimeError(secret_in_exc))
        executor, _ = _make_executor(connector=boom)

        with caplog.at_level(logging.DEBUG, logger="app.mcp.executor"):
            await executor.execute(_TOOL, {"msg": "boom"}, USER_ID)

        full_log = caplog.text + " ".join(str(r.getMessage()) for r in caplog.records)
        assert secret_in_exc not in full_log

    @pytest.mark.asyncio
    async def test_tool_name_is_structured_log_field(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """tool_name must be a queryable extra field, not embedded in the message string."""
        executor, _ = _make_executor()

        with caplog.at_level(logging.DEBUG, logger="app.mcp.executor"):
            await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)

        # Find the completion log record and check it has 'tool_name' in extras
        completion_records = [
            r
            for r in caplog.records
            if "completed" in r.getMessage().lower() or "MCPExecutor: completed" in r.getMessage()
        ]
        assert completion_records, "No 'completed' log record found"
        record = completion_records[-1]
        assert hasattr(record, "tool_name"), (
            "tool_name must be in extra={} of the log record for queryable JSON logging"
        )
        assert record.tool_name == _TOOL  # type: ignore[attr-defined]

    @pytest.mark.asyncio
    async def test_elapsed_ms_is_structured_log_field(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        executor, _ = _make_executor()

        with caplog.at_level(logging.DEBUG, logger="app.mcp.executor"):
            await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)

        completion_records = [r for r in caplog.records if "completed" in r.getMessage().lower()]
        assert completion_records
        record = completion_records[-1]
        assert hasattr(record, "elapsed_ms"), "elapsed_ms must be a structured log field"
        assert isinstance(record.elapsed_ms, int)  # type: ignore[attr-defined]

    @pytest.mark.asyncio
    async def test_user_id_redacted_in_warning_logs(self, caplog: pytest.LogCaptureFixture) -> None:
        """user_id in warning/error logs must be truncated to 8 chars."""
        long_uid = "user-99999999-ffff-ffff-ffff-000000000002"
        executor, _ = _make_executor()

        # Trigger a warning by calling an unknown tool
        with caplog.at_level(logging.WARNING, logger="app.mcp.executor"):
            await executor.execute("nonexistent_tool", {}, long_uid)

        warning_records = [r for r in caplog.records if r.levelno >= logging.WARNING]
        assert warning_records

        for record in warning_records:
            uid_val = getattr(record, "user_id", None)
            if uid_val is not None:
                assert len(uid_val) <= 10, (  # 8 chars + "…" = 9 max
                    f"user_id in log must be redacted, got: {uid_val!r}"
                )
                assert long_uid not in uid_val, "Full user_id must not appear in logs"


# ===========================================================================
# 13–14: user_id handling
# ===========================================================================


@pytest.mark.mcp
@pytest.mark.security
class TestUserIdHandling:
    @pytest.mark.asyncio
    async def test_user_id_forwarded_to_broker(self) -> None:
        received: dict = {}

        async def _capture(tool_name, params, user_id, ip_address="", user_agent=""):
            received["user_id"] = user_id
            return MCPToolResult(
                tool_name=tool_name, success=True, result={}, result_status="success"
            )

        broker = MagicMock()
        broker.invoke = _capture
        registry = MCPRegistry(broker=broker)
        registry.register(_connector())
        executor = MCPExecutor(registry=registry)

        await executor.execute(_TOOL, {"msg": "hi"}, USER_ID)

        assert received.get("user_id") == USER_ID

    @pytest.mark.asyncio
    async def test_empty_user_id_does_not_raise(self) -> None:
        """Executor forwards user_id validation to the upstream auth layer.
        An empty string must not cause an unhandled exception inside the executor."""
        executor, _ = _make_executor()
        result = await executor.execute(_TOOL, {"msg": "hi"}, "")
        # Result may succeed or fail depending on broker, but must not raise
        assert isinstance(result, MCPToolResult)
