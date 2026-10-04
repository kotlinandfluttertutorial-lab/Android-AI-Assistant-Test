# ============================================================
# Android AI Assistant — Backend
# Module  : mcp
# File    : models.py
# Purpose : Richer MCP tool model and domain-specific error types.
#
# Design rules:
#   - Zero infrastructure imports (no DB, HTTP, SQLAlchemy).
#   - MCPToolModel wraps MCPToolSchema and adds typed parameter
#     descriptors needed for schema validation.
#   - Error types carry safe messages only — no stack traces or
#     internal credentials.
# ============================================================
"""MCP domain models and error types."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from app.schemas.mcp import MCPToolSchema


# ---------------------------------------------------------------------------
# Richer tool model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class MCPParamDescriptor:
    """Description of one parameter accepted by an MCP tool.

    Attributes:
        name:        Parameter name (matches the key in the params dict).
        type:        JSON-schema type string: ``"string"``, ``"integer"``,
                     ``"boolean"``, ``"number"``, ``"array"``, ``"object"``.
        description: Human-readable description for documentation.
        required:    Whether this parameter must be present.
        enum:        If non-empty, the parameter value must be one of these.
        default:     Default value when the parameter is absent and not required.
    """

    name: str
    type: str = "string"
    description: str = ""
    required: bool = False
    enum: tuple[Any, ...] = field(default_factory=tuple)
    default: Any = None

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("MCPParamDescriptor.name must not be blank.")
        valid_types = {"string", "integer", "boolean", "number", "array", "object"}
        if self.type not in valid_types:
            raise ValueError(
                f"MCPParamDescriptor.type must be one of {valid_types}, got {self.type!r}."
            )


@dataclass(frozen=True)
class MCPToolModel:
    """Richer MCP tool description used by the validation and execution layer.

    Extends the lighter :class:`~app.schemas.mcp.MCPToolSchema` (which only
    carries a raw ``parameters`` dict) with structured :class:`MCPParamDescriptor`
    objects.  This lets :class:`~app.mcp.validator.MCPValidator` validate params
    without re-parsing the raw JSON Schema dict on every call.

    Attributes:
        tool_name:             Unique identifier (matches MCPToolSchema.tool_name).
        display_name:          Human-readable name for UI display.
        description:           One-sentence capability description.
        param_descriptors:     Structured parameter definitions.
        requires_confirmation: When True the executor must gate on user consent.
        timeout_ms:            Per-invocation timeout in milliseconds.
                               ``0`` means no timeout (not recommended for production).
        category:              Optional grouping tag (``"read"`` / ``"write"`` / etc.).
    """

    tool_name: str
    display_name: str
    description: str
    param_descriptors: tuple[MCPParamDescriptor, ...] = field(default_factory=tuple)
    requires_confirmation: bool = False
    timeout_ms: int = 30_000
    category: str = ""

    def __post_init__(self) -> None:
        if not self.tool_name.strip():
            raise ValueError("MCPToolModel.tool_name must not be blank.")
        if self.timeout_ms < 0:
            raise ValueError(f"MCPToolModel.timeout_ms must be ≥ 0, got {self.timeout_ms}.")

    @property
    def required_params(self) -> list[str]:
        """Names of parameters that are mandatory for this tool."""
        return [p.name for p in self.param_descriptors if p.required]

    @property
    def param_names(self) -> list[str]:
        """Names of all declared parameters."""
        return [p.name for p in self.param_descriptors]

    @classmethod
    def from_schema(cls, schema: MCPToolSchema) -> MCPToolModel:
        """Build an :class:`MCPToolModel` from an existing :class:`MCPToolSchema`.

        Parses the raw ``parameters`` JSON Schema dict to extract
        :class:`MCPParamDescriptor` objects where possible.

        Args:
            schema: An existing ``MCPToolSchema`` from the broker.

        Returns:
            :class:`MCPToolModel` with best-effort structured descriptors.
        """
        params_raw = schema.parameters or {}
        properties: dict[str, Any] = params_raw.get("properties", {})
        required_names: list[str] = params_raw.get("required", [])

        descriptors = []
        for name, prop in properties.items():
            descriptors.append(
                MCPParamDescriptor(
                    name=name,
                    type=prop.get("type", "string"),
                    description=prop.get("description", ""),
                    required=name in required_names,
                    enum=tuple(prop.get("enum", [])),
                )
            )

        return cls(
            tool_name=schema.tool_name,
            display_name=schema.tool_name.replace("_", " ").title(),
            description=schema.description,
            param_descriptors=tuple(descriptors),
            requires_confirmation=schema.requires_confirmation,
        )


# ---------------------------------------------------------------------------
# Error types
# ---------------------------------------------------------------------------


class MCPValidationError(ValueError):
    """Raised when params fail validation against a tool's parameter schema.

    Safe to surface to callers — carries no secrets or stack traces.

    Attributes:
        tool_name:  The tool whose validation failed.
        param_name: The offending parameter, if applicable.
        reason:     Human-readable description of the violation.
    """

    def __init__(self, tool_name: str, param_name: str | None, reason: str) -> None:
        self.tool_name = tool_name
        self.param_name = param_name
        self.reason = reason
        detail = f"[{param_name}]: {reason}" if param_name else reason
        super().__init__(f"MCP validation failed for tool '{tool_name}': {detail}")


class MCPExecutionError(RuntimeError):
    """Raised when a tool invocation fails after the executor's error handling.

    Attributes:
        tool_name:  Name of the tool that failed.
        safe_message: Human-readable error.  Must NOT contain stack traces.
        retryable:  True when the caller may retry immediately.
    """

    def __init__(
        self,
        tool_name: str,
        safe_message: str,
        retryable: bool = False,
    ) -> None:
        self.tool_name = tool_name
        self.safe_message = safe_message
        self.retryable = retryable
        super().__init__(f"[{tool_name}] {safe_message}")


class MCPTimeoutError(MCPExecutionError):
    """Raised when a tool invocation exceeds its configured timeout.

    Attributes:
        tool_name:  The tool that timed out.
        timeout_ms: The timeout limit that was exceeded.
    """

    def __init__(self, tool_name: str, timeout_ms: int) -> None:
        self.timeout_ms = timeout_ms
        super().__init__(
            tool_name=tool_name,
            safe_message=f"Invocation timed out after {timeout_ms} ms.",
            retryable=True,
        )
