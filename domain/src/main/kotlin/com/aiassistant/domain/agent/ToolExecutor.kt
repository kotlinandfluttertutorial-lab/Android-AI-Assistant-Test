/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ToolExecutor.kt
 * Purpose    : Domain port for the full tool execution pipeline:
 *              lookup → validate → permission check → run → timeout.
 *              Agents depend on this interface rather than calling
 *              Tool.execute() directly, ensuring the security pipeline
 *              is always applied.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework dependencies.
 *   - Depends only on domain types from Tool.kt and ToolRegistry.kt
 *     (already in domain/agent/).
 *   - [ToolExecutor] orchestrates the security pipeline; individual
 *     [Tool] implementations contain business logic.
 *
 * Security contract (must be preserved by all implementations):
 *   1. [execute] MUST call [Tool.validate] before [Tool.execute].
 *   2. Permission checks are performed before any I/O.
 *   3. Execution is bounded by a timeout (from [ToolSchema.timeoutMs]
 *      or [ToolExecutionRequest.timeoutMs] when > 0).
 *   4. [ToolExecutionResponse.error] MUST NOT contain stack traces
 *      or internal implementation details.
 *
 * Dependencies: domain/agent/Tool.kt, domain/agent/ToolRegistry.kt
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

// ── Value objects ─────────────────────────────────────────────────────────────

/**
 * Input for one [ToolExecutor.execute] call.
 *
 * @param toolName  Name of the tool to invoke.  Must not be blank.
 * @param args      String-valued arguments for the tool.
 * @param userId    Authenticated user ID.  Must not be blank.
 * @param callerPermissions  Permissions held by [userId].  The executor
 *                  checks that these cover [ToolSchema.requiredPermissions].
 * @param timeoutMs Per-call timeout override in milliseconds.
 *                  `0` means use [ToolSchema.timeoutMs].  Must be ≥ 0.
 */
@Serializable
data class ToolExecutionRequest(
    val toolName: String,
    val args: Map<String, String>,
    val userId: String,
    val callerPermissions: Set<ToolPermission> = emptySet(),
    val timeoutMs: Long = 0L,
) {
    init {
        require(toolName.isNotBlank()) { "ToolExecutionRequest.toolName must not be blank." }
        require(userId.isNotBlank()) { "ToolExecutionRequest.userId must not be blank." }
        require(timeoutMs >= 0) {
            "ToolExecutionRequest.timeoutMs must be ≥ 0, got $timeoutMs."
        }
    }
}

/**
 * Outcome of one [ToolExecutor.execute] call.
 *
 * @param toolName    Name of the tool that was invoked.
 * @param success     `true` when execution completed without error.
 * @param output      Tool output text; blank on failure.
 * @param error       Human-safe error message; blank on success.
 *                    MUST NOT contain stack traces or internal details.
 * @param durationMs  Wall-clock execution time in milliseconds.
 * @param metadata    Arbitrary diagnostic key-value pairs (safe to log).
 */
@Serializable
data class ToolExecutionResponse(
    val toolName: String,
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val durationMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** True when the result carries no usable output. */
    val isEmpty: Boolean get() = output.isBlank()

    companion object {
        /**
         * Convenience factory for a failed result with a safe [errorMessage].
         *
         * @param toolName     Name of the tool that failed.
         * @param errorMessage Human-safe description.  Must not contain stack traces.
         * @param durationMs   Elapsed time before failure.
         */
        fun failure(
            toolName: String,
            errorMessage: String,
            durationMs: Long = 0L,
        ): ToolExecutionResponse = ToolExecutionResponse(
            toolName = toolName,
            success = false,
            error = errorMessage,
            durationMs = durationMs,
        )
    }
}

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for the full tool execution security pipeline.
 *
 * Agents call this interface instead of invoking [Tool.execute] directly
 * to ensure that validation, permission checks, and timeout enforcement
 * are consistently applied on every invocation.
 *
 * Implementations live in `:data`:
 * - `DefaultToolExecutor` — backed by [DefaultToolRegistry], applies the
 *   full pipeline: lookup → validate → permission check → `withTimeout` →
 *   `Tool.execute` → result wrapping.
 *
 * ## Security pipeline
 * The [execute] method MUST apply these steps in order:
 * 1. Look up the tool by [ToolExecutionRequest.toolName].
 *    Return `failure("Tool not found")` if absent.
 * 2. Call [Tool.validate] with [ToolExecutionRequest.args].
 *    Return `failure("<validation message>")` on [ToolValidationError].
 * 3. Check that [ToolExecutionRequest.callerPermissions] ⊇
 *    [ToolSchema.requiredPermissions].
 *    Return `failure("Permission denied: …")` on [ToolPermissionDeniedError].
 * 4. Invoke [Tool.execute] under a `withTimeout()` of
 *    [ToolExecutionRequest.timeoutMs] (or [ToolSchema.timeoutMs] when 0).
 *    Return `failure("Execution timed out …")` on [ToolTimeoutError].
 * 5. Return the [ToolResult] mapped to [ToolExecutionResponse].
 *
 * **Never throws** — all errors are returned as [ToolExecutionResponse]
 * with [ToolExecutionResponse.success] `= false`.
 *
 * ## Usage
 * ```kotlin
 * // In ToolAgent.execute():
 * val response = toolExecutor.execute(
 *     ToolExecutionRequest(
 *         toolName = "calculator",
 *         args = mapOf("expression" to "42 * 7"),
 *         userId = context.userId,
 *         callerPermissions = setOf(ToolPermission.COMPUTE),
 *     )
 * )
 * if (response.success) {
 *     // yield AgentTokenEvent with response.output
 * } else {
 *     // yield AgentToolFailedEvent with response.error
 * }
 * ```
 */
interface ToolExecutor {

    /**
     * Validate and execute the tool specified by [request].
     *
     * Applies the full security pipeline (see interface KDoc above).
     * Never throws — all errors are surfaced as [ToolExecutionResponse]
     * with [ToolExecutionResponse.success] `= false`.
     *
     * @param request The tool execution request.
     * @return [ToolExecutionResponse] describing the outcome.
     */
    suspend fun execute(request: ToolExecutionRequest): ToolExecutionResponse

    /**
     * Return `true` when the named tool is registered and available.
     *
     * @param toolName Tool name to check.
     */
    fun isAvailable(toolName: String): Boolean

    /**
     * Return the names of all tools available through this executor.
     *
     * @return Sorted list of tool names.
     */
    fun listTools(): List<String>
}
