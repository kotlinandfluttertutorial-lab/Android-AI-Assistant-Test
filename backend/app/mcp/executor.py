# ============================================================
# Android AI Assistant — Backend
# Module  : mcp
# File    : executor.py
# Purpose : MCPExecutor — async tool executor with timeout, allowlist
#           enforcement, param validation, and result validation.
#
# Pipeline (in order):
#   1. Allowlist check   — tool must be in the allowed set
#   2. Param validation  — MCPValidator.validate()
#   3. Timeout wrapper   — asyncio.timeout(timeout_ms / 1000)
#   4. Invocation        — MCPBroker.invoke()
#   5. Result validation — MCPValidator.validate_result()
# ============================================================
"""MCPExecutor — async MCP tool invocation with full safety pipeline."""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from app.mcp.models import MCPExecutionError, MCPTimeoutError, MCPToolModel, MCPValidationError
from app.mcp.registry import MCPRegistry
from app.mcp.validator import MCPValidator
from app.schemas.mcp import MCPToolResult

logger = logging.getLogger(__name__)

# Default per-invocation timeout when neither the tool model nor the caller
# specifies one.
DEFAULT_TIMEOUT_MS: int = 30_000


class MCPExecutor:
    """Executes MCP tool invocations with a full safety pipeline.

    The executor applies five steps before any connector code runs:
    1. **Allowlist** — reject tools not in the registry's allowlist.
    2. **Validation** — validate params against the tool's parameter schema.
    3. **Timeout** — bound execution time using ``asyncio.timeout``.
    4. **Invocation** — delegate to ``MCPBroker.invoke()`` (which also writes
       the audit log).
    5. **Result validation** — check that the result is structurally valid.

    **Never raises** for expected failures (unknown tool, validation error,
    timeout) — all are surfaced as :class:`~app.schemas.mcp.MCPToolResult`
    with ``success=False`` and a safe ``error`` message.

    Only :class:`~app.mcp.models.MCPValidationError` and
    :class:`~app.mcp.models.MCPExecutionError` are re-raised when
    ``reraise_errors=True`` (useful for test assertions).

    Usage::

        executor = MCPExecutor(registry, validator)
        result = await executor.execute(
            tool_name="github_read",
            params={"action": "list_issues", "owner": "acme", "repo": "app"},
            user_id="user-uuid",
        )
        if result.success:
            print(result.result)
    """

    def __init__(
        self,
        registry: MCPRegistry,
        validator: MCPValidator | None = None,
        default_timeout_ms: int = DEFAULT_TIMEOUT_MS,
        reraise_errors: bool = False,
    ) -> None:
        """
        Args:
            registry:           :class:`~app.mcp.registry.MCPRegistry` with connectors.
            validator:          Optional :class:`~app.mcp.validator.MCPValidator`.
                                Created automatically when ``None``.
            default_timeout_ms: Fallback timeout (ms) when the tool model doesn't
                                declare one.  ``0`` disables the timeout.
            reraise_errors:     When ``True``, validation and execution errors are
                                re-raised instead of being captured in the result.
                                Primarily for unit tests.
        """
        self._registry = registry
        self._validator = validator or MCPValidator()
        self._default_timeout_ms = default_timeout_ms
        self._reraise_errors = reraise_errors

    # ── Public API ────────────────────────────────────────────────────────────

    async def execute(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
        ip_address: str = "",
        user_agent: str = "",
    ) -> MCPToolResult:
        """Run the full execution pipeline for *tool_name*.

        Args:
            tool_name:   Name of the MCP tool to invoke.
            params:      Tool-specific parameters dict.
            user_id:     Authenticated user ID (forwarded to broker).
            ip_address:  Client IP for audit log (optional).
            user_agent:  HTTP User-Agent for audit log (optional).

        Returns:
            :class:`~app.schemas.mcp.MCPToolResult` — never ``None``.

        Raises:
            :class:`~app.mcp.models.MCPValidationError`: Only when ``reraise_errors=True``.
            :class:`~app.mcp.models.MCPExecutionError`:  Only when ``reraise_errors=True``.
            :class:`~app.mcp.models.MCPTimeoutError`:    Only when ``reraise_errors=True``.
        """
        start_ms = time.monotonic() * 1000

        # ── Step 1: Allowlist check ────────────────────────────────────────
        if not self._registry.is_allowed(tool_name):
            msg = (
                f"Tool '{tool_name}' is not registered or not on the allowlist."
            )
            logger.warning("MCPExecutor: allowlist rejection tool=%r user=%r", tool_name, user_id)
            if self._reraise_errors:
                raise MCPExecutionError(tool_name=tool_name, safe_message=msg)
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                error=msg,
                result_status="error",
            )

        # ── Step 2: Param validation ───────────────────────────────────────
        model: MCPToolModel | None = self._registry.get_model(tool_name)
        if model is not None:
            try:
                self._validator.validate(params, model)
            except MCPValidationError as exc:
                logger.info(
                    "MCPExecutor: validation failed tool=%r param=%r reason=%s",
                    tool_name, exc.param_name, exc.reason,
                )
                if self._reraise_errors:
                    raise
                return MCPToolResult(
                    tool_name=tool_name,
                    success=False,
                    error=str(exc),
                    result_status="error",
                )

        # ── Step 3 + 4: Timeout + invocation ──────────────────────────────
        timeout_ms = (model.timeout_ms if model else 0) or self._default_timeout_ms
        try:
            if timeout_ms > 0:
                async with asyncio.timeout(timeout_ms / 1000):
                    result = await self._registry.broker.invoke(
                        tool_name=tool_name,
                        params=params,
                        user_id=user_id,
                        ip_address=ip_address,
                        user_agent=user_agent,
                    )
            else:
                result = await self._registry.broker.invoke(
                    tool_name=tool_name,
                    params=params,
                    user_id=user_id,
                    ip_address=ip_address,
                    user_agent=user_agent,
                )
        except TimeoutError:
            elapsed = int(time.monotonic() * 1000 - start_ms)
            logger.warning(
                "MCPExecutor: timeout tool=%r timeout_ms=%d elapsed_ms=%d",
                tool_name, timeout_ms, elapsed,
            )
            exc = MCPTimeoutError(tool_name=tool_name, timeout_ms=timeout_ms)
            if self._reraise_errors:
                raise exc
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                error=exc.safe_message,
                result_status="error",
            )
        except Exception as exc:
            logger.error(
                "MCPExecutor: unexpected error tool=%r: %s",
                tool_name, exc,
            )
            if self._reraise_errors:
                raise MCPExecutionError(
                    tool_name=tool_name,
                    safe_message="Tool invocation failed. Please try again.",
                ) from exc
            return MCPToolResult(
                tool_name=tool_name,
                success=False,
                error="Tool invocation failed. Please try again.",
                result_status="error",
            )

        # ── Step 5: Result validation ──────────────────────────────────────
        try:
            self._validator.validate_result(result, tool_name)
        except MCPValidationError as exc:
            logger.warning(
                "MCPExecutor: result validation failed tool=%r: %s",
                tool_name, exc,
            )
            if self._reraise_errors:
                raise
            # Return the result anyway — result validation is best-effort
            # to avoid breaking callers when a connector returns a non-standard status.

        elapsed = int(time.monotonic() * 1000 - start_ms)
        logger.debug(
            "MCPExecutor: completed tool=%r success=%s elapsed_ms=%d",
            tool_name, result.success, elapsed,
        )
        return result
