/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreateHabitUseCase.kt
 * Purpose    : Encapsulates the 'CreateHabit' business operation
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
 * File       : CreateHabitUseCase.kt
 * Purpose    : Encapsulates the 'CreateHabit' business operation
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
 * CreateHabitUseCase.kt
 *
 * Purpose: Creates a new HabitDefinition in the Productivity Suite's Habit Tracker.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, HabitDefinition
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for creating a new habit definition.
 *
 * THE AI_Assistant SHALL allow the user to define habits with name, description,
 * recurrence (daily/weekly), and target frequency (Requirement 19.1).
 *
 * @param productivityRepository Repository providing the habit creation operation.
 */
class CreateHabitUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Creates the given [habit] definition.
     *
     * Validates that the habit name is not blank and that [HabitDefinition.targetFrequency]
     * is at least 1 before delegating to the repository.
     *
     * @param habit The [HabitDefinition] to create. [HabitDefinition.name] must not be blank.
     *              [HabitDefinition.targetFrequency] must be â‰¥ 1.
     * @return [ApiResult.Success] with the persisted [HabitDefinition] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if validation fails.
     */
    suspend operator fun invoke(habit: HabitDefinition): ApiResult<HabitDefinition> {
        if (habit.name.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Habit name must not be blank.",
                    fields = mapOf(FIELD_NAME to "Name is required.")
                )
            )
        }

        if (habit.targetFrequency < 1) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Target frequency must be at least 1.",
                    fields = mapOf(FIELD_FREQUENCY to "Target frequency must be a positive integer.")
                )
            )
        }

        return productivityRepository.createHabit(habit)
    }

    internal companion object {
        const val FIELD_NAME = "name"
        const val FIELD_FREQUENCY = "targetFrequency"
    }
}
