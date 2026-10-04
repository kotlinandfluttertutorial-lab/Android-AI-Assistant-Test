# ============================================================
# Android AI Assistant — Backend
# Module  : mcp
# File    : registry.py
# Purpose : MCPRegistry — allowlist-aware wrapper around MCPBroker.
#
# Adds:
#   - Allowlist: only tools in the allowlist may be invoked.
#   - Duplicate detection: warns (or raises) when a tool is registered twice.
#   - Name lookup: get_model(tool_name) returns an MCPToolModel.
#   - Listing: list_registered() / list_allowed() convenience methods.
# ============================================================
"""MCPRegistry — allowlist-aware tool registry."""

from __future__ import annotations

import logging
import threading

from app.mcp.models import MCPToolModel
from app.schemas.mcp import MCPToolSchema
from app.services.mcp_broker import MCPBroker, MCPToolConnector

logger = logging.getLogger(__name__)

_SENTINEL_ALLOW_ALL = frozenset({"*"})


class MCPRegistry:
    """Allowlist-aware registry that wraps :class:`~app.services.mcp_broker.MCPBroker`.

    Responsibilities:
    - **Registration** – delegates to ``MCPBroker.register()`` and maintains a
      local ``_models`` dict of :class:`~app.mcp.models.MCPToolModel` objects
      derived from each connector's schema.
    - **Duplicate detection** – logs a warning (or raises ``ValueError`` when
      ``strict_duplicates=True``) when the same ``tool_name`` is registered twice.
    - **Allowlist** – restricts which tool names may be discovered or invoked.
      Pass ``allowed_tools={"*"}`` (the default) to allow all registered tools.
    - **Lookup** – ``get_model(tool_name)`` returns the rich
      :class:`~app.mcp.models.MCPToolModel` for a given tool.

    Usage::

        registry = MCPRegistry(broker, allowed_tools={"github_read", "slack_read"})
        registry.register(GitHubReadConnector())
        model = registry.get_model("github_read")
        schemas = registry.discover()   # only allowed tools
    """

    def __init__(
        self,
        broker: MCPBroker,
        allowed_tools: set[str] | None = None,
        strict_duplicates: bool = False,
    ) -> None:
        """
        Args:
            broker:            The underlying :class:`~app.services.mcp_broker.MCPBroker`.
            allowed_tools:     Set of tool names that may be discovered/invoked.
                               ``None`` or ``{"*"}`` means all tools are allowed.
            strict_duplicates: When ``True``, re-registering a tool name raises
                               ``ValueError``.  When ``False`` (default), a warning
                               is logged and the new connector replaces the old one.
        """
        self._broker = broker
        self._allowed: frozenset[str] = (
            _SENTINEL_ALLOW_ALL
            if (allowed_tools is None or allowed_tools == {"*"})
            else frozenset(allowed_tools)
        )
        self._strict_duplicates = strict_duplicates
        self._models: dict[str, MCPToolModel] = {}
        self._lock = threading.Lock()

    # ── Registration ──────────────────────────────────────────────────────────

    def register(self, connector: MCPToolConnector) -> None:
        """Register *connector* in the broker and build its :class:`MCPToolModel`.

        Args:
            connector: A concrete :class:`~app.services.mcp_broker.MCPToolConnector`.

        Raises:
            ValueError: If ``strict_duplicates=True`` and the tool name is already
                        registered.
        """
        name = connector.tool_name
        with self._lock:
            if name in self._models:
                if self._strict_duplicates:
                    raise ValueError(
                        f"MCPRegistry: tool '{name}' is already registered. "
                        "Use strict_duplicates=False to allow replacement."
                    )
                logger.warning("MCPRegistry: replacing existing connector for tool=%r", name)
            schema = connector.get_schema()
            self._models[name] = MCPToolModel.from_schema(schema)
        self._broker.register(connector)
        logger.debug("MCPRegistry: registered tool=%r", name)

    def unregister(self, tool_name: str) -> bool:
        """Remove *tool_name* from the registry.

        Note: :class:`~app.services.mcp_broker.MCPBroker` does not expose an
        unregister method, so this removes the local model only.  The broker
        will return an error for subsequent invocations of this tool.

        Args:
            tool_name: Name of the tool to remove.

        Returns:
            ``True`` when the tool was present and removed; ``False`` otherwise.
        """
        with self._lock:
            removed = self._models.pop(tool_name, None) is not None
        if removed:
            logger.info("MCPRegistry: unregistered tool=%r", tool_name)
        return removed

    # ── Discovery ─────────────────────────────────────────────────────────────

    def discover(self) -> list[MCPToolSchema]:
        """Return schemas for all *allowed* registered tools.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema` for tools that are
            both registered and on the allowlist.
        """
        all_schemas = self._broker.discover()
        if self._allowed is _SENTINEL_ALLOW_ALL:
            return all_schemas
        return [s for s in all_schemas if s.tool_name in self._allowed]

    def list_registered(self) -> list[str]:
        """Return the names of all registered tools (regardless of allowlist)."""
        with self._lock:
            return sorted(self._models.keys())

    def list_allowed(self) -> list[str]:
        """Return the names of tools that are registered AND on the allowlist."""
        with self._lock:
            names = sorted(self._models.keys())
        if self._allowed is _SENTINEL_ALLOW_ALL:
            return names
        return [n for n in names if n in self._allowed]

    # ── Lookup ────────────────────────────────────────────────────────────────

    def get_model(self, tool_name: str) -> MCPToolModel | None:
        """Return the :class:`~app.mcp.models.MCPToolModel` for *tool_name*, or ``None``.

        Args:
            tool_name: Name of the tool to look up.

        Returns:
            The model, or ``None`` when not registered.
        """
        with self._lock:
            return self._models.get(tool_name)

    def is_registered(self, tool_name: str) -> bool:
        """Return ``True`` when *tool_name* is registered (regardless of allowlist)."""
        with self._lock:
            return tool_name in self._models

    def is_allowed(self, tool_name: str) -> bool:
        """Return ``True`` when *tool_name* is registered AND on the allowlist."""
        if not self.is_registered(tool_name):
            return False
        return self._allowed is _SENTINEL_ALLOW_ALL or tool_name in self._allowed

    # ── Broker delegation ─────────────────────────────────────────────────────

    @property
    def broker(self) -> MCPBroker:
        """The underlying :class:`~app.services.mcp_broker.MCPBroker`."""
        return self._broker

    @property
    def size(self) -> int:
        """Number of registered tools."""
        with self._lock:
            return len(self._models)
