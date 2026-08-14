# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : mcp_broker.py
# Purpose : mcp_broker — services module
#
# Architecture Layer : Service
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""MCP Broker — registry and dispatcher for Model Context Protocol tool connectors.

The broker implements the Open/Closed Principle (OCP): adding a new tool
requires only creating a subclass of ``MCPToolConnector`` and registering it —
no existing broker code is modified.

Architecture
------------
- ``MCPToolConnector``  — abstract base class all connectors must implement.
- ``MCPBroker``         — registry that maps tool names to connectors.

Every call to ``MCPBroker.invoke()`` writes exactly one ``AuditLog`` entry via
``AuditService.log_mcp_invoke()`` (Property 12 — MCP Audit Log Completeness).
This invariant holds for *all* paths:

- Tool not found   → AuditLog written, ``result_status="error"``
- Confirmation req → AuditLog written, ``result_status="confirmation_required"``
                     connector's ``invoke()`` is NOT called
- Invocation OK    → AuditLog written, ``result_status="success"``
- Invocation error → AuditLog written, ``result_status="error"``

Usage::

    broker = MCPBroker(db)
    broker.register(GitHubConnector())
    broker.register(GmailConnector())

    schemas = broker.discover()            # → list[MCPToolSchema]
    result = await broker.invoke(
        tool_name="github",
        params={"title": "Bug report"},
        user_id="some-uuid",
    )

Requirements: 9.1, 9.8
"""

from __future__ import annotations

import logging
import uuid
from abc import ABC, abstractmethod
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.security.audit import AuditService

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Abstract connector base
# ---------------------------------------------------------------------------


class MCPToolConnector(ABC):
    """Abstract base class for all MCP tool connectors.

    Subclass this and implement the three abstract members to add a new tool.
    No other code in ``MCPBroker`` needs to change (OCP).

    Requirements: 9.1
    """

    # ------------------------------------------------------------------
    # Abstract interface
    # ------------------------------------------------------------------

    @property
    @abstractmethod
    def tool_name(self) -> str:
        """Unique identifier for this tool, e.g. ``'github'`` or ``'gmail'``.

        Returns:
            Tool name string.
        """

    @abstractmethod
    def get_schema(self) -> MCPToolSchema:
        """Return the JSON-Schema-style descriptor for this tool.

        Returns:
            An :class:`~app.schemas.mcp.MCPToolSchema` instance.
        """

    @abstractmethod
    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        """Execute the tool with the provided parameters.

        Args:
            params:  Tool-specific parameters.
            user_id: String UUID of the requesting user (for connector-level
                audit / context).

        Returns:
            An :class:`~app.schemas.mcp.MCPToolResult` instance.
        """

    # ------------------------------------------------------------------
    # Overridable configuration
    # ------------------------------------------------------------------

    @property
    def requires_confirmation(self) -> bool:
        """Whether this connector requires explicit user confirmation before invocation.

        Defaults to ``False``.  Override and return ``True`` for any write
        operation (e.g. create issue, send email, delete file) that should
        not run automatically.

        Returns:
            ``True`` when a confirmation step is mandatory before invoke.
        """
        return False


# ---------------------------------------------------------------------------
# MCPBroker
# ---------------------------------------------------------------------------


class MCPBroker:
    """Registry and dispatcher for registered :class:`MCPToolConnector` instances.

    Args:
        db: SQLAlchemy async session (transaction managed by the caller).

    Requirements: 9.1, 9.8
    """

    def __init__(self, db: AsyncSession) -> None:
        self._registry: dict[str, MCPToolConnector] = {}
        self._db = db

    # ------------------------------------------------------------------
    # Registration
    # ------------------------------------------------------------------

    def register(self, connector: MCPToolConnector) -> None:
        """Register a connector in the broker's registry.

        OCP: adding a new tool = adding one class + one ``register()`` call.
        No existing code in ``MCPBroker`` or any other connector is modified.

        Args:
            connector: A concrete :class:`MCPToolConnector` instance.
        """
        name = connector.tool_name
        if name in self._registry:
            logger.warning(
                "MCPBroker: overwriting existing connector for tool=%r", name
            )
        self._registry[name] = connector
        logger.debug("MCPBroker: registered tool=%r", name)

    # ------------------------------------------------------------------
    # Discovery
    # ------------------------------------------------------------------

    def discover(self) -> list[MCPToolSchema]:
        """Return the schemas for all registered connectors.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema` instances, one per
            registered connector.  Order is insertion order (Python 3.7+).
        """
        return [connector.get_schema() for connector in self._registry.values()]

    # ------------------------------------------------------------------
    # Invocation
    # ------------------------------------------------------------------

    async def invoke(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
        ip_address: str = "",
        user_agent: str = "",
    ) -> MCPToolResult:
        """Invoke a registered tool and write an audit log entry.

        **Property 12 guarantee**: exactly one ``AuditLog`` entry is written for
        every call to this method regardless of which code path is taken.

        Paths:
        - Unknown tool    → ``result_status="error"``,  ``success=False``
        - Requires confirmation → ``result_status="confirmation_required"``,
                                  connector's ``invoke()`` is NOT called
        - Invocation OK   → ``result_status="success"``, ``success=True``
        - Invocation error → ``result_status="error"``, ``success=False``,
                              ``error`` field contains a safe message (no
                              internal stack-trace details)

        Args:
            tool_name:   Name of the tool to invoke.
            params:      Tool-specific parameters.
            user_id:     String UUID of the requesting user.
            ip_address:  Client IP address (forwarded to audit log).
            user_agent:  HTTP User-Agent header value (forwarded to audit log).

        Returns:
            An :class:`~app.schemas.mcp.MCPToolResult` instance.

        Requirements: 9.1, 9.8
        """
        audit = AuditService(self._db)

        # Safely convert user_id to UUID; fall back to nil UUID on failure so
        # the audit log is never silently skipped.
        try:
            uid = uuid.UUID(user_id)
        except (ValueError, AttributeError):
            uid = uuid.UUID(int=0)

        # ---- Path 1: unknown tool ----------------------------------------
        if tool_name not in self._registry:
            logger.warning("MCPBroker: invoke called for unknown tool=%r", tool_name)
            await audit.log_mcp_invoke(
                user_id=uid,
                ip_address=ip_address,
                user_agent=user_agent,
                tool=tool_name,
                params_summary={"result_status": "error"},
            )
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                error=f"Tool '{tool_name}' is not registered.",
                result_status="error",
            )

        connector = self._registry[tool_name]

        # ---- Path 2: confirmation required --------------------------------
        if connector.requires_confirmation:
            logger.debug("MCPBroker: confirmation required for tool=%r", tool_name)
            await audit.log_mcp_invoke(
                user_id=uid,
                ip_address=ip_address,
                user_agent=user_agent,
                tool=tool_name,
                params_summary={"result_status": "confirmation_required"},
            )
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                result_status="confirmation_required",
            )

        # ---- Path 3: invocation (success or error) ------------------------
        try:
            result = await connector.invoke(params, user_id)
            await audit.log_mcp_invoke(
                user_id=uid,
                ip_address=ip_address,
                user_agent=user_agent,
                tool=tool_name,
                params_summary={"result_status": "success"},
            )
            # Ensure result_status is authoritative from the broker
            result.result_status = "success"
            result.success = True
            return result

        except Exception as exc:
            logger.exception(
                "MCPBroker: connector raised an exception for tool=%r: %s",
                tool_name,
                exc,
            )
            await audit.log_mcp_invoke(
                user_id=uid,
                ip_address=ip_address,
                user_agent=user_agent,
                tool=tool_name,
                params_summary={"result_status": "error"},
            )
            # Never expose internal exception details to the caller.
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                error="Tool invocation failed. Please try again later.",
                result_status="error",
            )
