# Agent Tool System

## Overview

The tool system gives agents the ability to invoke registered MCP (Model
Context Protocol) tools with full security enforcement: permission checks,
input validation, confirmation gates, timeouts, and audit logging.

```
ToolAgent.execute()
    │  auth → tool name → registry lookup → permissions → args → confirmation
    ▼
ToolRegistry.get(toolName)
    ▼
Tool.validate(args)          ← throws ToolValidationError on bad input
    ▼
Tool.execute(args, userId)   ← returns ToolResult
```

## ToolPermission Values

```kotlin
enum class ToolPermission {
    READ_DOCUMENTS,      // Read user's own documents
    COMPUTE,             // Numeric computation (no I/O)
    READ_DATETIME,       // Current date/time
    NETWORK_ACCESS,      // Outbound HTTP to external services
    READ_CONVERSATIONS,  // Read conversation history
    WRITE_EXTERNAL,      // Write to external services (GitHub, email, etc.)
}
```

Permissions are declared in `ToolSchema.requiredPermissions`. The caller
declares the permissions it grants in `AgentGateway.executeTool(callerPermissions)`.
`ToolAgent` verifies the intersection before calling `execute()`.

## Built-in Tools

| Tool | Permission | Description |
|------|-----------|-------------|
| `CalculatorTool` | `COMPUTE` | Safe arithmetic via recursive-descent parser; no `eval()` |
| `DateTimeTool` | `READ_DATETIME` | Current date/time; no side effects |
| `DocumentSearchTool` | `READ_DOCUMENTS` | Queries user's own RAG documents |
| `WebSearchTool` | `NETWORK_ACCESS` | Web search stub (requires `WebSearchProvider`) |

## Security Enforcement (Android ToolAgent)

1. **Authentication** — `userId` must be non-blank and non-anonymous.
2. **Tool exists** — `ToolRegistry.get()` throws `ToolNotFoundException` for unknown names.
3. **Permissions** — caller's declared permissions must include all `schema.requiredPermissions`.
4. **Input validation** — `tool.validate(args)` called before `execute()`.
5. **Confirmation gate** — `schema.requiresConfirmation && !confirmed` → `ToolConfirmationRequired`.
6. **Timeout** — `withTimeoutOrNull(schema.timeoutMs)` wraps `execute()`.
7. **Safe errors** — exceptions are caught; `ToolResult.error` never contains stack traces.

## Security Constraints for Tool Implementations

Tool `execute()` MUST NOT:
- Execute shell commands (`ProcessBuilder`, `Runtime.exec()`)
- Access arbitrary filesystem paths
- Read environment variables
- Query any database table other than the user's own data
- Return stack traces in `ToolResult.error`
- Expose API keys or secrets in any output field

## Adding a New Tool

```kotlin
// 1. Implement Tool interface
class MyTool @Inject constructor() : Tool {
    override val schema = ToolSchema(
        name = "my_tool",
        displayName = "My Tool",
        description = "Does something safe.",
        parameters = mapOf("input" to "The input string."),
        requiredPermissions = setOf(ToolPermission.COMPUTE),
        requiresConfirmation = false,
        timeoutMs = 5_000L,
    )

    override fun validate(args: Map<String, String>) {
        val input = args["input"] ?: throw ToolValidationError("my_tool", "input", "is required")
        if (input.isBlank()) throw ToolValidationError("my_tool", "input", "must not be blank")
    }

    override suspend fun execute(args: Map<String, String>, userId: String): ToolResult {
        val input = args["input"]!!
        return ToolResult(toolName = schema.name, success = true, output = "Processed: $input")
    }
}

// 2. Register in AgentDataModule
@Provides @Singleton
fun provideToolRegistry(
    calculatorTool: CalculatorTool,
    myTool: MyTool,          // ← add here
): ToolRegistry = DefaultToolRegistry().also { reg ->
    reg.register(calculatorTool)
    reg.register(myTool)     // ← add here
}
```

## Backend Tool Security (MCPBroker)

The backend `MCPBroker` does not perform independent authentication — it
trusts that `ToolAgent` (or the API router) has already verified the user.
Every `broker.invoke()` call writes an `AuditLog` entry regardless of outcome:

| Path | AuditLog? | Notes |
|------|-----------|-------|
| Unknown tool | ✅ | `result_status = "tool_not_found"` |
| Confirmation required | ✅ | `result_status = "confirmation_required"` |
| Success | ✅ | `result_status = "success"` |
| Error | ✅ | `result_status = "error"` |
