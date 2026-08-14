/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailNavigation.kt
 * Purpose    : EmailNavigation — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
 * Pattern Used       : Navigation Graph / Destinations
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailNavigation.kt
 * Purpose    : EmailNavigation — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
 * Pattern Used       : Navigation Graph / Destinations
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * EmailNavigation.kt
 *
 * Purpose: Navigation graph for the email composer and grammar correction flow.
 * Architecture: feature-email â€” Navigation layer; consumed by the app module's root NavHost.
 * Requirements: 14.4, 14.5
 */
package com.aiassistant.feature.email

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the email navigation sub-graph.
 */
object EmailRoute {
    /** Email root navigation graph route. */
    const val Graph = "email"

    /** Email composer screen route. */
    const val EmailComposer = "email/composer"

    /** Grammar correction screen route. */
    const val GrammarCorrection = "email/grammar-correction"
}

/**
 * Embeds the email navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * A single [EmailViewModel] instance is scoped to the email navigation graph so
 * state is shared between the composer and grammar correction screens without
 * being leaked to the app scope.
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "email") {
 *     emailNavGraph(navController = navController)
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the email graph.
 */
fun NavGraphBuilder.emailNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = EmailRoute.EmailComposer,
        route = EmailRoute.Graph
    ) {
        // â”€â”€ Email Composer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = EmailRoute.EmailComposer) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(EmailRoute.Graph)
            }
            val viewModel: EmailViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            // Navigate to grammar correction when the GrammarCorrected state is reached
            LaunchedEffect(uiState) {
                if (uiState is EmailUiState.GrammarCorrected) {
                    navController.navigate(EmailRoute.GrammarCorrection)
                }
            }

            EmailComposerScreen(
                uiState = uiState,
                onGenerateEmail = { ctx, intent -> viewModel.generateEmail(ctx, intent) },
                onCorrectGrammar = { draft -> viewModel.correctGrammar(draft) },
                onNavigateUp = onNavigateUp,
                onResetState = { viewModel.resetState() }
            )
        }

        // â”€â”€ Grammar Correction â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = EmailRoute.GrammarCorrection) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(EmailRoute.Graph)
            }
            val viewModel: EmailViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            GrammarCorrectionScreen(
                uiState = uiState,
                onNavigateUp = { navController.popBackStack() },
                onResetState = {
                    viewModel.resetState()
                    navController.popBackStack(EmailRoute.EmailComposer, inclusive = false)
                }
            )
        }
    }
}
