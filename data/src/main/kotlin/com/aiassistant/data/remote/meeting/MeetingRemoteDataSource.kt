/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingRemoteDataSource.kt
 * Purpose    : Wraps MeetingApiService Retrofit calls in a typed, testable class.
 *              All calls return ApiResult so callers never receive raw exceptions.
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (remote)
 *
 * Dependencies: MeetingApiService, ApiResult, DomainError, DispatcherProvider
 * Requirements: 19.1, 5.6
 * ============================================================
 */
package com.aiassistant.data.remote.meeting

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for meeting recording and transcription network operations.
 *
 * Wraps every [MeetingApiService] call in a safe try/catch and returns [ApiResult].
 * All network I/O is dispatched on [DispatcherProvider.io].
 *
 * @param api         Retrofit service for the meeting endpoints.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class MeetingRemoteDataSource @Inject constructor(
    private val api: MeetingApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Opens a new recording session on the Transcription_Service (Requirement 19.1).
     *
     * @param userId The authenticated user's identifier.
     * @return [ApiResult.Success] with the session identifier on success.
     */
    suspend fun startMeeting(userId: String): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.startMeeting(MeetingStartRequest(userId = userId)).sessionId
            }
        }

    /**
     * Signals the Transcription_Service to stop recording and queue audio for
     * transcription (Requirement 19.1).
     *
     * @param sessionId The session identifier returned by [startMeeting].
     * @return [ApiResult.Success] with [Unit] when audio is accepted into the queue.
     */
    suspend fun stopMeeting(sessionId: String): ApiResult<Unit> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.stopMeeting(
                    sessionId = sessionId,
                    body = MeetingStopRequest(sessionId = sessionId)
                )
                // Response body is informational only; expose Unit to the domain layer
                Unit
            }
        }

    /**
     * Retrieves the completed AI-generated meeting summary (Requirement 19.1).
     *
     * @param sessionId The session identifier returned by [startMeeting].
     * @return [ApiResult.Success] with the full Markdown summary text on success.
     */
    suspend fun getMeetingSummary(sessionId: String): ApiResult<String> =
        withContext(dispatchers.io) {
            safeApiCall {
                api.getMeetingSummary(sessionId = sessionId).summary
            }
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
        404 -> DomainError.ServerError(
            message = "Meeting session not found (HTTP 404).",
            httpStatusCode = 404,
            cause = this
        )
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
