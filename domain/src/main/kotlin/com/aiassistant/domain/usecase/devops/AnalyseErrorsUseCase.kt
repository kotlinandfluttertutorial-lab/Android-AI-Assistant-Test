/**
 * AnalyseErrorsUseCase.kt — domain module
 *
 * Triggers Phase 10 AI error analysis for recent observability events.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.usecase.devops

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.repository.DevOpsRepository
import javax.inject.Inject

class AnalyseErrorsUseCase @Inject constructor(private val repository: DevOpsRepository) {
    suspend operator fun invoke(lookbackMinutes: Int = 30, sessionId: String? = null): ApiResult<AiAnalysis> =
        repository.analyseErrors(
            lookbackMinutes = lookbackMinutes,
            sessionId = sessionId
        )
}
