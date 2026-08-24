/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorNavigation.kt
 * Purpose    : TranslatorNavigation — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
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
 * Module     : feature-translator
 * File       : TranslatorNavigation.kt
 * Purpose    : TranslatorNavigation — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
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
 * TranslatorNavigation.kt
 *
 * Purpose: Navigation graph for the Translator feature, embedding it into the app
 *          module's root NavHost.
 * Architecture: feature-translator â€” Navigation layer; consumed by the app module.
 * Dependencies: TranslatorScreen, TranslatorViewModel (Hilt), AndroidX Navigation Compose
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Route string is defined as a top-level constant for type-safety and easy reference
 *   from the app module's NavHost setup.
 * - [translatorNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   translator graph into its root NavHost without importing screen composables directly.
 * - The Translator feature is a single-screen flow; no nested navigation graph is needed.
 * - [hiltViewModel] is created inside the composable destination so the ViewModel is
 *   scoped to the back stack entry, not the whole graph.
 */
package com.aiassistant.feature.translator

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Route string for the Translator screen. */
const val TRANSLATOR_ROUTE = "translator"

/**
 * Embeds the Translator feature navigation destination into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root NavHost:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     translatorNavGraph(onNavigateBack = { navController.popBackStack() })
 * }
 * ```
 *
 * @param onNavigateBack Called when the user navigates back from the Translator screen.
 */
fun NavGraphBuilder.translatorNavGraph(onNavigateBack: () -> Unit) {
    composable(route = TRANSLATOR_ROUTE) {
        val viewModel: TranslatorViewModel = hiltViewModel()
        TranslatorScreen(
            onNavigateBack = onNavigateBack,
            viewModel = viewModel
        )
    }
}

/**
 * Returns the route string for navigating to the Translator screen.
 *
 * ```kotlin
 * navController.navigate(translatorRoute())
 * ```
 */
fun translatorRoute(): String = TRANSLATOR_ROUTE
