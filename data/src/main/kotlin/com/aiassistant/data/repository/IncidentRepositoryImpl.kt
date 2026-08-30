/**
 * IncidentRepositoryImpl.kt — data module
 *
 * Implements [IncidentRepository] using [IncidentRemoteDataSource].
 * Network-only (no local cache) — dashboard data is always fresh.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.devops.IncidentRemoteDataSource
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.model.IncidentSeverity
import com.aiassistant.domain.model.IncidentStatus
import com.aiassistant.domain.repository.IncidentRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
class IncidentRepositoryImpl @Inject constructor(
    private val remote: IncidentRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : IncidentRepository {

    override suspend fun getIncidents(status: String?, severity: String?, limit: Int): ApiResult<List<Incident>> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remote.listIncidents(status = status, severity = severity, limit = limit)) {
                is ApiResult.Success -> ApiResult.Success(result.data.incidents.map { it.toDomain() })
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    override suspend fun getOpenCount(): ApiResult<Int> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

        when (val result = remote.listIncidents(status = "OPEN", limit = 1)) {
            is ApiResult.Success -> ApiResult.Success(result.data.openCount)
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }
}

// ─── Mapper ───────────────────────────────────────────────────────────────────

private fun com.aiassistant.data.remote.devops.IncidentDto.toDomain() = Incident(
    id = id,
    title = title,
    severity = IncidentSeverity.fromString(severity),
    status = IncidentStatus.fromString(status),
    detectionMethod = detectionMethod,
    triggeredBy = triggeredBy,
    metricValue = metricValue,
    thresholdValue = thresholdValue,
    aiSummary = aiSummary,
    aiConfidence = aiConfidence,
    aiRecommendedFix = aiRecommendedFix,
    rcaSummary = rcaSummary,
    rcaConfidence = rcaConfidence,
    eventCount = eventCount,
    detectedAt = detectedAt,
    resolvedAt = resolvedAt
)
