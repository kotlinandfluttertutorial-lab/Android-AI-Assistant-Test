/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceNavigation.kt
 * Purpose    : VoiceNavigation — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
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
 * Module     : feature-voice
 * File       : VoiceNavigation.kt
 * Purpose    : VoiceNavigation — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
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
 * VoiceNavigation.kt
 *
 * Purpose: Navigation graph for the Voice Assistant feature, embedding it into the
 *          app module's root NavHost.
 * Architecture: feature-voice â€” Navigation layer; consumed by the app module.
 * Dependencies: VoiceScreen, VoiceViewModel (Hilt), AndroidX Navigation Compose
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 *
 * Design decisions:
 * - Route strings are defined on [VoiceRoute] object for type-safety and easy refactoring.
 * - [voiceNavGraph] is a [NavGraphBuilder] extension so the app module embeds the voice
 *   graph into its root NavHost without importing screen composables directly.
 * - Navigation state is driven by LaunchedEffect blocks inside screen composables, never
 *   by the ViewModel calling navigation APIs directly.
 * - conversationId and provider are optional nav arguments so the screen can be launched
 *   standalone (e.g. from a FAB) or with context from an existing conversation.
 */
package com.aiassistant.feature.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation

/**
 * Route string constants for the Voice Assistant navigation graph.
 */
object VoiceRoute {
    /** Root navigation graph route for the voice feature. */
    const val Graph = "voice"

    /** The main Voice Assistant screen route. */
    const val VoiceAssistant = "voice/assistant"

    // â”€â”€â”€ Optional argument names â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Optional nav argument: the conversation ID to associate voice messages with. */
    const val ARG_CONVERSATION_ID = "conversationId"

    /** Optional nav argument: the LLM provider identifier. */
    const val ARG_PROVIDER = "provider"

    /**
     * Builds a full route string for [VoiceAssistant] with optional query arguments.
     *
     * Usage:
     * ```kotlin
     * navController.navigate(VoiceRoute.voiceAssistantRoute("conv123", "openai"))
     * ```
     */
    fun voiceAssistantRoute(conversationId: String = "", provider: String = ""): String =
        "$VoiceAssistant?$ARG_CONVERSATION_ID=$conversationId&$ARG_PROVIDER=$provider"
}

/**
 * Embeds the Voice Assistant navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root NavHost:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     voiceNavGraph(
 *         navController = navController,
 *         onNavigateBack = { navController.popBackStack() },
 *     )
 * }
 * ```
 *
 * @param navController  The root [NavHostController] shared with the app module.
 * @param onNavigateBack Called when the user navigates back from the Voice screen.
 */
fun NavGraphBuilder.voiceNavGraph(navController: NavHostController, onNavigateBack: () -> Unit) {
    navigation(
        startDestination = VoiceRoute.VoiceAssistant,
        route = VoiceRoute.Graph
    ) {
        composable(
            route = "${VoiceRoute.VoiceAssistant}" +
                "?${VoiceRoute.ARG_CONVERSATION_ID}={${VoiceRoute.ARG_CONVERSATION_ID}}" +
                "&${VoiceRoute.ARG_PROVIDER}={${VoiceRoute.ARG_PROVIDER}}",
            arguments = listOf(
                navArgument(VoiceRoute.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(VoiceRoute.ARG_PROVIDER) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments
                ?.getString(VoiceRoute.ARG_CONVERSATION_ID)
                .orEmpty()
            val provider = backStackEntry.arguments
                ?.getString(VoiceRoute.ARG_PROVIDER)
                .orEmpty()

            VoiceScreenWithArgs(
                conversationId = conversationId,
                provider = provider,
                onNavigateBack = onNavigateBack
            )
        }
    }
}

/**
 * Thin wrapper that resolves nav arguments and forwards them to [VoiceScreen] via the
 * ViewModel before any state collection begins.
 *
 * @param conversationId Conversation ID passed via navigation arguments.
 * @param provider       LLM provider identifier passed via navigation arguments.
 * @param onNavigateBack Back navigation callback.
 */
@Composable
fun VoiceScreenWithArgs(conversationId: String, provider: String, onNavigateBack: () -> Unit) {
    val viewModel: VoiceViewModel = hiltViewModel()

    LaunchedEffect(conversationId, provider) {
        viewModel.setConversationContext(
            conversationId = conversationId,
            provider = provider
        )
    }

    VoiceScreen(
        onNavigateBack = onNavigateBack,
        viewModel = viewModel
    )
}
