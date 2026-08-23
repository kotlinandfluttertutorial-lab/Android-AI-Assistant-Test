/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ProductivityViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Productivity feature
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
 * File       : ProductivityViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Productivity feature
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
 * ProductivityViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the productivity
 *          feature's todo sub-section, including listing, filtering, editing, saving,
 *          deleting, and AI-assisted todo generation.
 * Architecture: feature-productivity â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (GetTodosUseCase, CreateTodoUseCase, UpdateTodoUseCase,
 *               DeleteTodoUseCase, GenerateTodosFromPromptUseCase),
 *               core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.TodoFilter
import com.aiassistant.domain.usecase.productivity.CreateTodoUseCase
import com.aiassistant.domain.usecase.productivity.DeleteTodoUseCase
import com.aiassistant.domain.usecase.productivity.GenerateTodosFromPromptUseCase
import com.aiassistant.domain.usecase.productivity.GetTodosUseCase
import com.aiassistant.domain.usecase.productivity.UpdateTodoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the productivity todo list and editor flows.
 *
 * Exposes a [StateFlow] of [ProductivityUiState] that composables observe. All blocking
 * work (network calls, database operations) is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class ProductivityViewModel @Inject constructor(
    private val getTodosUseCase: GetTodosUseCase,
    private val createTodoUseCase: CreateTodoUseCase,
    private val updateTodoUseCase: UpdateTodoUseCase,
    private val deleteTodoUseCase: DeleteTodoUseCase,
    private val generateTodosFromPromptUseCase: GenerateTodosFromPromptUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<ProductivityUiState>(ProductivityUiState.Loading)

    /** Observable productivity UI state. */
    val uiState: StateFlow<ProductivityUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        loadTodos()
    }

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads todos matching the given [filter] and emits [ProductivityUiState.TodoList].
     *
     * Collects the [GetTodosUseCase] flow reactively â€” subsequent Room database changes
     * automatically update the UI state.
     *
     * @param filter Criteria to narrow the result set.
     */
    fun loadTodos(filter: TodoFilter = TodoFilter()) {
        viewModelScope.launch {
            _uiState.value = ProductivityUiState.Loading
            getTodosUseCase(filter).collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> {
                        val current = _uiState.value as? ProductivityUiState.TodoList
                        ProductivityUiState.TodoList(
                            todos = result.data,
                            filterState = current?.filterState ?: TodoFilterState(),
                            aiSuggestedTodos = current?.aiSuggestedTodos ?: emptyList(),
                            isGeneratingAi = current?.isGeneratingAi ?: false
                        )
                    }
                    is ApiResult.Error -> ProductivityUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        val current = _uiState.value as? ProductivityUiState.TodoList
                        ProductivityUiState.TodoList(
                            todos = emptyList(),
                            filterState = current?.filterState ?: TodoFilterState()
                        )
                    }
                    is ApiResult.Loading -> ProductivityUiState.Loading
                }
            }
        }
    }

    /**
     * Converts a [TodoFilterState] to a domain [TodoFilter] and reloads the todo list.
     *
     * @param filterState The UI filter selection to apply.
     */
    fun applyFilter(filterState: TodoFilterState) {
        val domainFilter = TodoFilter(
            showCompleted = filterState.showCompleted,
            dueBefore = filterState.dueBefore,
            priority = filterState.priority
        )
        // Update filter state immediately so the UI reflects the selection
        val current = _uiState.value as? ProductivityUiState.TodoList
        if (current != null) {
            _uiState.value = current.copy(filterState = filterState)
        }
        loadTodos(domainFilter)
    }

    /**
     * Transitions to [ProductivityUiState.TodoEditor] for a new, empty todo item.
     */
    fun openNewTodo() {
        val now = Instant.now().toEpochMilli()
        val emptyTodo = TodoItem(
            id = UUID.randomUUID().toString(),
            userId = "",
            title = "",
            description = "",
            isCompleted = false,
            dueDate = null,
            priority = Priority.MEDIUM,
            tags = emptyList(),
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        _uiState.value = ProductivityUiState.TodoEditor(todo = emptyTodo, isNew = true)
    }

    /**
     * Transitions to [ProductivityUiState.TodoEditor] for an existing todo item.
     *
     * @param todo The todo item to open for editing.
     */
    fun openTodo(todo: TodoItem) {
        _uiState.value = ProductivityUiState.TodoEditor(todo = todo, isNew = false)
    }

    /**
     * Updates the draft todo in the [ProductivityUiState.TodoEditor] state without saving.
     *
     * No repository call is made â€” changes are buffered in state until the user
     * explicitly saves (Requirement 13.1).
     *
     * @param title       Updated todo title.
     * @param description Updated todo description.
     * @param dueDate     Updated due date as epoch milliseconds, or null to remove.
     * @param priority    Updated priority level.
     * @param tags        Updated tag list.
     */
    fun updateDraft(title: String, description: String, dueDate: Long?, priority: Priority, tags: List<String>) {
        val currentState = _uiState.value as? ProductivityUiState.TodoEditor ?: return
        _uiState.value = currentState.copy(
            todo = currentState.todo.copy(
                title = title,
                description = description,
                dueDate = dueDate,
                priority = priority,
                tags = tags,
                updatedAt = Instant.now().toEpochMilli()
            )
        )
    }

    /**
     * Persists the [todo] via [CreateTodoUseCase] or [UpdateTodoUseCase] and returns
     * to the todo list on success.
     *
     * Sets [ProductivityUiState.TodoEditor.isSaving] to true while in progress.
     * On success transitions to the list by calling [loadTodos].
     * On failure emits [ProductivityUiState.Error].
     *
     * @param todo The todo item (with current draft content) to persist.
     */
    fun saveTodo(todo: TodoItem) {
        val currentState = _uiState.value as? ProductivityUiState.TodoEditor ?: return
        _uiState.value = currentState.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                if (currentState.isNew) {
                    createTodoUseCase(todo)
                } else {
                    updateTodoUseCase(todo)
                }
            }
            when (result) {
                is ApiResult.Success -> loadTodos()
                is ApiResult.Error ->
                    _uiState.value =
                        ProductivityUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> _uiState.value = ProductivityUiState.Error(
                    "No network connection. To-do will sync when you're back online."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Permanently deletes the todo identified by [todoId] and refreshes the list.
     *
     * @param todoId The unique identifier of the todo item to delete.
     */
    fun deleteTodo(todoId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) { deleteTodoUseCase(todoId) }
            loadTodos()
        }
    }

    /**
     * Navigates back to the todo list by calling [loadTodos].
     */
    fun backToList() {
        loadTodos()
    }

    /**
     * Requests AI-generated todo items from [prompt] (Requirement 19.1).
     *
     * Sets [ProductivityUiState.TodoList.isGeneratingAi] to true while in progress.
     * On success, updates [ProductivityUiState.TodoList.aiSuggestedTodos].
     * On failure, emits [ProductivityUiState.Error].
     *
     * @param prompt Natural language description of the tasks to generate.
     */
    fun generateTodosFromPrompt(prompt: String) {
        val currentState = _uiState.value as? ProductivityUiState.TodoList ?: return
        _uiState.value = currentState.copy(isGeneratingAi = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                generateTodosFromPromptUseCase(prompt)
            }
            val latestState = _uiState.value as? ProductivityUiState.TodoList ?: return@launch
            _uiState.value = when (result) {
                is ApiResult.Success -> latestState.copy(
                    isGeneratingAi = false,
                    aiSuggestedTodos = result.data
                )
                is ApiResult.Error -> {
                    // Show error but keep list visible
                    _uiState.value = ProductivityUiState.Error(result.error.message)
                    return@launch
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = ProductivityUiState.Error(
                        "No network connection. AI generation requires internet access."
                    )
                    return@launch
                }
                is ApiResult.Loading -> latestState.copy(isGeneratingAi = true)
            }
        }
    }

    /**
     * Accepts an AI-suggested todo by creating it and removing it from the suggestions list.
     *
     * @param todo The suggested [TodoItem] to create and remove from suggestions.
     */
    fun acceptSuggestedTodo(todo: TodoItem) {
        val currentState = _uiState.value as? ProductivityUiState.TodoList ?: return
        // Optimistically remove from suggestions
        _uiState.value = currentState.copy(
            aiSuggestedTodos = currentState.aiSuggestedTodos.filter { it.id != todo.id }
        )

        viewModelScope.launch {
            withContext(dispatchers.io) { createTodoUseCase(todo) }
            // Reload to reflect the new item in the main list
            loadTodos()
        }
    }

    /**
     * Clears all AI-suggested todos without creating any of them.
     */
    fun dismissSuggestedTodos() {
        val currentState = _uiState.value as? ProductivityUiState.TodoList ?: return
        _uiState.value = currentState.copy(aiSuggestedTodos = emptyList())
    }
}
