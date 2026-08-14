/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderUiState.kt
 * Purpose    : ReminderUiState — feature-productivity module component
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
 * File       : ReminderUiState.kt
 * Purpose    : ReminderUiState — feature-productivity module component
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
 * ReminderUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the reminders sub-feature,
 *          including list, editor, AI suggestion processing, and error states.
 * Architecture: feature-productivity â€” MVVM presentation layer.
 * Dependencies: domain (Reminder, TodoItem)
 *
 * Requirements: 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.reminder

import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.TodoItem

/**
 * All possible UI states for the Reminders sub-feature.
 *
 * The [ProductivityViewModel] exposes a StateFlow of this sealed class. Composables
 * observe it and render accordingly.
 */
sealed class ReminderUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : ReminderUiState()

    /**
     * The reminder list screen is active.
     *
     * @param reminders Upcoming reminders sorted by trigger time ascending.
     * @param deletedReminder The most recently deleted reminder, held for undo snackbar.
     */
    data class ReminderList(val reminders: List<Reminder>, val deletedReminder: Reminder? = null) : ReminderUiState()

    /**
     * The reminder editor screen is active.
     *
     * @param reminder         The reminder being created or edited.
     * @param isNew            True when creating a new reminder; false when editing an existing one.
     * @param isSaving         True while the save operation is in progress.
     * @param availableTodos   List of existing TodoItems for the linked-todo picker.
     * @param titleError       Non-null when title validation fails.
     * @param triggerTimeError Non-null when trigger time is not in the future.
     * @param canScheduleExact False when SCHEDULE_EXACT_ALARM permission is missing (Android 12+).
     */
    data class ReminderEditor(
        val reminder: Reminder,
        val isNew: Boolean,
        val isSaving: Boolean = false,
        val availableTodos: List<TodoItem> = emptyList(),
        val titleError: String? = null,
        val triggerTimeError: String? = null,
        val canScheduleExact: Boolean = true
    ) : ReminderUiState()

    /**
     * The "AI Suggest" dialog is shown and the AI is processing the natural language prompt.
     *
     * @param prompt The natural language text submitted by the user.
     */
    data class AiSuggesting(val prompt: String) : ReminderUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : ReminderUiState()
}
