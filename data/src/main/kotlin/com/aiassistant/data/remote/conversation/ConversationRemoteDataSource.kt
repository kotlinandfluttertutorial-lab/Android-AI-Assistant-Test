/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ConversationRemoteDataSource.kt
 * Purpose    : ConversationRemoteDataSource — data module component
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
 * File       : ConversationRemoteDataSource.kt
 * Purpose    : ConversationRemoteDataSource — data module component
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
 * ConversationRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [ConversationApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.ConversationRepositoryImpl].
 * Dependencies: ConversationApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 10.1, 10.3, 11.1
 */
package com.aiassistant.data.remote.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for conversation-related network operations.
 *
 * Executes all Retrofit calls on [DispatcherProvider.io] and maps exceptions to
 * typed [ApiResult.Error] values — callers are always shielded from raw exceptions.
 *
 * @param api         Retrofit service for `/conversations/...`.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class ConversationRemoteDataSource @Inject constructor(
    private val api: ConversationApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Fetches all non-deleted conversations from the backend sorted by [updatedAt] DESC.
     */
    suspend fun getConversations(): ApiResult<List<ConversationDto>> = withContext(dispatchers.io) {
        safeApiCall { api.getConversations().items }
    }

    /**
     * Creates a new conversation on the backend.
     */
    suspend fun createConversation(title: String, provider: String): ApiResult<ConversationDto> =
        withContext(dispatchers.io) {
            safeApiCall { api.createConversation(CreateConversationRequest(title, provider)) }
        }

    /**
     * Soft-deletes a conversation on the backend.
     */
    suspend fun deleteConversation(id: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall { api.deleteConversation(id) }
    }

    /**
     * Renames a conversation on the backend (Requirement 11.3).
     */
    suspend fun renameConversation(id: String, newTitle: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall { api.renameConversation(id, RenameConversationRequest(newTitle)) }
    }

    /**
     * Pins or unpins a conversation on the backend (Requirement 11.3).
     */
    suspend fun pinConversation(id: String, isPinned: Boolean): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall { api.pinConversation(id, PinConversationRequest(isPinned)) }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(e.toDomainError())
    } catch (e: IOException) {
        ApiResult.Error(
            DomainError.NetworkError(
                message = e.message ?: "A network I/O error occurred.",
                cause = e
            )
        )
    }

    private fun HttpException.toDomainError(): DomainError = when (code()) {
        401 -> DomainError.Unauthorized(cause = this)
        403 -> DomainError.Forbidden(cause = this)
        in 400..499 -> DomainError.ValidationError(
            message = "The request was invalid (HTTP ${code()}).",
            cause = this
        )
        in 500..599 -> DomainError.ServerError(
            message = "A server error occurred (HTTP ${code()}).",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(
            message = "Unexpected HTTP response: ${code()}.",
            cause = this
        )
    }
}
