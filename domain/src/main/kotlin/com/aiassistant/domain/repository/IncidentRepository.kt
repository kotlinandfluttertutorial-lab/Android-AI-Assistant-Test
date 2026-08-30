/**
 * IncidentRepository.kt — domain module
 *
 * Contract for incident data access. Implemented by the data layer.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Incident

interface IncidentRepository {

    suspend fun getIncidents(
        status: String? = null,
        severity: String? = null,
        limit: Int = 20
    ): ApiResult<List<Incident>>

    suspend fun getOpenCount(): ApiResult<Int>
}
