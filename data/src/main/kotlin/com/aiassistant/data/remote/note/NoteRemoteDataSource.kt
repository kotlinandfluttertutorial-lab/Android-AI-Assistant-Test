/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : NoteRemoteDataSource.kt
 * Purpose    : NoteRemoteDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * File       : NoteRemoteDataSource.kt
 * Purpose    : NoteRemoteDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * NoteRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [NoteApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.NoteRepositoryImpl].
 * Dependencies: NoteApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5
 */
package com.aiassistant.data.remote.note

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for all `/notes/...` network operations.
 *
 * @param api         Retrofit service for note endpoints.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class NoteRemoteDataSource @Inject constructor(
    private val api: NoteApiService,
    private val dispatchers: DispatcherProvider
) {

    /** Fetches all notes for the authenticated user. */
    suspend fun getNotes(): ApiResult<List<NoteDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getNotes().items } }

    /** Creates a new note on the backend. */
    suspend fun createNote(title: String, content: String, tags: List<String>): ApiResult<NoteDto> =
        withContext(dispatchers.io) {
            safeApiCall { api.createNote(SaveNoteRequest(title, content, tags)) }
        }

    /** Updates an existing note on the backend. */
    suspend fun updateNote(noteId: String, title: String, content: String, tags: List<String>): ApiResult<NoteDto> =
        withContext(dispatchers.io) {
            safeApiCall { api.updateNote(noteId, SaveNoteRequest(title, content, tags)) }
        }

    /** Deletes a note from the backend. */
    suspend fun deleteNote(noteId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteNote(noteId) } }

    /** Requests an AI summary (≤150 words) for a note. */
    suspend fun summarizeNote(noteId: String): ApiResult<String> = withContext(dispatchers.io) {
        safeApiCall { api.summarizeNote(noteId).summary }
    }

    /** Requests an AI rewrite of a note in the user's learned style. */
    suspend fun rewriteNote(noteId: String): ApiResult<String> = withContext(dispatchers.io) {
        safeApiCall { api.rewriteNote(noteId).rewritten }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(e.toDomainError())
    } catch (e: IOException) {
        ApiResult.Error(
            DomainError.NetworkError(message = e.message ?: "A network I/O error occurred.", cause = e)
        )
    }

    private fun HttpException.toDomainError(): DomainError = when (code()) {
        401 -> DomainError.Unauthorized(cause = this)
        403 -> DomainError.Forbidden(cause = this)
        in 400..499 -> DomainError.ValidationError(message = "Invalid request (HTTP ${code()}).", cause = this)
        in 500..599 -> DomainError.ServerError(
            message = "Server error (HTTP ${code()}).",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(message = "Unexpected HTTP response: ${code()}.", cause = this)
    }
}
