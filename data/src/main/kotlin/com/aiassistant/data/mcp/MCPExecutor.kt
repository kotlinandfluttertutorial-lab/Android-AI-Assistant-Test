/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : mcp/MCPExecutor.kt
 * Purpose    : Async MCP tool executor with full safety pipeline:
 *              allowlist → validation → timeout → invocation → result check.
 *
 * Architecture Layer : Data — mcp
 * Pattern Used       : Executor / Façade
 *
 * Pipeline (in order):
 *   1. Allowlist check   — tool must be registered and allowed.
 *   2. Param validation  — MCPValidator.validate().
 *   3. Confirmation gate — if requiresConfirmation and !confirmed, return early.
 *   4. Timeout wrapper   — withTimeout(timeoutMs).
 *   5. Invocation        — MCPClient.invoke().
 *   6. Result validation — MCPValidator.validateResult().
 *
 * **Never throws** for expected failures — all are returned as
 * MCPInvocationResult(success=false, error=...).
 * Throws only when reraiseErrors=true (for unit tests).
 *
 * Design rules:
 *   - Pure Kotlin coroutines — no Android framework dependencies.
 *   - No credentials stored here.
 * ============================================================
 */

package com.aiassistant.data.mcp

import com.aiassistant.domain.agent.MCPClient
import com.aiassistant.domain.agent.MCPInvocationResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Default timeout when neither the model nor the caller specifies one. */
const val MCP_DEFAULT_TIMEOUT_MS: Long = 30_000L

/**
 * Result type from [MCPExecutor.execute], wrapping [MCPInvocationResult]
 * with execution metadata.
 */
data class MCPExecutionResult(
    val invocationResult: MCPInvocationResult,
    val durationMs: Long = 0L,
    val validationPassed: Boolean = true,
)

/**
 * Interface for the MCP tool execution pipeline.
 */
interface MCPExecutor {
    /**
     * Execute the full pipeline for [toolName].
     *
     * Never throws for expected failures — errors are returned as
     * [MCPExecutionResult] with [MCPInvocationResult.success] = `false`.
     *
     * @param toolName    Tool to invoke.
     * @param params      Tool-specific parameters.
     * @param userId      Authenticated user ID.
     * @param confirmed   `true` when the user has confirmed a write operation.
     * @param reraiseErrors When `true`, throws [MCPValidationException] /
     *                    [MCPExecutionException] instead of wrapping them.
     *                    For unit tests only.
     */
    suspend fun execute(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean = false,
        reraiseErrors: Boolean = false,
    ): MCPExecutionResult
}

/**
 * Default [MCPExecutor] implementation.
 *
 * @param registry  [MCPRegistry] with tool models and allowlist.
 * @param client    [MCPClient] used for actual invocation.
 * @param validator [MCPValidator] for param and result validation.
 * @param defaultTimeoutMs Fallback timeout when the model specifies 0.
 */
class DefaultMCPExecutor(
    private val registry: MCPRegistry,
    private val client: MCPClient,
    private val validator: MCPValidator = DefaultMCPValidator(),
    private val defaultTimeoutMs: Long = MCP_DEFAULT_TIMEOUT_MS,
) : MCPExecutor {

    override suspend fun execute(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean,
        reraiseErrors: Boolean,
    ): MCPExecutionResult {
        val startMs = System.currentTimeMillis()

        // ── Step 1: Allowlist ─────────────────────────────────────────────
        if (!registry.isAllowed(toolName)) {
            val msg = "Tool '$toolName' is not registered or not on the allowlist."
            if (reraiseErrors) throw MCPExecutionException(toolName, msg)
            return failure(toolName, msg, startMs)
        }

        val model = registry.getModel(toolName)

        // ── Step 2: Param validation ──────────────────────────────────────
        if (model != null) {
            try {
                validator.validate(params, model)
            } catch (e: MCPValidationException) {
                if (reraiseErrors) throw e
                return failure(toolName, e.message ?: "Validation failed.", startMs)
            }
        }

        // ── Step 3: Confirmation gate ─────────────────────────────────────
        val requiresConfirm = model?.requiresConfirmation ?: false
        if (requiresConfirm && !confirmed) {
            val result = MCPInvocationResult(
                toolName = toolName,
                success = false,
                requiresConfirmation = true,
                error = "This tool requires user confirmation before execution.",
            )
            return MCPExecutionResult(
                invocationResult = result,
                durationMs = elapsed(startMs),
                validationPassed = true,
            )
        }

        // ── Step 4 + 5: Timeout + invocation ─────────────────────────────
        val timeoutMs = (model?.takeIf { it.timeoutMs > 0 }?.timeoutMs) ?: defaultTimeoutMs
        val invocationResult: MCPInvocationResult = try {
            withTimeout(timeoutMs) {
                client.invoke(
                    toolName = toolName,
                    params = params,
                    userId = userId,
                    confirmed = confirmed,
                )
            }
        } catch (e: TimeoutCancellationException) {
            val exc = MCPTimeoutException(toolName = toolName, timeoutMs = timeoutMs)
            if (reraiseErrors) throw exc
            return failure(toolName, exc.safeMessage, startMs)
        } catch (e: Exception) {
            val msg = "Tool invocation failed. Please try again."
            if (reraiseErrors) throw MCPExecutionException(toolName, msg, cause = e)
            return failure(toolName, msg, startMs)
        }

        // ── Step 6: Result validation ─────────────────────────────────────
        var validationPassed = true
        try {
            validator.validateResult(invocationResult, toolName)
        } catch (e: MCPValidationException) {
            validationPassed = false
            if (reraiseErrors) throw e
            // Best-effort: return result anyway (result validation is informational)
        }

        return MCPExecutionResult(
            invocationResult = invocationResult,
            durationMs = elapsed(startMs),
            validationPassed = validationPassed,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun failure(toolName: String, error: String, startMs: Long) = MCPExecutionResult(
        invocationResult = MCPInvocationResult(
            toolName = toolName,
            success = false,
            error = error,
        ),
        durationMs = elapsed(startMs),
        validationPassed = false,
    )

    private fun elapsed(startMs: Long): Long = System.currentTimeMillis() - startMs
}
