/**
 * IncidentRemoteDataSource.kt — data module
 *
 * Wraps [IncidentApiService] with error handling so the repository
 * receives [ApiResult] rather than raw exceptions.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.remote.devops

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IncidentRemoteDataSource @Inject constructor(private val api: IncidentApiService) {
    suspend fun listIncidents(
        status: String? = null,
        severity: String? = null,
        limit: Int = 20
    ): ApiResult<IncidentListResponse> = safeCall {
        api.listIncidents(status = status, severity = severity, limit = limit)
    }

    suspend fun getIncident(id: String): ApiResult<IncidentDto> = safeCall {
        api.getIncident(id)
    }
}

// ─── DevOps remote data source ────────────────────────────────────────────────

@Singleton
class DevOpsRemoteDataSource @Inject constructor(private val api: DevOpsApiService) {
    suspend fun chat(question: String, provider: String? = null): ApiResult<DevOpsChatResponse> =
        safeCall { api.chat(DevOpsChatRequest(question = question, provider = provider)) }

    suspend fun analyseErrors(lookbackMinutes: Int = 30, sessionId: String? = null): ApiResult<ErrorAnalysisResponse> =
        safeCall {
            api.analyseErrors(ErrorAnalysisRequest(lookbackMinutes = lookbackMinutes, sessionId = sessionId))
        }
}

// ─── Shared helper ────────────────────────────────────────────────────────────

private inline fun <T> safeCall(block: () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: retrofit2.HttpException) {
    ApiResult.Error(DomainError.ServerError(httpStatusCode = e.code(), message = e.message() ?: "HTTP ${e.code()}"))
} catch (e: java.io.IOException) {
    ApiResult.NetworkUnavailable
} catch (e: Exception) {
    ApiResult.Error(DomainError.NetworkError(message = e.message ?: "Unknown error", cause = e))
}
