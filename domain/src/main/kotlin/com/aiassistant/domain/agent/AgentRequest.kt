/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentRequest.kt
 * Purpose    : Strongly-typed input contract for submitting work to an Agent.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Immutable value object (data class)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Validated at construction via init block; invalid requests are rejected
 *     before reaching the Agent, keeping Agent implementations clean
 *   - Serialisable for local persistence (offline queue) and transport
 *
 * Design Decision:
 *   A single AgentRequest type carries all information an agent needs to start
 *   execution.  Optional fields are nullable rather than overloaded constructors
 *   so that call-sites are explicit about what they are (and are not) providing.
 *   The conversationId is optional because some agent tasks (e.g. one-shot code
 *   analysis, document translation) are not tied to a persistent conversation.
 *
 * Dependencies: kotlinx-serialization-json, java.time.Instant
 * ============================================================
 */

package com.aiassistant.domain.agent

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Encapsulates everything an [Agent] needs to begin execution.
 *
 * Build via the [AgentRequest] constructor or the [AgentRequest.Builder] helper
 * when many optional fields are involved.
 *
 * Validation rules (enforced in `init`):
 * - [input] must not be blank.
 * - [userId] must not be blank.
 * - [maxSteps] must be in the range [1, 50].
 * - [timeoutMs] must be positive.
 *
 * @param requestId      Unique identifier for this request. Defaults to a random UUID.
 * @param userId         Identifier of the user who submitted the request.
 * @param input          Natural-language instruction or query for the agent.
 * @param conversationId Optional conversation to attach this execution to.
 * @param provider       LLM provider hint (e.g. "gemini", "openai").  If null the
 *                       agent uses its own default.
 * @param capabilities   Required capabilities the selected agent must support.
 *                       Empty set = no capability constraint.
 * @param context        Pre-assembled [AgentContext] injected by the orchestration layer.
 *                       May be null if the agent is responsible for building its own context.
 * @param maxSteps       Maximum number of tool-call / reasoning steps to allow.
 *                       Defaults to [DEFAULT_MAX_STEPS].  Range: 1–50.
 * @param timeoutMs      Wall-clock timeout in milliseconds for the entire execution.
 *                       Defaults to [DEFAULT_TIMEOUT_MS].
 * @param streamingEnabled Whether the caller expects token-by-token streaming events.
 * @param metadata       Arbitrary key-value pairs for caller-specific context
 *                       (e.g. screen name, feature flag state).
 * @param createdAt      Timestamp when the request was created.
 */
@Serializable
data class AgentRequest(
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val userId: String,
    val input: String,
    val conversationId: String? = null,
    val provider: String? = null,
    val capabilities: Set<AgentCapability> = emptySet(),
    val context: AgentContext? = null,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val streamingEnabled: Boolean = true,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = Instant.now().toEpochMilli(),
) {
    init {
        require(input.isNotBlank()) { "AgentRequest.input must not be blank." }
        require(userId.isNotBlank()) { "AgentRequest.userId must not be blank." }
        require(maxSteps in 1..MAX_STEPS_LIMIT) {
            "AgentRequest.maxSteps must be between 1 and $MAX_STEPS_LIMIT, was $maxSteps."
        }
        require(timeoutMs > 0) {
            "AgentRequest.timeoutMs must be positive, was $timeoutMs."
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    /**
     * Fluent builder for [AgentRequest] when many optional fields are set.
     *
     * ```kotlin
     * val request = AgentRequest.Builder(userId = "abc", input = "Summarise this document")
     *     .conversationId("conv-123")
     *     .capabilities(setOf(AgentCapability.DOCUMENT_RETRIEVAL))
     *     .maxSteps(10)
     *     .build()
     * ```
     */
    class Builder(private val userId: String, private val input: String) {
        private var requestId: String = java.util.UUID.randomUUID().toString()
        private var conversationId: String? = null
        private var provider: String? = null
        private var capabilities: Set<AgentCapability> = emptySet()
        private var context: AgentContext? = null
        private var maxSteps: Int = DEFAULT_MAX_STEPS
        private var timeoutMs: Long = DEFAULT_TIMEOUT_MS
        private var streamingEnabled: Boolean = true
        private var metadata: Map<String, String> = emptyMap()

        fun requestId(v: String)                         = apply { requestId = v }
        fun conversationId(v: String?)                   = apply { conversationId = v }
        fun provider(v: String?)                         = apply { provider = v }
        fun capabilities(v: Set<AgentCapability>)        = apply { capabilities = v }
        fun context(v: AgentContext?)                    = apply { context = v }
        fun maxSteps(v: Int)                             = apply { maxSteps = v }
        fun timeoutMs(v: Long)                           = apply { timeoutMs = v }
        fun streamingEnabled(v: Boolean)                 = apply { streamingEnabled = v }
        fun metadata(v: Map<String, String>)             = apply { metadata = v }

        /** Builds and validates the [AgentRequest].  Throws [IllegalArgumentException] on violations. */
        fun build(): AgentRequest = AgentRequest(
            requestId = requestId,
            userId = userId,
            input = input,
            conversationId = conversationId,
            provider = provider,
            capabilities = capabilities,
            context = context,
            maxSteps = maxSteps,
            timeoutMs = timeoutMs,
            streamingEnabled = streamingEnabled,
            metadata = metadata,
        )
    }

    companion object {
        /** Default maximum reasoning / tool-call steps per execution. */
        const val DEFAULT_MAX_STEPS: Int = 10

        /** Default wall-clock timeout: 60 seconds. */
        const val DEFAULT_TIMEOUT_MS: Long = 60_000L

        /** Hard upper limit for [maxSteps] to prevent runaway executions. */
        const val MAX_STEPS_LIMIT: Int = 50
    }
}
