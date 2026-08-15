/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GenerateTodosFromPromptUseCase.kt
 * Purpose    : Encapsulates the 'GenerateTodosFromPrompt' business operation
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
 * GenerateTodosFromPromptUseCase.kt
 *
 * Purpose: Requests AI-generated TodoItems from a natural language description.
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
 * Use case for generating a list of to-do items from a natural language prompt.
 *
 * THE AI_Orchestrator SHALL generate a list of [TodoItem] objects from a natural language
 * description (e.g. "Plan a product launch") (Requirement 19.1). The returned items are
 * suggestions the user can accept, modify, or discard before saving.
 *
 * @param productivityRepository Repository providing the AI todo generation operation.
 */
class GenerateTodosFromPromptUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Generates a list of suggested [TodoItem] objects from the given [prompt].
     *
     * @param prompt Natural language description of the tasks to generate.
     *               Must not be blank.
     * @return [ApiResult.Success] with the list of suggested [TodoItem] objects on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if [prompt] is blank.
     */
    suspend operator fun invoke(prompt: String): ApiResult<List<TodoItem>> {
        if (prompt.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Prompt must not be blank.",
                    fields = mapOf(FIELD_PROMPT to "A non-empty prompt is required.")
                )
            )
        }

        return productivityRepository.generateTodosFromPrompt(prompt.trim())
    }

    internal companion object {
        const val FIELD_PROMPT = "prompt"
    }
}
