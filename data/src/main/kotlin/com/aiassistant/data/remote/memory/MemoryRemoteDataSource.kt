/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MemoryRemoteDataSource.kt
 * Purpose    : MemoryRemoteDataSource — data module component
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
 * File       : MemoryRemoteDataSource.kt
 * Purpose    : MemoryRemoteDataSource — data module component
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
 * MemoryRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [MemoryApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.MemoryRepositoryImpl].
 * Dependencies: MemoryApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 7.3, 7.4
 */
package com.aiassistant.data.remote.memory

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for all `/memory/...` network operations.
 *
 * All Retrofit calls are executed on [DispatcherProvider.io] and exceptions are mapped
 * to typed [ApiResult.Error] values.
 *
 * @param api         Retrofit service for `/memory/...`.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class MemoryRemoteDataSource @Inject constructor(
    private val api: MemoryApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Fetches all memories belonging to the authenticated user.
     */
    suspend fun getMemories(): ApiResult<List<MemoryDto>> = withContext(dispatchers.io) {
        safeApiCall { api.getMemories() }
    }

    /**
     * Updates the content of a memory entry.
     */
    suspend fun updateMemory(memoryId: String, newContent: String): ApiResult<MemoryDto> = withContext(dispatchers.io) {
        safeApiCall { api.updateMemory(memoryId, UpdateMemoryRequest(newContent)) }
    }

    /**
     * Deletes a memory entry and its vector embedding.
     */
    suspend fun deleteMemory(memoryId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall { api.deleteMemory(memoryId) }
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
