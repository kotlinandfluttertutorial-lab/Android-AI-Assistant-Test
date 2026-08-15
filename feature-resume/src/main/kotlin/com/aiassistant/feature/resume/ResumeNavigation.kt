/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeNavigation.kt
 * Purpose    : ResumeNavigation — feature-resume module component
 *
 * Architecture Layer : Feature (feature-resume)
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
 * Module     : feature-resume
 * File       : ResumeNavigation.kt
 * Purpose    : ResumeNavigation — feature-resume module component
 *
 * Architecture Layer : Feature (feature-resume)
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
 * ResumeNavigation.kt
 *
 * Purpose: Navigation graph for the resume and cover letter flow, exposing
 *          ResumeBuilder and CoverLetterEditor screens.
 * Architecture: feature-resume â€” Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-resume screens, ResumeViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [ResumeRoute] for type-safety and easy refactoring.
 * - [resumeNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   resume graph into its root [NavHost] without importing screen composables directly.
 * - A single [ResumeViewModel] instance is scoped to the resume navigation graph so
 *   state is shared between the two screens without being leaked to the app scope.
 * - Navigation triggered by UI state changes uses [LaunchedEffect]; the ViewModel
 *   never calls navigation APIs directly.
 *
 * Requirements: 14.1, 14.2, 14.3
 */
package com.aiassistant.feature.resume

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the resume navigation sub-graph.
 */
object ResumeRoute {
    /** Resume root navigation graph route. */
    const val GRAPH = "resume"

    /** Resume builder screen route. */
    const val RESUME_BUILDER = "resume/builder"

    /** Cover letter editor screen route. */
    const val COVER_LETTER_EDITOR = "resume/cover-letter"
}

/**
 * Embeds the resume navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "resume") {
 *     resumeNavGraph(navController = navController, onNavigateUp = { navController.popBackStack() })
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the resume graph.
 */
fun NavGraphBuilder.resumeNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = ResumeRoute.RESUME_BUILDER,
        route = ResumeRoute.GRAPH
    ) {
        // â”€â”€ Resume Builder â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = ResumeRoute.RESUME_BUILDER) {
            val viewModel: ResumeViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            ResumeBuilderScreen(
                uiState = uiState,
                onGenerateResume = { history, jd ->
                    viewModel.generateResume(history, jd)
                },
                onExport = { content, format ->
                    viewModel.exportContent(content, format)
                },
                onNavigateUp = onNavigateUp,
                onResetState = { viewModel.resetState() }
            )
        }

        // â”€â”€ Cover Letter Editor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = ResumeRoute.COVER_LETTER_EDITOR) {
            val viewModel: ResumeViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            CoverLetterEditorScreen(
                uiState = uiState,
                onGenerateCoverLetter = { history, jd ->
                    viewModel.generateCoverLetter(history, jd)
                },
                onExport = { content, format ->
                    viewModel.exportContent(content, format, fileName = "cover_letter_export")
                },
                onNavigateUp = onNavigateUp,
                onResetState = { viewModel.resetState() }
            )
        }
    }
}
