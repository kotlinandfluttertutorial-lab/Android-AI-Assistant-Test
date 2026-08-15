/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : UpdateTodoUseCase.kt
 * Purpose    : Encapsulates the 'UpdateTodo' business operation
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
 * UpdateTodoUseCase.kt
 *
 * Purpose: Updates an existing TodoItem in the Productivity Suite's To-Do List.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, TodoItem
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for updating an existing to-do item.
 *
 * @param productivityRepository Repository providing the todo update operation.
 */
class UpdateTodoUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Updates the given [todo] item.
     *
     * @param todo The [TodoItem] with updated fields. [TodoItem.title] must not be blank.
     * @return [ApiResult.Success] with the updated [TodoItem] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the title is blank.
     */
    suspend operator fun invoke(todo: TodoItem): ApiResult<TodoItem> {
        if (todo.title.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Todo title must not be blank.",
                    fields = mapOf(FIELD_TITLE to "Title is required.")
                )
            )
        }

        return productivityRepository.updateTodo(todo)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
    }
}
