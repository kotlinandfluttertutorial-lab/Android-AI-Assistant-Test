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
 * Purpose: Navigation graph for the Voice Assistant feature, defining the graph structure
 *          and wiring the VoiceScreen to VoiceViewModel.
 * Architecture: feature-voice — Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-voice screens, VoiceViewModel (Hilt), AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [VoiceRoute] for type-safety and easy refactoring.
 * - [voiceNavGraph] is a [NavGraphBuilder] extension so the app module embeds the voice
 *   graph into its root [NavHost] without importing screen composables directly.
 * - The Voice Assistant screen accepts optional `conversationId` and `provider` query
 *   parameters to resume an existing conversation or use a specific model.
 *
 * Requirements: 2.1, 2.3, 19.1
 */
package com.aiassistant.feature.voice

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
    const val GRAPH = "voice"

    /** The main Voice Assistant screen route. */
    const val VOICE_ASSISTANT = "voice/assistant"

    // ── Optional argument names ──────────────────────────────────────────────────

    /** Optional nav argument: the conversation ID to associate voice messages with. */
    const val ARG_CONVERSATION_ID = "conversationId"

    /** Optional nav argument: the LLM provider identifier. */
    const val ARG_PROVIDER = "provider"

    /**
     * Builds a full route string for [VOICE_ASSISTANT] with optional query arguments.
     *
     * Usage:
     * ```kotlin
     * navController.navigate(VoiceRoute.voiceAssistantRoute("conv123", "openai"))
     * ```
     */
    fun voiceAssistantRoute(conversationId: String = "", provider: String = ""): String =
        "$VOICE_ASSISTANT?$ARG_CONVERSATION_ID=$conversationId&$ARG_PROVIDER=$provider"
}

/**
 * Embeds the Voice Assistant navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "voice") {
 *     voiceNavGraph(navController = navController, onNavigateBack = { ... })
 * }
 * ```
 *
 * @param navController  The root [NavHostController] shared with the app module.
 * @param onNavigateBack Called when the user taps the back arrow in the voice screen.
 */
fun NavGraphBuilder.voiceNavGraph(navController: NavHostController, onNavigateBack: () -> Unit) {
    navigation(
        startDestination = VoiceRoute.VOICE_ASSISTANT,
        route = VoiceRoute.GRAPH
    ) {
        composable(
            route = "${VoiceRoute.VOICE_ASSISTANT}?" +
                "${VoiceRoute.ARG_CONVERSATION_ID}={${VoiceRoute.ARG_CONVERSATION_ID}}&" +
                "${VoiceRoute.ARG_PROVIDER}={${VoiceRoute.ARG_PROVIDER}}",
            arguments = listOf(
                navArgument(VoiceRoute.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(VoiceRoute.ARG_PROVIDER) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            val viewModel: VoiceViewModel = hiltViewModel()
            VoiceScreen(
                viewModel = viewModel,
                onNavigateBack = onNavigateBack
            )
        }
    }
}
