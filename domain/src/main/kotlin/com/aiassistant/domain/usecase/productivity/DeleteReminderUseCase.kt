/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteReminderUseCase.kt
 * Purpose    : Encapsulates the 'DeleteReminder' business operation
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
 * DeleteReminderUseCase.kt
 *
 * Purpose: Permanently deletes a Reminder from local storage and cancels any scheduled
 *          AlarmManager alarm.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), ProductivityRepository
 *
 * Requirements: 16.3, 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for deleting a reminder.
 *
 * The data layer cancels the associated AlarmManager alarm before removing the record
 * from local Room and the backend (Requirement 16.3).
 *
 * @param productivityRepository Repository providing the reminder delete operation.
 */
class DeleteReminderUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Deletes the reminder with the given [reminderId].
     *
     * @param reminderId The unique identifier of the reminder to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(reminderId: String): ApiResult<Unit> = productivityRepository.deleteReminder(reminderId)
}
