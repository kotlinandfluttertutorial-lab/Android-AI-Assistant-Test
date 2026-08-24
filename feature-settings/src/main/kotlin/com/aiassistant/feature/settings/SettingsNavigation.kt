/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsNavigation.kt
 * Purpose    : Navigation graph for the settings feature, exposing the Settings screen backed
 *          by [SettingsViewModel] scoped to the nav graph entry.
 * Architecture: feature-settings — Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-settings screens, SettingsViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [SettingsRoute] for type-safety and easy refactoring.
 * - [settingsNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   settings graph into its root [NavHost] without importing screen composables directly.
 * - The Settings feature is a single-screen graph (no sub-screens), so the graph route
 *   and the screen route are distinct but the navigation graph wrapper is kept for
 *   consistency with other feature modules and to allow future expansion.
 *
 * Requirements: 3.2, 24.2, 16.4, 7.6
 * ============================================================
 */
package com.aiassistant.feature.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * Route string constants for the settings navigation graph.
 */
object SettingsRoute {
    /** Settings screen route. */
    const val SCREEN = "settings"

    /** Cost Dashboard screen route. */
    const val COST_DASHBOARD = "settings/cost_dashboard"
}

/**
 * Embeds the settings screen into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     settingsNavGraph(navController = navController)
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the settings screen.
 *                      Defaults to [NavHostController.popBackStack].
 */
fun NavGraphBuilder.settingsNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() },
    onLoggedOut: () -> Unit = {
        navController.navigate("auth") {
            popUpTo(0) { inclusive = true }
        }
    }
) {
    composable(route = SettingsRoute.SCREEN) {
        val viewModel: SettingsViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsState()

        settingsScreen(
            uiState = uiState,
            onNavigateUp = onNavigateUp,
            onProviderSelected = { provider -> viewModel.selectProvider(provider) },
            onThemeSelected = { themeMode -> viewModel.selectTheme(themeMode) },
            onNotificationToggle = { key, enabled -> viewModel.setNotificationEnabled(key, enabled) },
            onPrivacyModeToggle = { enabled -> viewModel.setPrivacyMode(enabled) },
            onChangePassword = { current, new -> viewModel.changePassword(current, new) },
            onLinkGoogle = { idToken -> viewModel.linkGoogleAccount(idToken) },
            onUnlinkGoogle = { viewModel.unlinkGoogleAccount() },
            onLogout = { viewModel.logout(onLoggedOut) },
            onActionConsumed = { viewModel.onActionConsumed() },
            onRetry = { viewModel.retry() },
            onNavigateToCostDashboard = { navController.navigate(SettingsRoute.COST_DASHBOARD) }
        )
    }

    composable(route = SettingsRoute.COST_DASHBOARD) {
        val viewModel: CostDashboardViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsState()

        costDashboardScreen(
            uiState = uiState,
            onNavigateUp = { navController.popBackStack() },
            onAddAlert = { threshold -> viewModel.addAlert(threshold) },
            onDeleteAlert = { alertId -> viewModel.deleteAlert(alertId) },
            onDismissBanner = { alertId -> viewModel.dismissBanner(alertId) },
            onClearAlertError = { viewModel.clearAlertLimitError() },
            onRetry = { viewModel.retry() }
        )
    }
}
