/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ContextSuggestion.kt
 * Purpose    : Domain entities for context-aware AI suggestions
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Data Class / Enum
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
 * ContextSuggestion.kt
 *
 * Purpose: Domain entities representing context-aware AI suggestions displayed as chips
 *          or cards on Note, CalendarEvent, and Conversation screens.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 33.1, 33.2, 33.3, 33.5
 */

package com.aiassistant.domain.model

/**
 * The type of context-aware suggestion the AI has generated.
 *
 * Each value corresponds to a specific AI action that can be taken on the
 * target screen content.
 */
enum class SuggestionType(val value: String) {
    /** Condense the note content into a brief summary. */
    SUMMARIZE("summarize"),

    /** Elaborate on the note content with additional detail. */
    EXPAND("expand"),

    /** Extract and list action items from the note content. */
    ADD_ACTION_ITEMS("add_action_items"),

    /** Generate a structured meeting agenda from the calendar event details. */
    DRAFT_AGENDA("draft_agenda"),

    /** Generate clarifying or discussion questions to prepare for the meeting. */
    PREP_QUESTIONS("prep_questions"),

    /** Look up profiles and background information for attendees of the meeting. */
    LOOKUP_ATTENDEES("lookup_attendees"),

    /** Offer a contextual continuation prompt to resume a stale conversation. */
    CONTINUE_CONVERSATION("continue_conversation");

    companion object {
        /**
         * Returns the [SuggestionType] matching [value], or `null` if no match is found.
         *
         * @param value The raw string value to look up.
         * @return The matching [SuggestionType], or `null`.
         */
        fun fromValue(value: String): SuggestionType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * The screen type on which a [ContextSuggestion] should be displayed.
 *
 * Determines both the visual presentation style (chips vs card) and which
 * suggestion types are applicable per Requirement 33.1 and 33.2.
 */
enum class TargetScreenType(val value: String) {
    /** Suggestions shown as dismissible chips above the keyboard on the Note editor. */
    NOTE("note"),

    /** Suggestions shown as a non-blocking card below calendar event details. */
    CALENDAR_EVENT("calendar_event"),

    /** Suggestions shown as a continuation prompt on the Conversation screen. */
    CHAT_CONVERSATION("chat_conversation");

    companion object {
        /**
         * Returns the [TargetScreenType] matching [value], or `null` if no match is found.
         *
         * @param value The raw string value to look up.
         * @return The matching [TargetScreenType], or `null`.
         */
        fun fromValue(value: String): TargetScreenType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A single context-aware AI suggestion generated for the currently active screen.
 *
 * THE AI_Orchestrator SHALL generate a set of 1–3 contextual suggestions for Notes,
 * CalendarEvents, and Conversations and display them without blocking the editor
 * (Requirements 33.1, 33.2, 33.3).
 *
 * @param id               Unique identifier for this suggestion instance (used for dismissal tracking).
 * @param type             The category of action this suggestion represents.
 * @param displayText      Short label shown on the chip or card (e.g., "Summarize this note").
 * @param preFillText      Text pre-populated into the AI input field when the suggestion is tapped;
 *                         empty string if the suggestion does not require a pre-filled prompt.
 * @param targetScreenType The screen type on which this suggestion should be rendered.
 */
data class ContextSuggestion(
    val id: String,
    val type: SuggestionType,
    val displayText: String,
    val preFillText: String,
    val targetScreenType: TargetScreenType
)
