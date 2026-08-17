/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetCalendarEventsUseCase.kt
 * Purpose    : Encapsulates the 'GetCalendarEvents' business operation
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
 * GetCalendarEventsUseCase.kt
 *
 * Purpose: Retrieves calendar events within a specified date range for the authenticated user.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), ProductivityRepository, CalendarEvent, DateRange
 *
 * Requirements: 19.1
 */

package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.repository.DateRange
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case for observing calendar events within a date range.
 *
 * THE AI_Assistant SHALL display events from the local Room database and optionally merge
 * events from the Google Calendar MCP connector when connected (Requirement 19.1). The
 * flow emits from local Room first and may emit again when MCP-sourced events arrive.
 *
 * @param productivityRepository Repository providing the calendar event retrieval operation.
 */
class GetCalendarEventsUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Returns a [Flow] of [CalendarEvent] objects within the given [range].
     *
     * @param range The date/time window to query (epoch milliseconds, inclusive).
     * @return Cold [Flow] emitting [ApiResult.Success] with the events list on each update.
     */
    operator fun invoke(range: DateRange): Flow<ApiResult<List<CalendarEvent>>> =
        productivityRepository.getCalendarEvents(range)
}
