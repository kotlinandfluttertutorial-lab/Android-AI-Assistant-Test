/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageApiService.kt
 * Purpose    : MessageApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * Module     : data
 * File       : MessageApiService.kt
 * Purpose    : MessageApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * MessageApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/messages/...` REST endpoints.
 *          Consumed exclusively by [MessageRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 10.2, 10.3, 2.6
 */
package com.aiassistant.data.remote.message

import com.aiassistant.core.network.model.PaginatedResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// ─── Request / Response models ────────────────────────────────────────────────

@Serializable
data class SendMessageRequest(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("content") val content: String,
    @SerialName("provider") val provider: String
)

@Serializable
data class RegenerateMessageRequest(@SerialName("original_message_id") val originalMessageId: String)

@Serializable
data class MessageDto(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("role") val role: String,
    @SerialName("content") val content: String,
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("provider") val provider: String = "",
    @SerialName("created_at") val createdAt: Long
)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for the `/conversations/{id}/messages` endpoints.
 *
 * Instantiated by [com.aiassistant.data.di.ConversationDataModule] and injected into
 * [MessageRemoteDataSource].
 */
interface MessageApiService {

    /**
     * Sends a new message in a conversation. The server generates the AI response
     * and returns it. For streaming responses, the WebSocket path is used instead —
     * this endpoint covers non-streaming (REST) submissions only.
     */
    @POST("conversations/{conversationId}/messages")
    suspend fun sendMessage(@Path("conversationId") conversationId: String, @Body body: SendMessageRequest): MessageDto

    /**
     * Requests a regenerated response for an existing assistant message.
     * The server appends the new response as an alternative (Requirement 2.6).
     */
    @POST("conversations/{conversationId}/messages/{messageId}/regenerate")
    suspend fun regenerateMessage(
        @Path("conversationId") conversationId: String,
        @Path("messageId") messageId: String,
        @Body body: RegenerateMessageRequest
    ): MessageDto

    /**
     * Retrieves all messages for a conversation. Used during sync to check
     * server-authoritative content (server-wins for Message content per Requirement 10.3).
     */
    @GET("conversations/{conversationId}/messages")
    suspend fun getMessages(@Path("conversationId") conversationId: String): PaginatedResponse<MessageDto>
}
