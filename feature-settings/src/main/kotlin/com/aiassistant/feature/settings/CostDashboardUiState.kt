/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : CostDashboardUiState.kt
 * Purpose    : UI state model for the AI Cost Dashboard screen
 *
 * Architecture Layer : Feature (feature-settings) — Presentation
 * Pattern Used       : Sealed class UI state (MVVM)
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 * ============================================================
 */

package com.aiassistant.feature.settings

import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.SpendingAlert

/**
 * Represents the UI state for the AI Cost Dashboard screen.
 *
 * The [CostDashboardViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this
 * sealed class. Composables observe it and render accordingly.
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 */
sealed class CostDashboardUiState {

    /**
     * The screen is loading data. Shows a progress indicator.
     * If the backend does not respond within 10 seconds, transitions to [Error].
     *
     * Requirements: 34.2, 34.3
     */
    data object Loading : CostDashboardUiState()

    /**
     * The dashboard is ready with cost data.
     *
     * @param costSummary        90-day cost summary from the backend.
     * @param alerts             List of spending alerts (max 3).
     * @param alertLimitError    Non-null when the user attempts to add a 4th alert.
     * @param isAddingAlert      True while a POST /usage/alerts call is in flight.
     * @param isDeletingAlertId  Non-null ID of the alert currently being deleted.
     * @param triggeredBanners   List of triggered [SpendingAlert] objects whose banners
     *                           have not been dismissed by the user. The banner persists
     *                           until [CostDashboardViewModel.dismissBanner] is called.
     *
     * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
     */
    data class Ready(
        val costSummary: CostSummary,
        val alerts: List<SpendingAlert>,
        val alertLimitError: String? = null,
        val isAddingAlert: Boolean = false,
        val isDeletingAlertId: String? = null,
        val triggeredBanners: List<SpendingAlert> = emptyList()
    ) : CostDashboardUiState()

    /**
     * A network error or timeout occurred while loading.
     *
     * @param message Human-readable description of the failure.
     *
     * Requirements: 34.2, 34.3
     */
    data class Error(val message: String) : CostDashboardUiState()
}
