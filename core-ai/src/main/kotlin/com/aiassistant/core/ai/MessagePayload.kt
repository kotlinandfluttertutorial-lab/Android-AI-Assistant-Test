/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ai
 * File       : MessagePayload.kt
 * Purpose    : MessagePayload — core-ai module component
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
 * File       : MessagePayload.kt
 * Purpose    : MessagePayload — core-ai module component
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
 * MessagePayload.kt
 *
 * Purpose: Data class representing the outgoing chat message sent over the WebSocket
 *          connection to the backend AI Orchestrator.
 * Architecture: core-ai â€” shared model, no Android or third-party framework dependencies
 *               beyond kotlinx.serialization.
 * Dependencies: kotlinx.serialization
 *
 * Design decisions:
 * - @Serializable so it can be serialized to JSON and sent directly via
 *   [AIStreamClient.sendMessage] without a separate mapper.
 * - `provider` is a plain String rather than an enum to decouple core-ai from the
 *   domain-level LLMProvider enum; callers pass the provider identifier string.
 */

package com.aiassistant.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload sent to the backend WebSocket endpoint to initiate or continue a chat turn.
 *
 * Serialises to:
 * ```json
 * {
 *   "conversationId": "uuid",
 *   "content": "User message text",
 *   "provider": "openai"
 * }
 * ```
 *
 * @param conversationId Unique identifier of the conversation this message belongs to.
 * @param content        The user's message text.
 * @param provider       The LLM provider identifier to use for this request
 *                       (e.g. "openai", "gemini", "claude", "ollama", "llama", "mistral").
 */
@Serializable
data class MessagePayload(
    @SerialName("conversationId")
    val conversationId: String,
    @SerialName("content")
    val content: String,
    @SerialName("provider")
    val provider: String
)
