/**
 * AskDevOpsAssistantUseCase.kt — domain module
 *
 * Sends a natural language DevOps question to the Phase 13 assistant
 * and returns the grounded answer with citations.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.usecase.devops

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.DevOpsChatResult
import com.aiassistant.domain.repository.DevOpsRepository
import javax.inject.Inject

class AskDevOpsAssistantUseCase @Inject constructor(
    private val repository: DevOpsRepository,
) {
    suspend operator fun invoke(
        question: String,
        provider: String? = null,
    ): ApiResult<DevOpsChatResult> = repository.chat(
        question = question,
        provider = provider,
    )
}
