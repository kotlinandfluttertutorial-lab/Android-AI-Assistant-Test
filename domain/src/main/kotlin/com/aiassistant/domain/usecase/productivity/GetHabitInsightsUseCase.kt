/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetHabitInsightsUseCase.kt
 * Purpose    : Encapsulates the 'GetHabitInsights' business operation
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
 * GetHabitInsightsUseCase.kt
 *
 * Purpose: Requests AI-generated insights about a habit's completion patterns from the
 *          AI Orchestrator.
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
 * Use case for fetching AI-generated habit insights.
 *
 * THE AI_Orchestrator SHALL analyse a habit's [HabitEntry] history and return a natural
 * language summary covering completion rate, best/worst days, and streak predictions
 * (Requirement 19.1). Insights are generated on-demand and not cached locally.
 *
 * @param productivityRepository Repository providing the AI insight generation operation.
 */
class GetHabitInsightsUseCase @Inject constructor(private val productivityRepository: ProductivityRepository) {

    /**
     * Requests AI-generated insights for the habit with the given [habitId].
     *
     * @param habitId The unique identifier of the [HabitDefinition] to analyse.
     * @return [ApiResult.Success] with the insights text on success.
     */
    suspend operator fun invoke(habitId: String): ApiResult<String> = productivityRepository.getHabitInsights(habitId)
}
