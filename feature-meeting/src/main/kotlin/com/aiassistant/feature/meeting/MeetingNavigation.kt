/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingNavigation.kt
 * Purpose    : MeetingNavigation — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * Module     : feature-meeting
 * File       : MeetingNavigation.kt
 * Purpose    : MeetingNavigation — feature-meeting module component
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * MeetingNavigation.kt
 *
 * Purpose: Navigation graph for the Meeting Recorder feature, embedding it into the
 *          app module's root NavHost.
 * Architecture: feature-meeting â€” Navigation layer; consumed by the app module.
 * Dependencies: MeetingRecorderScreen, MeetingSummaryScreen, MeetingViewModel (Hilt),
 *               AndroidX Navigation Compose
 *
 * Requirements: 19.1
 *
 * Design decisions:
 * - Route strings are defined as top-level constants for type-safety.
 * - [meetingNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   meeting graph into its root NavHost without importing screen composables directly.
 * - MeetingViewModel is scoped at the navigation graph level (hiltViewModel with the
 *   graph back stack entry) so both MeetingRecorderScreen and MeetingSummaryScreen share
 *   the same ViewModel instance â€” no need to pass the Complete state as nav arguments.
 * - Navigation from Recording to Summary is triggered by a callback from the screen
 *   composable (not by the ViewModel calling navigation APIs directly).
 */
package com.aiassistant.feature.meeting

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/** Route for the meeting recorder screen. */
const val MEETING_ROUTE = "meeting"

/** Route for the meeting summary screen. */
const val MEETING_SUMMARY_ROUTE = "meeting_summary"

/** Root navigation graph route for the meeting feature. */
private const val MEETING_GRAPH_ROUTE = "meeting_graph"

/**
 * Embeds the Meeting feature navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Both [MeetingRecorderScreen] and [MeetingSummaryScreen] are wired to the same
 * [MeetingViewModel] instance via graph-scoped Hilt injection.
 *
 * Usage in the app module's root NavHost:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     meetingNavGraph(
 *         navController = navController,
 *         onNavigateBack = { navController.popBackStack() },
 *     )
 * }
 * ```
 *
 * @param navController  The root [NavController] shared with the app module.
 * @param onNavigateBack Called when the user navigates back from the Meeting screens.
 */
fun NavGraphBuilder.meetingNavGraph(navController: NavController, onNavigateBack: () -> Unit) {
    navigation(
        startDestination = MEETING_ROUTE,
        route = MEETING_GRAPH_ROUTE
    ) {
        composable(route = MEETING_ROUTE) { backStackEntry ->
            // Obtain the ViewModel scoped to the meeting navigation graph so both screens
            // share the same instance without passing data through nav arguments.
            val parentEntry = androidx.compose.runtime.remember(backStackEntry) {
                navController.getBackStackEntry(MEETING_GRAPH_ROUTE)
            }
            val viewModel: MeetingViewModel = hiltViewModel(parentEntry)

            MeetingRecorderScreen(
                onNavigateBack = onNavigateBack,
                onRecordingComplete = {
                    navController.navigate(MEETING_SUMMARY_ROUTE) {
                        // Keep the recorder screen in the back stack so the user can
                        // navigate back to start a new recording.
                        launchSingleTop = true
                    }
                },
                viewModel = viewModel
            )
        }

        composable(route = MEETING_SUMMARY_ROUTE) { backStackEntry ->
            val parentEntry = androidx.compose.runtime.remember(backStackEntry) {
                navController.getBackStackEntry(MEETING_GRAPH_ROUTE)
            }
            val viewModel: MeetingViewModel = hiltViewModel(parentEntry)

            MeetingSummaryScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                viewModel = viewModel
            )
        }
    }
}

/**
 * Builds the navigation route string to navigate into the meeting feature graph.
 */
fun meetingRoute(): String = MEETING_GRAPH_ROUTE
