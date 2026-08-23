/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoteApiService.kt
 * Purpose    : NoteApiService — data module component
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
 * File       : NoteApiService.kt
 * Purpose    : NoteApiService — data module component
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
 * NoteApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/notes/...` REST endpoints.
 *          Consumed exclusively by [NoteRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5
 */
package com.aiassistant.data.remote.note

import com.aiassistant.core.network.model.PaginatedResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

// ─── DTOs ─────────────────────────────────────────────────────────────────────

/** Response DTO for a single note returned by `/notes/...` endpoints. */
@Serializable
data class NoteDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("sync_status") val syncStatus: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

/** Request body for `POST /notes` and `PUT /notes/{id}`. */
@Serializable
data class SaveNoteRequest(
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String> = emptyList()
)

/** Response from `POST /notes/{id}/summarize`. */
@Serializable
data class SummarizeNoteResponse(@SerialName("summary") val summary: String)

/** Response from `POST /notes/{id}/rewrite`. */
@Serializable
data class RewriteNoteResponse(@SerialName("rewritten") val rewritten: String)

// ─── Retrofit service ─────────────────────────────────────────────────────────

/** Retrofit service for notes endpoints. */
interface NoteApiService {

    /** Returns all notes for the authenticated user. */
    @GET("notes")
    suspend fun getNotes(): PaginatedResponse<NoteDto>

    /** Creates a new note. */
    @POST("notes")
    suspend fun createNote(@Body body: SaveNoteRequest): NoteDto

    /** Updates an existing note. */
    @PUT("notes/{noteId}")
    suspend fun updateNote(@Path("noteId") noteId: String, @Body body: SaveNoteRequest): NoteDto

    /** Deletes a note. */
    @DELETE("notes/{noteId}")
    suspend fun deleteNote(@Path("noteId") noteId: String)

    /**
     * Requests an AI summary of the note (≤150 words, Requirement 13.2).
     *
     * @param noteId The note to summarise.
     */
    @POST("notes/{noteId}/summarize")
    suspend fun summarizeNote(@Path("noteId") noteId: String): SummarizeNoteResponse

    /**
     * Requests an AI rewrite of the note in the user's learned writing style.
     *
     * @param noteId The note to rewrite.
     */
    @POST("notes/{noteId}/rewrite")
    suspend fun rewriteNote(@Path("noteId") noteId: String): RewriteNoteResponse
}
