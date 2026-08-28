/**
 * GetIncidentsUseCase.kt — domain module
 *
 * Fetches the recent incident list with optional status/severity filters.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.usecase.devops

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.repository.IncidentRepository
import javax.inject.Inject

class GetIncidentsUseCase @Inject constructor(
    private val repository: IncidentRepository,
) {
    suspend operator fun invoke(
        status:   String? = null,
        severity: String? = null,
        limit:    Int     = 20,
    ): ApiResult<List<Incident>> = repository.getIncidents(
        status   = status,
        severity = severity,
        limit    = limit,
    )
}
