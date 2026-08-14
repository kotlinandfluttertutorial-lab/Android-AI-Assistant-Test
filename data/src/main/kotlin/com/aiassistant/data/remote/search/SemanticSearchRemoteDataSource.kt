/**
 * SemanticSearchRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [SemanticSearchApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.SemanticSearchRepositoryImpl].
 * Dependencies: SemanticSearchApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 36.1, 36.3
 */
package com.aiassistant.data.remote.search

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for all `/search/...` network operations.
 *
 * All Retrofit calls are executed on [DispatcherProvider.io] and exceptions are mapped
 * to typed [ApiResult.Error] values.
 *
 * @param api         Retrofit service for `/search/...`.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class SemanticSearchRemoteDataSource @Inject constructor(
    private val api: SemanticSearchApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Performs a semantic search query against the backend.
     *
     * @param query Natural language search string.
     * @return [ApiResult.Success] with the response DTO, or a typed error.
     */
    suspend fun search(query: String): ApiResult<SemanticSearchResponseDto> = withContext(dispatchers.io) {
        safeApiCall { api.search(SemanticSearchRequest(query)) }
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
