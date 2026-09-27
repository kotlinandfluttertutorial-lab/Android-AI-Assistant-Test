/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ToolRegistry.kt
 * Purpose    : Central store for Tool instances + DefaultToolRegistry.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Registry (interface + default impl)
 *
 * Key Concepts:
 *   - Thread-safe via ConcurrentHashMap (same pattern as DefaultAgentRegistry)
 *   - No Android/framework dependencies — pure Kotlin
 *   - ToolRegistry is the only access point for tools; ToolAgent never
 *     constructs or holds Tool references directly
 *   - findByPermission() is the primary discovery path for ToolAgent
 *
 * Dependencies: domain agent models (Tool, ToolSchema, ToolPermission)
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Thrown by [ToolRegistry.get] when no tool is registered under the requested name.
 */
class ToolNotFoundException(val requestedName: String) :
    Exception("No tool registered with name '$requestedName'.")

/**
 * Central registry for [Tool] instances.
 *
 * All operations are thread-safe.
 */
interface ToolRegistry {

    /** Register [tool] under [tool.schema.name]. Replaces any existing entry. */
    fun register(tool: Tool)

    /** Remove the tool registered under [name]. No-op if absent. */
    fun unregister(name: String)

    /**
     * Return the tool registered under [name].
     * @throws ToolNotFoundException when no tool is registered under [name].
     */
    fun get(name: String): Tool

    /** Return the tool registered under [name], or null if absent. */
    fun getOrNull(name: String): Tool?

    /** Return an immutable snapshot of all registered tools. */
    fun list(): List<Tool>

    /**
     * Return all tools whose [ToolSchema.requiredPermissions] are a subset of
     * [callerPermissions] — i.e. tools the caller is allowed to use.
     *
     * Returns all tools when [callerPermissions] is empty (unrestricted context).
     */
    fun findByPermission(callerPermissions: Set<ToolPermission>): List<Tool>

    /** True when no tools are registered. */
    val isEmpty: Boolean

    /** Number of registered tools. */
    val size: Int
}

// ── Default in-memory implementation ─────────────────────────────────────────

/**
 * Thread-safe in-memory [ToolRegistry] backed by [java.util.concurrent.ConcurrentHashMap].
 */
class DefaultToolRegistry : ToolRegistry {

    private val store = java.util.concurrent.ConcurrentHashMap<String, Tool>()

    override fun register(tool: Tool) {
        store[tool.schema.name] = tool
    }

    override fun unregister(name: String) {
        store.remove(name)
    }

    override fun get(name: String): Tool =
        store[name] ?: throw ToolNotFoundException(name)

    override fun getOrNull(name: String): Tool? = store[name]

    override fun list(): List<Tool> = store.values.toList()

    override fun findByPermission(callerPermissions: Set<ToolPermission>): List<Tool> {
        if (callerPermissions.isEmpty()) return list()
        return store.values.filter { tool ->
            tool.schema.requiredPermissions.all { it in callerPermissions }
        }
    }

    override val isEmpty: Boolean get() = store.isEmpty()
    override val size: Int get() = store.size
}
