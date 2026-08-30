/**
 * DashboardViewModel.kt — feature-dashboard module
 *
 * Manages all state for the DevOps Dashboard screen.
 *
 * On load (and on pull-to-refresh) it:
 *   1. Fetches recent incidents (last 20)
 *   2. Computes severity counts from the incident list
 *   3. Triggers Phase 10 AI error analysis (last 30 min)
 *
 * On user chat submit it:
 *   4. Calls the Phase 13 DevOps Assistant with the question
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.model.IncidentSeverity
import com.aiassistant.domain.usecase.devops.AnalyseErrorsUseCase
import com.aiassistant.domain.usecase.devops.AskDevOpsAssistantUseCase
import com.aiassistant.domain.usecase.devops.GetIncidentsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getIncidents: GetIncidentsUseCase,
    private val analyseErrors: AnalyseErrorsUseCase,
    private val askDevOpsAssistant: AskDevOpsAssistantUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // ── Offline state ──────────────────────────────────────────────────────────

    val isOffline: StateFlow<Boolean> = connectivityObserver.isConnectedFlow
        .map { !it }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = !connectivityObserver.isConnected()
        )

    // ── Dashboard state ────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // ── Chat state ─────────────────────────────────────────────────────────────

    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    // ── Remediation state (Phase 15) ───────────────────────────────────────────

    private val _remediationState = MutableStateFlow<RemediationUiState>(RemediationUiState.Idle)
    val remediationState: StateFlow<RemediationUiState> = _remediationState.asStateFlow()

    init {
        load()
    }

    // ── Public actions ─────────────────────────────────────────────────────────

    /** Initial load + pull-to-refresh. */
    fun refresh() {
        val current = _uiState.value
        // Show inline refresh spinner if we already have content
        if (current is DashboardUiState.Content) {
            _uiState.update { (it as DashboardUiState.Content).copy(isRefreshing = true) }
        }
        load()
    }

    /** Submit a DevOps question to the Phase 13 assistant. */
    fun askQuestion(question: String) {
        if (question.isBlank()) return
        viewModelScope.launch {
            _chatState.value = ChatUiState.Loading

            val result = withContext(dispatchers.io) {
                askDevOpsAssistant(question = question.trim())
            }

            _chatState.value = when (result) {
                is ApiResult.Success -> ChatUiState.Success(result.data)
                is ApiResult.Error -> ChatUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> ChatUiState.Error("No network connection.")
                is ApiResult.Loading -> ChatUiState.Loading
            }
        }
    }

    /** Clear the chat result so the input is blank again. */
    fun clearChat() {
        _chatState.value = ChatUiState.Idle
    }

    // ── Remediation actions (Phase 15) ─────────────────────────────────────────

    /** Request remediation recommendations for an incident. */
    fun recommendRemediation(incidentId: String) {
        viewModelScope.launch {
            _remediationState.value = RemediationUiState.Loading
            // Stub: real implementation will call
            //   POST /incidents/{incidentId}/remediation/recommend
            // via a RemediationRepository (extend data module following IncidentRepositoryImpl pattern).
            // Suppressed unused-parameter warning: incidentId is intentionally kept for the
            // real implementation that will replace this placeholder.
            withContext(dispatchers.io) {
                // placeholder — no network call yet
            }
            _remediationState.value = RemediationUiState.Idle
        }
    }

    /** Record human approval of a remediation action. */
    fun approveAction(incidentId: String, actionId: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                // POST /incidents/{incidentId}/remediation/{actionId}/approve
                // Extend RemediationRepository when data layer is wired
            }
        }
    }

    /** Record human rejection of a remediation action. */
    fun rejectAction(incidentId: String, actionId: String, reason: String = "") {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                // POST /incidents/{incidentId}/remediation/{actionId}/reject
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun load() {
        viewModelScope.launch {
            // Fetch incidents and AI analysis in parallel
            val incidentsDeferred = async(dispatchers.io) {
                getIncidents(limit = 20)
            }
            val analysisDeferred = async(dispatchers.io) {
                analyseErrors(lookbackMinutes = 30)
            }

            val incidentsResult = incidentsDeferred.await()
            val analysisResult = analysisDeferred.await()

            // Incidents are required; analysis is optional
            when (incidentsResult) {
                is ApiResult.Success -> {
                    val incidents = incidentsResult.data
                    val analysis = (analysisResult as? ApiResult.Success)?.data

                    _uiState.value = DashboardUiState.Content(
                        counts = computeCounts(incidents),
                        incidents = incidents,
                        aiAnalysis = analysis,
                        isRefreshing = false,
                        isOffline = isOffline.value
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = DashboardUiState.Error(
                        message = "No network connection.",
                        isOffline = true
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = DashboardUiState.Error(
                        message = incidentsResult.error.message
                    )
                }
                is ApiResult.Loading -> { /* ignore */ }
            }
        }
    }

    private fun computeCounts(incidents: List<Incident>): IncidentCounts {
        val open = incidents.filter {
            it.status == com.aiassistant.domain.model.IncidentStatus.OPEN ||
                it.status == com.aiassistant.domain.model.IncidentStatus.INVESTIGATING
        }
        return IncidentCounts(
            critical = open.count { it.severity == IncidentSeverity.CRITICAL },
            high = open.count { it.severity == IncidentSeverity.HIGH },
            medium = open.count { it.severity == IncidentSeverity.MEDIUM },
            low = open.count { it.severity == IncidentSeverity.LOW },
            open = open.size
        )
    }
}
