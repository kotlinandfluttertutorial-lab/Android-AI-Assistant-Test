/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : QueryRoutingLogRepositoryImpl.kt
 * Purpose    : Implements QueryRoutingLogRepository wrapping QueryRoutingLogDao.
 *
 * Architecture Layer : Data — repository implementation.
 *
 * Requirements: 36.10
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.QueryRoutingLogDao
import com.aiassistant.core.database.entity.QueryRoutingLogEntity
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.model.OnDevicePathPreference
import com.aiassistant.domain.model.OnDeviceRoutingDecision
import com.aiassistant.domain.repository.QueryRoutingLogRepository
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueryRoutingLogRepositoryImpl @Inject constructor(
    private val dao: QueryRoutingLogDao,
    private val dispatchers: DispatcherProvider,
) : QueryRoutingLogRepository {

    override suspend fun logDecision(
        userId: String,
        decision: OnDeviceRoutingDecision,
    ): ApiResult<Unit> = withContext(dispatchers.io) {
        try {
            dao.insert(
                QueryRoutingLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    timestamp = System.currentTimeMillis(),
                    selectedPath = decision.path.name,
                    capabilityBitmask = decision.capabilityBitmask,
                    userOverride = null, // user override is embedded in the reason string
                    fallbackOccurred = decision.fallbackOccurred,
                    reason = decision.reason,
                )
            )
            ApiResult.Success(Unit)
        } catch (e: Exception) {
            ApiResult.Error(DomainError.ServerError("Log write failed: ${e.message}", 500))
        }
    }

    override suspend fun getRecentDecisions(
        userId: String,
        limit: Int,
    ): ApiResult<List<OnDeviceRoutingDecision>> = withContext(dispatchers.io) {
        try {
            val entities = dao.getRecentLogs(userId, limit)
            ApiResult.Success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            ApiResult.Error(DomainError.ServerError("Failed to load routing logs: ${e.message}", 500))
        }
    }

    override suspend fun deleteOlderThan(cutoffMs: Long): ApiResult<Unit> =
        withContext(dispatchers.io) {
            try {
                dao.deleteOlderThan(cutoffMs)
                ApiResult.Success(Unit)
            } catch (e: Exception) {
                ApiResult.Error(DomainError.ServerError("Failed to prune routing logs: ${e.message}", 500))
            }
        }

    private fun QueryRoutingLogEntity.toDomain() = OnDeviceRoutingDecision(
        path = if (selectedPath == "ON_DEVICE") OnDeviceInferencePath.ON_DEVICE
               else OnDeviceInferencePath.CLOUD,
        capabilityBitmask = capabilityBitmask,
        reason = reason,
        fallbackOccurred = fallbackOccurred,
    )
}
