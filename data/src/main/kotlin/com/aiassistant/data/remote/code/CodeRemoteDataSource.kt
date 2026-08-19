/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeRemoteDataSource.kt
 * Purpose    : Wraps CodeApiService Retrofit calls in a typed, testable class.
 *              All calls return ApiResult so callers never receive raw exceptions.
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (remote)
 *
 * Dependencies: CodeApiService, ApiResult, DomainError, DispatcherProvider
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 * ============================================================
 */
package com.aiassistant.data.remote.code

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for code analysis network operations.
 *
 * Maps domain objects ([CodeAnalysisRequest], [CodeAnalysisResult]) to/from the network
 * DTOs ([CodeAnalysisRequestDto], [CodeAnalysisResponseDto]) and wraps every call in a
 * safe try/catch returning [ApiResult].
 *
 * @param api         Retrofit service for the code analysis endpoint.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class CodeRemoteDataSource @Inject constructor(
    private val api: CodeApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Submits code for AI analysis and returns the structured result.
     *
     * Maps [SupportedLanguage] and [CodeAction] domain enums to the lowercase string
     * values expected by the backend, then maps the response DTO back to [CodeAnalysisResult].
     *
     * @param request Domain request containing code, language, and action.
     * @return [ApiResult.Success] with [CodeAnalysisResult] on success.
     */
    suspend fun analyzeCode(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult> =
        withContext(dispatchers.io) {
            safeApiCall {
                val dto = api.analyzeCode(
                    CodeAnalysisRequestDto(
                        code = request.code,
                        languageId = request.language.toApiId(),
                        action = request.action.toApiId()
                    )
                )
                CodeAnalysisResult(
                    languageId = dto.languageId,
                    originalCode = dto.originalCode,
                    action = request.action,
                    content = dto.content
                )
            }
        }

    // ─── Mapping helpers ──────────────────────────────────────────────────────

    private fun SupportedLanguage.toApiId(): String = when (this) {
        SupportedLanguage.KOTLIN -> "kotlin"
        SupportedLanguage.JAVA -> "java"
        SupportedLanguage.PYTHON -> "python"
        SupportedLanguage.JAVASCRIPT -> "javascript"
        SupportedLanguage.CPP -> "cpp"
        SupportedLanguage.SQL -> "sql"
    }

    private fun CodeAction.toApiId(): String = when (this) {
        CodeAction.EXPLAIN -> "explain"
        CodeAction.FIX_BUG -> "fix_bug"
        CodeAction.GENERATE_TESTS -> "generate_tests"
    }

    // ─── Safe call helper ─────────────────────────────────────────────────────

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
