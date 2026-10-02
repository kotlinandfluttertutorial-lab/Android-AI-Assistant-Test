/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : mcp/MCPServer.kt
 * Purpose    : Convenience façade composing MCPRegistry, MCPValidator,
 *              and MCPExecutor into a single entry point that mirrors
 *              the MCPServer concept from the MCP specification.
 *
 * Architecture Layer : Data — mcp
 * Pattern Used       : Façade
 *
 * Usage:
 *   val server = MCPServer.create(client, allowedTools = setOf("github_read"))
 *   server.registry.refresh()            // discover from backend
 *   server.registry.register(localModel) // register local tool
 *   val result = server.execute("github_read", params, userId)
 * ============================================================
 */

package com.aiassistant.data.mcp

import com.aiassistant.domain.agent.MCPClient
import com.aiassistant.domain.agent.MCPInvocationResult
import com.aiassistant.domain.model.MCPTool

/**
 * Façade that composes [MCPRegistry], [MCPValidator], and [MCPExecutor].
 *
 * This is the object callers (agents, use cases) should interact with.
 * It owns the full lifecycle: discover → register → validate → execute.
 *
 * @property registry  The underlying [MCPRegistry].
 * @property executor  The underlying [MCPExecutor].
 */
class MCPServer(
    val registry: MCPRegistry,
    val executor: MCPExecutor,
) {
    // ── Registration ──────────────────────────────────────────────────────────

    /** Register [model] directly (not via backend discovery). */
    fun register(model: MCPToolModel) = registry.register(model)

    /** Unregister [toolName]. Returns `true` when it was present. */
    fun unregister(toolName: String): Boolean = registry.unregister(toolName)

    // ── Discovery ─────────────────────────────────────────────────────────────

    /** Refresh tool list from the backend via [MCPClient.discoverTools]. */
    suspend fun refresh() = registry.refresh()

    /** Return all allowed+registered tool names. */
    fun listAllowed(): List<String> = registry.listAllowed()

    /** Return [MCPTool] domain entities for all allowed tools. */
    fun discoverTools(): List<MCPTool> =
        registry.listAllowed().mapNotNull { name ->
            registry.getModel(name)?.toMCPTool()
        }

    /** Return `true` when [toolName] is registered and allowed. */
    fun isAllowed(toolName: String): Boolean = registry.isAllowed(toolName)

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Execute the full MCP safety pipeline for [toolName].
     *
     * Never throws — all errors are returned via [MCPInvocationResult.success] = `false`.
     *
     * @param toolName  Name of the tool to invoke.
     * @param params    Tool-specific parameters.
     * @param userId    Authenticated user ID.
     * @param confirmed `true` when the user has approved a write operation.
     */
    suspend fun execute(
        toolName: String,
        params: Map<String, String>,
        userId: String,
        confirmed: Boolean = false,
    ): MCPInvocationResult {
        val result = executor.execute(
            toolName = toolName,
            params = params,
            userId = userId,
            confirmed = confirmed,
        )
        return result.invocationResult
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /**
         * Create a fully wired [MCPServer].
         *
         * @param client          [MCPClient] for backend invocation and discovery.
         * @param allowedTools    Tool allowlist. `null` = allow all.
         * @param defaultTimeoutMs Fallback per-invocation timeout.
         * @param strictDuplicates Raise when a tool is registered twice.
         */
        fun create(
            client: MCPClient,
            allowedTools: Set<String>? = null,
            defaultTimeoutMs: Long = MCP_DEFAULT_TIMEOUT_MS,
            strictDuplicates: Boolean = false,
        ): MCPServer {
            val registry = DefaultMCPRegistry(
                client = client,
                allowedTools = allowedTools,
                strictDuplicates = strictDuplicates,
            )
            val validator = DefaultMCPValidator()
            val executor = DefaultMCPExecutor(
                registry = registry,
                client = client,
                validator = validator,
                defaultTimeoutMs = defaultTimeoutMs,
            )
            return MCPServer(registry = registry, executor = executor)
        }
    }
}
