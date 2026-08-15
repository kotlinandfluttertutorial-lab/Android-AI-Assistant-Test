/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetTodosUseCase.kt
 * Purpose    : Encapsulates the 'GetTodos' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * GetTodosUseCase.kt
 *
 * Purpose: Retrieves a filtered, sorted list of TodoItems for the authenticated user.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), ProductivityRepository, TodoItem, TodoFilter
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.ProductivityRepository
import com.aiassistant.domain.repository.TodoFilter
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case for observing the list of to-do items.
 *
 * THE AI_Assistant SHALL display a paginated list of [TodoItem] objects filterable by
 * completion status and due date (Requirement 19.1). Results are emitted from the local
 * Room database first, following the offline-first strategy.
 *
 * @param productivityRepository Repository providing the todo retrieval operation.
 */
class GetTodosUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Returns a [Flow] of [TodoItem] objects matching the given [filter].
     *
     * The flow emits a new list whenever the underlying Room database changes, allowing
     * the UI to remain reactive without polling.
     *
     * @param filter Criteria to narrow the result set. Defaults to [TodoFilter] with
     *               all options at their default values (show all, no date/priority filter).
     * @return Cold [Flow] emitting [ApiResult.Success] with the filtered list on each update.
     */
    operator fun invoke(filter: TodoFilter = TodoFilter()): Flow<ApiResult<List<TodoItem>>> =
        productivityRepository.getTodos(filter)
}
