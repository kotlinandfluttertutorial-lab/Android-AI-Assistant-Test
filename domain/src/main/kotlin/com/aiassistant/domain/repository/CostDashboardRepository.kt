/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CostDashboardRepository.kt
 * Purpose    : Domain contract for AI Cost Dashboard data access
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface (pure Kotlin, no framework deps)
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 * ============================================================
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.SpendingAlert

/**
 * Domain-layer contract for Cost Dashboard operations.
 *
 * Implemented in the data module by [CostDashboardRepositoryImpl].
 * Feature modules interact only with this interface, never with the implementation.
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 */
interface CostDashboardRepository {

    /**
     * Fetch the aggregated 90-day cost summary for the authenticated user.
     *
     * Returns token usage and estimated cost (USD) broken down by AI feature,
     * LLM provider, and calendar day for the last 90 days.
     *
     * @return [ApiResult.Success] with [CostSummary] on success.
     *         [ApiResult.Error] on backend error.
     *         [ApiResult.NetworkUnavailable] when offline.
     *
     * Requirements: 34.1, 34.2
     */
    suspend fun getCostSummary(): ApiResult<CostSummary>

    /**
     * Retrieve all spending alert thresholds for the authenticated user (max 3).
     *
     * @return [ApiResult.Success] with the list of [SpendingAlert] objects.
     *
     * Requirements: 34.4
     */
    suspend fun getAlerts(): ApiResult<List<SpendingAlert>>

    /**
     * Create a new spending alert threshold for the authenticated user.
     *
     * The backend enforces:
     * - threshold must be in [$0.01, $999.99]
     * - max 3 alerts per user (HTTP 422 on 4th attempt)
     *
     * @param thresholdUsd The alert threshold amount in USD.
     * @return [ApiResult.Success] with the created [SpendingAlert].
     *         [ApiResult.Error] with HTTP 422 message on 4th attempt.
     *
     * Requirements: 34.4
     */
    suspend fun createAlert(thresholdUsd: Double): ApiResult<SpendingAlert>

    /**
     * Delete the spending alert with the given [alertId].
     *
     * Only deletes alerts owned by the authenticated user.
     *
     * @param alertId UUID string of the alert to delete.
     * @return [ApiResult.Success] with [Unit] on successful deletion.
     *         [ApiResult.Error] if the alert was not found or not owned.
     *
     * Requirements: 34.4
     */
    suspend fun deleteAlert(alertId: String): ApiResult<Unit>
}
