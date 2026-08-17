/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreateReminderUseCase.kt
 * Purpose    : Encapsulates the 'CreateReminder' business operation
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
 * CreateReminderUseCase.kt
 *
 * Purpose: Creates a new Reminder in the Productivity Suite and queues AlarmManager
 *          scheduling for local notification delivery.
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
 * Use case for creating a new reminder.
 *
 * THE AI_Assistant SHALL deliver local notifications via NotificationManager and
 * AlarmManager at the scheduled trigger time (Requirement 16.3). This use case validates
 * the reminder and delegates to the repository; the data layer handles AlarmManager
 * scheduling.
 *
 * @param productivityRepository Repository providing the reminder creation operation.
 */
class CreateReminderUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Creates the given [reminder].
     *
     * Validates that the reminder title is not blank before delegating to the repository.
     *
     * @param reminder The [Reminder] to create. [Reminder.title] must not be blank.
     * @return [ApiResult.Success] with the persisted [Reminder] on success,
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

        return productivityRepository.createReminder(reminder)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
    }
}
