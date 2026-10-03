"""MCP infrastructure — Model Context Protocol tool discovery, validation, and execution.

Public surface
--------------
MCPToolModel      – richer tool description (typed parameter schema, required fields).
MCPValidationError – raised when params fail schema validation.
MCPExecutionError  – raised when a tool invocation fails after all retries.
MCPTimeoutError    – raised when a tool invocation exceeds its timeout.
MCPRegistry        – allowlist-aware registry wrapping MCPBroker.
MCPValidator       – validates params against a tool's JSON Schema descriptor.
MCPExecutor        – async executor with timeout, validation, and result checking.
MCPServer          – convenience façade composing registry + validator + executor.

Architecture
------------
The existing MCPBroker / MCPToolConnector ABC is reused without modification.
This package adds the missing layers above it:

    MCPToolConnector (existing ABC)
         ↓
    MCPBroker (existing — register / discover / invoke + audit)
         ↓
    MCPRegistry (new — allowlist, duplicate detection, name lookup)
         ↓
    MCPValidator (new — JSON Schema param validation)
         ↓
    MCPExecutor (new — timeout, result validation, error wrapping)
         ↓
    MCPServer   (new — single entry point for all of the above)

No production credentials are stored here; the existing connectors read
credentials from the application Settings singleton at call time.
"""

from app.mcp.executor import MCPExecutor
from app.mcp.models import (
    MCPExecutionError,
    MCPTimeoutError,
    MCPToolModel,
    MCPValidationError,
)
from app.mcp.registry import MCPRegistry
from app.mcp.server import MCPServer
from app.mcp.validator import MCPValidator

__all__ = [
    "MCPExecutionError",
    "MCPExecutor",
    "MCPRegistry",
    "MCPServer",
    "MCPTimeoutError",
    "MCPToolModel",
    "MCPValidationError",
    "MCPValidator",
]
