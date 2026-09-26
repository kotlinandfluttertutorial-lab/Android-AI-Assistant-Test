/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRegistry.kt
 * Purpose    : Central store for Agent instances, keyed by name.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Registry (interface + in-domain implementation)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Thread-safe by contract (implementation must be safe for concurrent
 *     read/write from coroutine contexts)
 *   - The orchestrator routes requests via the registry; it contains no
 *     hardcoded agent-specific routing logic
 *
 * Design Decision:
 *   AgentRegistry is defined as an interface in :domain so use cases and
 *   the orchestrator can depend on it without pulling in :data. The
 *   DefaultAgentRegistry implementation (in :domain, pure Kotlin) is
 *   sufficient — no Android runtime is needed for a thread-safe map.
 *
 * Dependencies: domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Error thrown by [AgentRegistry.get] when no agent is registered under
 * the requested name.
 */
class AgentNotFoundException(
    val requestedName: String,
) : Exception("No agent registered with name '$requestedName'.")

/**
 * Central store for [Agent] instances.
 *
 * ## Contract
 * - Names are case-sensitive.
 * - Registering a second agent under an existing name replaces the first.
 * - All operations are thread-safe.
 */
interface AgentRegistry {

    /**
     * Register [agent] under [agent.name].
     *
     * If an agent is already registered under that name it is replaced.
     */
    fun register(agent: Agent)

    /**
     * Remove the agent registered under [name].
     *
     * No-op if no agent is registered under [name].
     */
    fun unregister(name: String)

    /**
     * Return the agent registered under [name].
     *
     * @throws AgentNotFoundException when no agent is registered under [name].
     */
    fun get(name: String): Agent

    /**
     * Return the agent registered under [name], or `null` if not found.
     */
    fun getOrNull(name: String): Agent?

    /**
     * Return an immutable snapshot of all registered agents.
     */
    fun list(): List<Agent>

    /**
     * Return all agents that declare support for every capability in [capabilities].
     *
     * Returns an empty list when no agent satisfies the constraint.
     * Returns [list] unchanged when [capabilities] is empty.
     */
    fun findByCapability(capabilities: Set<AgentCapability>): List<Agent>

    /** True when no agents are registered. */
    val isEmpty: Boolean

    /** Number of registered agents. */
    val size: Int
}

// ── Default in-memory implementation ────────────────────────────────────────

/**
 * Thread-safe in-memory [AgentRegistry] backed by a [java.util.concurrent.ConcurrentHashMap].
 *
 * This implementation is sufficient for both the domain-test environment and
 * the Android runtime. Hilt injects it as a singleton via the data module's
 * DI module.
 */
class DefaultAgentRegistry : AgentRegistry {

    private val store = java.util.concurrent.ConcurrentHashMap<String, Agent>()

    override fun register(agent: Agent) {
        store[agent.name] = agent
    }

    override fun unregister(name: String) {
        store.remove(name)
    }

    override fun get(name: String): Agent =
        store[name] ?: throw AgentNotFoundException(name)

    override fun getOrNull(name: String): Agent? = store[name]

    override fun list(): List<Agent> = store.values.toList()

    override fun findByCapability(capabilities: Set<AgentCapability>): List<Agent> {
        if (capabilities.isEmpty()) return list()
        return store.values.filter { agent ->
            capabilities.all { cap -> cap in agent.capabilities }
        }
    }

    override val isEmpty: Boolean get() = store.isEmpty()

    override val size: Int get() = store.size
}
