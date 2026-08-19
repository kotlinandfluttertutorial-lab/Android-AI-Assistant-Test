/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeRepositoryImpl.kt
 * Purpose    : Production implementation of CodeRepository. Delegates code analysis
 *              requests to the AI Orchestrator backend via CodeRemoteDataSource.
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation
 *
 * Dependencies: CodeRemoteDataSource, ConnectivityObserver (core-network)
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 * ============================================================
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.code.CodeRemoteDataSource
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.repository.CodeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CodeRepository].
 *
 * Routes code analysis requests to `POST /code/analyze` on the AI Orchestrator backend.
 *
 * **Backend status:** The `/code/analyze` endpoint is not yet implemented on the backend.
 * When offline or while the endpoint is unavailable, this repository returns a clear
 * [ApiResult.Error] with [DomainError.ServerError] (HTTP 501) so the UI can display
 * a meaningful "feature coming soon" message rather than a generic network error.
 *
 * The URL, DTOs, and wiring are complete on the Android side; no changes will be needed
 * once the backend team adds the endpoint.
 *
 * @param remoteDataSource     Retrofit-backed data source for the code analysis endpoint.
 * @param connectivityObserver Synchronous connectivity state snapshot.
 */
@Singleton
class CodeRepositoryImpl @Inject constructor(
    private val remoteDataSource: CodeRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver
) : CodeRepository {

    /**
     * Submits code for AI analysis (Requirements 12.1–12.4, 12.6).
     *
     * - **Offline**: returns [ApiResult.NetworkUnavailable] immediately.
     * - **Online, endpoint not yet deployed**: the Retrofit call will receive an HTTP
     *   404/501 from the backend, which [CodeRemoteDataSource] maps to [ApiResult.Error].
     *   The [CodeViewModel] surfaces this as "Code analysis is not yet available."
     * - **Online, endpoint deployed**: delegates to [CodeRemoteDataSource] and returns
     *   [ApiResult.Success] with the structured [CodeAnalysisResult].
     *
     * @param request The code analysis request containing code, language, and action.
     * @return [ApiResult.Success] with [CodeAnalysisResult] on success, or an error variant.
     */
    override suspend fun analyzeCode(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult> {
        if (!connectivityObserver.isConnected()) {
            return ApiResult.NetworkUnavailable
        }
        return remoteDataSource.analyzeCode(request)
    }
}
