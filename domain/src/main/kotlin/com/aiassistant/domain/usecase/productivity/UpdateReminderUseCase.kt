/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : UpdateReminderUseCase.kt
 * Purpose    : Encapsulates the 'UpdateReminder' business operation
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
 * UpdateReminderUseCase.kt
 *
 * Purpose: Updates an existing Reminder and queues an AlarmManager reschedule.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, Reminder
 *
 * Requirements: 16.3, 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for updating an existing reminder.
 *
 * The data layer is responsible for rescheduling the AlarmManager alarm after a
 * successful update (Requirement 16.3).
 *
 * @param productivityRepository Repository providing the reminder update operation.
 */
class UpdateReminderUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Updates the given [reminder].
     *
     * @param reminder The [Reminder] with updated fields. [Reminder.title] must not be blank.
     * @return [ApiResult.Success] with the updated [Reminder] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the title is blank.
     */
    suspend operator fun invoke(reminder: Reminder): ApiResult<Reminder> {
        if (reminder.title.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Reminder title must not be blank.",
                    fields = mapOf(FIELD_TITLE to "Title is required.")
                )
            )
        }

        return productivityRepository.updateReminder(reminder)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
    }
}
