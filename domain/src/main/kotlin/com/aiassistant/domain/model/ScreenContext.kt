/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ScreenContext.kt
 * Purpose    : Sealed class carrying screen-specific context for suggestion generation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Sealed Class
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
 * ScreenContext.kt
 *
 * Purpose: Sealed class that carries the screen-specific context data passed to
 *          GetContextSuggestionsUseCase. Each subclass represents a distinct screen type
 *          and holds the relevant content fields needed for AI suggestion generation.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 33.1, 33.2, 33.3, 33.4
 *
 * Design decisions:
 * - Sealed class ensures exhaustive `when` expressions in use cases and repositories.
 * - `screenInstanceId` on every subclass is used for both rate-gating (one generation
 *   per 5-second idle window per screen) and dismissal tracking (session-scoped).
 */

package com.aiassistant.domain.model

/**
 * Carries the context data from the currently active screen to the suggestion use case.
 *
 * Each concrete subclass corresponds to one of the three screen types that support
 * context-aware suggestions (Requirement 33.1, 33.2, 33.3).
 *
 * The [screenInstanceId] uniquely identifies a specific screen instance (e.g., a
 * particular note being edited or a specific calendar event being viewed). It is used:
 * - As the key for the 5-second rate gate in [GetContextSuggestionsUseCase].
 * - As the key for session-scoped dismissal tracking in [DismissSuggestionUseCase].
 */
sealed class ScreenContext {

    /**
     * The unique identifier for the specific screen instance.
     *
     * Should be a stable ID derived from the underlying data entity (e.g., note ID,
     * event ID, conversation ID) so that the rate gate and dismissal state are correctly
     * associated with the same content across repeated idle events.
     */
    abstract val screenInstanceId: String

    /**
     * Context data for the Note editor screen (Requirement 33.1).
     *
     * Suggestion types applicable to this context: [SuggestionType.SUMMARIZE],
     * [SuggestionType.EXPAND], [SuggestionType.ADD_ACTION_ITEMS].
     *
     * @param noteContent      The current text content of the note being edited or viewed.
     * @param screenInstanceId Unique identifier for this note screen instance (typically the note ID).
     */
    data class NoteContext(val noteContent: String, override val screenInstanceId: String) : ScreenContext()

    /**
     * Context data for the CalendarEvent detail screen (Requirement 33.2).
     *
     * Suggestion types applicable to this context: [SuggestionType.DRAFT_AGENDA],
     * [SuggestionType.PREP_QUESTIONS], [SuggestionType.LOOKUP_ATTENDEES].
     *
     * @param eventId          Unique identifier of the calendar event.
     * @param eventTitle       The title/subject of the calendar event.
     * @param eventDescription Optional description or notes attached to the event.
     * @param attendeeNames    Names of the attendees listed on the event.
     * @param screenInstanceId Unique identifier for this calendar event screen instance (typically the event ID).
     */
    data class CalendarEventContext(
        val eventId: String,
        val eventTitle: String,
        val eventDescription: String?,
        val attendeeNames: List<String>,
        override val screenInstanceId: String
    ) : ScreenContext()

    /**
     * Context data for the Conversation screen (Requirement 33.3).
     *
     * The AI suggests continuing the conversation when the last message is more than
     * 24 hours old (Requirement 33.3).
     *
     * Suggestion types applicable to this context: [SuggestionType.CONTINUE_CONVERSATION].
     *
     * @param lastMessageContent   The text content of the most recent message in the conversation.
     * @param lastMessageAgeMillis Age of the last message in milliseconds. The AI_Orchestrator
     *                             uses this to determine whether a "Continue this conversation"
     *                             suggestion is appropriate (threshold: 24 hours).
     * @param screenInstanceId     Unique identifier for this conversation screen instance
     *                             (typically the conversation ID).
     */
    data class ConversationContext(
        val lastMessageContent: String,
        val lastMessageAgeMillis: Long,
        override val screenInstanceId: String
    ) : ScreenContext()
}
