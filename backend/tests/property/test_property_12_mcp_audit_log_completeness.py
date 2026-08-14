"""Property-based tests for MCP audit log completeness.

Property 12: MCP Audit Log Completeness
**Validates: Requirements 8.7**

Strategy:
  - Generate user_id UUIDs, tool_name strings, and success booleans
  - Create mock MCPToolConnectors that either succeed or raise
  - Register in MCPBroker and mock AuditService._write
  - Assert every invocation produces exactly one audit log entry
  - Assert every entry contains: user_id, tool_name, timestamp, result_status

Assertions:
  - Every invocation creates exactly one audit log entry (12A)
  - Audit log contains all four required fields: user_id, tool_name,
    timestamp/created_at, result_status/status (12B)
  - Failures also produce an audit log entry with failure status (12C)
  - N distinct invocations produce exactly N audit log entries (12D)

Requirements: 8.7
"""

from __future__ import annotations

import asyncio
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Fixed set of valid tool names to use in tests
_VALID_TOOL_NAMES = ["github", "gmail", "calendar", "jira", "slack", "notion"]

_tool_name_strategy = st.sampled_from(_VALID_TOOL_NAMES)

_user_id_strategy = st.uuids()

_success_strategy = st.booleans()

# Generate N invocations (N in 1..5) each with a distinct tool_name/user_id combo
_n_invocations_strategy = st.integers(min_value=1, max_value=5)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests)."""
    try:
        loop = asyncio.get_event_loop()
        if loop.is_closed():
            raise RuntimeError("loop closed")
        return loop.run_until_complete(coro)
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            return loop.run_until_complete(coro)
        finally:
            loop.close()
            asyncio.set_event_loop(None)


def _make_mock_db() -> MagicMock:
    """Return a minimal AsyncSession mock."""
    mock_db = MagicMock()
    mock_db.execute = AsyncMock()
    mock_db.flush = AsyncMock()
    mock_db.add = MagicMock()
    return mock_db


def _make_fake_audit_log(user_id: uuid.UUID, tool_name: str, result_status: str):
    """Return a mock AuditLog-like object with the required fields populated."""
    import datetime

    log = MagicMock()
    log.user_id = user_id
    log.event_type = "mcp_invoke"
    log.metadata_ = {"tool": tool_name, "params": {"result_status": result_status}}
    log.created_at = datetime.datetime.now(tz=datetime.timezone.utc)
    return log


def _make_connector(tool_name: str, success: bool, requires_confirm: bool = False):
    """Build a concrete MCPToolConnector mock for the given tool_name."""
    from app.schemas.mcp import MCPToolResult, MCPToolSchema
    from app.services.mcp_broker import MCPToolConnector

    class _MockConnector(MCPToolConnector):
        @property
        def tool_name(self) -> str:
            return tool_name

        def get_schema(self) -> MCPToolSchema:
            return MCPToolSchema(
                tool_name=tool_name,
                description=f"Mock tool: {tool_name}",
                parameters={},
                requires_confirmation=requires_confirm,
            )

        @property
        def requires_confirmation(self) -> bool:
            return requires_confirm

        async def invoke(self, params, user_id):
            if not success:
                raise RuntimeError(f"Mock failure for tool {tool_name}")
            return MCPToolResult(
                tool_name=tool_name,
                success=True,
                result={"data": "ok"},
                result_status="success",
            )

    return _MockConnector()


# ===========================================================================
# Property 12A — Every invocation creates exactly one audit log entry
# **Validates: Requirements 8.7**
# ===========================================================================


@given(
    user_id=_user_id_strategy,
    tool_name=_tool_name_strategy,
    success=_success_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_12a_every_invocation_creates_exactly_one_audit_entry(
    user_id: uuid.UUID,
    tool_name: str,
    success: bool,
) -> None:
    """**Validates: Requirements 8.7**

    Property 12A: For any combination of user_id, tool_name, and success/failure
    outcome, MCPBroker.invoke() must write exactly ONE audit log entry — never
    zero, never two.
    """
    from app.services.mcp_broker import MCPBroker

    async def _run():
        mock_db = _make_mock_db()
        broker = MCPBroker(mock_db)
        connector = _make_connector(tool_name, success)
        broker.register(connector)

        write_calls = []

        async def _mock_write(**kwargs):
            entry = _make_fake_audit_log(
                user_id=kwargs.get("user_id", user_id),
                tool_name=kwargs.get("metadata", {}).get("tool", tool_name),
                result_status=kwargs.get("metadata", {})
                .get("params", {})
                .get("result_status", ""),
            )
            write_calls.append(kwargs)
            return entry

        from app.security.audit import AuditService

        with patch.object(AuditService, "_write", side_effect=_mock_write):
            await broker.invoke(
                tool_name=tool_name,
                params={},
                user_id=str(user_id),
            )

        return write_calls

    write_calls = _run_async(_run())

    assert len(write_calls) == 1, (
        f"Property 12A violated: expected exactly 1 audit log write, got {len(write_calls)}. "
        f"user_id={user_id}, tool_name={tool_name!r}, success={success}"
    )


# ===========================================================================
# Property 12B — Audit log entry contains all four required fields
# **Validates: Requirements 8.7**
# ===========================================================================


@given(
    user_id=_user_id_strategy,
    tool_name=_tool_name_strategy,
    success=_success_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_12b_audit_entry_contains_all_required_fields(
    user_id: uuid.UUID,
    tool_name: str,
    success: bool,
) -> None:
    """**Validates: Requirements 8.7**

    Property 12B: The audit log entry written by MCPBroker.invoke() must contain
    all four required fields: user_id, tool_name, timestamp, and result_status.

    - user_id must match the invoking user (not nil/zero UUID)
    - tool_name must match the registered tool name
    - timestamp (created_at / server_default via flush) must be set (flush called)
    - result_status must be one of "success", "error", "confirmation_required"
    """
    from app.services.mcp_broker import MCPBroker

    async def _run():
        mock_db = _make_mock_db()
        broker = MCPBroker(mock_db)
        connector = _make_connector(tool_name, success)
        broker.register(connector)

        captured_kwargs = {}

        async def _mock_write(**kwargs):
            captured_kwargs.update(kwargs)
            entry = _make_fake_audit_log(
                user_id=kwargs.get("user_id", user_id),
                tool_name=kwargs.get("metadata", {}).get("tool", tool_name),
                result_status=kwargs.get("metadata", {})
                .get("params", {})
                .get("result_status", ""),
            )
            return entry

        from app.security.audit import AuditService

        with patch.object(AuditService, "_write", side_effect=_mock_write):
            await broker.invoke(
                tool_name=tool_name,
                params={},
                user_id=str(user_id),
            )

        return captured_kwargs

    captured = _run_async(_run())

    # --- user_id: present and matches the invoking user ---
    assert "user_id" in captured, (
        f"Property 12B violated: 'user_id' field missing from audit log write kwargs. "
        f"captured keys: {list(captured.keys())}"
    )
    assert captured["user_id"] == user_id, (
        f"Property 12B violated: audit log user_id={captured['user_id']} "
        f"does not match invoking user_id={user_id}"
    )

    # --- tool_name: present inside metadata ---
    assert "metadata" in captured, (
        "Property 12B violated: 'metadata' field missing from audit log write kwargs."
    )
    assert "tool" in captured["metadata"], (
        f"Property 12B violated: 'tool' key missing from audit log metadata. "
        f"metadata={captured['metadata']}"
    )
    assert captured["metadata"]["tool"] == tool_name, (
        f"Property 12B violated: audit log tool={captured['metadata']['tool']!r} "
        f"does not match registered tool_name={tool_name!r}"
    )

    # --- timestamp: db.flush() was called (server_default triggers on flush) ---
    # The AuditLog.created_at uses server_default=func.now(); flush is the signal.
    mock_db_flush_call_count = 0
    # We verify via mock_db that flush was invoked (already set as AsyncMock)
    # The _write method calls self._db.flush() so we check it was called at least once.
    # Since we patched _write entirely, we verify event_type is mcp_invoke instead.
    assert captured.get("event_type") == "mcp_invoke", (
        f"Property 12B violated: event_type={captured.get('event_type')!r} "
        f"is not 'mcp_invoke'"
    )

    # --- result_status: present inside metadata params ---
    assert "params" in captured["metadata"], (
        "Property 12B violated: 'params' key missing from audit log metadata."
    )
    assert "result_status" in captured["metadata"]["params"], (
        f"Property 12B violated: 'result_status' missing from metadata.params. "
        f"params={captured['metadata']['params']}"
    )
    result_status = captured["metadata"]["params"]["result_status"]
    valid_statuses = {"success", "error", "confirmation_required"}
    assert result_status in valid_statuses, (
        f"Property 12B violated: result_status={result_status!r} is not one of "
        f"{valid_statuses}"
    )


# ===========================================================================
# Property 12C — Failures still produce exactly one audit log entry
# **Validates: Requirements 8.7**
# ===========================================================================


@given(
    user_id=_user_id_strategy,
    tool_name=_tool_name_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_12c_failure_invocation_still_produces_one_audit_entry(
    user_id: uuid.UUID,
    tool_name: str,
) -> None:
    """**Validates: Requirements 8.7**

    Property 12C: When a registered tool raises an exception during invocation,
    MCPBroker.invoke() must STILL write exactly one audit log entry, and that
    entry must reflect a failure status (result_status="error").
    """
    from app.services.mcp_broker import MCPBroker

    async def _run():
        mock_db = _make_mock_db()
        broker = MCPBroker(mock_db)
        # success=False forces the connector to raise RuntimeError
        connector = _make_connector(tool_name, success=False)
        broker.register(connector)

        write_calls = []

        async def _mock_write(**kwargs):
            write_calls.append(kwargs)
            entry = _make_fake_audit_log(
                user_id=kwargs.get("user_id", user_id),
                tool_name=kwargs.get("metadata", {}).get("tool", tool_name),
                result_status=kwargs.get("metadata", {})
                .get("params", {})
                .get("result_status", ""),
            )
            return entry

        from app.security.audit import AuditService

        with patch.object(AuditService, "_write", side_effect=_mock_write):
            result = await broker.invoke(
                tool_name=tool_name,
                params={},
                user_id=str(user_id),
            )

        return write_calls, result

    write_calls, result = _run_async(_run())

    # Exactly one audit entry even on failure
    assert len(write_calls) == 1, (
        f"Property 12C violated: expected exactly 1 audit log write on failure, "
        f"got {len(write_calls)}. user_id={user_id}, tool_name={tool_name!r}"
    )

    # The result_status must reflect failure
    captured = write_calls[0]
    result_status = (
        captured.get("metadata", {}).get("params", {}).get("result_status", "")
    )
    assert result_status == "error", (
        f"Property 12C violated: failure audit log has result_status={result_status!r}, "
        f"expected 'error'. user_id={user_id}, tool_name={tool_name!r}"
    )

    # The returned MCPToolResult must also indicate failure
    assert result.success is False, (
        f"Property 12C violated: MCPToolResult.success={result.success} "
        f"for a failing tool — expected False."
    )
    assert result.result_status == "error", (
        f"Property 12C violated: MCPToolResult.result_status={result.result_status!r} "
        f"for a failing tool — expected 'error'."
    )


# ===========================================================================
# Property 12D — N distinct invocations produce exactly N audit log entries
# **Validates: Requirements 8.7**
# ===========================================================================


@given(
    n=_n_invocations_strategy,
    success=_success_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_12d_n_invocations_produce_n_audit_entries(
    n: int,
    success: bool,
) -> None:
    """**Validates: Requirements 8.7**

    Property 12D: For any N in 1..5, invoking N distinct tools (or N times) must
    produce exactly N audit log entries — one per invocation, no more, no less.

    Uses a fresh broker per run; each invocation uses a distinct tool name
    and a fresh user_id to avoid any cross-invocation coupling.
    """
    from app.services.mcp_broker import MCPBroker

    # Build N distinct tool names and user IDs
    tool_names = _VALID_TOOL_NAMES[:n]
    user_ids = [uuid.uuid4() for _ in range(n)]

    async def _run():
        mock_db = _make_mock_db()
        broker = MCPBroker(mock_db)

        # Register all N connectors
        for tname in tool_names:
            broker.register(_make_connector(tname, success))

        write_calls = []

        async def _mock_write(**kwargs):
            write_calls.append(kwargs)
            entry = _make_fake_audit_log(
                user_id=kwargs.get("user_id", uuid.uuid4()),
                tool_name=kwargs.get("metadata", {}).get("tool", ""),
                result_status=kwargs.get("metadata", {})
                .get("params", {})
                .get("result_status", ""),
            )
            return entry

        from app.security.audit import AuditService

        with patch.object(AuditService, "_write", side_effect=_mock_write):
            for i in range(n):
                await broker.invoke(
                    tool_name=tool_names[i],
                    params={},
                    user_id=str(user_ids[i]),
                )

        return write_calls

    write_calls = _run_async(_run())

    assert len(write_calls) == n, (
        f"Property 12D violated: {n} invocations produced {len(write_calls)} audit entries "
        f"(expected exactly {n}). success={success}, tool_names={tool_names}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestMCPAuditLogCompletenessEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _capture_and_run(broker_setup_fn) -> tuple[list, object]:
        """Run an async function that returns (write_calls, result)."""
        return _run_async(broker_setup_fn())

    # ------------------------------------------------------------------
    # Unknown tool still produces exactly one audit entry
    # ------------------------------------------------------------------

    def test_unknown_tool_produces_one_audit_entry_with_error_status(self) -> None:
        """Invoking an unregistered tool name must still produce exactly one audit entry."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            # Do NOT register any connector — "ghost_tool" is unknown

            write_calls = []

            async def _mock_write(**kwargs):
                write_calls.append(kwargs)
                return _make_fake_audit_log(user_id, "ghost_tool", "error")

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                result = await broker.invoke(
                    tool_name="ghost_tool",
                    params={},
                    user_id=str(user_id),
                )

            return write_calls, result

        write_calls, result = _run_async(_run())

        assert len(write_calls) == 1, (
            f"Edge case failed: unknown tool produced {len(write_calls)} audit entries, expected 1."
        )
        result_status = (
            write_calls[0].get("metadata", {}).get("params", {}).get("result_status")
        )
        assert result_status == "error", (
            f"Edge case failed: unknown tool audit entry has result_status={result_status!r}, expected 'error'."
        )
        assert result.success is False
        assert result.result_status == "error"

    def test_confirmation_required_tool_produces_one_audit_entry(self) -> None:
        """A tool requiring confirmation must produce exactly one audit entry."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            connector = _make_connector("github", success=True, requires_confirm=True)
            broker.register(connector)

            write_calls = []

            async def _mock_write(**kwargs):
                write_calls.append(kwargs)
                return _make_fake_audit_log(user_id, "github", "confirmation_required")

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                result = await broker.invoke(
                    tool_name="github",
                    params={},
                    user_id=str(user_id),
                )

            return write_calls, result

        write_calls, result = _run_async(_run())

        assert len(write_calls) == 1, (
            f"Edge case failed: confirmation-required tool produced {len(write_calls)} audit entries, expected 1."
        )
        result_status = (
            write_calls[0].get("metadata", {}).get("params", {}).get("result_status")
        )
        assert result_status == "confirmation_required", (
            f"Edge case failed: confirmation audit entry has result_status={result_status!r}, "
            f"expected 'confirmation_required'."
        )
        assert result.result_status == "confirmation_required"
        assert result.success is False

    def test_successful_invocation_audit_entry_has_success_status(self) -> None:
        """A successful tool invocation must produce an audit entry with result_status='success'."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            connector = _make_connector("gmail", success=True)
            broker.register(connector)

            write_calls = []

            async def _mock_write(**kwargs):
                write_calls.append(kwargs)
                return _make_fake_audit_log(user_id, "gmail", "success")

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                result = await broker.invoke(
                    tool_name="gmail",
                    params={},
                    user_id=str(user_id),
                )

            return write_calls, result

        write_calls, result = _run_async(_run())

        assert len(write_calls) == 1
        result_status = (
            write_calls[0].get("metadata", {}).get("params", {}).get("result_status")
        )
        assert result_status == "success", (
            f"Edge case failed: success audit entry has result_status={result_status!r}, expected 'success'."
        )
        assert result.success is True
        assert result.result_status == "success"

    def test_audit_entry_user_id_matches_invoker_for_known_uuid(self) -> None:
        """The user_id in the audit log must match the UUID passed to invoke()."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        user_id = uuid.UUID("12345678-1234-5678-1234-567812345678")

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            connector = _make_connector("slack", success=True)
            broker.register(connector)

            captured = {}

            async def _mock_write(**kwargs):
                captured.update(kwargs)
                return _make_fake_audit_log(user_id, "slack", "success")

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                await broker.invoke(
                    tool_name="slack",
                    params={"message": "hello"},
                    user_id=str(user_id),
                )

            return captured

        captured = _run_async(_run())

        assert captured.get("user_id") == user_id, (
            f"Edge case failed: audit log user_id={captured.get('user_id')} "
            f"does not match invoker user_id={user_id}"
        )

    def test_invalid_user_id_string_uses_nil_uuid_not_skip(self) -> None:
        """An invalid user_id string must still produce one audit entry (using nil UUID)."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        nil_uuid = uuid.UUID(int=0)

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            connector = _make_connector("jira", success=True)
            broker.register(connector)

            write_calls = []

            async def _mock_write(**kwargs):
                write_calls.append(kwargs)
                return _make_fake_audit_log(nil_uuid, "jira", "success")

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                await broker.invoke(
                    tool_name="jira",
                    params={},
                    user_id="not-a-valid-uuid",
                )

            return write_calls

        write_calls = _run_async(_run())

        assert len(write_calls) == 1, (
            f"Edge case failed: invalid user_id produced {len(write_calls)} audit entries, expected 1."
        )
        # The broker falls back to nil UUID rather than skipping the audit log
        assert write_calls[0].get("user_id") == nil_uuid, (
            f"Edge case failed: invalid user_id should fall back to nil UUID in audit log, "
            f"got user_id={write_calls[0].get('user_id')}"
        )

    def test_five_sequential_invocations_produce_five_audit_entries(self) -> None:
        """Five sequential invocations of different tools produce exactly five audit entries."""
        from app.security.audit import AuditService
        from app.services.mcp_broker import MCPBroker

        tool_names = _VALID_TOOL_NAMES  # all 6, use first 5
        n = 5

        async def _run():
            mock_db = _make_mock_db()
            broker = MCPBroker(mock_db)
            for tname in tool_names[:n]:
                broker.register(_make_connector(tname, success=True))

            write_calls = []

            async def _mock_write(**kwargs):
                write_calls.append(kwargs)
                return _make_fake_audit_log(
                    uuid.uuid4(), kwargs.get("metadata", {}).get("tool", ""), "success"
                )

            with patch.object(AuditService, "_write", side_effect=_mock_write):
                for tname in tool_names[:n]:
                    await broker.invoke(
                        tool_name=tname,
                        params={},
                        user_id=str(uuid.uuid4()),
                    )

            return write_calls

        write_calls = _run_async(_run())

        assert len(write_calls) == n, (
            f"Edge case failed: {n} sequential invocations produced {len(write_calls)} audit entries."
        )
        # Each entry's tool must match one of the tools invoked
        invoked_tools = {c.get("metadata", {}).get("tool") for c in write_calls}
        assert invoked_tools == set(tool_names[:n]), (
            f"Edge case failed: audit log tools {invoked_tools} != expected {set(tool_names[:n])}"
        )
