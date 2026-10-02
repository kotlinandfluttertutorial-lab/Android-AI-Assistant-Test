# ============================================================
# Android AI Assistant — Backend
# Module  : mcp
# File    : server.py
# Purpose : MCPServer — convenience façade composing registry,
#           validator, and executor into a single entry point.
#
# Design:
#   Callers should interact with MCPServer rather than constructing
#   MCPRegistry / MCPValidator / MCPExecutor separately.  This mirrors
#   the "server" concept in the MCP spec where the server owns tool
#   discovery, validation, and execution.
# ============================================================
"""MCPServer — single entry point for the MCP tool layer."""

from __future__ import annotations

import logging
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.mcp.executor import MCPExecutor
from app.mcp.models import MCPToolModel
from app.mcp.registry import MCPRegistry
from app.mcp.validator import MCPValidator
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPBroker, MCPToolConnector

logger = logging.getLogger(__name__)


class MCPServer:
    """Façade that composes :class:`~app.mcp.registry.MCPRegistry`,
    :class:`~app.mcp.validator.MCPValidator`, and
    :class:`~app.mcp.executor.MCPExecutor` into a single entry point.

    This is the object callers (API routers, agents) should use.  Instantiate
    it once per request with a fresh :class:`~app.services.mcp_broker.MCPBroker`
    (which holds the DB session), register your connectors, then call
    :meth:`execute`.

    Usage::

        async def handle_request(db: AsyncSession, user_id: str):
            server = MCPServer.create(db, allowed_tools={"github_read"})
            server.register(GitHubReadConnector())
            result = await server.execute(
                tool_name="github_read",
                params={"action": "list_issues", "owner": "acme", "repo": "app"},
                user_id=user_id,
            )
    """

    def __init__(
        self,
        registry: MCPRegistry,
        executor: MCPExecutor,
    ) -> None:
        self._registry = registry
        self._executor = executor

    # ── Factory ───────────────────────────────────────────────────────────────

    @classmethod
    def create(
        cls,
        db: AsyncSession,
        allowed_tools: set[str] | None = None,
        default_timeout_ms: int = 30_000,
        strict_duplicates: bool = False,
    ) -> "MCPServer":
        """Construct a fully wired :class:`MCPServer` for one request.

        Args:
            db:                 SQLAlchemy async session (needed by MCPBroker
                                for audit log writes).
            allowed_tools:      Tool allowlist.  ``None`` or ``{"*"}`` allows all.
            default_timeout_ms: Fallback per-invocation timeout.
            strict_duplicates:  Raise on duplicate tool registration when ``True``.

        Returns:
            A ready-to-use :class:`MCPServer`.
        """
        broker = MCPBroker(db)
        registry = MCPRegistry(
            broker=broker,
            allowed_tools=allowed_tools,
            strict_duplicates=strict_duplicates,
        )
        validator = MCPValidator()
        executor = MCPExecutor(
            registry=registry,
            validator=validator,
            default_timeout_ms=default_timeout_ms,
        )
        return cls(registry=registry, executor=executor)

    # ── Registration ──────────────────────────────────────────────────────────

    def register(self, connector: MCPToolConnector) -> None:
        """Register *connector* in the underlying registry.

        Args:
            connector: Any :class:`~app.services.mcp_broker.MCPToolConnector`.

        Raises:
            ValueError: When ``strict_duplicates=True`` and the tool is already
                        registered.
        """
        self._registry.register(connector)

    # ── Discovery ─────────────────────────────────────────────────────────────

    def discover(self) -> list[MCPToolSchema]:
        """Return schemas for all *allowed* registered tools.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema`.
        """
        return self._registry.discover()

    def get_model(self, tool_name: str) -> MCPToolModel | None:
        """Return the :class:`~app.mcp.models.MCPToolModel` for *tool_name*."""
        return self._registry.get_model(tool_name)

    def list_allowed(self) -> list[str]:
        """Return sorted names of all allowed + registered tools."""
        return self._registry.list_allowed()

    def is_allowed(self, tool_name: str) -> bool:
        """Return ``True`` when *tool_name* is registered and allowed."""
        return self._registry.is_allowed(tool_name)

    # ── Execution ─────────────────────────────────────────────────────────────

    async def execute(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
        ip_address: str = "",
        user_agent: str = "",
    ) -> MCPToolResult:
        """Run the full safety pipeline and invoke *tool_name*.

        Delegates to :class:`~app.mcp.executor.MCPExecutor.execute`.
        Never raises — all errors are returned as ``MCPToolResult(success=False)``.

        Args:
            tool_name:   Name of the tool to invoke.
            params:      Tool-specific parameters.
            user_id:     Authenticated user UUID string.
            ip_address:  Client IP for audit logging (optional).
            user_agent:  HTTP User-Agent for audit logging (optional).

        Returns:
            :class:`~app.schemas.mcp.MCPToolResult`.
        """
        return await self._executor.execute(
            tool_name=tool_name,
            params=params,
            user_id=user_id,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    # ── Properties ────────────────────────────────────────────────────────────

    @property
    def registry(self) -> MCPRegistry:
        return self._registry

    @property
    def executor(self) -> MCPExecutor:
        return self._executor
