/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : CalendarNavigation.kt
 * Purpose    : CalendarNavigation — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Navigation Graph / Destinations
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
 * File       : CalendarNavigation.kt
 * Purpose    : CalendarNavigation — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Navigation Graph / Destinations
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
 * CalendarNavigation.kt
 *
 * Purpose: Navigation graph for the CalendarView sub-feature, defining route constants
 *          and a NavGraphBuilder extension that wires CalendarViewScreen composables
 *          to CalendarViewModel callbacks.
 * Architecture: feature-productivity — Navigation layer; consumed by productivityNavGraph
 *               in ProductivityNavigation.kt or embedded directly in the app's root NavHost.
 * Dependencies: feature-productivity calendar screens and CalendarViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - CalendarRoute defines type-safe route constants.
 * - calendarNavGraph is a NavGraphBuilder extension so it integrates cleanly into the
 *   existing productivity navigation graph without exposing screen composables to callers.
 * - A single CalendarViewModel scoped to the calendar nav graph ensures state is shared
 *   across the CalendarView and EventEditor destinations.
 * - The EventEditor destination is embedded in the same nav graph — it does not push a
 *   new nav entry; instead, the ViewModel's state machine drives which content is rendered.
 *   This avoids duplicating ViewModel scope and simplifies back-stack management.
 *
 * Requirements: 8.2, 13.1, 19.1
 */
package com.aiassistant.feature.productivity.calendar

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the calendar navigation sub-graph.
 */
object CalendarRoute {
    /** Calendar root navigation graph route. */
    const val GRAPH = "productivity/calendar-graph"

    /** Calendar view screen route (monthly/weekly grid + event list). */
    const val CALENDAR_VIEW = "productivity/calendar"
}

/**
 * Embeds the calendar navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Wires the calendar screen using [CalendarRoute.CALENDAR_VIEW].
 *
 * @param navController The root [NavHostController] shared with the parent graph.
 * @param onNavigateUp  Called when the user navigates back out of the calendar graph.
 */
fun NavGraphBuilder.calendarNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = CalendarRoute.CALENDAR_VIEW,
        route = CalendarRoute.GRAPH
    ) {
        composable(route = CalendarRoute.CALENDAR_VIEW) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(CalendarRoute.GRAPH)
            }
            val viewModel: CalendarViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            CalendarViewScreen(
                uiState = uiState,
                onSelectDate = { date -> viewModel.selectDate(date) },
                onSwitchViewMode = { mode -> viewModel.switchViewMode(mode) },
                onNewEvent = { defaultStart -> viewModel.openNewEvent(defaultStart) },
                onEditEvent = { event -> viewModel.openEditEvent(event) },
                onDeleteEvent = { eventId -> viewModel.deleteEvent(eventId) },
                onBack = onNavigateUp,
                // uses SuggestMeetingTimesUseCase
                onRequestAiSuggestions = { viewModel.requestAiMeetingTimeSuggestions() },
                onAcceptSuggestedTime = { suggestion ->
                    viewModel.openNewEvent(suggestion.startTime)
                },
                onUpdateDraft = { title, description, startTime, endTime, location, isAllDay ->
                    viewModel.updateDraft(title, description, startTime, endTime, location, isAllDay)
                },
                onSaveEvent = { viewModel.saveEvent() },
                onBackToCalendar = { viewModel.backToCalendar() },
                onEventViewed = { event ->
                    viewModel.requestContextSuggestionsForEvent(event.id, event)
                }
            )
        }
    }
}
