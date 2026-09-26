/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Agent.kt
 * Purpose    : Provider-independent interface every Agent implementation must satisfy.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Strategy / Provider)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Implementations live in the data layer; the domain only defines the contract
 *   - execute() returns a cold Flow<AgentEvent> so callers get incremental updates
 *     without polling, and cancellation is handled automatically via Flow collection
 *   - canHandle() is a pure predicate — no side effects, no network calls
 *
 * Design Decision:
 *   This interface mirrors the existing AIStreamClient pattern in core-ai: a cold
 *   Flow is returned rather than a suspend function so that the caller controls
 *   the lifecycle via coroutine scope cancellation.  Streaming token events are
 *   emitted from within the same flow rather than via a separate channel.
 *
 *   All agent implementations are registered in a registry (future AgentRegistry),
 *   not hardwired.  The orchestration layer calls canHandle() on each registered
 *   agent and routes to the first match.
 *
 * Dependencies: kotlinx.coroutines.flow, domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * The contract every agent must implement.
 *
 * An agent is a self-contained unit of AI capability that:
 * 1. Declares what it can do via [capabilities].
 * 2. Decides whether it can handle a specific request via [canHandle].
 * 3. Executes the request and emits progress as a [Flow] of [AgentEvent].
 *
 * ## Implementing an Agent
 *
 * ```kotlin
 * @Singleton
 * class ConversationalAgent @Inject constructor(
 *     private val streamClient: AIStreamClient,
 *     private val dispatchers: DispatcherProvider,
 * ) : Agent {
 *
 *     override val name = "conversational"
 *     override val description = "General-purpose conversational AI agent."
 *     override val capabilities = setOf(
 *         AgentCapability.TEXT_GENERATION,
 *         AgentCapability.STREAMING,
 *         AgentCapability.MEMORY_ACCESS,
 *     )
 *
 *     override fun canHandle(request: AgentRequest): Boolean =
 *         request.capabilities.isEmpty() ||
 *         request.capabilities.all { it in capabilities }
 *
 *     override fun execute(
 *         request: AgentRequest,
 *         execution: AgentExecution,
 *     ): Flow<AgentEvent> = callbackFlow {
 *         // ... emit AgentEvent values here ...
 *         awaitClose { /* cleanup */ }
 *     }
 * }
 * ```
 *
 * ## Threading
 *
 * [execute] is called from whatever dispatcher the orchestration layer uses.
 * Implementations must not block the calling thread; use `withContext` or
 * `flowOn` to shift heavy work to the IO dispatcher.
 *
 * ## Cancellation
 *
 * When the collector cancels the returned Flow, the agent must stop all
 * in-flight work (WebSocket connection, tool calls, etc.) within 500 ms.
 * Use `awaitClose { cleanup() }` in `callbackFlow` implementations.
 */
interface Agent {

    /**
     * Unique, stable identifier for this agent.
     *
     * Used in [AgentResult.agentName] and routing logs.  Convention: lowercase,
     * hyphenated, e.g. "conversational", "code-analysis", "on-device-rag".
     */
    val name: String

    /**
     * One-sentence human-readable description of what this agent does.
     *
     * Surfaced in settings/debug UIs.
     */
    val description: String

    /**
     * The set of [AgentCapability] values this agent declares support for.
     *
     * The orchestration layer uses this set (together with [canHandle]) to route
     * requests to the most capable available agent.
     */
    val capabilities: Set<AgentCapability>

    /**
     * Returns `true` if this agent is able to handle [request].
     *
     * Default implementation: returns true when every capability required by the
     * request is declared in [capabilities], or when no capability constraint is
     * specified.
     *
     * Override to add richer logic (e.g. checking context size, online/offline
     * state, feature flags).
     *
     * This must be a pure function — no side effects, no suspend.
     *
     * @param request The incoming agent request to evaluate.
     */
    fun canHandle(request: AgentRequest): Boolean =
        request.capabilities.isEmpty() || request.capabilities.all { it in capabilities }

    /**
     * Executes the [request] and emits [AgentEvent] values until completion.
     *
     * The returned Flow is cold — execution only starts when a collector subscribes.
     * The Flow always terminates with one of:
     * - [AgentEvent.Completed] — execution finished successfully.
     * - [AgentEvent.Failed]    — unrecoverable error.
     * - [AgentEvent.Cancelled] — cancelled by the collector.
     *
     * The [execution] parameter carries the pre-populated [AgentExecution] snapshot
     * (with status STARTED) created by the orchestration layer.  The agent updates
     * it by emitting [AgentEvent.StatusChanged] events; it does NOT mutate the
     * [AgentExecution] object directly.
     *
     * @param request   The validated [AgentRequest] to execute.
     * @param execution The pre-created [AgentExecution] context for this run.
     * @return A cold [Flow] of [AgentEvent] values.
     */
    fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent>
}
