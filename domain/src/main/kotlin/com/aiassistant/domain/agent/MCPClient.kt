/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MCPClient.kt
 * Purpose    : Domain port for discovering and invoking MCP
 *              (Model Context Protocol) tools on behalf of agents.
 *              Android agents call this interface; the data layer
 *              implementation routes to the backend via Retrofit.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Port — Hexagonal Architecture)
 *
 * Design Rules:
 *   - Pure Kotlin, zero Android/framework/network dependencies.
 *   - Uses domain model [MCPTool] from domain/model/MCPTool.kt.
 *   - Result types are defined here as simple value objects —
 *     no Retrofit response types, no JSON annotations required
 *     at the domain layer.
 *
 * Relationship to existing types:
 *   - [MCPTool] (domain/model/MCPTool.kt): domain entity for tool
 *     discovery — reused directly.
 *   - The data layer [MCPRepositoryImpl] implements both [MCPClient]
 *     (for agent invocation) and the future [MCPRepository]
 *     (for UI list display), keeping a single HTTP client.
 *
 * Audit contract:
 *   Every [invoke] call MUST result in an audit log entry on the
 *   backend side (enforced by MCPBroker).  This interface does not
 *   enforce it — callers trust that the data-layer implementation
 *   routes to the audited backend endpoint.
 *
 * Dependencies: domain/model/MCPTool.kt, kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import com.aiassistant.domain.model.MCPTool
import kotlinx.serialization.Serializable

// ── Value objects ─────────────────────────────────────────────────────────────

/**
 * Result returned by [MCPClient.invoke].
 *
 * @param toolName      Name of the tool that was (or would have been) invoked.
 * @param success       `true` when the tool executed without error.
 * @param output        Tool output text; empty on failure.
 * @param error         Human-safe error message; empty on success.
 *                      MUST NOT contain stack traces or secret values.
 * @param requiresConfirmation  `true` when the backend requires explicit user
 *                      consent before executing (e.g. write operations).
 *                      When `true`, [success] is `false` and the caller must
 *                      present a confirmation UI before retrying.
 * @param metadata      Arbitrary key-value pairs from the tool's response
 *                      (e.g. `"issueUrl"`, `"emailId"`).
 */
@Serializable
data class MCPInvocationResult(
    val toolName: String,
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val requiresConfirmation: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
) {
    /** True when the result carries no usable output. */
    val isEmpty: Boolean get() = output.isBlank()
}

// ── Error type ────────────────────────────────────────────────────────────────

/**
 * Thrown by [MCPClient.invoke] on unrecoverable network or server failure.
 *
 * Note: tool-level errors (unknown tool, permission denied, connector error)
 * are returned as [MCPInvocationResult] with `success=false`, not thrown.
 * [MCPClientException] is reserved for transport-level failures.
 *
 * @param message   Human-readable description.
 * @param retryable True when a retry may succeed.
 * @param cause     Underlying throwable, if any.
 */
class MCPClientException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Domain port for MCP tool discovery and invocation from the Android client.
 *
 * Implementations live in `:data`:
 * - `RemoteMCPClientAdapter` — calls `GET /mcp/tools` and `POST /mcp/invoke`
 *   via Retrofit.
 *
 * ## Confirmation flow
 * When [invoke] returns [MCPInvocationResult.requiresConfirmation] `= true`:
 * 1. The calling agent emits an [AgentEvent] (e.g. `ToolConfirmationRequired`).
 * 2. The UI presents a [ToolConfirmationDialog].
 * 3. On user approval, the caller invokes [invoke] again with
 *    `confirmed = true`.
 *
 * ## Usage
 * ```kotlin
 * // In ToolAgent.execute():
 * val tools = mcpClient.discoverTools()
 * val github = tools.first { it.name == "github" }
 *
 * val result = mcpClient.invoke(
 *     toolName = "github",
 *     params = mapOf("action" to "list_issues", "repo" to "android-ai-assistant"),
 *     userId = context.userId,
 * )
 * if (result.requiresConfirmation) {
 *     // yield AgentToolConfirmationRequired event and wait
 * }
 * ```
 */
interface MCPClient {

    /**
     * Return all MCP tools currently registered on the backend.
     *
     * Implementations may cache this list for the duration of a session
     * to avoid repeated network calls.
     *
     * @return List of [MCPTool] available for invocation.
     *         Empty list when no tools are registered or the backend
     *         is unreachable.
     */
    suspend fun discoverTools(): List<MCPTool>

    /**
     * Invoke the named MCP tool on behalf of [userId].
     *
     * Never throws for tool-level errors (unknown tool, connector failure,
     * confirmation required) — these are returned as [MCPInvocationResult]
     * with [MCPInvocationResult.success] `= false`.
     *
     * @param toolName  Name of the tool to invoke.  Must not be blank.
     * @param params    Tool-specific string parameters.
     * @param userId    Authenticated user ID.  Must not be blank.
     * @param confirmed When `true`, bypasses the confirmation gate for write
     *                  operations (the user has approved the action in the UI).
     * @return [MCPInvocationResult] describing the outcome.
     * @throws MCPClientException On transport-level failure (network error,
     *                            server 5xx) when a retry is appropriate.
     */
    suspend fun invoke(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean = false,
    ): MCPInvocationResult

    /**
     * Return `true` when a tool with [toolName] is registered.
     *
     * Based on the last [discoverTools] result.  May be stale
     * if tools were registered/deregistered since last discovery.
     *
     * @param toolName Tool name to check.
     */
    fun isRegistered(toolName: String): Boolean
}
