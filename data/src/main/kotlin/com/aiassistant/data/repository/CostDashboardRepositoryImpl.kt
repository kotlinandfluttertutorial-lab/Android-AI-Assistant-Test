/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CostDashboardRepositoryImpl.kt
 * Purpose    : Implements CostDashboardRepository using Retrofit remote source
 *
 * Architecture Layer : Data — Repository Implementation
 * Pattern Used       : Repository (remote-only, no local cache needed for cost data)
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 * ============================================================
 */

package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.remote.usage.CostDashboardApiService
import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.DailyCostRow
import com.aiassistant.domain.model.SpendingAlert
import com.aiassistant.domain.repository.CostDashboardRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Production implementation of [CostDashboardRepository].
 *
 * All data comes exclusively from the remote backend (cost data is not cached
 * locally — it is computed server-side with aggregations over 90 days of records).
 *
 * @param apiService      Retrofit service for /usage/ endpoints.
 * @param dispatchers     Injectable dispatcher provider for IO context.
 *
 * Requirements: 34.1, 34.2, 34.4, 34.7
 */
@Singleton
class CostDashboardRepositoryImpl @Inject constructor(
    private val apiService: CostDashboardApiService,
    private val dispatchers: DispatcherProvider
) : CostDashboardRepository {

    /**
     * Fetches the 90-day aggregated cost summary from the backend.
     *
     * Requirements: 34.1, 34.2
     */
    override suspend fun getCostSummary(): ApiResult<CostSummary> = withContext(dispatchers.io) {
        safeApiCall {
            val dto = apiService.getCostSummary()
            CostSummary(
                totalInputTokens = dto.totalInputTokens,
                totalOutputTokens = dto.totalOutputTokens,
                totalCostUsd = dto.totalCostUsd,
                rows = dto.rows.map { row ->
                    DailyCostRow(
                        feature = row.feature,
                        provider = row.provider,
                        day = row.day,
                        inputTokens = row.inputTokens,
                        outputTokens = row.outputTokens,
                        costUsd = row.costUsd
                    )
                },
                windowDays = dto.windowDays
            )
        }
    }

    /**
     * Lists all spending alerts for the authenticated user.
     *
     * Requirements: 34.4
     */
    override suspend fun getAlerts(): ApiResult<List<SpendingAlert>> = withContext(dispatchers.io) {
        safeApiCall {
            val dto = apiService.getAlerts()
            dto.alerts.map { alert ->
                SpendingAlert(
                    id = alert.id,
                    userId = alert.userId,
                    thresholdUsd = alert.thresholdUsd,
                    isTriggered = alert.isTriggered,
                    triggeredAt = alert.triggeredAt,
                    dismissedAt = alert.dismissedAt,
                    createdAt = alert.createdAt
                )
            }
        }
    }

    /**
     * Creates a new spending alert threshold.
     *
     * Requirements: 34.4
     */
    override suspend fun createAlert(thresholdUsd: Double): ApiResult<SpendingAlert> = withContext(dispatchers.io) {
        safeApiCall {
            val dto = apiService.createAlert(
                com.aiassistant.data.remote.usage.CreateAlertRequest(thresholdUsd = thresholdUsd)
            )
            SpendingAlert(
                id = dto.id,
                userId = dto.userId,
                thresholdUsd = dto.thresholdUsd,
                isTriggered = dto.isTriggered,
                triggeredAt = dto.triggeredAt,
                dismissedAt = dto.dismissedAt,
                createdAt = dto.createdAt
            )
        }
    }

    /**
     * Deletes the spending alert with the given [alertId].
     *
     * Requirements: 34.4
     */
    override suspend fun deleteAlert(alertId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall {
            apiService.deleteAlert(alertId)
            Unit
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Wraps a suspending Retrofit call and maps any exception to a typed [ApiResult].
     */
    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(
            when (e.code()) {
                401 -> DomainError.Unauthorized(cause = e)
                403 -> DomainError.Forbidden(cause = e)
                422 -> DomainError.ValidationError(
                    message = parseErrorBody(e) ?: "Unprocessable entity (HTTP 422).",
                    cause = e
                )
                in 400..499 -> DomainError.ValidationError(
                    message = "Invalid request (HTTP ${e.code()}).",
                    cause = e
                )
                in 500..599 -> DomainError.ServerError(
                    httpStatusCode = e.code(),
                    cause = e
                )
                else -> DomainError.NetworkError(
                    message = "Unexpected HTTP ${e.code()}.",
                    cause = e
                )
            }
        )
    } catch (e: IOException) {
        ApiResult.NetworkUnavailable
    }

    /**
     * Attempt to extract a human-readable detail message from an [HttpException]'s error body.
     */
    private fun parseErrorBody(e: HttpException): String? =
        runCatching { e.response()?.errorBody()?.string() }.getOrNull()
}
