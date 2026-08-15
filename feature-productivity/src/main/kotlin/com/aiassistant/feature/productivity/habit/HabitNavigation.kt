/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitNavigation.kt
 * Purpose    : HabitNavigation — feature-productivity module component
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
 * File       : HabitNavigation.kt
 * Purpose    : HabitNavigation — feature-productivity module component
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
 * HabitNavigation.kt
 *
 * Purpose: Navigation sub-graph for the Habit Tracker section of the Productivity Suite,
 *          exposing HabitListScreen, HabitEditorScreen, and HabitInsightsScreen backed
 *          by a shared HabitViewModel scoped to the nav graph.
 *
 * Architecture: feature-productivity — Navigation layer; consumed by the productivity
 *               feature nav graph or the app module's root NavHost.
 * Dependencies: HabitViewModel (Hilt), HabitListScreen, HabitEditorScreen,
 *               HabitInsightsScreen, AndroidX Navigation Compose.
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the habit tracker navigation sub-graph.
 */
object HabitRoute {
    /** Habit tracker root navigation graph route. */
    const val GRAPH = "habits"

    /** Habit list screen route. */
    const val LIST = "habits/list"

    /** Habit editor screen route. */
    const val EDITOR = "habits/editor"

    /** Habit insights screen route. */
    const val INSIGHTS = "habits/insights"
}

/**
 * Embeds the habit tracker navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * A single [HabitViewModel] is scoped to the "habits" nav graph and shared between
 * the list, editor, and insights screens.
 *
 * @param navController The parent [NavHostController].
 * @param onNavigateUp  Called when the user navigates back out of the habits graph.
 */
fun NavGraphBuilder.habitsNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = HabitRoute.LIST,
        route = HabitRoute.GRAPH
    ) {
        // ── Habit list ───────────────────────────────────────────────────────────
        composable(route = HabitRoute.LIST) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(HabitRoute.GRAPH)
            }
            val viewModel: HabitViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            HabitListScreen(
                uiState = uiState,
                onHabitClick = { habit ->
                    viewModel.openEditHabit(habit)
                    navController.navigate(HabitRoute.EDITOR)
                },
                onNewHabit = {
                    viewModel.openNewHabit()
                    navController.navigate(HabitRoute.EDITOR)
                },
                onDeleteHabit = { habitId -> viewModel.deleteHabit(habitId) },
                onLogCompletion = { habitId -> viewModel.logCompletion(habitId) },
                onViewInsights = { habit ->
                    viewModel.openInsights(habit)
                    navController.navigate(HabitRoute.INSIGHTS)
                },
                streakCalculator = { entries, recurrence ->
                    viewModel.calculateStreak(entries, recurrence)
                },
                todayCompletedChecker = { entries, freq ->
                    viewModel.isTodayCompleted(entries, freq)
                }
            )
        }

        // ── Habit editor ─────────────────────────────────────────────────────────
        composable(route = HabitRoute.EDITOR) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(HabitRoute.GRAPH)
            }
            val viewModel: HabitViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            HabitEditorScreen(
                uiState = uiState,
                onUpdateDraft = { name, description, recurrence, targetFrequency ->
                    viewModel.updateDraft(
                        name = name,
                        description = description,
                        recurrence = recurrence,
                        targetFrequency = targetFrequency
                    )
                },
                onSave = {
                    viewModel.saveHabit()
                    // Pop back if no name error remains in editor state
                    val currentState = viewModel.uiState.value
                    if (currentState !is HabitUiState.HabitEditor ||
                        currentState.nameError == null
                    ) {
                        navController.popBackStack()
                    }
                },
                onBack = {
                    viewModel.backToList()
                    navController.popBackStack()
                }
            )
        }

        // ── Habit insights ───────────────────────────────────────────────────────
        composable(route = HabitRoute.INSIGHTS) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(HabitRoute.GRAPH)
            }
            val viewModel: HabitViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            HabitInsightsScreen(
                uiState = uiState,
                onBack = {
                    viewModel.backToList()
                    navController.popBackStack()
                },
                onRetry = {
                    // Re-fetch insights for the current habit
                    val currentState = viewModel.uiState.value
                    if (currentState is HabitUiState.HabitInsights) {
                        viewModel.openInsights(currentState.habit)
                    }
                }
            )
        }
    }
}
