/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : QueryMetricsRepository.kt
 * Purpose    : Domain contract for recording on-device query performance metrics
 *              (TTFT, tokens/sec, RAM peak) after each successful OnDeviceQueryUseCase
 *              execution.  Metrics are displayed in BenchmarkScreen.
 *
 * Architecture Layer : Domain — interface only, zero Android dependencies.
 *
 * Dependencies       : core-common (ApiResult), domain model
 *
 * Requirements: 32.10
 * ============================================================
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult

/**
 * A single on-device query performance sample.
 *
 * @param userId           Owner of the query.
 * @param timestampMs      Epoch millis when the query completed.
 * @param ttftMs           Time-to-first-token in milliseconds.
 * @param tokensGenerated  Total tokens produced.
 * @param generationTimeMs Total generation wall-clock time.
 * @param peakRamMb        Peak RAM consumed during the query.
 * @param accelerator      Accelerator used (e.g. "CPU", "GPU", "NPU").
 */
data class QueryMetricsSample(
    val userId: String,
    val timestampMs: Long,
    val ttftMs: Long,
    val tokensGenerated: Int,
    val generationTimeMs: Long,
    val peakRamMb: Int,
    val accelerator: String
)

/**
 * Persistence contract for on-device query performance metrics.
 */
interface QueryMetricsRepository {

    /**
     * Records one [QueryMetricsSample] for future benchmark reporting.
     *
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun recordSample(sample: QueryMetricsSample): ApiResult<Unit>

    /**
     * Returns the last [limit] samples for [userId], newest-first.
     */
    suspend fun getRecentSamples(userId: String, limit: Int): ApiResult<List<QueryMetricsSample>>
}
