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
 * Routes code analysis requests to the online AI Orchestrator when the device has
 * connectivity, or returns [ApiResult.NetworkUnavailable] when offline (code analysis
 * requires network — there is no on-device fallback).
 *
 * @param remoteDataSource  Retrofit-backed data source for the code analysis endpoint.
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
     * - **Online**: delegates to [CodeRemoteDataSource] → `POST /code/analyze`.
     * - **Offline**: returns [ApiResult.NetworkUnavailable] — code analysis requires
     *   network access; there is no on-device equivalent.
     *
     * @param request The code analysis request containing code, language, and action.
     * @return [ApiResult.Success] with [CodeAnalysisResult] on success.
     */
    override suspend fun analyzeCode(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult> =
        if (connectivityObserver.isConnected()) {
            remoteDataSource.analyzeCode(request)
        } else {
            ApiResult.NetworkUnavailable
        }
}
