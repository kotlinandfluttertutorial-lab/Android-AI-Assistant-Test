/**
 * DashboardUiState.kt — feature-dashboard module
 *
 * Sealed class representing every possible UI state of the DevOps Dashboard.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard

import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult
import com.aiassistant.domain.model.Incident

/** Counts by severity displayed at the top of the dashboard. */
data class IncidentCounts(
    val critical: Int = 0,
    val high:     Int = 0,
    val medium:   Int = 0,
    val low:      Int = 0,
    val open:     Int = 0,
) {
    val total: Int get() = critical + high + medium + low
}

sealed class DashboardUiState {

    /** First load — spinner shown. */
    data object Loading : DashboardUiState()

    /** Full data loaded successfully. */
    data class Content(
        val counts:       IncidentCounts,
        val incidents:    List<Incident>,
        val aiAnalysis:   AiAnalysis?,
        val isRefreshing: Boolean = false,
        val isOffline:    Boolean = false,
    ) : DashboardUiState()

    /** Network or server error on initial load. */
    data class Error(
        val message:   String,
        val isOffline: Boolean = false,
    ) : DashboardUiState()
}

/** State for the DevOps assistant chat section. */
sealed class ChatUiState {
    data object Idle    : ChatUiState()
    data object Loading : ChatUiState()
    data class  Success(val result: DevOpsChatResult) : ChatUiState()
    data class  Error(val message: String)            : ChatUiState()
}
