/**
 * DevOpsRepository.kt — domain module
 *
 * Contract for DevOps assistant and error analysis access.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult

interface DevOpsRepository {

    suspend fun chat(
        question: String,
        provider: String? = null,
    ): ApiResult<DevOpsChatResult>

    suspend fun analyseErrors(
        lookbackMinutes: Int = 30,
        sessionId: String? = null,
    ): ApiResult<AiAnalysis>
}
