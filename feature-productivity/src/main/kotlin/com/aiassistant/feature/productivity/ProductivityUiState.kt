/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ProductivityUiState.kt
 * Purpose    : ProductivityUiState — feature-productivity module component
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
 * File       : ProductivityUiState.kt
 * Purpose    : ProductivityUiState — feature-productivity module component
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
 * ProductivityUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the productivity
 *          feature, including todo list, editor, loading, and error states.
 * Architecture: feature-productivity â€” MVVM presentation layer.
 * Dependencies: domain (TodoItem, Priority)
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity

import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.TodoItem

/**
 * Filter state for the todo list screen, mirroring the domain [com.aiassistant.domain.repository.TodoFilter]
 * but tailored for UI representation (no `tag` field â€” tags are managed separately).
 *
 * @param showCompleted If `true`, completed items are shown alongside pending ones.
 * @param dueBefore     Optional epoch milliseconds upper bound for the due date filter.
 * @param priority      Optional priority filter; `null` means show all priorities.
 */
data class TodoFilterState(
    val showCompleted: Boolean = true,
    val dueBefore: Long? = null,
    val priority: Priority? = null
)

/**
 * Represents every possible UI state in the productivity feature's todo sub-section.
 *
 * The [ProductivityViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class ProductivityUiState {

    /** A data load or navigation transition is in progress. */
    data object Loading : ProductivityUiState()

    /**
     * The todo list screen is active.
     *
     * @param todos             The filtered list of todo items for the current user.
     * @param filterState       Currently applied filter criteria.
     * @param aiSuggestedTodos  Todo items suggested by the AI; shown in a separate section.
     * @param isGeneratingAi    True while the AI prompt generation call is in progress.
     */
    data class TodoList(
        val todos: List<TodoItem>,
        val filterState: TodoFilterState = TodoFilterState(),
        val aiSuggestedTodos: List<TodoItem> = emptyList(),
        val isGeneratingAi: Boolean = false
    ) : ProductivityUiState()

    /**
     * The todo editor screen is active.
     *
     * @param todo      The todo item being created or edited.
     * @param isNew     True when creating a new item; false when editing an existing one.
     * @param isSaving  True while the save operation is in progress.
     */
    data class TodoEditor(val todo: TodoItem, val isNew: Boolean, val isSaving: Boolean = false) :
        ProductivityUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : ProductivityUiState()
}
