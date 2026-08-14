/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ResumeRemoteDataSource.kt
 * Purpose    : ResumeRemoteDataSource — data module component
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
 * File       : ResumeRemoteDataSource.kt
 * Purpose    : ResumeRemoteDataSource — data module component
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
 * ResumeRemoteDataSource.kt â€” data module
 *
 * Purpose: Wraps [ResumeApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module â€” remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.ResumeRepositoryImpl].
 * Dependencies: ResumeApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 14.1, 14.2, 14.4, 14.5
 */
package com.aiassistant.data.remote.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for all resume and email network operations.
 *
 * @param api         Retrofit service for resume/email endpoints.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class ResumeRemoteDataSource @Inject constructor(
    private val api: ResumeApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Generates an ATS-optimised resume in Markdown format (Requirement 14.1).
     *
     * @param professionalHistory User's work experience, education, and skills.
     * @param jobDescription      Target job posting text.
     * @return [ApiResult.Success] with Markdown text on success.
     */
    suspend fun generateResume(professionalHistory: String, jobDescription: String): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.generateResume(
                    ResumeGenerateRequest(
                        professionalHistory = professionalHistory,
                        jobDescription = jobDescription
                    )
                ).resumeMarkdown
            }
        }

    /**
     * Generates a tailored cover letter (â‰¤ 400 words) (Requirement 14.2).
     *
     * @param professionalHistory User's work experience, education, and skills.
     * @param jobDescription      Target job posting text.
     * @return [ApiResult.Success] with cover letter text on success.
     */
    suspend fun generateCoverLetter(professionalHistory: String, jobDescription: String): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.generateCoverLetter(
                    CoverLetterGenerateRequest(
                        professionalHistory = professionalHistory,
                        jobDescription = jobDescription
                    )
                ).coverLetterText
            }
        }

    /**
     * Generates a professional email with subject, greeting, body, and closing
     * (Requirement 14.4).
     *
     * @param context A description of the email situation and relevant background.
     * @param intent  The purpose or goal of the email.
     * @return [ApiResult.Success] with the full email text on success.
     */
    suspend fun generateEmail(context: String, intent: String): ApiResult<String> = withContext(dispatchers.io) {
        safeApiCall {
            api.generateEmail(
                EmailGenerateRequest(context = context, intent = intent)
            ).emailText
        }
    }

    /**
     * Corrects grammar in a draft email (Requirement 14.5).
     *
     * @param draftEmail The raw draft email text to correct.
     * @return [ApiResult.Success] with the corrected email text on success.
     */
    suspend fun correctGrammar(draftEmail: String): ApiResult<String> = withContext(dispatchers.io) {
        safeApiCall {
            api.correctGrammar(
                GrammarCorrectRequest(draftEmail = draftEmail)
            ).correctedText
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
