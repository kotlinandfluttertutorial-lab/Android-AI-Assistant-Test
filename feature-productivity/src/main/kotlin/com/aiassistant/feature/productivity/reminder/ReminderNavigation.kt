/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderNavigation.kt
 * Purpose    : ReminderNavigation — feature-productivity module component
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
 * File       : ReminderNavigation.kt
 * Purpose    : ReminderNavigation — feature-productivity module component
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
 * ReminderNavigation.kt
 *
 * Purpose: Navigation sub-graph for the Reminders section of the Productivity Suite,
 *          exposing ReminderListScreen and ReminderEditorScreen backed by a shared
 *          ProductivityViewModel scoped to the nav graph.
 *
 * Architecture: feature-productivity — Navigation layer; consumed by the app module's
 *               root NavHost or the productivity feature nav graph.
 * Dependencies: ProductivityViewModel (Hilt), ReminderListScreen, ReminderEditorScreen,
 *               AndroidX Navigation Compose.
 *
 * Requirements: 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.reminder

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the reminder navigation sub-graph.
 */
object ReminderRoute {
    /** Reminders root navigation graph route. */
    const val GRAPH = "reminders"

    /** Reminder list screen route. */
    const val LIST = "reminders/list"

    /** Reminder editor screen route. */
    const val EDITOR = "reminders/editor"
}

/**
 * Embeds the reminders navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * A single [ProductivityViewModel] is scoped to the reminders nav graph and shared
 * between the list and editor screens.
 *
 * @param navController The parent [NavHostController].
 * @param onNavigateUp  Called when the user navigates back out of the reminders graph.
 */
fun NavGraphBuilder.remindersNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = ReminderRoute.LIST,
        route = ReminderRoute.GRAPH
    ) {
        // ── Reminder list ────────────────────────────────────────────────────────
        composable(route = ReminderRoute.LIST) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(ReminderRoute.GRAPH)
            }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            ReminderListScreen(
                uiState = uiState,
                onReminderClick = { reminder ->
                    viewModel.openEditReminder(reminder)
                    navController.navigate(ReminderRoute.EDITOR)
                },
                onNewReminder = {
                    viewModel.openNewReminder()
                    navController.navigate(ReminderRoute.EDITOR)
                },
                onDeleteReminder = { reminderId -> viewModel.deleteReminder(reminderId) },
                onUndoDelete = { viewModel.undoDelete() },
                onClearUndo = { viewModel.clearUndoState() },
                onAiSuggest = { prompt ->
                    viewModel.suggestReminder(prompt)
                    navController.navigate(ReminderRoute.EDITOR)
                }
            )
        }

        // ── Reminder editor ──────────────────────────────────────────────────────
        composable(route = ReminderRoute.EDITOR) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(ReminderRoute.GRAPH)
            }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            // Launcher to open system SCHEDULE_EXACT_ALARM settings
            val settingsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) {
                // After returning from settings, refresh the editor state to re-check permission
                viewModel.loadReminders()
            }

            ReminderEditorScreen(
                uiState = uiState,
                onUpdateDraft = { title, triggerTime, rrule, linkedTodoId ->
                    viewModel.updateDraft(
                        title = title,
                        triggerTime = triggerTime,
                        recurrenceRule = rrule,
                        linkedTodoId = linkedTodoId
                    )
                },
                onSave = {
                    viewModel.saveReminder()
                    if (uiState !is ReminderUiState.ReminderEditor ||
                        (
                            (uiState as? ReminderUiState.ReminderEditor)?.titleError == null &&
                                (uiState as? ReminderUiState.ReminderEditor)?.triggerTimeError == null
                            )
                    ) {
                        onNavigateUp()
                    }
                },
                onBack = {
                    viewModel.backToList()
                    onNavigateUp()
                },
                onOpenExactAlarmSettings = {
                    val intent = viewModel.buildExactAlarmSettingsIntent()
                    if (intent != null) {
                        settingsLauncher.launch(intent)
                    }
                }
            )
        }
    }
}
