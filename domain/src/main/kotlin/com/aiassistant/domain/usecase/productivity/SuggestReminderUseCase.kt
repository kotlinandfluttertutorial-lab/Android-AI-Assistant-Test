/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SuggestReminderUseCase.kt
 * Purpose    : Encapsulates the 'SuggestReminder' business operation
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
 * File       : SuggestReminderUseCase.kt
 * Purpose    : Encapsulates the 'SuggestReminder' business operation
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
 * SuggestReminderUseCase.kt
 *
 * Purpose: Requests an AI-suggested Reminder from a natural language description.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, Reminder
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for requesting an AI-suggested reminder from a natural language prompt.
 *
 * THE AI_Orchestrator SHALL suggest reminders based on natural language input
 * (e.g. "Remind me to review the PR before tomorrow's standup") (Requirement 19.1).
 * The returned [Reminder] is a suggestion the user can confirm or modify before saving.
 *
 * @param productivityRepository Repository providing the AI reminder suggestion operation.
 */
class SuggestReminderUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Requests an AI-suggested [Reminder] from the given natural language [prompt].
     *
     * @param prompt Natural language description of what to be reminded about.
     *               Must not be blank.
     * @return [ApiResult.Success] with a pre-populated [Reminder] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if [prompt] is blank.
     */
    suspend operator fun invoke(prompt: String): ApiResult<Reminder> {
        if (prompt.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Prompt must not be blank.",
                    fields = mapOf(FIELD_PROMPT to "A non-empty prompt is required.")
                )
            )
        }

        return productivityRepository.suggestReminder(prompt.trim())
    }

    internal companion object {
        const val FIELD_PROMPT = "prompt"
    }
}
