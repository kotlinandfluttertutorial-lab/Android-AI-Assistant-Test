/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : SuggestionRemoteDataSource.kt
 * Purpose    : Wraps SuggestionApiService Retrofit calls and maps the response
 *              DTOs to domain ContextSuggestion objects.
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (remote)
 *
 * Dependencies: SuggestionApiService, ApiResult, DomainError, DispatcherProvider
 * Requirements: 33.1, 33.2, 33.6, 33.7
 * ============================================================
 */
package com.aiassistant.data.remote.suggestion

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SuggestionType
import com.aiassistant.domain.model.TargetScreenType
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for context-aware AI suggestion network operations.
 *
 * Maps [ScreenContext] domain objects to the [SuggestionContextRequest] DTO,
 * calls the backend, then maps [SuggestionResponseItem] back to domain
 * [ContextSuggestion] objects. Unknown suggestion types are silently dropped.
 *
 * @param api         Retrofit service for the suggestions endpoint.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class SuggestionRemoteDataSource @Inject constructor(
    private val api: SuggestionApiService,
    private val dispatchers: DispatcherProvider
) {

    /**
     * Fetches 0–3 AI suggestions for the given screen [context].
     *
     * Returns [ApiResult.Success] with an empty list when the backend responds with
     * no suggestions (privacy mode, timeout, AI error — all normalised to HTTP 200
     * by the backend). HTTP errors are mapped to [ApiResult.Error].
     *
     * Requirements: 33.1, 33.2, 33.6, 33.7
     */
    suspend fun getSuggestions(context: ScreenContext): ApiResult<List<ContextSuggestion>> =
        withContext(dispatchers.io) {
            safeApiCall {
                val request = context.toRequest()
                val response = api.getContextSuggestions(request)
                response.suggestions.mapNotNull { item -> item.toDomain() }
            }
        }

    // ─── Mapping helpers ──────────────────────────────────────────────────────

    /** Maps a domain [ScreenContext] to the network [SuggestionContextRequest]. */
    private fun ScreenContext.toRequest(): SuggestionContextRequest = when (this) {
        is ScreenContext.NoteContext -> SuggestionContextRequest(
            screenType = "notes",
            noteContent = noteContent.take(500),
            noteLength = noteContent.length
        )
        is ScreenContext.CalendarEventContext -> SuggestionContextRequest(
            screenType = "calendar",
            eventTitle = eventTitle,
            attendees = attendeeNames
        )
        is ScreenContext.ConversationContext -> SuggestionContextRequest(
            screenType = "chat",
            lastMessageContent = lastMessageContent,
            lastMessageAgeSeconds = (lastMessageAgeMillis / 1_000L).toInt().coerceAtLeast(0)
        )
    }

    /**
     * Maps a [SuggestionResponseItem] DTO to a domain [ContextSuggestion].
     *
     * Returns `null` for unknown [type] or [targetScreenType] values so the list
     * stays valid even when the backend introduces new types the client hasn't
     * seen yet.
     */
    private fun SuggestionResponseItem.toDomain(): ContextSuggestion? {
        val suggestionType = SuggestionType.fromValue(type) ?: return null
        val screenType = TargetScreenType.fromValue(targetScreenType) ?: return null
        return ContextSuggestion(
            id = id.ifBlank { UUID.randomUUID().toString() },
            type = suggestionType,
            displayText = displayText,
            preFillText = preFillText,
            targetScreenType = screenType
        )
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
