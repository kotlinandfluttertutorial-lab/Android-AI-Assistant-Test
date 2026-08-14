/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeNavigation.kt
 * Purpose    : CodeNavigation — feature-code module component
 *
 * Architecture Layer : Feature (feature-code)
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
 * Module     : feature-code
 * File       : CodeNavigation.kt
 * Purpose    : CodeNavigation — feature-code module component
 *
 * Architecture Layer : Feature (feature-code)
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
 * CodeNavigation.kt
 *
 * Purpose: Navigation graph for the code feature, exposing CodeEditor and CodeAnalysis
 *          screens backed by a single shared CodeViewModel scoped to the nav graph.
 * Architecture: feature-code â€” Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-code screens, CodeViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [CodeRoute] for type-safety and easy refactoring.
 * - [codeNavGraph] is a [NavGraphBuilder] extension so the app module embeds the code
 *   graph into its root [NavHost] without importing screen composables directly.
 * - A single [CodeViewModel] instance is scoped to the code navigation graph so state
 *   (in-progress analysis, results) is shared between editor and analysis screens without
 *   being leaked to the app scope.
 * - Navigation from Editor â†’ Analysis happens on submit; Analysis â†’ Editor on back.
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 */
package com.aiassistant.feature.code

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the code navigation sub-graph.
 */
object CodeRoute {
    /** Code root navigation graph route. */
    const val Graph = "code"

    /** Code editor screen route â€” entry point for the code feature. */
    const val Editor = "code/editor"

    /** Code analysis result screen route â€” shown after AI analysis completes. */
    const val Analysis = "code/analysis"
}

/**
 * Embeds the code navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "code") {
 *     codeNavGraph(navController = navController)
 * }
 * ```
 *
 * A single [CodeViewModel] is scoped to the nav graph so both screens share state
 * without the ViewModel surviving beyond the graph's lifecycle.
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the code graph.
 */
fun NavGraphBuilder.codeNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = CodeRoute.Editor,
        route = CodeRoute.Graph
    ) {
        // â”€â”€ Code Editor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = CodeRoute.Editor) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(CodeRoute.GRAPH)
            }
            val viewModel: CodeViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            // Auto-navigate to analysis when result is ready
            if (uiState is CodeUiState.AnalysisResult) {
                navController.navigate(CodeRoute.Analysis) {
                    launchSingleTop = true
                }
            }

            CodeEditorScreen(
                uiState = uiState,
                onCodeChange = { code, language -> viewModel.updateCode(code, language) },
                onLanguageSelect = { language -> viewModel.selectLanguage(language) },
                onActionSelect = { action -> viewModel.selectAction(action) },
                onSubmit = { viewModel.submitForAnalysis() }
            )
        }

        // â”€â”€ Code Analysis â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = CodeRoute.ANALYSIS) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(CodeRoute.GRAPH)
            }
            val viewModel: CodeViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            // Guard: only render when we have a result; otherwise go back to editor
            val analysisState = uiState as? CodeUiState.AnalysisResult
            if (analysisState == null) {
                navController.popBackStack(CodeRoute.Editor, inclusive = false)
                return@composable
            }

            CodeAnalysisScreen(
                uiState = analysisState,
                onBackToEditor = {
                    viewModel.backToEditor()
                    navController.popBackStack(CodeRoute.EDITOR, inclusive = false)
                }
            )
        }
    }
}
