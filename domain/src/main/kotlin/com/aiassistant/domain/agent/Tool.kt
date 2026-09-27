/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Tool.kt
 * Purpose    : Provider-independent tool contract: interface, schema,
 *              result, and permission types used by ToolAgent and ToolRegistry.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface hierarchy + value objects
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Tool.execute() is suspend so I/O tools (calculator, document search)
 *     never block the caller's thread
 *   - ToolPermission is a closed enum; adding a new permission is a
 *     deliberate change to this file — prevents accidental escalation
 *   - Tools declare required permissions; ToolAgent validates before invoking
 *   - Argument validation happens inside Tool.validate() before execute()
 *   - Tools MUST NOT expose secrets, shell access, or unrestricted filesystem
 *
 * Security model:
 *   1. Caller authenticates (userId is non-null, non-blank)
 *   2. ToolRegistry enforces ownership — caller can only use tools permitted
 *      for their role
 *   3. Tool.validate() rejects malformed arguments with ToolValidationError
 *   4. Tool.execute() runs under a caller-supplied timeout via withTimeout()
 *   5. Tool results carry no internal stack traces — only safe error messages
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Permissions ───────────────────────────────────────────────────────────────

/**
 * Closed set of permissions a [Tool] may require.
 *
 * A tool declares its [ToolPermission] requirements in [ToolSchema.requiredPermissions].
 * [ToolAgent] checks that the calling user has all required permissions before
 * calling [Tool.execute].
 *
 * Deliberately minimal — add values here only when a genuinely new capability
 * category is introduced.
 */
@Serializable
enum class ToolPermission {
    /** Read user's own documents. */
    READ_DOCUMENTS,

    /** Perform numeric computation (no I/O). */
    COMPUTE,

    /** Read current date/time (no user data accessed). */
    READ_DATETIME,

    /** Issue outbound HTTP requests to external services. */
    NETWORK_ACCESS,

    /** Read conversation history. */
    READ_CONVERSATIONS,

    /** Write to external services (e.g. create a GitHub issue). */
    WRITE_EXTERNAL,
}

// ── Schema ────────────────────────────────────────────────────────────────────

/**
 * Static description of a [Tool] — name, capability description, parameter
 * schema, and security requirements.
 *
 * @param name                Unique stable identifier (e.g. `"calculator"`).
 * @param displayName         Human-readable name for the Tools screen.
 * @param description         One-sentence capability description.
 * @param parametersSchema    JSON Schema–style map of parameter name → description.
 *                            Empty map = no parameters.
 * @param requiredPermissions Permissions the caller must hold to invoke this tool.
 * @param requiresConfirmation When `true`, the UI must show a confirmation dialog
 *                             before the tool executes (write operations).
 * @param timeoutMs           Maximum wall-clock time (ms) the tool may run.
 *                            Defaults to [DEFAULT_TIMEOUT_MS].
 */
@Serializable
data class ToolSchema(
    val name: String,
    val displayName: String,
    val description: String,
    val parametersSchema: Map<String, String> = emptyMap(),
    val requiredPermissions: Set<ToolPermission> = emptySet(),
    val requiresConfirmation: Boolean = false,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(name.isNotBlank()) { "ToolSchema.name must not be blank." }
        require(displayName.isNotBlank()) { "ToolSchema.displayName must not be blank." }
        require(timeoutMs > 0) { "ToolSchema.timeoutMs must be positive." }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}

// ── Result ────────────────────────────────────────────────────────────────────

/**
 * Outcome of a single [Tool.execute] call.
 *
 * @param toolName   Name of the tool that produced this result.
 * @param success    True when the tool completed without error.
 * @param output     The tool's output (plain text, JSON, Markdown).
 *                   Non-null on success; may be null on failure.
 * @param error      Human-safe error message. Non-null on failure.
 *                   MUST NOT contain stack traces or secrets.
 * @param durationMs Wall-clock time the tool execution took.
 * @param metadata   Arbitrary diagnostic key-value pairs.
 */
@Serializable
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val durationMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** True when the result carries no usable output. */
    val isEmpty: Boolean get() = output.isNullOrBlank()
}

// ── Errors ────────────────────────────────────────────────────────────────────

/**
 * Thrown by [Tool.validate] when arguments are malformed or missing.
 *
 * Safe to display to the user — no internal details should be included.
 *
 * @param toolName   The tool whose validation failed.
 * @param fieldName  The offending parameter name, if applicable.
 * @param reason     Human-readable description of the violation.
 */
class ToolValidationError(
    val toolName: String,
    val fieldName: String? = null,
    val reason: String,
) : Exception(buildMessage(toolName, fieldName, reason)) {
    companion object {
        private fun buildMessage(toolName: String, fieldName: String?, reason: String): String =
            if (fieldName != null) "[$toolName] Parameter '$fieldName': $reason"
            else "[$toolName] $reason"
    }
}

/**
 * Thrown when the caller lacks a required [ToolPermission].
 *
 * @param toolName           The tool that required the missing permission.
 * @param missingPermission  The permission that was absent.
 */
class ToolPermissionDeniedError(
    val toolName: String,
    val missingPermission: ToolPermission,
) : Exception("[$toolName] Permission denied: $missingPermission required.")

/**
 * Thrown when a tool execution exceeds its declared [ToolSchema.timeoutMs].
 *
 * @param toolName  The tool that timed out.
 * @param timeoutMs The limit that was exceeded.
 */
class ToolTimeoutError(
    val toolName: String,
    val timeoutMs: Long,
) : Exception("[$toolName] Execution timed out after ${timeoutMs}ms.")

// ── Tool interface ────────────────────────────────────────────────────────────

/**
 * Contract every tool implementation must satisfy.
 *
 * ## Implementing a tool
 *
 * ```kotlin
 * class CalculatorTool : Tool {
 *     override val schema = ToolSchema(
 *         name = "calculator",
 *         displayName = "Calculator",
 *         description = "Evaluates arithmetic expressions.",
 *         parametersSchema = mapOf("expression" to "The arithmetic expression to evaluate."),
 *         requiredPermissions = setOf(ToolPermission.COMPUTE),
 *     )
 *
 *     override fun validate(args: Map<String, String>) {
 *         args["expression"]?.ifBlank { throw ToolValidationError("calculator", "expression", "must not be blank") }
 *             ?: throw ToolValidationError("calculator", "expression", "is required")
 *     }
 *
 *     override suspend fun execute(
 *         args: Map<String, String>,
 *         userId: String,
 *     ): ToolResult { ... }
 * }
 * ```
 *
 * ## Security rules
 * - Tools MUST NOT read from or write to arbitrary file paths.
 * - Tools MUST NOT execute shell commands.
 * - Tools MUST NOT include stack traces or secrets in [ToolResult.error].
 * - Tools MUST validate all [args] in [validate] before executing.
 */
interface Tool {

    /** Static description of this tool. */
    val schema: ToolSchema

    /**
     * Validates [args] before execution.
     *
     * Called by [com.aiassistant.data.agent.ToolAgent] before [execute].
     * Should throw [ToolValidationError] for any missing or malformed argument.
     *
     * @throws ToolValidationError if validation fails.
     */
    fun validate(args: Map<String, String>)

    /**
     * Executes this tool with the given [args] on behalf of [userId].
     *
     * The implementation must honour [ToolSchema.timeoutMs]; the caller ([ToolAgent])
     * wraps this call in `withTimeout()` as an additional enforcement layer.
     *
     * @param args    Validated arguments (already passed through [validate]).
     * @param userId  Authenticated user ID — never null or blank.
     * @return [ToolResult] — never throws; all errors are surfaced as
     *         `ToolResult(success=false, error=...)`.
     */
    suspend fun execute(args: Map<String, String>, userId: String): ToolResult
}
