/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationApiService.kt
 * Purpose    : ConversationApiService — data module component
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
 * File       : ConversationApiService.kt
 * Purpose    : ConversationApiService — data module component
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
 * ConversationApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/conversations/...` REST endpoints.
 *          Consumed exclusively by [ConversationRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 10.1, 10.3, 11.1
 */
package com.aiassistant.data.remote.conversation

import com.aiassistant.core.network.model.PaginatedResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

// ─── Request / Response models ────────────────────────────────────────────────

@Serializable
data class CreateConversationRequest(
    @SerialName("title") val title: String,
    @SerialName("provider") val provider: String
)

@Serializable
data class RenameConversationRequest(@SerialName("title") val title: String)

@Serializable
data class PinConversationRequest(@SerialName("is_pinned") val isPinned: Boolean)

@Serializable
data class ConversationDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title") val title: String,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("provider") val provider: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for the `/conversations/...` endpoints.
 *
 * Instantiated by [com.aiassistant.data.di.ConversationDataModule] and injected into
 * [ConversationRemoteDataSource].
 */
interface ConversationApiService {

    /**
     * Retrieves all non-deleted conversations for the authenticated user,
     * sorted by [updatedAt] descending.
     */
    @GET("conversations")
    suspend fun getConversations(): PaginatedResponse<ConversationDto>

    /**
     * Creates a new conversation on the backend.
     */
    @POST("conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): ConversationDto

    /**
     * Soft-deletes a conversation by its identifier.
     */
    @DELETE("conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Unit

    /**
     * Renames a conversation (Requirement 11.3).
     */
    @PATCH("conversations/{id}/title")
    suspend fun renameConversation(@Path("id") id: String, @Body body: RenameConversationRequest): Unit

    /**
     * Pins or unpins a conversation (Requirement 11.3).
     */
    @PATCH("conversations/{id}/pin")
    suspend fun pinConversation(@Path("id") id: String, @Body body: PinConversationRequest): Unit
}
