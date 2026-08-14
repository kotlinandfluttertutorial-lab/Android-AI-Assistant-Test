/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitUiState.kt
 * Purpose    : HabitUiState — feature-productivity module component
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
 * File       : HabitUiState.kt
 * Purpose    : HabitUiState — feature-productivity module component
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
 * HabitUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the habit tracker
 *          sub-feature, including list, editor, insights, loading, and error states.
 * Architecture: feature-productivity â€” MVVM presentation layer.
 * Dependencies: domain (HabitDefinition, HabitEntry)
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry

/**
 * All possible UI states for the Habit Tracker sub-feature.
 *
 * The [HabitViewModel] exposes a StateFlow of this sealed class. Composables observe it
 * and render accordingly.
 */
sealed class HabitUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : HabitUiState()

    /**
     * The habit list screen is active.
     *
     * @param habits           All tracked habit definitions for the authenticated user.
     * @param habitEntriesMap  Map of habitId â†’ list of completion entries, used for streak
     *                         calculation and today's completion check.
     * @param isLoading        True while a background operation (e.g. log completion) is running.
     */
    data class HabitList(
        val habits: List<HabitDefinition>,
        val habitEntriesMap: Map<String, List<HabitEntry>>,
        val isLoading: Boolean = false
    ) : HabitUiState()

    /**
     * The habit editor screen is active.
     *
     * @param habit      The habit definition being created or edited (draft state).
     * @param isNew      True when creating a new habit; false when editing an existing one.
     * @param isSaving   True while the save operation is in progress.
     * @param nameError  Non-null when name validation fails.
     */
    data class HabitEditor(
        val habit: HabitDefinition,
        val isNew: Boolean,
        val isSaving: Boolean = false,
        val nameError: String? = null
    ) : HabitUiState()

    /**
     * The habit insights screen is active.
     *
     * @param habit        The habit whose insights are being displayed.
     * @param insightsText The AI-generated insights text (empty while loading).
     * @param isLoading    True while the AI insights are being fetched.
     */
    data class HabitInsights(val habit: HabitDefinition, val insightsText: String, val isLoading: Boolean = false) :
        HabitUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : HabitUiState()
}
