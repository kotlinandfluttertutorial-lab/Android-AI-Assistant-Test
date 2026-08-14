/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteTodoUseCase.kt
 * Purpose    : Encapsulates the 'DeleteTodo' business operation
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteTodoUseCase.kt
 * Purpose    : Encapsulates the 'DeleteTodo' business operation
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
 * DeleteTodoUseCase.kt
 *
 * Purpose: Permanently deletes a TodoItem from local storage and the backend.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), ProductivityRepository
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for deleting a to-do item.
 *
 * @param productivityRepository Repository providing the todo delete operation.
 */
class DeleteTodoUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Deletes the to-do item with the given [todoId].
     *
     * @param todoId The unique identifier of the to-do item to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(todoId: String): ApiResult<Unit> = productivityRepository.deleteTodo(todoId)
}
