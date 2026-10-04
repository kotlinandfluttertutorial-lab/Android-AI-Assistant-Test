# ============================================================
# Android AI Assistant — Backend
# Module  : orchestration
# File    : safety.py
# Purpose : Agent-layer safety guards for the orchestration loop.
#
# Design rules:
#   - Treat ALL tool output and RAG content as untrusted data.
#   - Scrub sensitive arguments BEFORE they reach any logger.
#   - Never raise from sanitise methods — return safe fallbacks.
#   - Auth checks raise PermissionError so the loop can abort cleanly.
#   - Zero network I/O; pure synchronous logic (async where needed for
#     InjectionDetector which hits the DB for audit logging).
# ============================================================
"""AgentSafetyGuard — safety controls for the single-agent orchestration loop.

Responsibilities
----------------
1. **Tool-output sanitisation** — every tool/MCP response is passed through
   :meth:`AgentSafetyGuard.sanitize_tool_output` before being accumulated in
   the execution state.  Harmful patterns are stripped via the existing
   :class:`~app.services.safety_service.SafetyService`.

2. **RAG-content sanitisation** — retrieved document chunks are treated as
   untrusted and run through :meth:`AgentSafetyGuard.sanitize_rag_content`
   before being injected into the LLM prompt.

3. **Sensitive-argument redaction** — tool parameters such as passwords,
   tokens, and secrets are replaced with ``"[redacted]"`` in any dict that
   will reach a logger, preventing credential leakage in structured logs.

4. **User-authorisation checks** — :meth:`AgentSafetyGuard.check_user_authorization`
   verifies that the requesting user holds every permission required by a tool
   before the tool is dispatched.

5. **Tool-permission checks** — :meth:`AgentSafetyGuard.check_tool_permission`
   validates a raw tool schema dict against the user's permission set.

All methods are intentionally lightweight and have no external I/O so they can
be called inline inside the hot execution loop without adding latency.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Iterable
from typing import Any

from app.services.safety_service import SafetyFilterError, SafetyService

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Sensitive-argument key patterns (case-insensitive substring match)
# ---------------------------------------------------------------------------

_SENSITIVE_KEY_PATTERNS: list[re.Pattern[str]] = [
    re.compile(pattern, re.IGNORECASE)
    for pattern in [
        r"password",
        r"passwd",
        r"secret",
        r"token",
        r"api[_\-]?key",
        r"access[_\-]?key",
        r"private[_\-]?key",
        r"credential",
        r"auth",
        r"bearer",
        r"jwt",
        r"ssn",
        r"card[_\-]?number",
        r"cvv",
    ]
]

# Placeholder substituted for redacted values in log-safe copies.
_REDACTED_PLACEHOLDER = "[redacted]"


def _is_sensitive_key(key: str) -> bool:
    """Return True when *key* looks like a credential or sensitive field name."""
    return any(pat.search(key) for pat in _SENSITIVE_KEY_PATTERNS)


# ---------------------------------------------------------------------------
# AgentSafetyGuard
# ---------------------------------------------------------------------------


class AgentSafetyGuard:
    """Safety controls applied inline during agent execution.

    All sanitise methods follow a *never raise* contract: when the underlying
    :class:`~app.services.safety_service.SafetyService` fails to clean a
    piece of content, the content is **replaced by a safe stub** rather than
    propagated to the caller.  This keeps the execution loop alive while
    preventing harmful content from reaching the user.

    Args:
        safety_service: Shared :class:`~app.services.safety_service.SafetyService`
            instance.  If *None*, a new instance is created.

    Example::

        guard = AgentSafetyGuard()
        safe_output = guard.sanitize_tool_output(raw_mcp_response)
        log_safe_params = guard.redact_sensitive_args("db_query", {"password": "s3cr3t"})
    """

    def __init__(self, safety_service: SafetyService | None = None) -> None:
        self._safety = safety_service or SafetyService()

    # ------------------------------------------------------------------
    # Tool-output sanitisation
    # ------------------------------------------------------------------

    def sanitize_tool_output(self, output: str) -> str:
        """Sanitise a raw MCP tool response before accumulating it in state.

        The output is treated as **untrusted data** originating from an
        external process.  It is run through the safety filter; if the filter
        raises :class:`~app.services.safety_service.SafetyFilterError` (i.e.
        harmful content could not be fully redacted), a safe stub is returned
        instead so the execution loop is not interrupted.

        Args:
            output: Raw string output from an MCP tool call.

        Returns:
            Sanitised output, or ``"[tool output blocked: safety filter failed]"``
            when the filter cannot fully redact harmful content.
        """
        if not isinstance(output, str):
            output = str(output)
        try:
            return self._safety.filter_response(output)
        except SafetyFilterError:
            logger.warning(
                "Tool output blocked by safety filter — returning stub. Raw length: %d chars.",
                len(output),
            )
            return "[tool output blocked: safety filter failed]"

    # ------------------------------------------------------------------
    # RAG-content sanitisation
    # ------------------------------------------------------------------

    def sanitize_rag_content(self, content: str) -> str:
        """Sanitise a retrieved document chunk before injecting it into the prompt.

        Retrieved chunks are treated as **untrusted data** because they
        originate from user-uploaded documents, external URLs, or third-party
        connectors.  The same safety filter used for tool output is applied.

        Args:
            content: Raw text of a RAG chunk retrieved from the vector store.

        Returns:
            Sanitised content, or ``"[rag content blocked: safety filter failed]"``
            when the filter cannot fully redact harmful content.
        """
        if not isinstance(content, str):
            content = str(content)
        try:
            return self._safety.filter_response(content)
        except SafetyFilterError:
            logger.warning(
                "RAG content blocked by safety filter — returning stub. Raw length: %d chars.",
                len(content),
            )
            return "[rag content blocked: safety filter failed]"

    # ------------------------------------------------------------------
    # Sensitive-argument redaction
    # ------------------------------------------------------------------

    def redact_sensitive_args(
        self,
        tool_name: str,
        params: dict[str, Any],
    ) -> dict[str, Any]:
        """Return a log-safe copy of *params* with sensitive values redacted.

        Keys whose names match any pattern in ``_SENSITIVE_KEY_PATTERNS``
        (e.g. ``password``, ``token``, ``api_key``) have their values replaced
        with ``"[redacted]"``.  Nested dicts and lists are recursively
        processed.  The original *params* dict is **not mutated**.

        Args:
            tool_name: Name of the tool being called (used only for logging).
            params:    Raw tool-call parameters dict.

        Returns:
            A new dict safe to pass to any logger or observability sink.

        Example::

            safe = guard.redact_sensitive_args(
                "create_user",
                {"username": "alice", "password": "s3cr3t"},
            )
            # safe == {"username": "alice", "password": "[redacted]"}
        """
        redacted = self._redact_recursive(params)
        logger.debug(
            "redact_sensitive_args: tool=%r redacted_keys=%r",
            tool_name,
            _find_redacted_keys(params),
        )
        return redacted

    def _redact_recursive(self, value: Any) -> Any:
        """Recursively redact sensitive values in dicts and lists."""
        if isinstance(value, dict):
            return {
                k: _REDACTED_PLACEHOLDER if _is_sensitive_key(str(k)) else self._redact_recursive(v)
                for k, v in value.items()
            }
        if isinstance(value, list):
            return [self._redact_recursive(item) for item in value]
        return value

    # ------------------------------------------------------------------
    # User-authorisation checks
    # ------------------------------------------------------------------

    def check_user_authorization(
        self,
        user_id: str,
        tool_name: str,
        required_permissions: Iterable[str],
        user_permissions: Iterable[str],
    ) -> None:
        """Verify that the user holds every permission required by a tool.

        This is an *additive* check on top of the RBAC layer: it lets the
        orchestration loop enforce fine-grained, tool-specific permissions
        before dispatching.

        Args:
            user_id:             The requesting user's identifier (for logging).
            tool_name:           Name of the tool about to be called.
            required_permissions: Permissions the tool declares it needs.
            user_permissions:    Permissions the authenticated user actually holds.

        Raises:
            PermissionError: When the user lacks one or more required permissions.
                             The execution loop should catch this and abort the run
                             with ``RunStatus.PERMISSION_DENIED``.
        """
        required_set = set(required_permissions)
        user_set = set(user_permissions)
        missing = required_set - user_set
        if missing:
            logger.warning(
                "Authorization denied: user=%r tool=%r missing_permissions=%r",
                user_id,
                tool_name,
                sorted(missing),
            )
            raise PermissionError(
                f"User '{user_id}' lacks permissions {sorted(missing)} "
                f"required by tool '{tool_name}'."
            )
        logger.debug(
            "Authorization granted: user=%r tool=%r",
            user_id,
            tool_name,
        )

    # ------------------------------------------------------------------
    # Tool-schema permission checks
    # ------------------------------------------------------------------

    def check_tool_permission(
        self,
        tool_schema: dict[str, Any],
        user_permissions: Iterable[str],
    ) -> None:
        """Validate a raw tool schema dict against the user's permission set.

        The tool schema may optionally include a ``"required_permissions"``
        list.  When present, each permission is checked against
        *user_permissions*.  When absent, the method returns without error
        (i.e. tools that declare no requirements are always permitted).

        Args:
            tool_schema:      Raw tool descriptor dict (as returned by the MCP
                              registry).  Expected shape::

                                  {
                                      "name": "some_tool",
                                      "required_permissions": ["read:data"],
                                      ...
                                  }

            user_permissions: Permissions the authenticated user actually holds.

        Raises:
            PermissionError: When the user lacks one or more declared permissions.
        """
        tool_name: str = tool_schema.get("name", "<unknown>")
        required: list[str] = tool_schema.get("required_permissions", [])
        if not required:
            return  # tool declares no permission requirements — always allowed
        user_set = set(user_permissions)
        missing = set(required) - user_set
        if missing:
            logger.warning(
                "Tool permission check failed: tool=%r missing=%r",
                tool_name,
                sorted(missing),
            )
            raise PermissionError(
                f"Tool '{tool_name}' requires permissions {sorted(missing)} "
                f"that the user does not hold."
            )


# ---------------------------------------------------------------------------
# Private helpers
# ---------------------------------------------------------------------------


def _find_redacted_keys(params: dict[str, Any]) -> list[str]:
    """Collect the top-level key names that would be redacted (for debug logging)."""
    return [k for k in params if _is_sensitive_key(str(k))]
