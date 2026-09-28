/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ToolAgent.kt
 * Purpose    : Agent that parses LLM tool-call decisions and executes
 *              them through the ToolRegistry with full security validation.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Security pipeline before every tool invocation:
 *       1. Authenticated userId (non-null, non-blank) — enforced here
 *       2. Tool exists in registry — ToolNotFoundException → Failed
 *       3. Caller permissions checked — ToolPermissionDeniedError → Failed
 *       4. Arguments validated — ToolValidationError → Failed
 *       5. Execution under timeout — ToolTimeoutError → Failed
 *       6. Tool result never exposes stack traces
 *   - No shell access, no arbitrary file access, no secrets in output
 *   - Tool confirmation: when ToolSchema.requiresConfirmation=true, emits
 *     ToolConfirmationRequired and waits for a second request with
 *     metadata["confirmed"]="true" before executing
 *   - All tool calls are logged as AgentEvent.ToolStarted / ToolCompleted /
 *     ToolFailed so the UI can show progress indicators
 *
 * Request metadata keys:
 *   "tool_name"   — name of the tool to invoke (required)
 *   "tool_args.*" — tool arguments, prefixed with "tool_args."
 *                   e.g. metadata["tool_args.expression"] = "2+2"
 *   "confirmed"   — "true" to confirm a write-action tool
 *   "permissions" — comma-separated ToolPermission names the caller grants
 *
 * Dependencies: domain (Agent, ToolRegistry, Tool, ToolPermission,
 *               ToolValidationError, ToolPermissionDeniedError, ToolTimeoutError)
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.ToolNotFoundException
import com.aiassistant.domain.agent.ToolPermission
import com.aiassistant.domain.agent.ToolPermissionDeniedError
import com.aiassistant.domain.agent.ToolRegistry
import com.aiassistant.domain.agent.ToolValidationError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Agent that executes tool calls with full security validation.
 *
 * ## Execution pipeline
 * ```
 * AgentRequest
 *   → extract tool_name + tool_args.* + permissions from metadata
 *   → check userId is non-blank (authentication)
 *   → ToolRegistry.get(tool_name)  ← ToolNotFoundException → Failed
 *   → check caller permissions ← ToolPermissionDeniedError → Failed
 *   → if requiresConfirmation && !confirmed → ToolConfirmationRequired event
 *   → Tool.validate(args) ← ToolValidationError → Failed
 *   → withTimeout(schema.timeoutMs) { tool.execute(args, userId) }
 *   → ToolResult → AgentEvent.ToolCompleted or ToolFailed
 *   → AgentEvent.Completed
 * ```
 *
 * ## Streaming protocol
 * 1. [AgentEvent.Started]
 * 2. [AgentEvent.StatusChanged] (RUNNING)
 * 3. [AgentEvent.ToolStarted]
 * 4a. [AgentEvent.ToolConfirmationRequired] (if confirmation needed)
 *   OR
 * 4b. [AgentEvent.ToolCompleted] or [AgentEvent.ToolFailed]
 * 5. [AgentEvent.Completed] or [AgentEvent.Failed]
 */
@Singleton
class ToolAgent @Inject constructor(
    private val toolRegistry: ToolRegistry,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Executes registered tools (Calculator, DocumentSearch, DateTime, WebSearch) " +
            "with permission validation, argument checking, and timeout enforcement."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.TOOL_USE,
        AgentCapability.TEXT_GENERATION,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return request.capabilities.isEmpty() || AgentCapability.TOOL_USE in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        // ── 1. Authenticate — userId must be non-blank ────────────────────
        val userId = request.userId.trim()
        if (userId.isBlank()) {
            emit(failedEvent(execution, request, "UNAUTHENTICATED",
                "Tool execution requires an authenticated user."))
            return@flow
        }

        // ── 2. Extract tool name ──────────────────────────────────────────
        val toolName = request.metadata[METADATA_TOOL_NAME]?.trim()
        if (toolName.isNullOrBlank()) {
            emit(failedEvent(execution, request, "MISSING_TOOL_NAME",
                "metadata['$METADATA_TOOL_NAME'] is required."))
            return@flow
        }

        // ── 3. Resolve tool from registry ─────────────────────────────────
        val tool = try {
            toolRegistry.get(toolName)
        } catch (e: ToolNotFoundException) {
            emit(failedEvent(execution, request, "TOOL_NOT_FOUND", e.message ?: "Tool '$toolName' not found."))
            return@flow
        }

        // ── 4. Parse caller permissions ────────────────────────────────────
        val callerPermissions = parsePermissions(request.metadata[METADATA_PERMISSIONS])

        // ── 5. Check required permissions ─────────────────────────────────
        val requiredPermissions = tool.schema.requiredPermissions
        val missingPermission = requiredPermissions.firstOrNull { it !in callerPermissions }
        if (missingPermission != null) {
            val err = ToolPermissionDeniedError(toolName, missingPermission)
            emit(failedEvent(execution, request, "PERMISSION_DENIED", err.message ?: "Permission denied."))
            return@flow
        }

        // ── 6. Extract tool arguments (prefixed with "tool_args.") ─────────
        val toolArgs: Map<String, String> = request.metadata
            .filter { it.key.startsWith(ARGS_PREFIX) }
            .mapKeys { it.key.removePrefix(ARGS_PREFIX) }

        // ── 7. Confirmation gate for write-action tools ────────────────────
        val confirmed = request.metadata[METADATA_CONFIRMED]?.trim()?.lowercase() == "true"
        if (tool.schema.requiresConfirmation && !confirmed) {
            emit(
                AgentEvent.ToolConfirmationRequired(
                    toolName = toolName,
                    parameters = toolArgs.entries.joinToString(", ") { "${it.key}=${it.value}" },
                    rationale = tool.schema.description,
                )
            )
            // Do NOT proceed — caller must resend with confirmed=true
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.PARTIAL,
                        content = null,
                        metadata = mapOf(
                            "awaiting_confirmation" to toolName,
                            "action" to "resend_with_confirmed_true",
                        ),
                    )
                )
            )
            return@flow
        }

        // ── 8. Validate arguments ─────────────────────────────────────────
        try {
            tool.validate(toolArgs)
        } catch (e: ToolValidationError) {
            emit(failedEvent(execution, request, "INVALID_ARGUMENTS",
                e.message ?: "Argument validation failed."))
            return@flow
        }

        // ── 9. Execute with timeout ────────────────────────────────────────
        emit(AgentEvent.ToolStarted(toolName, toolArgs.toString()))

        val start = System.currentTimeMillis()
        val toolResult = try {
            withTimeout(tool.schema.timeoutMs) {
                tool.execute(toolArgs, userId)
            }
        } catch (_: TimeoutCancellationException) {
            Timber.w("ToolAgent: tool '$toolName' timed out after ${tool.schema.timeoutMs}ms")
            null
        } catch (e: Exception) {
            Timber.e(e, "ToolAgent: unexpected exception from tool '$toolName'")
            null
        }

        // ── 10. Surface result as agent events ────────────────────────────
        if (toolResult == null) {
            emit(AgentEvent.ToolFailed(toolName, "Tool '$toolName' timed out or failed unexpectedly."))
            emit(failedEvent(execution, request, "TOOL_TIMEOUT",
                "Tool '$toolName' exceeded its ${tool.schema.timeoutMs}ms time limit."))
            return@flow
        }

        val durationMs = System.currentTimeMillis() - start

        if (toolResult.success) {
            emit(AgentEvent.ToolCompleted(toolName, toolResult.output ?: "", durationMs))
            emit(
                AgentEvent.Completed(
                    AgentResult(
                        executionId = execution.executionId,
                        requestId = request.requestId,
                        agentName = name,
                        status = AgentStatus.COMPLETED,
                        content = toolResult.output,
                        metadata = buildMap {
                            put("tool_name", toolName)
                            put("duration_ms", durationMs.toString())
                            toolResult.metadata.forEach { (k, v) -> put("tool.$k", v) }
                        },
                    )
                )
            )
        } else {
            val errorMsg = toolResult.error ?: "Tool '$toolName' failed without an error message."
            emit(AgentEvent.ToolFailed(toolName, errorMsg))
            emit(failedEvent(execution, request, "TOOL_ERROR", errorMsg))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parsePermissions(raw: String?): Set<ToolPermission> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { token ->
                try { ToolPermission.valueOf(token.trim().uppercase()) }
                catch (_: IllegalArgumentException) { null }
            }
            .toSet()
    }

    private fun failedEvent(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentEvent.Failed(
        AgentResult(
            executionId = execution.executionId,
            requestId = request.requestId,
            agentName = name,
            status = AgentStatus.FAILED,
            error = AgentError(code = code, message = message),
        )
    )

    companion object {
        const val NAME = "tool-executor"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_TOOL_NAME = "tool_name"
        const val METADATA_CONFIRMED = "confirmed"
        const val METADATA_PERMISSIONS = "permissions"
        const val ARGS_PREFIX = "tool_args."
    }
}
