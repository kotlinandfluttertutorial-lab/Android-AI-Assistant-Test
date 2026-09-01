/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : ProfileNavigation.kt
 * Purpose    : Navigation graph for the Profile and Memory Management screen,
 *              wiring all ProfileViewModel callbacks and handling one-shot
 *              navigation events (AccountDeleted → auth screen).
 *
 * Architecture Layer : Feature (feature-profile)
 * Pattern Used       : Navigation Graph / Destinations
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *   - LaunchedEffect for one-shot side-effect handling
 *
 * Dependencies:
 *   - feature-profile (ProfileScreen, ProfileViewModel, ProfileEvent)
 *   - AndroidX Navigation Compose
 *   - Hilt Navigation Compose
 *
 * Design decisions:
 * - Route strings are defined on [ProfileRoute] for type-safety and easy refactoring.
 * - [profileNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   profile graph into its root [NavHost] without importing screen composables directly.
 * - [ProfileEvent.AccountDeleted] is consumed in a [LaunchedEffect] tied to the
 *   nav back-stack entry so it fires exactly once and the caller handles navigation
 *   (e.g., pop to auth screen).
 * - All ProfileViewModel callbacks are wired here — the ProfileScreen composable itself
 *   is stateless and receives lambdas for every user interaction.
 * - collectAsStateWithLifecycle is used instead of collectAsState to avoid collecting
 *   when the lifecycle is STOPPED (e.g., screen is in the back stack behind a dialog).
 *
 * Requirements: 7.3, 7.4, 28.1, 28.2
 * ============================================================
 */
package com.aiassistant.feature.profile

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

/**
 * Route string constants for the profile navigation graph.
 */
object ProfileRoute {
    /** Profile screen route — navigated to from HomeDashboard and Settings. */
    const val SCREEN = "profile"
}

/**
 * Embeds the Profile and Memory Management screen into the caller's [NavGraphBuilder].
 *
 * Also registers the [MemoryListRoute.SCREEN] destination so the caller can navigate
 * to the standalone memory list from within the same nav graph.
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     profileNavGraph(
 *         navController = navController,
 *         onNavigateUp = { navController.popBackStack() },
 *         onAccountDeleted = {
 *             navController.navigate("auth") { popUpTo(0) { inclusive = true } }
 *         },
 *     )
 * }
 * ```
 *
 * @param navController  The root [NavHostController] shared with the app module.
 * @param onNavigateUp   Called when the user taps the back arrow in the Profile top bar.
 *                       Defaults to [NavHostController.popBackStack].
 * @param onAccountDeleted Called when account deletion succeeds; caller should clear
 *                         local data and navigate to the authentication screen.
 *                         Defaults to navigating to "auth" with inclusive pop-up.
 */
fun NavGraphBuilder.profileNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() },
    onAccountDeleted: () -> Unit = {
        navController.navigate("auth") {
            popUpTo(0) { inclusive = true }
        }
    }
) {
    // ── Memory List ── standalone destination reachable from ProfileScreen ──────
    memoryListNavGraph(
        navController = navController,
        onNavigateUp = { navController.popBackStack() }
    )

    composable(route = ProfileRoute.SCREEN) {
        val viewModel: ProfileViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        // Consume the one-shot AccountDeleted event and delegate navigation to the caller.
        LaunchedEffect(Unit) {
            viewModel.profileEvents.collect { event ->
                when (event) {
                    is ProfileEvent.AccountDeleted -> onAccountDeleted()
                }
            }
        }

        ProfileScreen(
            uiState = uiState,
            onNavigateUp = onNavigateUp,
            onNavigateToMemoryList = {
                navController.navigate(MemoryListRoute.SCREEN)
            },
            // ── Memory actions ─────────────────────────────────────────────
            onUpdateEditContent = { content -> viewModel.updateEditContent(content) },
            onCancelEdit = { viewModel.cancelEditMemory() },
            onSaveEdit = { viewModel.saveMemoryEdit() },
            onDeleteMemory = { memoryId -> viewModel.deleteMemory(memoryId) },
            // ── Name editing ───────────────────────────────────────────────
            onStartEditName = { viewModel.startEditName() },
            onUpdateEditingName = { name -> viewModel.updateEditingName(name) },
            onCancelEditName = { viewModel.cancelEditName() },
            onSaveDisplayName = { viewModel.saveDisplayName() },
            // ── Data export (Requirement 28.1) ────────────────────────────
            onRequestDataExport = { viewModel.requestDataExport() },
            // ── Account deletion (Requirement 28.2) ───────────────────────
            onInitiateAccountDeletion = { viewModel.initiateAccountDeletion() },
            onUpdateDeletionInput = { input -> viewModel.updateDeletionConfirmationInput(input) },
            onCancelAccountDeletion = { viewModel.cancelAccountDeletion() },
            onConfirmAccountDeletion = { viewModel.confirmAccountDeletion() },
            onDismissDeletionError = { viewModel.dismissDeletionError() },
            // ── General ────────────────────────────────────────────────────
            onDismissError = { viewModel.dismissError() },
            onRetry = { viewModel.retry() }
        )
    }
}
