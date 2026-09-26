/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : LlmClient.kt
 * Purpose    : Provider-independent LLM client interface used by Agent
 *              implementations to generate completions and stream tokens.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Strategy / Adapter)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Wraps the existing AIStreamClient (WebSocket) and on-device engine
 *     without modifying either; concrete adapters live in :data
 *   - generate() for non-streaming completions (code analysis, translation,
 *     document generation) — returns full text
 *   - stream() for incremental token delivery (chat) — returns cold Flow
 *
 * Design Decision:
 *   This is a thin domain-layer contract. The two concrete adapters in :data are:
 *     - CloudLlmClientAdapter   — wraps AIStreamClient (WebSocket to backend)
 *     - OnDeviceLlmClientAdapter — wraps OnDeviceInferenceClient (GGUF engine)
 *   Neither existing class is modified.
 *
 * Dependencies: kotlinx.coroutines.flow, domain agent models
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Provider-independent contract for generating LLM completions.
 *
 * Implementations must:
 * - Never block the calling coroutine for more than ~10 ms of CPU work
 *   before suspending or launching I/O.
 * - Honour cancellation: [stream] must stop emitting tokens when the
 *   collecting coroutine is cancelled.
 * - Never throw from [stream]; surface errors as [LlmEvent.Error].
 */
interface LlmClient {

    /**
     * The provider name this client is backed by (e.g. "gemini", "on_device").
     * Matches the existing [com.aiassistant.core.ai.LlmProvider.id] values.
     */
    val providerName: String

    /**
     * Returns `true` when this client can currently serve requests.
     * For the cloud adapter this mirrors connectivity; for on-device it
     * mirrors model readiness.
     */
    val isAvailable: Boolean

    /**
     * Generate a complete (non-streaming) text response for [request].
     *
     * @return [LlmResponse] with the full generated text and usage stats.
     * @throws LlmClientException on unrecoverable failure.
     */
    suspend fun generate(request: LlmRequest): LlmResponse

    /**
     * Stream response tokens incrementally for [request].
     *
     * The returned Flow emits [LlmEvent] values and always terminates with
     * [LlmEvent.Done] or [LlmEvent.Error].  Never throws; errors surface
     * as events.
     */
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

// ── Supporting types ────────────────────────────────────────────────────────

/**
 * Input to any [LlmClient] call.
 *
 * @param prompt         The user's message or assembled agent prompt.
 * @param systemPrompt   Optional system / persona instruction.
 * @param conversationId Conversation identifier for logging.
 * @param userId         User identifier for audit and rate-limiting.
 * @param maxTokens      Max tokens to generate; null = provider default.
 * @param temperature    Sampling temperature; null = provider default.
 * @param ragContext     RAG document snippets already assembled by the caller.
 */
data class LlmRequest(
    val prompt: String,
    val systemPrompt: String = "",
    val conversationId: String? = null,
    val userId: String? = null,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val ragContext: List<String> = emptyList(),
) {
    init {
        require(prompt.isNotBlank()) { "LlmRequest.prompt must not be blank." }
    }
}

/**
 * Non-streaming response returned by [LlmClient.generate].
 *
 * @param text        The generated text.
 * @param provider    Provider that served the request.
 * @param inputTokens Input tokens consumed.
 * @param outputTokens Output tokens generated.
 * @param fallbackUsed True when a fallback model served the request.
 */
data class LlmResponse(
    val text: String,
    val provider: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val fallbackUsed: Boolean = false,
)

/**
 * Event emitted by [LlmClient.stream].
 */
sealed class LlmEvent {
    /** An incremental text token. */
    data class Token(val text: String) : LlmEvent()

    /** Stream finished successfully. */
    data class Done(val inputTokens: Int = 0, val outputTokens: Int = 0) : LlmEvent()

    /** Stream failed. */
    data class Error(val message: String) : LlmEvent()
}

/**
 * Thrown by [LlmClient.generate] on unrecoverable failure.
 *
 * @param message   Human-readable description.
 * @param provider  Provider that raised the error.
 * @param retryable True when a retry may succeed (transient error).
 */
class LlmClientException(
    message: String,
    val provider: String = "",
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
