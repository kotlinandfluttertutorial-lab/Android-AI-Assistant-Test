package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.repository.CodeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CodeRepositoryImpl.kt — data module
 *
 * Purpose: Data-layer implementation of [CodeRepository]. Delegates code analysis
 *          requests to the AI Orchestrator backend via the network layer.
 *
 * This is a stub implementation that returns a structured error indicating that
 * the backend endpoint is not yet wired. The feature-code module provides its
 * full implementation via the AI Orchestrator REST endpoint once integrated.
 *
 * Architecture: data module — @Singleton scoped for process-wide reuse.
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 */
@Singleton
class CodeRepositoryImpl @Inject constructor() : CodeRepository {

    override suspend fun analyzeCode(request: CodeAnalysisRequest): ApiResult<CodeAnalysisResult> {
        // TODO: Replace with actual Retrofit call to POST /code/analyze once
        //       the CodeApiService and CodeRemoteDataSource are implemented.
        return ApiResult.Error(
            DomainError.ServerError(
                message = "Code analysis backend not yet connected",
                httpStatusCode = 501
            )
        )
    }
}
