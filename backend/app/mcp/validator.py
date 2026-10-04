# ============================================================
# Android AI Assistant — Backend
# Module  : mcp
# File    : validator.py
# Purpose : MCPValidator — validates tool invocation params against
#           a tool's declared parameter schema.
#
# Design rules:
#   - No infrastructure imports.
#   - Raises MCPValidationError (a ValueError subclass) on failure.
#   - Handles both MCPToolModel descriptors and raw MCPToolSchema dicts.
#   - Validates: required params present, type coercion safety, enum membership.
# ============================================================
"""MCPValidator — parameter schema validation for MCP tool calls."""

from __future__ import annotations

import logging
from typing import Any

from app.mcp.models import MCPParamDescriptor, MCPToolModel, MCPValidationError
from app.schemas.mcp import MCPToolSchema

logger = logging.getLogger(__name__)

# Python type names that map to JSON Schema type strings
_TYPE_MAP: dict[str, type] = {
    "string": str,
    "integer": int,
    "boolean": bool,
    "number": (int, float),  # type: ignore[dict-item]
    "array": list,
    "object": dict,
}


class MCPValidator:
    """Validates tool invocation parameters before they reach the connector.

    Validation pipeline (in order):
    1. **Required params** — every parameter marked ``required=True`` must be present.
    2. **Type check** — when a type is declared and the value is present, verify
       that the Python type is compatible.
    3. **Enum check** — when a param declares an ``enum``, the value must be a member.

    All violations raise :class:`~app.mcp.models.MCPValidationError` which carries
    a human-safe message (no secrets, no stack traces).

    Usage::

        validator = MCPValidator()
        # Using MCPToolModel (preferred):
        validator.validate(params, model)
        # Using raw MCPToolSchema:
        validator.validate_from_schema(params, schema)
    """

    def validate(
        self,
        params: dict[str, Any],
        model: MCPToolModel,
    ) -> None:
        """Validate *params* against *model*'s :class:`MCPParamDescriptor` list.

        Args:
            params: The parameters dict to validate.
            model:  The :class:`~app.mcp.models.MCPToolModel` describing the tool.

        Raises:
            :class:`~app.mcp.models.MCPValidationError`: On the first validation failure.
        """
        for descriptor in model.param_descriptors:
            self._check_descriptor(params, descriptor, model.tool_name)

    def validate_from_schema(
        self,
        params: dict[str, Any],
        schema: MCPToolSchema,
    ) -> None:
        """Validate *params* against a raw :class:`~app.schemas.mcp.MCPToolSchema`.

        Converts the schema to an :class:`~app.mcp.models.MCPToolModel` first,
        then delegates to :meth:`validate`.

        Args:
            params: The parameters dict to validate.
            schema: The :class:`~app.schemas.mcp.MCPToolSchema` from the broker.

        Raises:
            :class:`~app.mcp.models.MCPValidationError`: On the first validation failure.
        """
        model = MCPToolModel.from_schema(schema)
        self.validate(params, model)

    def validate_result(
        self,
        result: Any,
        tool_name: str,
    ) -> None:
        """Validate that a tool result is structurally sound.

        Currently checks:
        - Result is not ``None``.
        - ``result_status`` field is a known value when present.

        Args:
            result:    The :class:`~app.schemas.mcp.MCPToolResult` to check.
            tool_name: Name of the tool (for error messages).

        Raises:
            :class:`~app.mcp.models.MCPValidationError`: When the result is invalid.
        """
        if result is None:
            raise MCPValidationError(
                tool_name=tool_name,
                param_name=None,
                reason="Tool returned None — expected MCPToolResult.",
            )
        status = getattr(result, "result_status", None)
        valid_statuses = {"success", "error", "confirmation_required"}
        if status is not None and status not in valid_statuses:
            raise MCPValidationError(
                tool_name=tool_name,
                param_name="result_status",
                reason=(
                    f"Unknown result_status {status!r}. Expected one of {sorted(valid_statuses)}."
                ),
            )

    # ── Private helpers ───────────────────────────────────────────────────────

    @staticmethod
    def _check_descriptor(
        params: dict[str, Any],
        descriptor: MCPParamDescriptor,
        tool_name: str,
    ) -> None:
        name = descriptor.name
        value = params.get(name)

        # 1. Required check
        if descriptor.required and value is None:
            raise MCPValidationError(
                tool_name=tool_name,
                param_name=name,
                reason="is required but was not provided.",
            )

        # Skip further checks when the parameter is absent (and not required)
        if value is None:
            return

        # 2. Type check
        expected_py_type = _TYPE_MAP.get(descriptor.type)
        if expected_py_type and not isinstance(value, expected_py_type):
            raise MCPValidationError(
                tool_name=tool_name,
                param_name=name,
                reason=(f"expected type '{descriptor.type}' but got {type(value).__name__!r}."),
            )

        # 3. Enum check
        if descriptor.enum and value not in descriptor.enum:
            raise MCPValidationError(
                tool_name=tool_name,
                param_name=name,
                reason=(f"value {value!r} is not in the allowed set {list(descriptor.enum)}."),
            )
