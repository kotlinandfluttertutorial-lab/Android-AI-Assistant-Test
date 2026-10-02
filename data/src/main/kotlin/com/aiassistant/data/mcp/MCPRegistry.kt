/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : mcp/MCPRegistry.kt
 * Purpose    : MCP tool registry — discovers tools from backend,
 *              maintains local allowlist, supports registration of
 *              custom local tools.
 *
 * Architecture Layer : Data — mcp
 * Pattern Used       : Registry (interface + default impl)
 *
 * Design rules:
 *   - Thread-safe via ConcurrentHashMap.
 *   - Uses [MCPClient] for remote discovery — does NOT make direct
 *     HTTP calls here.
 *   - Allowlist is applied on discovery and isAllowed() checks.
 *   - No external credentials stored here.
 * ============================================================
 */

package com.aiassistant.data.mcp

import com.aiassistant.domain.agent.MCPClient
import com.aiassistant.domain.model.MCPTool
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry interface for MCP tools.
 *
 * Combines remote discovery (via [MCPClient]) with local registration of
 * custom tools and allowlist enforcement.
 */
interface MCPRegistry {

    /**
     * Refresh the tool list from the backend via [MCPClient.discoverTools].
     * Applies the allowlist filter to the discovered set.
     */
    suspend fun refresh()

    /** Register a local [MCPToolModel] directly (not via backend). */
    fun register(model: MCPToolModel)

    /**
     * Unregister the tool with [name].
     * @return `true` when the tool was present and removed; `false` otherwise.
     */
    fun unregister(name: String): Boolean

    /** Return an [MCPToolModel] by name, or `null` if not registered. */
    fun getModel(name: String): MCPToolModel?

    /** Return `true` when [name] is registered regardless of allowlist. */
    fun isRegistered(name: String): Boolean

    /** Return `true` when [name] is registered AND on the allowlist. */
    fun isAllowed(name: String): Boolean

    /** Return all allowed+registered tool names, sorted. */
    fun listAllowed(): List<String>

    /** Return all registered tool names, sorted (ignores allowlist). */
    fun listAll(): List<String>

    /** Number of registered tools (ignores allowlist). */
    val size: Int
}

/**
 * Default thread-safe [MCPRegistry] backed by a [ConcurrentHashMap].
 *
 * @param client        [MCPClient] used for remote tool discovery.
 * @param allowedTools  Set of allowed tool names.  `null` or `setOf("*")` allows all.
 * @param strictDuplicates When `true`, re-registering a name throws [IllegalStateException].
 */
class DefaultMCPRegistry(
    private val client: MCPClient,
    allowedTools: Set<String>? = null,
    private val strictDuplicates: Boolean = false,
) : MCPRegistry {

    private val store = ConcurrentHashMap<String, MCPToolModel>()
    private val allowAll = allowedTools == null || allowedTools == setOf("*")
    private val allowlist: Set<String> = if (allowAll) emptySet() else (allowedTools ?: emptySet())

    // ── Registration ──────────────────────────────────────────────────────────

    override fun register(model: MCPToolModel) {
        val existing = store[model.name]
        if (existing != null && strictDuplicates) {
            throw IllegalStateException(
                "MCPRegistry: tool '${model.name}' is already registered. " +
                    "Use strictDuplicates=false to allow replacement."
            )
        }
        store[model.name] = model
    }

    override fun unregister(name: String): Boolean = store.remove(name) != null

    // ── Discovery ─────────────────────────────────────────────────────────────

    override suspend fun refresh() {
        val tools: List<MCPTool> = try {
            client.discoverTools()
        } catch (_: Exception) {
            return  // graceful degradation — keep existing registrations
        }
        tools.forEach { tool ->
            val model = MCPToolModel.fromMCPTool(tool)
            if (isToolAllowed(model.name)) {
                store[model.name] = model
            }
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    override fun getModel(name: String): MCPToolModel? = store[name]

    override fun isRegistered(name: String): Boolean = store.containsKey(name)

    override fun isAllowed(name: String): Boolean =
        isRegistered(name) && isToolAllowed(name)

    override fun listAllowed(): List<String> =
        store.keys.filter { isToolAllowed(it) }.sorted()

    override fun listAll(): List<String> = store.keys.sorted()

    override val size: Int get() = store.size

    // ── Private ───────────────────────────────────────────────────────────────

    private fun isToolAllowed(name: String): Boolean = allowAll || name in allowlist
}
