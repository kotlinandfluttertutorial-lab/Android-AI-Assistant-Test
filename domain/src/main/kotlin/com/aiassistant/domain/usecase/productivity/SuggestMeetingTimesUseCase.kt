/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SuggestMeetingTimesUseCase.kt
 * Purpose    : Encapsulates the 'SuggestMeetingTimes' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
 *
 * Dependencies: ProductivityRepository, core-common (ApiResult, DomainError)
 * Requirements: 8.2
 * ============================================================
 */
package com.aiassistant.domain.usecase.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ProductivityRepository
import javax.inject.Inject

/**
 * Use case for requesting AI-generated optimal meeting time suggestions.
 *
 * The AI Orchestrator analyses the user's calendar context described in [prompt]
 * and returns a list of ISO 8601 datetime strings representing suggested start times
 * (Requirement 8.2).
 *
 * @param productivityRepository Repository providing the meeting time suggestion operation.
 */
class SuggestMeetingTimesUseCase @Inject constructor(
    private val productivityRepository: ProductivityRepository
) {

    /**
     * Requests AI meeting time suggestions.
     *
     * Validates that [prompt] is not blank before delegating. An empty prompt would
     * produce a meaningless AI result and waste a backend round-trip.
     *
     * @param prompt          Natural language description of the meeting requirements
     *                        (e.g. "30-minute sync with Alice about the Q3 roadmap").
     *                        Must not be blank.
     * @param durationMinutes Duration of the meeting in minutes. Must be between 1 and 1440
     *                        (matching the backend constraint). Defaults to 60.
     * @return [ApiResult.Success] with a list of ISO 8601 datetime strings on success.
     *         [ApiResult.Error] with [DomainError.ValidationError] if [prompt] is blank
     *         or [durationMinutes] is out of range.
     *         [ApiResult.NetworkUnavailable] when the device is offline.
     */
    suspend operator fun invoke(
        prompt: String,
        durationMinutes: Int = 60
    ): ApiResult<List<String>> {
        if (prompt.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Meeting suggestion prompt must not be blank.",
                    fields = mapOf(FIELD_PROMPT to "A description of the meeting is required.")
                )
            )
        }
        if (durationMinutes < 1 || durationMinutes > 1440) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Meeting duration must be between 1 and 1440 minutes.",
                    fields = mapOf(FIELD_DURATION to "Duration must be between 1 and 1440 minutes.")
                )
            )
        }
        return productivityRepository.suggestMeetingTimes(prompt, durationMinutes)
    }

    internal companion object {
        const val FIELD_PROMPT = "prompt"
        const val FIELD_DURATION = "durationMinutes"
    }
}
