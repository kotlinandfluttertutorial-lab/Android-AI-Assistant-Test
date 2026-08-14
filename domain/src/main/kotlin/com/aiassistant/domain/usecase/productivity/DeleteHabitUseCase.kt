/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteHabitUseCase.kt
 * Purpose    : Encapsulates the 'DeleteHabit' business operation
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
 * File       : DeleteHabitUseCase.kt
 * Purpose    : Encapsulates the 'DeleteHabit' business operation
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
 * DeleteHabitUseCase.kt
 *
 * Purpose: Permanently deletes a HabitDefinition and all its associated HabitEntry
 *          records from local storage and the backend.
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
 * Use case for deleting a habit definition and all its completion history.
 *
 * THE AI_Assistant SHALL permanently delete the [HabitDefinition] and all associated
 * [HabitEntry] records from local Room (via cascade delete) and the backend
 * (Requirement 19.1).
 *
 * @param productivityRepository Repository providing the habit delete operation.
 */
class DeleteHabitUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Deletes the habit with the given [habitId] and all its completion entries.
     *
     * @param habitId The unique identifier of the habit to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(habitId: String): ApiResult<Unit> = productivityRepository.deleteHabit(habitId)
}
