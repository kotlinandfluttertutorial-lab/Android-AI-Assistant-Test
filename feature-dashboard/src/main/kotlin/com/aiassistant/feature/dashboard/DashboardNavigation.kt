/**
 * DashboardNavigation.kt — feature-dashboard module
 *
 * Route constants and NavGraphBuilder extension for the DevOps Dashboard.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.feature.dashboard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

object DashboardRoute {
    /** Main dashboard screen route. */
    const val SCREEN = "devops/dashboard"
}

fun NavGraphBuilder.dashboardNavGraph(navController: NavHostController) {
    composable(route = DashboardRoute.SCREEN) {
        DashboardScreen(
            onIncidentClick = { incidentId ->
                // Future: navigate to incident detail screen
                // navController.navigate("devops/incident/$incidentId")
            }
        )
    }
}
