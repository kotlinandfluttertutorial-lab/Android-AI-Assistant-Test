/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : TranslationRemoteDataSource.kt
 * Purpose    : TranslationRemoteDataSource — data module component
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
 * File       : TranslationRemoteDataSource.kt
 * Purpose    : TranslationRemoteDataSource — data module component
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
 * TranslationRemoteDataSource.kt â€” data module
 *
 * Purpose: Wraps [TranslationApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module â€” remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.TranslationRepositoryImpl].
 * Dependencies: TranslationApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Mirrors the pattern established by [ResumeRemoteDataSource]: one safeApiCall helper
 *   converts HTTP exceptions and IOExceptions into typed DomainErrors.
 * - DispatcherProvider is injected to allow deterministic testing without real I/O.
 */
package com.aiassistant.data.remote.translator

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for text translation network operations.
 *
 * @param api         Retrofit service for the translation endpoint.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class TranslationRemoteDataSource @Inject constructor(
    private val api: TranslationApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage] via the AI Orchestrator
     * (Requirement 10.5 / 19.1).
     *
     * @param text           The text to translate.
     * @param sourceLanguage BCP 47 source language code.
     * @param targetLanguage BCP 47 target language code.
     * @return [ApiResult.Success] with the translated text on success.
     */
    suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.translateText(
                    TranslationRequest(
                        text = text,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                ).translatedText
            }
        }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            message = "Invalid request (HTTP ${code()}).",
            cause = this
        )
        in 500..599 -> DomainError.ServerError(
            message = "Server error (HTTP ${code()}).",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(
            message = "Unexpected HTTP response: ${code()}.",
            cause = this
        )
    }
}
