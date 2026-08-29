/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-database
 * File       : QueryRoutingLogDao.kt
 * Purpose    : Room DAO for the query_routing_log table.  Records every
 *              routing decision made by QueryRouter so engineers can audit
 *              why a query was handled on-device vs in the cloud and whether
 *              any automatic fallback occurred.
 *
 * Architecture Layer : Core-Database — data access layer.
 *                      Written to by QueryRoutingLogRepository (data module)
 *                      on every RouteQueryUseCase invocation.
 *                      Read by BenchmarkScreen and debug overlays.
 *
 * Dependencies       : Room, QueryRoutingLogEntity
 *
 * Design Decision    : deleteOlderThan() enforces a 30-day rolling retention
 *                      window as specified in the task.  The cutoff is passed
 *                      in as a Long (epoch millis) so the caller controls the
 *                      clock — making it easy to unit-test without mocking
 *                      System.currentTimeMillis().
 *                      getRecentLogs() uses LIMIT so the query stays fast even
 *                      if retention cleanup is delayed; the UI never needs more
 *                      than a few hundred rows.
 * ============================================================
 */
package com.aiassistant.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiassistant.core.database.entity.QueryRoutingLogEntity

@Dao
interface QueryRoutingLogDao {

    /**
     * Inserts a routing decision log entry.  IGNORE strategy prevents
     * duplicate primary-key errors in the unlikely event that two coroutines
     * race to log the same UUID — the first writer wins.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: QueryRoutingLogEntity)

    /**
     * Returns up to [limit] recent routing decisions for [userId], ordered
     * newest-first.  Used by BenchmarkScreen to display the last N routing
     * decisions and their bitmask breakdowns.
     *
     * @param userId The authenticated user whose logs to retrieve.
     * @param limit  Maximum number of rows to return (e.g. 50 for a debug list).
     */
    @Query(
        """
        SELECT * FROM query_routing_log
        WHERE userId = :userId
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentLogs(userId: String, limit: Int): List<QueryRoutingLogEntity>

    /**
     * Deletes all log entries older than [cutoffMs] (epoch millis).
     * Called by a periodic WorkManager job to enforce the 30-day retention
     * policy:
     *
     *   val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
     *   queryRoutingLogDao.deleteOlderThan(cutoff)
     *
     * @param cutoffMs Rows with timestamp < cutoffMs are deleted.
     */
    @Query("DELETE FROM query_routing_log WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
