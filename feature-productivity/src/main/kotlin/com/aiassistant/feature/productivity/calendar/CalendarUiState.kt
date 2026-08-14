/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : CalendarUiState.kt
 * Purpose    : CalendarUiState — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : UI State Data Class
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
 * Module     : feature-productivity
 * File       : CalendarUiState.kt
 * Purpose    : CalendarUiState — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : UI State Data Class
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
 * CalendarUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the CalendarView
 *          sub-feature of the Productivity Suite, including the main calendar view,
 *          event editor, loading, and error states.
 * Architecture: feature-productivity â€” MVVM presentation layer; calendar sub-package.
 * Dependencies: domain (CalendarEvent, DateRange)
 *
 * Requirements: 8.2, 13.1, 19.1
 */
package com.aiassistant.feature.productivity.calendar

import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.repository.DateRange
import java.time.LocalDate

/**
 * The view mode for the calendar grid.
 */
enum class CalendarViewMode {
    /** Show a full month grid with day cells. */
    MONTHLY,

    /** Show a single week as a vertical list of days. */
    WEEKLY
}

/**
 * An AI-suggested meeting time slot with an explanatory reason.
 *
 * @param startTime Epoch milliseconds of the suggested meeting start.
 * @param endTime   Epoch milliseconds of the suggested meeting end.
 * @param reason    Human-readable explanation for why this time is optimal.
 */
data class SuggestedMeetingTime(val startTime: Long, val endTime: Long, val reason: String)

/**
 * Represents every possible UI state in the CalendarView sub-feature.
 *
 * The [CalendarViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class CalendarUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : CalendarUiState()

    /**
     * The calendar view screen is active.
     *
     * @param events                   All events loaded for the current [dateRange].
     * @param selectedDate             The currently highlighted date in the calendar grid.
     * @param viewMode                 Monthly or weekly grid display mode.
     * @param dateRange                The current loaded date window (e.g., full month).
     * @param aiSuggestedTimes         Meeting time suggestions from the AI Orchestrator.
     * @param isLoadingAiSuggestions   True while the AI suggestion call is in progress.
     * @param isMergingGoogleCalendar  True when events from Google Calendar MCP are being
     *                                 merged into the displayed set.
     * @param eventContextSuggestions  Context-aware suggestions map per event ID (Requirement 33.2).
     */
    data class CalendarView(
        val events: List<CalendarEvent>,
        val selectedDate: LocalDate,
        val viewMode: CalendarViewMode,
        val dateRange: DateRange,
        val aiSuggestedTimes: List<SuggestedMeetingTime> = emptyList(),
        val isLoadingAiSuggestions: Boolean = false,
        val isMergingGoogleCalendar: Boolean = false,
        val eventContextSuggestions: Map<String, List<com.aiassistant.domain.model.ContextSuggestion>> = emptyMap()
    ) : CalendarUiState()

    /**
     * The event editor screen is active.
     *
     * @param event        The [CalendarEvent] being created or edited (may be a draft).
     * @param isNew        True when creating a new event; false when editing an existing one.
     * @param isSaving     True while the save operation is in progress.
     * @param titleError   Inline validation error message for the title field, or null.
     * @param endTimeError Inline validation error message for the end time field, or null.
     */
    data class EventEditor(
        val event: CalendarEvent,
        val isNew: Boolean,
        val isSaving: Boolean = false,
        val titleError: String? = null,
        val endTimeError: String? = null
    ) : CalendarUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : CalendarUiState()
}
