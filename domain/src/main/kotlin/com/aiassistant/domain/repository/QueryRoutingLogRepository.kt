/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : QueryRoutingLogRepository.kt
 * Purpose    : Domain contract for persisting and querying routing decision
 *              audit log entries.  Implemented in the data module wrapping
 *              QueryRoutingLogDao.
 *
 * Architecture Layer : Domain — interface only, zero Android dependencies.
 *
 * Dependencies       : core-common (ApiResult), domain model (OnDeviceRoutingDecision)
 *
 * Requirements: 36.10
 * ============================================================
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.OnDeviceRoutingDecision

/**
 * Persistence contract for query routing audit log entries.
 */
interface QueryRoutingLogRepository {

    /**
     * Persists one [OnDeviceRoutingDecision] log entry for audit and debugging.
     *
     * @param userId   Owner of the query session.
     * @param decision The routing decision to persist.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun logDecision(userId: String, decision: OnDeviceRoutingDecision): ApiResult<Unit>

    /**
     * Returns up to [limit] recent routing decisions for [userId], newest-first.
     * Used by BenchmarkScreen to show the last N decisions and their bitmask breakdown.
     *
     * @param userId Owner whose log to query.
     * @param limit  Maximum rows to return (e.g. 50).
     */
    suspend fun getRecentDecisions(userId: String, limit: Int): ApiResult<List<OnDeviceRoutingDecision>>

    /**
     * Deletes all log entries older than [cutoffMs] (epoch millis).
     * Called periodically to enforce the 30-day retention window.
     */
    suspend fun deleteOlderThan(cutoffMs: Long): ApiResult<Unit>
}
