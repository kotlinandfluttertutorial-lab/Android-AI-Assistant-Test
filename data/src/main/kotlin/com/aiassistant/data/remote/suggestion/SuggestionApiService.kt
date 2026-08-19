/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : SuggestionApiService.kt
 * Purpose    : Retrofit service interface for the /api/v1/suggestions/context
 *              REST endpoint used by the Context Suggestion feature.
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
 *
 * Dependencies: Retrofit, kotlinx.serialization
 * Requirements: 33.1, 33.2, 33.6, 33.7
 * ============================================================
 */
package com.aiassistant.data.remote.suggestion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// ─── Request DTO ──────────────────────────────────────────────────────────────

/**
 * Request body for `POST /api/v1/suggestions/context`.
 *
 * Fields are screen-type-specific; only `screenType` is required.
 * The backend accepts `null` for all optional context fields.
 *
 * @param screenType             Current screen ("notes" | "calendar" | "chat").
 * @param noteContent            First 500 chars of the note being edited (notes screen).
 * @param noteLength             Total character count of the note (notes screen).
 * @param eventTitle             Calendar event title (calendar screen).
 * @param eventDatetimeMs        Calendar event start time as epoch millis (calendar screen).
 * @param attendees              List of attendee name/email strings (calendar screen).
 * @param lastMessageContent     Last chat message content (chat screen).
 * @param lastMessageAgeSeconds  Seconds elapsed since the last message (chat screen).
 * @param conversationTitle      Chat conversation title (chat screen).
 * @param provider               LLM provider to use; defaults to "openai".
 */
@Serializable
data class SuggestionContextRequest(
    @SerialName("screen_type") val screenType: String,
    @SerialName("note_content") val noteContent: String? = null,
    @SerialName("note_length") val noteLength: Int? = null,
    @SerialName("event_title") val eventTitle: String? = null,
    @SerialName("event_datetime") val eventDatetimeMs: Long? = null,
    @SerialName("attendees") val attendees: List<String>? = null,
    @SerialName("last_message_content") val lastMessageContent: String? = null,
    @SerialName("last_message_age_seconds") val lastMessageAgeSeconds: Int? = null,
    @SerialName("conversation_title") val conversationTitle: String? = null,
    @SerialName("provider") val provider: String = "openai"
)

// ─── Response DTOs ────────────────────────────────────────────────────────────

/**
 * A single AI suggestion returned by the backend.
 *
 * @param id               UUID string identifying this suggestion.
 * @param type             AI action type (e.g. "summarize", "draft_agenda").
 * @param displayText      Human-readable chip label shown to the user.
 * @param preFillText      Text pre-filled in the chat input when the chip is tapped.
 * @param targetScreenType Screen type this suggestion belongs to.
 */
@Serializable
data class SuggestionResponseItem(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("display_text") val displayText: String,
    @SerialName("pre_fill_text") val preFillText: String,
    @SerialName("target_screen_type") val targetScreenType: String
)

/**
 * Response wrapper from `POST /api/v1/suggestions/context`.
 *
 * Always returns HTTP 200 with 0–3 items. An empty list means
 * privacy mode is on, the AI call timed out, or no suggestions apply.
 *
 * @param suggestions List of 0–3 context-aware suggestions.
 */
@Serializable
data class SuggestionsResponse(
    @SerialName("suggestions") val suggestions: List<SuggestionResponseItem> = emptyList()
)

// ─── Retrofit service ─────────────────────────────────────────────────────────

/**
 * Retrofit service for the context suggestion endpoint.
 *
 * The path includes the full `/api/v1/` prefix because the Retrofit base URL
 * ends at the server root (e.g. `https://api.handsonandroid.com/`) and
 * this endpoint does not share the same prefix as the productivity or
 * resume endpoints.
 *
 * Consumed exclusively by [SuggestionRemoteDataSource].
 */
interface SuggestionApiService {

    /**
     * Returns 0–3 AI-generated context suggestions tailored to the user's
     * current screen (Requirements 33.1, 33.2, 33.6, 33.7).
     *
     * The response is always HTTP 200:
     * - Empty list when privacy mode is on (Req 33.7).
     * - Empty list when AI call exceeds 3 seconds (Req 33.6).
     * - Empty list when AI response cannot be parsed.
     *
     * @param body Screen context payload.
     * @return Response containing 0–3 suggestion items.
     */
    @POST("api/v1/suggestions/context")
    suspend fun getContextSuggestions(@Body body: SuggestionContextRequest): SuggestionsResponse
}
