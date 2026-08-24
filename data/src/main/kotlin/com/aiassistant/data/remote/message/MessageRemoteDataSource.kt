/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MessageRemoteDataSource.kt
 * Purpose    : MessageRemoteDataSource — data module component
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
 * File       : MessageRemoteDataSource.kt
 * Purpose    : MessageRemoteDataSource — data module component
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
 * MessageRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [MessageApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.MessageRepositoryImpl].
 * Dependencies: MessageApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 10.2, 10.3, 2.6
 */
package com.aiassistant.data.remote.message

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for message-related network operations.
 *
 * @param api         Retrofit service for `/conversations/{id}/messages/...`.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class MessageRemoteDataSource @Inject constructor(
    private val api: MessageApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Sends a message to the backend and returns the AI-generated response.
     */
    suspend fun sendMessage(conversationId: String, content: String, provider: String): ApiResult<MessageDto> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.sendMessage(
                    conversationId,
                    SendMessageRequest(conversationId, content, provider)
                )
            }
        }

    /**
     * Requests a regenerated response for an existing assistant message.
     */
    suspend fun regenerateMessage(conversationId: String, originalMessageId: String): ApiResult<MessageDto> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.regenerateMessage(
                    conversationId,
                    originalMessageId,
                    RegenerateMessageRequest(originalMessageId)
                )
            }
        }

    /**
     * Fetches all messages for a conversation. Used for server-wins conflict resolution
     * on sync (Requirement 10.3).
     */
    suspend fun getMessages(conversationId: String): ApiResult<List<MessageDto>> = withContext(dispatchers.io) {
        safeApiCall { api.getMessages(conversationId).items }
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
