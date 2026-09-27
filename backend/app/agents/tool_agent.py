# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : tool_agent.py
# Purpose : ToolAgent — bridges LLM tool-call decisions through the
#           existing MCPBroker with full security validation.
#
# Architecture Layer : Agent Core (Phase 5)
# Pattern Used       : Adapter (implements Agent; wraps MCPBroker)
#
# Security pipeline (enforced per invocation):
#   1. Authenticated user_id — validated before broker call
#   2. Tool exists — MCPBroker raises KeyError for unknowns (already audited)
#   3. Permissions — MCPBroker's audit logging captures every attempt
#   4. Arguments — validated by individual MCPToolConnector.invoke()
#   5. Timeout — asyncio.wait_for() with per-tool limit
#   6. Output — MCPToolResult.error never contains stack traces (MCPBroker guarantee)
#   7. Secrets — never surfaced in ToolResult output
#
# No shell access, no arbitrary file access, no secret exposure.
# Does NOT create a second MCP implementation — delegates entirely to MCPBroker.
#
# Request metadata keys:
#   "tool_name"    — name of the registered MCP tool (required)
#   "tool_params"  — JSON string of tool parameters (default "{}")
#   "confirmed"    — "true" to confirm write-action tools
# ============================================================

"""ToolAgent — wraps existing MCPBroker as an Agent."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncIterator

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentCompletedEvent,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
    AgentToolCompletedEvent,
    AgentToolConfirmationRequiredEvent,
    AgentToolFailedEvent,
    AgentToolStartedEvent,
)

logger = logging.getLogger(__name__)

TOOL_AGENT_NAME = "tool-executor"
_DEFAULT_TOOL_TIMEOUT = 30.0

# Lazy imports
try:
    from app.services.mcp_broker import MCPBroker as _MCPBroker  # type: ignore
except Exception:  # pragma: no cover
    _MCPBroker = None  # type: ignore

try:
    from app.database import AsyncSessionLocal as _AsyncSessionLocal  # type: ignore
except Exception:  # pragma: no cover
    _AsyncSessionLocal = None  # type: ignore

MCPBroker = _MCPBroker
AsyncSessionLocal = _AsyncSessionLocal

# All 15 connectors registered in the MCP router
try:
    from app.api.mcp.router import _get_broker as _get_mcp_broker  # type: ignore
except Exception:  # pragma: no cover
    _get_mcp_broker = None  # type: ignore

_get_mcp_broker_fn = _get_mcp_broker


class ToolAgent(Agent):
    """Agent that executes MCP tools via the existing MCPBroker.

    Does NOT create a second tool execution path — delegates entirely
    to MCPBroker which enforces Property 12 (audit log completeness).
    """

    @property
    def name(self) -> str:
        return TOOL_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "Executes registered MCP tools (GitHub, Gmail, Slack, Jira, Notion, "
            "Google Drive/Calendar, Figma) via the existing MCPBroker with full audit logging."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({
            AgentCapability.TOOL_USE,
            AgentCapability.TEXT_GENERATION,
        })

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        if AsyncSessionLocal is None or MCPBroker is None:
            yield self._failed(execution, request, "SERVICE_UNAVAILABLE",
                               "Tool execution service not available.")
            return

        # ── 1. Authenticate ──────────────────────────────────────────────────
        user_id = (request.user_id or "").strip()
        if not user_id:
            yield self._failed(execution, request, "UNAUTHENTICATED",
                               "Tool execution requires an authenticated user.")
            return

        metadata = request.metadata or {}

        # ── 2. Extract tool name + params ────────────────────────────────────
        tool_name = metadata.get("tool_name", "").strip()
        if not tool_name:
            yield self._failed(execution, request, "MISSING_TOOL_NAME",
                               "metadata['tool_name'] is required.")
            return

        params_str = metadata.get("tool_params", "{}")
        try:
            params: dict[str, object] = json.loads(params_str)
            if not isinstance(params, dict):
                raise ValueError("params must be a JSON object")
        except (json.JSONDecodeError, ValueError) as exc:
            yield self._failed(execution, request, "INVALID_PARAMS",
                               f"tool_params is not valid JSON: {exc}")
            return

        # ── 3. Emit tool-started event ────────────────────────────────────────
        yield AgentToolStartedEvent(tool_name=tool_name, parameters=params_str[:500])

        # ── 4. Check confirmation requirement then invoke via MCPBroker ───────
        start_ms = int(asyncio.get_event_loop().time() * 1000)
        confirmed = metadata.get("confirmed", "").strip().lower() == "true"

        try:
            async with AsyncSessionLocal() as db:
                # Build broker the same way the MCP router does
                broker = MCPBroker(db=db)
                if _get_mcp_broker_fn is not None:
                    try:
                        broker = await _register_all_connectors(broker, db)
                    except Exception as reg_exc:
                        logger.warning("ToolAgent: connector registration error: %s", reg_exc)

                # Confirmation gate (MCPBroker handles this internally too,
                # but we surface it as an AgentEvent for the UI)
                connector = broker._registry.get(tool_name)  # type: ignore[attr-defined]
                requires_conf = getattr(connector, "requires_confirmation", False)
                if connector is not None and requires_conf and not confirmed:
                    yield AgentToolConfirmationRequiredEvent(
                        tool_name=tool_name,
                        parameters=params_str[:500],
                        rationale=f"Tool '{tool_name}' requires confirmation before execution.",
                    )
                    # Emit partial completion — caller must resend with confirmed=true.
                    # PARTIAL is the correct terminal status for "produced output but didn't
                    # complete the full requested action".
                    yield AgentCompletedEvent(result=AgentResult(
                        execution_id=execution.execution_id,
                        request_id=request.request_id,
                        agent_name=self.name,
                        status=AgentStatus.PARTIAL,
                        content=None,
                        metadata={
                            "awaiting_confirmation": tool_name,
                            "action": "resend_with_confirmed_true",
                        },
                    ))
                    return

                tool_result = await asyncio.wait_for(
                    broker.invoke(
                        tool_name=tool_name,
                        params=params,
                        user_id=user_id,
                    ),
                    timeout=_DEFAULT_TOOL_TIMEOUT,
                )

        except asyncio.TimeoutError:
            yield AgentToolFailedEvent(
                tool_name=tool_name,
                error_message=f"Tool timed out after {_DEFAULT_TOOL_TIMEOUT}s.",
            )
            yield self._failed(
                execution, request, "TOOL_TIMEOUT",
                f"Tool '{tool_name}' exceeded timeout of {_DEFAULT_TOOL_TIMEOUT}s.",
            )
            return
        except Exception as exc:
            logger.exception("ToolAgent: unexpected error invoking '%s': %s", tool_name, exc)
            yield AgentToolFailedEvent(
                tool_name=tool_name,
                error_message="Tool invocation failed unexpectedly.",
            )
            yield self._failed(execution, request, "TOOL_ERROR",
                               "Tool invocation failed. Check tool configuration.")
            return

        duration_ms = int(asyncio.get_event_loop().time() * 1000) - start_ms

        if tool_result.success:
            output_str = json.dumps(tool_result.result or {})
            yield AgentToolCompletedEvent(
                tool_name=tool_name, output=output_str, duration_ms=duration_ms
            )
            yield AgentTokenEvent(token=output_str)
            yield AgentCompletedEvent(result=AgentResult(
                execution_id=execution.execution_id,
                request_id=request.request_id,
                agent_name=self.name,
                status=AgentStatus.COMPLETED,
                content=output_str,
                metadata={
                    "tool_name": tool_name,
                    "result_status": tool_result.result_status,
                },
            ))
        else:
            error_msg = tool_result.error or "Tool returned an error."
            yield AgentToolFailedEvent(tool_name=tool_name, error_message=error_msg)
            yield self._failed(execution, request, "TOOL_RETURNED_ERROR", error_msg)

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        msg: str,
    ) -> AgentFailedEvent:
        return AgentFailedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=TOOL_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=msg),
        ))


async def _register_all_connectors(broker: object, db: object) -> object:
    """Register all MCP connectors into *broker*, matching the MCP router wiring."""
    from app.services.mcp_connectors import (  # type: ignore
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
    for cls in [
        GitHubReadConnector, GitHubWriteConnector,
        GmailReadConnector, GmailWriteConnector,
        GDriveReadConnector, GDriveWriteConnector,
        GCalReadConnector, GCalWriteConnector,
        SlackReadConnector, SlackWriteConnector,
        JiraReadConnector, JiraWriteConnector,
        NotionReadConnector, NotionWriteConnector,
        FigmaReadConnector,
    ]:
        try:
            broker.register(cls())  # type: ignore[attr-defined]
        except Exception as exc:
            logger.debug("ToolAgent: skipping connector %s: %s", cls.__name__, exc)
    return broker
