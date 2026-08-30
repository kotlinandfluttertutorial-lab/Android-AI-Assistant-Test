/**
 * DevOpsRepositoryImpl.kt — data module
 *
 * Implements [DevOpsRepository] backed by [DevOpsRemoteDataSource].
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.devops.DevOpsRemoteDataSource
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult
import com.aiassistant.domain.repository.DevOpsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
class DevOpsRepositoryImpl @Inject constructor(
    private val remote: DevOpsRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : DevOpsRepository {

    override suspend fun chat(question: String, provider: String?): ApiResult<DevOpsChatResult> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remote.chat(question = question, provider = provider)) {
                is ApiResult.Success -> ApiResult.Success(
                    DevOpsChatResult(
                        sessionId = result.data.sessionId,
                        question = result.data.question,
                        answer = result.data.answer,
                        citations = result.data.citations,
                        toolsUsed = result.data.toolCalls.map { it.toolName },
                        roundsUsed = result.data.roundsUsed,
                        llmProvider = result.data.llmProvider
                    )
                )
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    override suspend fun analyseErrors(lookbackMinutes: Int, sessionId: String?): ApiResult<AiAnalysis> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

            when (val result = remote.analyseErrors(lookbackMinutes = lookbackMinutes, sessionId = sessionId)) {
                is ApiResult.Success -> ApiResult.Success(
                    AiAnalysis(
                        analysisId = result.data.analysisId,
                        severity = result.data.severity,
                        summary = result.data.summary,
                        likelyRootCause = result.data.likelyRootCause,
                        confidence = result.data.confidence,
                        recommendedFix = result.data.recommendedFix,
                        evidence = result.data.evidence,
                        possibleCauses = result.data.possibleCauses,
                        relatedDocs = result.data.relatedDocumentation,
                        lowConfidenceWarning = result.data.lowConfidenceWarning,
                        eventsAnalysed = result.data.eventsAnalysed
                    )
                )
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }
}
