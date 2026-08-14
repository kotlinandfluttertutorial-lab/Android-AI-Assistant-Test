/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CostDashboardApiService.kt
 * Purpose    : Retrofit service for /usage/ REST endpoints
 *
 * Architecture Layer : Data — Remote Data Source
 * Pattern Used       : Retrofit API Service Interface
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 * ============================================================
 */

package com.aiassistant.data.remote.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ─── Response DTOs ────────────────────────────────────────────────────────────

/**
 * Remote DTO for a single (feature, provider, calendar-day) aggregated cost row.
 */
@Serializable
data class DailyCostRowDto(
    @SerialName("feature") val feature: String,
    @SerialName("provider") val provider: String,
    @SerialName("day") val day: String,
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("cost_usd") val costUsd: Double
)

/**
 * Remote DTO for the full cost summary response (GET /usage/cost).
 */
@Serializable
data class CostSummaryDto(
    @SerialName("total_input_tokens") val totalInputTokens: Int,
    @SerialName("total_output_tokens") val totalOutputTokens: Int,
    @SerialName("total_cost_usd") val totalCostUsd: Double,
    @SerialName("rows") val rows: List<DailyCostRowDto>,
    @SerialName("window_days") val windowDays: Int = 90
)

/**
 * Remote DTO for a single spending alert (POST / GET /usage/alerts).
 */
@Serializable
data class SpendingAlertDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("threshold_usd") val thresholdUsd: Double,
    @SerialName("is_triggered") val isTriggered: Boolean,
    @SerialName("triggered_at") val triggeredAt: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

/**
 * Remote DTO for the list of spending alerts (GET /usage/alerts).
 */
@Serializable
data class SpendingAlertListDto(@SerialName("alerts") val alerts: List<SpendingAlertDto>)

/**
 * Remote DTO for the delete alert response (DELETE /usage/alerts/{id}).
 */
@Serializable
data class SpendingAlertDeleteDto(
    @SerialName("deleted") val deleted: Boolean,
    @SerialName("alert_id") val alertId: String
)

// ─── Request DTOs ─────────────────────────────────────────────────────────────

/**
 * Request body for POST /usage/alerts.
 */
@Serializable
data class CreateAlertRequest(@SerialName("threshold_usd") val thresholdUsd: Double)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service interface for the AI Cost Dashboard endpoints.
 *
 * All endpoints require a valid JWT in the Authorization header (handled by
 * the core-network [AuthInterceptor]).
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 */
interface CostDashboardApiService {

    /**
     * Fetch the aggregated 90-day cost summary for the authenticated user.
     *
     * GET /usage/cost
     *
     * @param userId Optional query parameter. The backend returns HTTP 403 if this
     *               does not match the authenticated user's ID. Callers should not
     *               supply this parameter for normal use.
     *
     * Requirements: 34.1, 34.2, 34.7
     */
    @GET("usage/cost")
    suspend fun getCostSummary(@Query("user_id") userId: String? = null): CostSummaryDto

    /**
     * List all spending alert thresholds for the authenticated user.
     *
     * GET /usage/alerts
     *
     * Requirements: 34.4
     */
    @GET("usage/alerts")
    suspend fun getAlerts(): SpendingAlertListDto

    /**
     * Create a new spending alert threshold.
     *
     * POST /usage/alerts
     *
     * @param body Request body containing the threshold amount in USD.
     *
     * Requirements: 34.4
     */
    @POST("usage/alerts")
    suspend fun createAlert(@Body body: CreateAlertRequest): SpendingAlertDto

    /**
     * Delete the spending alert identified by [alertId].
     *
     * DELETE /usage/alerts/{alert_id}
     *
     * @param alertId The UUID string of the alert to delete.
     *
     * Requirements: 34.4
     */
    @DELETE("usage/alerts/{alert_id}")
    suspend fun deleteAlert(@Path("alert_id") alertId: String): SpendingAlertDeleteDto
}
