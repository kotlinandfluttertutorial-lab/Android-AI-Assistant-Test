/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : TokenUsage.kt
 * Purpose    : TokenUsage — core-ai module component
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
 * File       : TokenUsage.kt
 * Purpose    : TokenUsage — core-ai module component
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
 * TokenUsage.kt
 *
 * Purpose: Data class representing input/output token consumption reported by the backend
 *          in the "done" WebSocket event. Used for usage tracking (Requirement 2.9).
 * Architecture: core-ai â€” shared model, no Android or third-party framework dependencies
 *               beyond kotlinx.serialization.
 * Dependencies: kotlinx.serialization
 *
 * Design decisions:
 * - Separate data class (not nested inside StreamEvent.Done) so it can be referenced
 *   independently by analytics and usage-stats screens.
 * - @Serializable enables direct deserialization from the backend JSON payload.
 */

package com.aiassistant.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Token consumption summary delivered in the `{"type":"done","usage":{...}}` event.
 *
 * @param inputTokens  Number of tokens in the prompt sent to the LLM provider.
 * @param outputTokens Number of tokens in the completion returned by the LLM provider.
 */
@Serializable
data class TokenUsage(
    @SerialName("inputTokens")
    val inputTokens: Int,
    @SerialName("outputTokens")
    val outputTokens: Int
)
