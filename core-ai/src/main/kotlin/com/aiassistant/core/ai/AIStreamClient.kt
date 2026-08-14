/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : AIStreamClient.kt
 * Purpose    : AIStreamClient — core-ai module component
 *
 * Architecture Layer : Core-AI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : AIStreamClient.kt
 * Purpose    : AIStreamClient — core-ai module component
 *
 * Architecture Layer : Core-AI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * AIStreamClient.kt
 *
 * Purpose: Contract for the WebSocket streaming client that connects to the backend
 *          /ws/chat/{conversationId} endpoint and emits structured StreamEvent values.
 * Architecture: core-ai â€” interface definition only; implementation in AIStreamClientImpl.
 * Dependencies: kotlinx.coroutines.flow (Flow)
 *
 * Design decisions:
 * - Returns `Flow<StreamEvent>` so consumers can apply standard Kotlin Flow operators
 *   (filter, map, catch, onEach, etc.) without coupling to the WebSocket lifecycle.
 * - `connect` is non-suspending; the Flow itself is cold and begins the connection
 *   lazily on the first collector. This keeps the API consistent with other Flow-based
 *   repositories in the project.
 * - `sendMessage` is fire-and-forget (non-suspending) to match WebSocket send semantics;
 *   delivery errors surface as `StreamEvent.Error` emissions on the Flow.
 * - `disconnect` is synchronous; it cancels any in-flight reconnection attempts and
 *   closes the underlying WebSocket cleanly.
 */

package com.aiassistant.core.ai

import kotlinx.coroutines.flow.Flow

/**
 * WebSocket streaming client for the AI chat endpoint.
 *
 * Typical usage in a ViewModel:
 * ```kotlin
 * streamClient.connect(conversationId, jwt)
 *     .onEach { event ->
 *         when (event) {
 *             is StreamEvent.Token    -> _uiState.value = state.appendToken(event.text)
 *             is StreamEvent.Done     -> _uiState.value = state.complete(event.usage)
 *             is StreamEvent.Error    -> _uiState.value = state.error(event.message)
 *             is StreamEvent.ToolCall -> handleToolCall(event)
 *         }
 *     }
 *     .launchIn(viewModelScope)
 * ```
 */
interface AIStreamClient {

    /**
     * Opens (or re-opens after backoff) a WebSocket connection to
     * `/ws/chat/{conversationId}?token={jwt}` and returns a cold [Flow] of [StreamEvent]
     * values.
     *
     * The Flow:
     * - emits [StreamEvent.Token] for every incremental token received.
     * - emits [StreamEvent.Done] when the backend signals stream completion.
     * - emits [StreamEvent.Error] on protocol-level errors or after all reconnect
     *   attempts are exhausted.
     * - emits [StreamEvent.ToolCall] when the AI Orchestrator invokes an MCP tool.
     * - completes (without error) after a [StreamEvent.Done] is emitted.
     *
     * Reconnection follows exponential backoff (Requirement 26.4):
     *   1 s â†’ 2 s â†’ 4 s â†’ 8 s â†’ 16 s, capped at 30 s, max 5 total attempts.
     * After all attempts are exhausted the Flow emits a final [StreamEvent.Error] and
     * completes.
     *
     * @param conversationId Conversation to connect to.
     * @param jwt            Signed JWT for authentication; passed as a query parameter.
     * @return Cold [Flow] of [StreamEvent]; a new connection is created on each collection.
     */
    fun connect(conversationId: String, jwt: String): Flow<StreamEvent>

    /**
     * Sends a [MessagePayload] over the currently open WebSocket connection.
     *
     * Must be called after the Flow from [connect] has emitted at least one event
     * (i.e. the connection is established). If the connection is not yet open or has been
     * closed, the payload is silently dropped; the caller is responsible for observing
     * [StreamEvent.Error] to detect failures.
     *
     * @param payload The chat message to send to the AI Orchestrator.
     */
    fun sendMessage(payload: MessagePayload)

    /**
     * Closes the WebSocket connection and cancels any pending reconnection attempts.
     *
     * Safe to call from any thread. Subsequent calls to [sendMessage] are no-ops until
     * a new [connect] Flow is collected.
     */
    fun disconnect()
}
