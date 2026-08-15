/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteCalendarEventUseCase.kt
 * Purpose    : Encapsulates the 'DeleteCalendarEvent' business operation
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
 * DeleteCalendarEventUseCase.kt
 *
 * Purpose: Permanently deletes a CalendarEvent from local storage and the backend.
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
 * Use case for deleting a calendar event.
 *
 * THE AI_Assistant SHALL permanently delete the event from local Room and the backend
 * (Requirement 19.1).
 *
 * @param productivityRepository Repository providing the calendar event delete operation.
 */
class DeleteCalendarEventUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Deletes the calendar event with the given [eventId].
     *
     * @param eventId The unique identifier of the calendar event to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend operator fun invoke(eventId: String): ApiResult<Unit> = productivityRepository.deleteCalendarEvent(eventId)
}
