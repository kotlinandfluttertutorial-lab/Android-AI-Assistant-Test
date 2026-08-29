/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : QueryMetricsRepositoryImpl.kt
 * Purpose    : In-memory implementation of QueryMetricsRepository.
 *              Stores up to MAX_SAMPLES performance samples in a circular
 *              buffer — no Room entity needed for this lightweight data.
 *
 * Architecture Layer : Data — repository implementation.
 *
 * Design Decision    : Metrics are ephemeral (session-scoped) — they are used
 *                      only by BenchmarkScreen which is opened on demand.  A
 *                      full Room table would add migration overhead for data
 *                      that does not need to survive app restarts.  If long-term
 *                      metrics history becomes a requirement, this can be swapped
 *                      for a Room-backed impl behind the same interface.
 *
 * Requirements: 32.10
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.repository.QueryMetricsSample
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SAMPLES = 200

@Singleton
class QueryMetricsRepositoryImpl @Inject constructor() : QueryMetricsRepository {

    private val samples = CopyOnWriteArrayList<QueryMetricsSample>()

    override suspend fun recordSample(sample: QueryMetricsSample): ApiResult<Unit> {
        if (samples.size >= MAX_SAMPLES) samples.removeAt(0)
        samples.add(sample)
        return ApiResult.Success(Unit)
    }

    override suspend fun getRecentSamples(
        userId: String,
        limit: Int,
    ): ApiResult<List<QueryMetricsSample>> {
        val result = samples
            .filter { it.userId == userId }
            .takeLast(limit)
            .reversed()
        return ApiResult.Success(result)
    }
}
