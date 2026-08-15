/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreateCalendarEventUseCase.kt
 * Purpose    : Encapsulates the 'CreateCalendarEvent' business operation
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
 * CreateCalendarEventUseCase.kt
 *
 * Purpose: Creates a new CalendarEvent in the Productivity Suite's Calendar feature.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ProductivityRepository, CalendarEvent
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for creating a new calendar event.
 *
 * THE AI_Assistant SHALL persist calendar events locally and sync to the backend when
 * connected, following a local-first strategy (Requirement 19.1).
 *
 * @param productivityRepository Repository providing the calendar event creation operation.
 */
class CreateCalendarEventUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Creates the given [event].
     *
     * Validates that the event title is not blank and that [CalendarEvent.endTime] is
     * not before [CalendarEvent.startTime] for non-all-day events.
     *
     * @param event The [CalendarEvent] to create. [CalendarEvent.title] must not be blank.
     *              [CalendarEvent.endTime] must be â‰¥ [CalendarEvent.startTime].
     * @return [ApiResult.Success] with the persisted [CalendarEvent] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if validation fails.
     */
    suspend operator fun invoke(event: CalendarEvent): ApiResult<CalendarEvent> {
        if (event.title.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Event title must not be blank.",
                    fields = mapOf(FIELD_TITLE to "Title is required.")
                )
            )
        }

        if (!event.isAllDay && event.endTime < event.startTime) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Event end time must not be before the start time.",
                    fields = mapOf(FIELD_END_TIME to "End time must be after or equal to start time.")
                )
            )
        }

        return productivityRepository.createCalendarEvent(event)
    }

    internal companion object {
        const val FIELD_TITLE = "title"
        const val FIELD_END_TIME = "endTime"
    }
}
