/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : MockAIStreamClient.kt
 * Purpose    : MockAIStreamClient — core-ai module component
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
 * File       : MockAIStreamClient.kt
 * Purpose    : MockAIStreamClient — core-ai module component
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
 * MockAIStreamClient.kt â€” core-ai debug source set
 *
 * Purpose: Deterministic, in-process implementation of [AIStreamClient] for use in
 *          automated tests and debug builds. Emits a configurable sequence of
 *          [StreamEvent.Token] events followed by [StreamEvent.Done] without making
 *          any network calls or requiring API credentials.
 *
 * Architecture: core-ai debug â€” replaces [AIStreamClientImpl] when the debug variant
 *               of [MockAIModule] is installed in the Hilt component graph.
 *
 * Design decisions:
 * - The default response is streamed word-by-word to simulate token streaming.
 * - The optional [customTokens] constructor parameter allows test callers to inject
 *   deterministic token sequences.
 * - `sendMessage` and `disconnect` are intentional no-ops; the mock's contract
 *   is fully expressed through the cold [Flow] returned by [connect].
 * - A small artificial delay (10 ms) between tokens keeps test output readable
 *   without significantly slowing CI runs.
 *
 * Requirements: 21.4
 */
package com.aiassistant.core.ai

import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mock implementation of [AIStreamClient] that streams a predetermined response.
 *
 * The default response is:
 * `"This is a mock AI response for testing purposes."`
 * delivered one word at a time followed by a trailing space.
 *
 * @param customTokens Optional list of token strings to emit instead of the default
 *                     response. Each element becomes one [StreamEvent.Token] event.
 */
class MockAIStreamClient @Inject constructor() : AIStreamClient {

    private val customTokens: List<String> = DEFAULT_TOKENS

    /**
     * Returns a cold [Flow] that emits each token in [customTokens] as a
     * [StreamEvent.Token], then emits [StreamEvent.Done] and completes.
     *
     * The [conversationId] and [jwt] parameters are accepted but ignored; no
     * network connection is opened.
     */
    override fun connect(conversationId: String, jwt: String): Flow<StreamEvent> = flow {
        customTokens.forEach { token ->
            emit(StreamEvent.Token(token))
            delay(TOKEN_DELAY_MS)
        }
        emit(
            StreamEvent.Done(
                usage = TokenUsage(
                    inputTokens = 10,
                    outputTokens = customTokens.size
                )
            )
        )
    }

    /** No-op â€” the mock does not maintain a WebSocket connection. */
    override fun sendMessage(payload: MessagePayload) = Unit

    /** No-op â€” the mock has no connection to close. */
    override fun disconnect() = Unit

    companion object {
        /** Delay between emitted tokens to simulate streaming latency in CI. */
        private const val TOKEN_DELAY_MS = 10L

        /** Default mock response split into individual word tokens. */
        val DEFAULT_TOKENS: List<String> = "This is a mock AI response for testing purposes."
            .split(" ")
            .map { "$it " }
    }
}
