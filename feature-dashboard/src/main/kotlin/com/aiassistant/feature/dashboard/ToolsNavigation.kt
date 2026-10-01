/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-dashboard
 * File       : ToolsNavigation.kt
 * Purpose    : Route constants and NavGraphBuilder extension for the AI Tools
 *              Library screen — the "AI Tools" bottom-nav tab destination.
 *
 * Architecture Layer : feature-dashboard — Navigation layer.
 *                      Consumed by the app module's root NavHost.
 *
 * Design decisions   :
 * - Placed in feature-dashboard alongside DashboardNavigation.kt because the
 *   ToolsScreen is a top-level hub screen (no deep sub-graph) and logically
 *   belongs next to the app dashboard shells, not inside individual feature
 *   modules (which would create cross-module imports for simple navigation).
 * - [toolsNavGraph] is a NavGraphBuilder extension so the app module embeds
 *   it into rootNavHost without importing ToolsScreen directly.
 * - NavController is passed through for any future tool-detail navigation.
 *
 * Phase C — Home Dashboard & Tools Screen
 * ============================================================
 */
package com.aiassistant.feature.dashboard

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

// ── Route constants ───────────────────────────────────────────────────────────

object ToolsRoute {
    /** Top-level AI Tools library screen route. */
    const val SCREEN = "tools"
}

// ── NavGraph extension ────────────────────────────────────────────────────────

/**
 * Embeds the AI Tools Library screen into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's rootNavHost:
 * ```kotlin
 * toolsNavGraph(navController = navController)
 * ```
 *
 * @param navController Root NavHostController for tool-specific navigation.
 * @param onNavigateToTool Called with the route string when a tool card is tapped.
 */
fun NavGraphBuilder.toolsNavGraph(
    navController: NavHostController,
    onNavigateToTool: (route: String) -> Unit = { navController.navigate(it) },
) {
    composable(route = ToolsRoute.SCREEN) {
        ToolsScreen(onNavigateToTool = onNavigateToTool)
    }
}
