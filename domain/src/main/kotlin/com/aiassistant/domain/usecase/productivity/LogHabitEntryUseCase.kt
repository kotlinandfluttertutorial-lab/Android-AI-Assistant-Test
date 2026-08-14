/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : LogHabitEntryUseCase.kt
 * Purpose    : Encapsulates the 'LogHabitEntry' business operation
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
 * File       : LogHabitEntryUseCase.kt
 * Purpose    : Encapsulates the 'LogHabitEntry' business operation
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
 * LogHabitEntryUseCase.kt
 *
 * Purpose: Records a habit completion event in the Productivity Suite's Habit Tracker.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, HabitEntry
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for logging a single habit completion entry.
 *
 * THE AI_Assistant SHALL record habit completion data as [HabitEntry] objects in the
 * local Room database and sync to the backend (Requirement 19.1). The collection of
 * entries provides the data for streak calculations and AI-generated insights.
 *
 * @param productivityRepository Repository providing the habit entry log operation.
 */
class LogHabitEntryUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Logs the given [entry] as a habit completion event.
     *
     * Validates that the entry's [HabitEntry.habitId] is not blank before delegating
     * to the repository.
     *
     * @param entry The [HabitEntry] to log. [HabitEntry.habitId] must not be blank.
     * @return [ApiResult.Success] with the persisted [HabitEntry] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if [HabitEntry.habitId]
     *         is blank.
     */
    suspend operator fun invoke(entry: HabitEntry): ApiResult<HabitEntry> {
        if (entry.habitId.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Habit ID must not be blank.",
                    fields = mapOf(FIELD_HABIT_ID to "A valid habit ID is required.")
                )
            )
        }

        return productivityRepository.logHabitEntry(entry)
    }

    internal companion object {
        const val FIELD_HABIT_ID = "habitId"
    }
}
