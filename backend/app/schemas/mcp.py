# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : schemas
# File    : mcp.py
# Purpose : mcp — schemas module
#
# Architecture Layer : Pydantic Schema
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Pydantic v2 schemas for the MCP (Model Context Protocol) broker.

These schemas are shared between the MCP broker service and the API router
layer.

``MCPToolSchema``  — describes a registered tool (name, description, params,
    confirmation flag).
``MCPToolResult``  — the result returned by a tool invocation.
``MCPInvokeRequest`` — the request body for POST /tools/{tool_name}/invoke.

Requirements: 9.1
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class MCPToolSchema(BaseModel):
    """Schema returned by ``MCPBroker.discover()`` for each registered connector.

    Requirements: 9.1
    """

    model_config = ConfigDict(from_attributes=True)

    tool_name: str = Field(
        description="Unique tool identifier, e.g. 'github' or 'gmail'."
    )
    description: str = Field(
        description="Human-readable description of what the tool does."
    )
    parameters: dict[str, Any] = Field(
        description="JSON Schema-style parameter definitions for the tool.",
    )
    requires_confirmation: bool = Field(
        default=False,
        description=(
            "When ``True`` the broker will NOT invoke the tool automatically — "
            "the caller must present a confirmation token first."
        ),
    )


class MCPToolResult(BaseModel):
    """Result returned by ``MCPBroker.invoke()``.

    Requirements: 9.1
    """

    model_config = ConfigDict(from_attributes=True)

    tool_name: str = Field(
        description="The tool that was (or would have been) invoked."
    )
    success: bool = Field(description="``True`` when the tool executed without error.")
    result: dict[str, Any] | None = Field(
        default=None,
        description="Success payload returned by the connector, or ``None``.",
    )
    error: str | None = Field(
        default=None,
        description=(
            "Human-readable error message on failure.  "
            "Never contains internal stack traces or system details."
        ),
    )
    result_status: str = Field(
        description=(
            "Outcome of the invocation attempt.  "
            "One of: 'success' | 'error' | 'confirmation_required'."
        ),
    )


class MCPInvokeRequest(BaseModel):
    """Request body for ``POST /tools/{tool_name}/invoke``.

    Requirements: 9.1
    """

    params: dict[str, Any] = Field(
        default_factory=dict,
        description="Tool-specific parameters as a key/value mapping.",
    )
