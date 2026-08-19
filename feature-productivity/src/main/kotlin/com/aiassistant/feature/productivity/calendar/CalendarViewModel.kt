/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : CalendarViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Calendar feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * File       : CalendarViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Calendar feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * CalendarViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the CalendarView
 *          sub-feature, including event loading, creating, deleting, view mode switching,
 *          date selection, and AI meeting-time suggestion.
 * Architecture: feature-productivity â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (GetCalendarEventsUseCase, CreateCalendarEventUseCase,
 *               DeleteCalendarEventUseCase, CalendarEvent, DateRange),
 *               core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 8.2, 13.1, 19.1
 */
package com.aiassistant.feature.productivity.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.repository.DateRange
import com.aiassistant.domain.usecase.productivity.CreateCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.DeleteCalendarEventUseCase
import com.aiassistant.domain.usecase.productivity.GetCalendarEventsUseCase
import com.aiassistant.domain.usecase.productivity.SuggestMeetingTimesUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel for the CalendarView and EventEditor flows.
 * Exposes a [StateFlow] of [CalendarUiState] that composables observe. All blocking
 * work (database operations, network calls) is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val getCalendarEventsUseCase: GetCalendarEventsUseCase,
    private val createCalendarEventUseCase: CreateCalendarEventUseCase,
    private val deleteCalendarEventUseCase: DeleteCalendarEventUseCase,
    private val suggestMeetingTimesUseCase: SuggestMeetingTimesUseCase,
    private val dispatchers: DispatcherProvider,
    private val getContextSuggestionsUseCase: GetContextSuggestionsUseCase
) : ViewModel() {

    // ─── Suggestion settings (updated by Settings screen) ────────────────────
    private var isSuggestionsEnabled: Boolean = true
    private var isPrivacyModeEnabled: Boolean = false

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<CalendarUiState>(CalendarUiState.Loading)

    /** Observable calendar UI state. */
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        loadEventsForCurrentMonth()
    }

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads calendar events for the given [range] and emits [CalendarUiState.CalendarView].
     *
     * Preserves the current [CalendarUiState.CalendarView.selectedDate], [viewMode], and
     * AI suggestion state when transitioning from an existing CalendarView state.
     *
     * @param range The epoch-millisecond date window to query.
     */
    fun loadEvents(range: DateRange) {
        val currentCalendarState = _uiState.value as? CalendarUiState.CalendarView
        val selectedDate = currentCalendarState?.selectedDate ?: LocalDate.now()
        val viewMode = currentCalendarState?.viewMode ?: CalendarViewMode.MONTHLY

        _uiState.value = CalendarUiState.Loading

        viewModelScope.launch {
            getCalendarEventsUseCase(range).collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> {
                        val current = _uiState.value as? CalendarUiState.CalendarView
                        CalendarUiState.CalendarView(
                            events = result.data,
                            selectedDate = current?.selectedDate ?: selectedDate,
                            viewMode = current?.viewMode ?: viewMode,
                            dateRange = range,
                            aiSuggestedTimes = current?.aiSuggestedTimes ?: emptyList(),
                            isLoadingAiSuggestions = current?.isLoadingAiSuggestions ?: false,
                            isMergingGoogleCalendar = result.data.any { event ->
                                event.source == CalendarEventSource.GOOGLE_CALENDAR
                            }
                        )
                    }
                    is ApiResult.Error -> CalendarUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        val current = _uiState.value as? CalendarUiState.CalendarView
                        CalendarUiState.CalendarView(
                            events = current?.events ?: emptyList(),
                            selectedDate = current?.selectedDate ?: selectedDate,
                            viewMode = current?.viewMode ?: viewMode,
                            dateRange = range
                        )
                    }
                    is ApiResult.Loading -> CalendarUiState.Loading
                }
            }
        }
    }

    /**
     * Computes the date range for the current calendar month and calls [loadEvents].
     */
    fun loadEventsForCurrentMonth() {
        val range = monthRangeFor(LocalDate.now())
        loadEvents(range)
    }

    /**
     * Switches the calendar view to [mode] and reloads the appropriate date range.
     *
     * Monthly mode reloads the full month containing the selected date.
     * Weekly mode reloads the 7-day week starting from Monday of the selected week.
     *
     * @param mode The [CalendarViewMode] to switch to.
     */
    fun switchViewMode(mode: CalendarViewMode) {
        val currentState = _uiState.value as? CalendarUiState.CalendarView ?: return
        val updatedState = currentState.copy(viewMode = mode)
        _uiState.value = updatedState

        val range = when (mode) {
            CalendarViewMode.MONTHLY -> monthRangeFor(currentState.selectedDate)
            CalendarViewMode.WEEKLY -> weekRangeFor(currentState.selectedDate)
        }

        // Reload with the appropriate range, preserving the already-updated viewMode
        viewModelScope.launch {
            getCalendarEventsUseCase(range).collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> {
                        val current = _uiState.value as? CalendarUiState.CalendarView
                        CalendarUiState.CalendarView(
                            events = result.data,
                            selectedDate = current?.selectedDate ?: currentState.selectedDate,
                            viewMode = mode,
                            dateRange = range,
                            aiSuggestedTimes = current?.aiSuggestedTimes ?: emptyList(),
                            isLoadingAiSuggestions = current?.isLoadingAiSuggestions ?: false,
                            isMergingGoogleCalendar = result.data.any { event ->
                                event.source == CalendarEventSource.GOOGLE_CALENDAR
                            }
                        )
                    }
                    is ApiResult.Error -> CalendarUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        (_uiState.value as? CalendarUiState.CalendarView)
                            ?: updatedState
                    }
                    is ApiResult.Loading -> _uiState.value
                }
            }
        }
    }

    /**
     * Updates the highlighted [date] in the calendar grid without reloading events.
     *
     * @param date The [LocalDate] to mark as selected.
     */
    fun selectDate(date: LocalDate) {
        val currentState = _uiState.value as? CalendarUiState.CalendarView ?: return
        _uiState.value = currentState.copy(selectedDate = date)
    }

    /**
     * Transitions to [CalendarUiState.EventEditor] with a blank new event pre-seeded
     * with [defaultStartTime].
     *
     * @param defaultStartTime Epoch milliseconds for the new event's start time;
     *                         defaults to the current system time.
     */
    fun openNewEvent(defaultStartTime: Long = System.currentTimeMillis()) {
        val now = Instant.now().toEpochMilli()
        val blankEvent = CalendarEvent(
            id = UUID.randomUUID().toString(),
            userId = "",
            title = "",
            description = "",
            startTime = defaultStartTime,
            endTime = defaultStartTime + DEFAULT_EVENT_DURATION_MS,
            location = null,
            isAllDay = false,
            source = CalendarEventSource.LOCAL,
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        _uiState.value = CalendarUiState.EventEditor(event = blankEvent, isNew = true)
    }

    /**
     * Transitions to [CalendarUiState.EventEditor] for an existing [event].
     *
     * @param event The [CalendarEvent] to open for editing.
     */
    fun openEditEvent(event: CalendarEvent) {
        _uiState.value = CalendarUiState.EventEditor(event = event, isNew = false)
    }

    /**
     * Updates the draft event in [CalendarUiState.EventEditor] without saving.
     *
     * No repository call is made â€” changes are buffered in state until the user
     * explicitly saves (Requirement 13.1).
     *
     * @param title       Updated event title.
     * @param description Updated event description.
     * @param startTime   Updated start time as epoch milliseconds.
     * @param endTime     Updated end time as epoch milliseconds.
     * @param location    Updated location string, or null to clear.
     * @param isAllDay    Whether this is an all-day event.
     */
    fun updateDraft(
        title: String,
        description: String,
        startTime: Long,
        endTime: Long,
        location: String?,
        isAllDay: Boolean
    ) {
        val currentState = _uiState.value as? CalendarUiState.EventEditor ?: return
        _uiState.value = currentState.copy(
            event = currentState.event.copy(
                title = title,
                description = description,
                startTime = startTime,
                endTime = endTime,
                location = location,
                isAllDay = isAllDay,
                updatedAt = Instant.now().toEpochMilli()
            ),
            titleError = null,
            endTimeError = null
        )
    }

    /**
     * Validates and persists the current draft event via [CreateCalendarEventUseCase].
     *
     * Sets [CalendarUiState.EventEditor.isSaving] to true while in progress.
     * On success, transitions back to the calendar view by calling [loadEventsForCurrentMonth].
     * On validation failure, populates inline field errors in the editor state.
     * On API failure, emits [CalendarUiState.Error].
     */
    fun saveEvent() {
        val currentState = _uiState.value as? CalendarUiState.EventEditor ?: return

        // Client-side validation
        var titleError: String? = null
        var endTimeError: String? = null

        if (currentState.event.title.isBlank()) {
            titleError = "Title is required."
        }
        if (!currentState.event.isAllDay && currentState.event.endTime < currentState.event.startTime) {
            endTimeError = "End time must be after start time."
        }

        if (titleError != null || endTimeError != null) {
            _uiState.value = currentState.copy(
                titleError = titleError,
                endTimeError = endTimeError
            )
            return
        }

        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                // Only create is supported here; update would require an UpdateCalendarEventUseCase
                createCalendarEventUseCase(currentState.event)
            }
            when (result) {
                is ApiResult.Success -> loadEventsForCurrentMonth()
                is ApiResult.Error -> {
                    // Propagate domain validation errors back into the editor
                    val domainFields = (
                        result.error as?
                            com.aiassistant.core.common.DomainError.ValidationError
                        )?.fields
                    if (domainFields != null) {
                        _uiState.value = currentState.copy(
                            isSaving = false,
                            titleError = domainFields["title"],
                            endTimeError = domainFields["endTime"]
                        )
                    } else {
                        _uiState.value = CalendarUiState.Error(result.error.message)
                    }
                }
                is ApiResult.NetworkUnavailable -> _uiState.value = CalendarUiState.Error(
                    "No network connection. Event will sync when you're back online."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Permanently deletes the event identified by [eventId] and reloads the calendar.
     *
     * @param eventId The unique identifier of the event to delete.
     */
    fun deleteEvent(eventId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) { deleteCalendarEventUseCase(eventId) }
            loadEventsForCurrentMonth()
        }
    }

    /**
     * Transitions back to the calendar view by reloading events for the current month.
     */
    fun backToCalendar() {
        loadEventsForCurrentMonth()
    }

    // ─── Context suggestion methods (Requirements 33.2) ─────────────────────

    /**
     * Updates the global suggestions-enabled flag. Called by the Settings screen when
     * the user toggles the context suggestions setting (Requirement 33.8).
     *
     * @param enabled `true` to allow suggestions; `false` to suppress all suggestions.
     */
    fun updateSuggestionsEnabled(enabled: Boolean) {
        isSuggestionsEnabled = enabled
    }

    /**
     * Updates the privacy-mode flag. Called by the Settings screen when the user
     * toggles Privacy Mode (Requirement 33.7).
     *
     * @param enabled `true` if Privacy Mode is active (suggestions suppressed).
     */
    fun updatePrivacyMode(enabled: Boolean) {
        isPrivacyModeEnabled = enabled
    }

    /**
     * Requests context-aware suggestions for the given [event] and stores them in
     * [CalendarUiState.CalendarView.eventContextSuggestions] keyed by [eventId].
     *
     * A 3-second timeout is applied; if the use case does not respond within that
     * window, the suggestions for this event are silently left empty (Requirement 33.6).
     * Failures are also silently swallowed — suggestions are non-blocking.
     *
     * @param eventId The unique identifier of the event being viewed.
     * @param event   The full [CalendarEvent] whose context is sent to the AI.
     */
    fun requestContextSuggestionsForEvent(eventId: String, event: CalendarEvent) {
        viewModelScope.launch(dispatchers.io) {
            val result = withTimeoutOrNull(SUGGESTION_TIMEOUT_MS) {
                getContextSuggestionsUseCase(
                    context = ScreenContext.CalendarEventContext(
                        eventId = eventId,
                        eventTitle = event.title,
                        eventDescription = event.description.ifBlank { null },
                        attendeeNames = emptyList(),
                        screenInstanceId = eventId
                    ),
                    isPrivacyModeEnabled = isPrivacyModeEnabled,
                    isSuggestionsEnabled = isSuggestionsEnabled
                )
            }

            val suggestions = when (result) {
                is com.aiassistant.core.common.ApiResult.Success -> result.data
                else -> emptyList()
            }

            val currentState = _uiState.value as? CalendarUiState.CalendarView ?: return@launch
            _uiState.value = currentState.copy(
                eventContextSuggestions = currentState.eventContextSuggestions + (eventId to suggestions)
            )
        }
    }

    /**


    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Computes a [DateRange] spanning the entire month that contains [date].
     */
    private fun monthRangeFor(date: LocalDate): DateRange {
        val yearMonth = YearMonth.of(date.year, date.month)
        val start = yearMonth.atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val end = yearMonth.atEndOfMonth()
            .atTime(23, 59, 59)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return DateRange(start = start, end = end)
    }

    /**
     * Computes a [DateRange] spanning the 7-day week starting on Monday of [date]'s week.
     */
    private fun weekRangeFor(date: LocalDate): DateRange {
        val monday = date.with(DayOfWeek.MONDAY)
        val sunday = monday.plusDays(6)
        val start = monday
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val end = sunday
            .atTime(23, 59, 59)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return DateRange(start = start, end = end)
    }

    // â”€â”€â”€ Constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private companion object {
        /** Default event duration: 1 hour in milliseconds. */
        const val DEFAULT_EVENT_DURATION_MS = 3_600_000L

        /** Simulated AI suggestion response time. */
        const val AI_SUGGESTION_DELAY_MS = 1_200L

        /** Timeout for context suggestion requests (Requirement 33.6). */
        const val SUGGESTION_TIMEOUT_MS = 3_000L
    }
}