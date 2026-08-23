/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MemoryApiService.kt
 * Purpose    : MemoryApiService — data module component
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
 * File       : MemoryApiService.kt
 * Purpose    : MemoryApiService — data module component
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
 * MemoryApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/memory/...` REST endpoints.
 *          Consumed exclusively by [MemoryRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 7.3, 7.4
 */
package com.aiassistant.data.remote.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path

// ─── Request / Response DTOs ──────────────────────────────────────────────────

/**
 * Response DTO for a single memory entry returned by `/memory/...` endpoints.
 */
@Serializable
data class MemoryDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("content") val content: String,
    @SerialName("memory_type") val memoryType: String, // "preference" | "fact" | "style"
    @SerialName("created_at") val createdAt: Long
)

/**
 * Request body for `PATCH /memory/{id}`.
 */
@Serializable
data class UpdateMemoryRequest(@SerialName("content") val content: String)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for memory endpoints.
 *
 * Memories are never cached locally (sensitive data). All operations go directly to the
 * remote Memory Service (Requirement 7.3, 7.4).
 */
interface MemoryApiService {

    /**
     * Returns all memories belonging to the authenticated user.
     */
    @GET("memory")
    suspend fun getMemories(): List<MemoryDto>

    /**
     * Updates the content of a specific memory entry.
     *
     * @param memoryId The unique identifier of the memory to update.
     * @param body     The update request body containing new content.
     */
    @PATCH("memory/{memoryId}")
    suspend fun updateMemory(@Path("memoryId") memoryId: String, @Body body: UpdateMemoryRequest): MemoryDto

    /**
     * Deletes a memory entry and its associated ChromaDB embedding (Requirement 7.4).
     *
     * The backend must remove the embedding within 10 seconds of this call.
     *
     * @param memoryId The unique identifier of the memory to delete.
     */
    @DELETE("memory/{memoryId}")
    suspend fun deleteMemory(@Path("memoryId") memoryId: String)
}
