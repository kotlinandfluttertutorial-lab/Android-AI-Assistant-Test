/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : DocumentRemoteDataSource.kt
 * Purpose    : DocumentRemoteDataSource — data module component
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
 * File       : DocumentRemoteDataSource.kt
 * Purpose    : DocumentRemoteDataSource — data module component
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
 * DocumentRemoteDataSource.kt — data module
 *
 * Purpose: Wraps [DocumentApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module — remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.DocumentRepositoryImpl].
 * Dependencies: DocumentApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 4.1, 4.6, 4.10
 */
package com.aiassistant.data.remote.document

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import retrofit2.HttpException

/**
 * Remote data source for all `/documents/...` and `/jobs/...` network operations.
 *
 * All Retrofit calls are executed on [DispatcherProvider.io] and exceptions are mapped
 * to typed [ApiResult.Error] values.
 *
 * @param api         Retrofit service for document and job endpoints.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class DocumentRemoteDataSource @Inject constructor(
    private val api: DocumentApiService,
    private val dispatchers: DispatcherProvider
) {

    /** Returns all documents for the authenticated user. */
    suspend fun getDocuments(): ApiResult<List<DocumentDto>> = withContext(dispatchers.io) {
        safeApiCall { api.getDocuments().documents }
    }

    /** Uploads a document as a multipart form. */
    suspend fun uploadDocument(file: MultipartBody.Part): ApiResult<DocumentUploadResponseDto> =
        withContext(dispatchers.io) { safeApiCall { api.uploadDocument(file) } }

    /** Polls the backend ingestion job status. */
    suspend fun getJobStatus(jobId: String): ApiResult<JobStatusDto> =
        withContext(dispatchers.io) { safeApiCall { api.getJobStatus(jobId) } }

    /** Queries the RAG index for a document. */
    suspend fun queryDocument(documentId: String, query: String): ApiResult<DocumentQueryResponse> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.queryDocuments(
                    DocumentQueryRequest(
                        query = query,
                        documentIds = listOf(documentId)
                    )
                )
            }
        }

    /** Deletes a document and its embeddings. */
    suspend fun deleteDocument(documentId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteDocument(documentId) } }

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
