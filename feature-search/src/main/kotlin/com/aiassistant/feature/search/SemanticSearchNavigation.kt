/**
 * SemanticSearchNavigation.kt
 *
 * Purpose: Navigation helper for the semantic search feature, providing route constants
 *          and a composable extension for the Navigation Compose graph.
 * Architecture: feature-search — navigation layer.
 * Dependencies: Jetpack Compose Navigation, SemanticSearchScreen.
 *
 * Requirements: 36.1, 36.5
 */
package com.aiassistant.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/** Navigation route constant for the semantic search screen. */
const val SEMANTIC_SEARCH_ROUTE = "semantic_search"

/**
 * Navigates to the semantic search screen.
 */
fun NavHostController.navigateToSemanticSearch() {
    navigate(SEMANTIC_SEARCH_ROUTE)
}

/**
 * Registers the semantic search screen in a [NavGraphBuilder].
 *
 * @param onNavigateToResult Callback invoked with a deep-link URI when the user taps a result.
 */
fun NavGraphBuilder.semanticSearchScreen(onNavigateToResult: (String) -> Unit) {
    composable(route = SEMANTIC_SEARCH_ROUTE) {
        SemanticSearchScreen(
            onNavigateToResult = onNavigateToResult
        )
    }
}
