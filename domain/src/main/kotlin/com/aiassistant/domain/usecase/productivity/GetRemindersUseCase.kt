/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetRemindersUseCase.kt
 * Purpose    : Encapsulates the 'GetReminders' business operation
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
 * GetRemindersUseCase.kt
 *
 * Purpose: Retrieves all reminders for the authenticated user, sorted by trigger time.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), ProductivityRepository, Reminder
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case for observing the list of reminders.
 *
 * THE AI_Assistant SHALL display a list of upcoming [Reminder] objects sorted by
 * trigger time (Requirement 19.1). Results are emitted from the local Room database
 * first, following the offline-first strategy.
 *
 * @param productivityRepository Repository providing the reminder retrieval operation.
 */
class GetRemindersUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Returns a [Flow] of all [Reminder] objects for the authenticated user, sorted
     * by [Reminder.triggerTime] ascending.
     *
     * The flow emits a new list whenever the underlying Room database changes.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full sorted reminder list.
     */
    operator fun invoke(): Flow<ApiResult<List<Reminder>>> = productivityRepository.getReminders()
}
