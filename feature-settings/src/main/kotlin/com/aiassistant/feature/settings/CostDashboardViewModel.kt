/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : CostDashboardViewModel.kt
 * Purpose    : ViewModel for the AI Cost Dashboard screen
 *
 * Architecture Layer : Feature (feature-settings) — MVVM ViewModel
 * Pattern Used       : StateFlow MVVM, withTimeout for 10-s loading limit
 *
 * Key Concepts:
 *   - All backend calls use withTimeout(10_000) to enforce the 10-s loading limit
 *   - Persistent banners are stored in the ViewModel and never dismissed automatically
 *   - Alert limit enforcement shows an inline error on the 4th attempt (HTTP 422)
 *   - Never returns another user's data (JWT scoping enforced server-side)
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 * ============================================================
 */

package com.aiassistant.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.repository.CostDashboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Loading timeout before showing an error (Requirement 34.3). */
private const val LOADING_TIMEOUT_MS = 10_000L

/** Maximum number of spending alerts per user (Requirement 34.4). */
private const val MAX_ALERTS = 3

/**
 * ViewModel for the AI Cost Dashboard screen.
 *
 * Exposes [uiState] as a [StateFlow] of [CostDashboardUiState]. All network
 * calls are dispatched on [DispatcherProvider.io] and wrapped with a 10-second
 * timeout that transitions the state to [CostDashboardUiState.Error] if the
 * backend does not respond in time (Requirement 34.3).
 *
 * Persistent spending-alert banners ([CostDashboardUiState.Ready.triggeredBanners])
 * remain in state until the user explicitly calls [dismissBanner] (Requirement 34.6).
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 */
@HiltViewModel
class CostDashboardViewModel @Inject constructor(
    private val repository: CostDashboardRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<CostDashboardUiState>(CostDashboardUiState.Loading)

    /** Observable Cost Dashboard UI state. */
    val uiState: StateFlow<CostDashboardUiState> = _uiState.asStateFlow()

    /**
     * Set of alert IDs whose banners have been explicitly dismissed by the user.
     * Used to prevent re-showing a dismissed banner on reload.
     */
    private val dismissedBannerIds = mutableSetOf<String>()

    init {
        loadData()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    /**
     * Load cost summary and alerts from the backend.
     *
     * Uses [withTimeout] with a 10-second limit. If the backend does not respond
     * in time, transitions to [CostDashboardUiState.Error] (Requirement 34.3).
     *
     * On success, computes the list of triggered banners that have not been
     * dismissed by the user and injects them into the [Ready] state.
     */
    fun loadData() {
        _uiState.value = CostDashboardUiState.Loading

        viewModelScope.launch {
            try {
                withTimeout(LOADING_TIMEOUT_MS) {
                    val summaryResult = withContext(dispatchers.io) {
                        repository.getCostSummary()
                    }
                    val alertsResult = withContext(dispatchers.io) {
                        repository.getAlerts()
                    }

                    when {
                        summaryResult is ApiResult.Success && alertsResult is ApiResult.Success -> {
                            val alerts = alertsResult.data
                            val triggeredBanners = alerts.filter { alert ->
                                alert.isTriggered &&
                                    alert.dismissedAt == null &&
                                    alert.id !in dismissedBannerIds
                            }
                            _uiState.value = CostDashboardUiState.Ready(
                                costSummary = summaryResult.data,
                                alerts = alerts,
                                triggeredBanners = triggeredBanners
                            )
                        }
                        summaryResult is ApiResult.NetworkUnavailable ||
                            alertsResult is ApiResult.NetworkUnavailable -> {
                            _uiState.value = CostDashboardUiState.Error(
                                "No network connection. Please check your connection and try again."
                            )
                        }
                        summaryResult is ApiResult.Error -> {
                            _uiState.value = CostDashboardUiState.Error(
                                summaryResult.error.message ?: "Failed to load cost data."
                            )
                        }
                        alertsResult is ApiResult.Error -> {
                            _uiState.value = CostDashboardUiState.Error(
                                (alertsResult as ApiResult.Error).error.message
                                    ?: "Failed to load alerts."
                            )
                        }
                        else -> {
                            _uiState.value = CostDashboardUiState.Error(
                                "Failed to load cost dashboard data."
                            )
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Backend did not respond within 10 seconds (Requirement 34.3)
                _uiState.value = CostDashboardUiState.Error(
                    "Request timed out. The server did not respond within 10 seconds."
                )
            }
        }
    }

    // ── Alert management ──────────────────────────────────────────────────────

    /**
     * Create a new spending alert threshold.
     *
     * Validates locally that the user does not already have [MAX_ALERTS] alerts.
     * If they do, sets [CostDashboardUiState.Ready.alertLimitError] with an inline
     * error message instead of making a network call (Requirement 34.5).
     *
     * On HTTP 422 from the backend (4th attempt enforcement), surfaces the error
     * as [CostDashboardUiState.Ready.alertLimitError].
     *
     * @param thresholdUsd The alert threshold amount in USD.
     *
     * Requirements: 34.4, 34.5
     */
    fun addAlert(thresholdUsd: Double) {
        val current = _uiState.value as? CostDashboardUiState.Ready ?: return

        // Local client-side guard (also enforced server-side)
        if (current.alerts.size >= MAX_ALERTS) {
            _uiState.value = current.copy(
                alertLimitError = "You can have at most $MAX_ALERTS spending alerts. " +
                    "Please delete an existing alert before adding a new one."
            )
            return
        }

        _uiState.value = current.copy(isAddingAlert = true, alertLimitError = null)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                repository.createAlert(thresholdUsd)
            }
            val latestReady = _uiState.value as? CostDashboardUiState.Ready ?: return@launch

            when (result) {
                is ApiResult.Success -> {
                    val updatedAlerts = latestReady.alerts + result.data
                    _uiState.value = latestReady.copy(
                        alerts = updatedAlerts,
                        isAddingAlert = false,
                        alertLimitError = null
                    )
                }
                is ApiResult.Error -> {
                    val msg = result.error.message ?: "Failed to create alert."
                    _uiState.value = latestReady.copy(
                        isAddingAlert = false,
                        alertLimitError = msg
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = latestReady.copy(
                        isAddingAlert = false,
                        alertLimitError = "No network connection. Please try again."
                    )
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Delete the spending alert with the given [alertId].
     *
     * On success, removes the alert from the list in state.
     *
     * @param alertId UUID string of the alert to delete.
     *
     * Requirements: 34.4
     */
    fun deleteAlert(alertId: String) {
        val current = _uiState.value as? CostDashboardUiState.Ready ?: return
        _uiState.value = current.copy(isDeletingAlertId = alertId)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                repository.deleteAlert(alertId)
            }
            val latestReady = _uiState.value as? CostDashboardUiState.Ready ?: return@launch

            when (result) {
                is ApiResult.Success -> {
                    val updatedAlerts = latestReady.alerts.filterNot { it.id == alertId }
                    val updatedBanners = latestReady.triggeredBanners.filterNot { it.id == alertId }
                    _uiState.value = latestReady.copy(
                        alerts = updatedAlerts,
                        triggeredBanners = updatedBanners,
                        isDeletingAlertId = null
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = latestReady.copy(isDeletingAlertId = null)
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = latestReady.copy(isDeletingAlertId = null)
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Dismiss the persistent spending-alert banner for the given [alertId].
     *
     * The banner will not reappear for this alert even if the data is reloaded,
     * because [dismissedBannerIds] is kept in memory for the ViewModel's lifetime.
     *
     * Note: For production use, the dismissal should also be persisted server-side
     * (via a PATCH to set dismissed_at). This ViewModel keeps it in-memory for the
     * session.
     *
     * @param alertId The UUID string of the alert whose banner should be dismissed.
     *
     * Requirements: 34.6
     */
    fun dismissBanner(alertId: String) {
        dismissedBannerIds.add(alertId)
        val current = _uiState.value as? CostDashboardUiState.Ready ?: return
        _uiState.value = current.copy(
            triggeredBanners = current.triggeredBanners.filterNot { it.id == alertId }
        )
    }

    /**
     * Clear any inline alert-limit error (after the user has acknowledged it).
     *
     * Requirements: 34.5
     */
    fun clearAlertLimitError() {
        val current = _uiState.value as? CostDashboardUiState.Ready ?: return
        _uiState.value = current.copy(alertLimitError = null)
    }

    /**
     * Reload the dashboard data from scratch.
     * Useful after a network error or after an alert is triggered.
     */
    fun retry() = loadData()
}
