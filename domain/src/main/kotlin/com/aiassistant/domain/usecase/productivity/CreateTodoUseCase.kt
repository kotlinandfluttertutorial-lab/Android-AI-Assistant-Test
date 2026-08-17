/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreateTodoUseCase.kt
 * Purpose    : Encapsulates the 'CreateTodo' business operation
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
 * CreateTodoUseCase.kt
 *
 * Purpose: Creates a new TodoItem in the Productivity Suite's To-Do List.
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
 * Use case for creating a new to-do item.
 *
 * THE AI_Assistant SHALL persist todo items locally and sync to the backend when connected,
 * following a local-first strategy (Requirement 19.1).
 *
 * @param productivityRepository Repository providing the todo creation operation.
 */
class CreateTodoUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Creates the given [todo] item.
     *
     * @param todo The [TodoItem] to create. [TodoItem.title] must not be blank.
     * @return [ApiResult.Success] with the persisted [TodoItem] on success,
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

        return productivityRepository.createTodo(todo)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
    }
}
