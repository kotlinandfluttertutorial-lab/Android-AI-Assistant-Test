# ============================================================================
# Android AI Assistant — Backend
# Module  : mcp/connectors
# File    : demo.py
# Purpose : DemoMCPConnector — zero-credential MCP tool connector for local
#           development and integration testing.
#
# Design rules:
#   - Works with NO external credentials, API keys, or network calls.
#   - All tools are synchronous and deterministic — safe to call in tests.
#   - Never raises; all errors are returned as MCPToolResult(success=False).
#   - Registered by the AgentServiceFactory in development mode automatically.
#
# Tools provided:
#   demo_echo       — returns the input message unchanged (liveness test)
#   demo_add        — adds two numbers (arithmetic sanity check)
#   demo_env_info   — returns safe, non-secret runtime info (Python version,
#                     environment name, enabled features)
#   demo_rag_ping   — checks that the RAG pipeline can be reached from within
#                     the agent execution context (integration test helper)
# ============================================================================
"""DemoMCPConnector — self-contained MCP tools for local dev and smoke tests.

Usage — register in the agent runner factory::

    from app.mcp.connectors.demo import DemoMCPConnector
    mcp_server.register(DemoMCPConnector())

Then call via the agent or directly::

    result = await mcp_server.execute(
        tool_name="demo_echo",
        params={"message": "hello"},
        user_id="dev-user",
    )
    assert result.success
    assert result.result["echo"] == "hello"
"""

from __future__ import annotations

import platform
import sys
from typing import Any

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

# ---------------------------------------------------------------------------
# Tool name constants — importable so tests can reference them without
# hard-coding strings.
# ---------------------------------------------------------------------------
TOOL_ECHO = "demo_echo"
TOOL_ADD = "demo_add"
TOOL_ENV_INFO = "demo_env_info"
TOOL_RAG_PING = "demo_rag_ping"


def _ok(tool_name: str, result: Any) -> MCPToolResult:
    return MCPToolResult(tool_name=tool_name, success=True, result=result, error=None)


def _err(tool_name: str, message: str) -> MCPToolResult:
    return MCPToolResult(tool_name=tool_name, success=False, result=None, error=message)


class DemoMCPConnector(MCPToolConnector):
    """Zero-credential MCP connector for local development and smoke testing.

    All four tools execute synchronously in-process with no external I/O.
    Register this connector on any :class:`~app.mcp.MCPServer` instance when
    running in development mode to verify the full MCP dispatch pipeline works
    without any third-party credentials.

    Example::

        server = MCPServer.create(db=db)
        server.register(DemoMCPConnector())
        result = await server.execute("demo_echo", {"message": "hi"}, "u1")
    """

    # ── MCPToolConnector contract ─────────────────────────────────────────────

    def get_schemas(self) -> list[MCPToolSchema]:
        """Return schema descriptors for all demo tools."""
        return [
            MCPToolSchema(
                name=TOOL_ECHO,
                display_name="Demo: Echo",
                description=(
                    "Returns the input message unchanged. "
                    "Use as a connectivity / liveness test for the MCP pipeline."
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "message": {
                            "type": "string",
                            "description": "Any text to echo back.",
                        }
                    },
                    "required": ["message"],
                },
                category="demo",
            ),
            MCPToolSchema(
                name=TOOL_ADD,
                display_name="Demo: Add",
                description=(
                    "Adds two numbers and returns the sum. "
                    "Use to verify parameter parsing in the MCP layer."
                ),
                parameters={
                    "type": "object",
                    "properties": {
                        "a": {"type": "number", "description": "First operand."},
                        "b": {"type": "number", "description": "Second operand."},
                    },
                    "required": ["a", "b"],
                },
                category="demo",
            ),
            MCPToolSchema(
                name=TOOL_ENV_INFO,
                display_name="Demo: Environment Info",
                description=(
                    "Returns safe, non-secret runtime information: Python version, "
                    "platform, ENVIRONMENT setting, and which feature flags are on. "
                    "Useful for verifying the backend is configured correctly."
                ),
                parameters={
                    "type": "object",
                    "properties": {},
                    "required": [],
                },
                category="demo",
            ),
            MCPToolSchema(
                name=TOOL_RAG_PING,
                display_name="Demo: RAG Ping",
                description=(
                    "Performs a lightweight health check against the ChromaDB vector "
                    "store to confirm the RAG pipeline is reachable from within the "
                    "agent execution context. Returns 'ok' on success or an error "
                    "message if ChromaDB is unreachable."
                ),
                parameters={
                    "type": "object",
                    "properties": {},
                    "required": [],
                },
                category="demo",
            ),
        ]

    async def invoke(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
    ) -> MCPToolResult:
        """Dispatch a demo tool call.

        All tools are non-blocking and produce deterministic results.
        Never raises — all errors are captured in the returned result.
        """
        try:
            if tool_name == TOOL_ECHO:
                return self._echo(params)
            if tool_name == TOOL_ADD:
                return self._add(params)
            if tool_name == TOOL_ENV_INFO:
                return self._env_info()
            if tool_name == TOOL_RAG_PING:
                return await self._rag_ping()
            return _err(tool_name, f"Unknown demo tool: '{tool_name}'.")
        except Exception as exc:  # pylint: disable=broad-except
            return _err(tool_name, f"Unexpected error in demo tool: {exc}")

    # ── Individual tool implementations ──────────────────────────────────────

    @staticmethod
    def _echo(params: dict[str, Any]) -> MCPToolResult:
        """Return the input message unchanged."""
        message = params.get("message")
        if message is None:
            return _err(TOOL_ECHO, "Required parameter 'message' is missing.")
        return _ok(TOOL_ECHO, {"echo": str(message), "length": len(str(message))})

    @staticmethod
    def _add(params: dict[str, Any]) -> MCPToolResult:
        """Add two numbers."""
        a = params.get("a")
        b = params.get("b")
        if a is None or b is None:
            return _err(TOOL_ADD, "Required parameters 'a' and 'b' are missing.")
        try:
            result = float(a) + float(b)
            return _ok(TOOL_ADD, {"a": a, "b": b, "sum": result})
        except (TypeError, ValueError) as exc:
            return _err(TOOL_ADD, f"Parameters 'a' and 'b' must be numbers: {exc}")

    @staticmethod
    def _env_info() -> MCPToolResult:
        """Return safe, non-secret runtime metadata."""
        settings = get_settings()
        return _ok(
            TOOL_ENV_INFO,
            {
                "python_version": sys.version,
                "platform": platform.system(),
                "environment": settings.ENVIRONMENT,
                "features": {
                    "rag_enabled": True,
                    "mcp_enabled": True,
                    "agent_enabled": True,
                    "prometheus_enabled": settings.PROMETHEUS_ENABLED,
                    "otel_enabled": settings.OTEL_ENABLED,
                    "llm_fallback_enabled": settings.LLM_ENABLE_FALLBACK,
                },
                "agent_limits": {
                    "max_steps": settings.MAX_AGENT_STEPS,
                    "max_tool_calls": settings.MAX_AGENT_TOOL_CALLS,
                    "timeout_seconds": settings.AGENT_TIMEOUT_SECONDS,
                },
            },
        )

    @staticmethod
    async def _rag_ping() -> MCPToolResult:
        """Check that ChromaDB is reachable from within the agent context."""
        try:
            import chromadb  # type: ignore[import]

            settings = get_settings()
            host = settings.CHROMA_HOST if hasattr(settings, "CHROMA_HOST") else "chromadb"
            port_attr = getattr(settings, "CHROMA_PORT", 8000)
            port = int(port_attr)

            client = chromadb.HttpClient(host=host, port=port)
            client.heartbeat()
            collections = client.list_collections()
            return _ok(
                TOOL_RAG_PING,
                {
                    "status": "ok",
                    "host": host,
                    "port": port,
                    "collection_count": len(collections),
                },
            )
        except Exception as exc:  # pylint: disable=broad-except
            return _err(
                TOOL_RAG_PING,
                f"ChromaDB unreachable: {exc}. "
                "Ensure the chromadb service is running and CHROMA_HOST/PORT are set.",
            )
