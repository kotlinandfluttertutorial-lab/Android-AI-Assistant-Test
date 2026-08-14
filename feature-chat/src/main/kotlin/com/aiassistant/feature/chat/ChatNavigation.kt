/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatNavigation.kt
 * Purpose    : ChatNavigation — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
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
 * Module     : feature-chat
 * File       : ChatNavigation.kt
 * Purpose    : ChatNavigation — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
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
 * ChatNavigation.kt
 *
 * Purpose: Navigation graph for the chat feature, connecting the conversation list
 *          (ChatListScreen) and individual conversation detail screens.
 * Architecture: feature-chat â€” Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-chat screens, ChatViewModel (Hilt), AndroidX Navigation Compose
 *
 * Design decisions:
 * - Route strings live on [ChatRoute] companion object for type-safety and easy
 *   refactoring â€” avoid hard-coded strings at call sites.
 * - [chatNavGraph] is a [NavGraphBuilder] extension so the app module can embed the chat
 *   graph into its root [NavHost] without importing screen composables directly.
 *
 * Requirements: 11.1, 11.3, 11.5, 10.4
 */
package com.aiassistant.feature.chat

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aiassistant.core.ai.LlmProvider

/**
 * Route string constants for the chat navigation graph.
 */
object ChatRoute {
    /** Flat list of all conversations. */
    const val List = "chat/list"

    /**
     * Individual conversation detail screen.
     * The [conversationId] path segment is URL-encoded.
     */
    const val Detail = "chat/detail/{conversationId}"

    /**
     * Comparison Mode screen embedded in an active conversation.
     * The [conversationId] path segment is URL-encoded.
     */
    const val Comparison = "chat/comparison/{conversationId}"

    /**
     * Builds the resolved route to a specific conversation detail screen.
     *
     * @param conversationId The ID of the conversation to navigate to.
     * @return A fully-resolved navigation route string.
     */
    fun detail(conversationId: String) = "chat/detail/$conversationId"

    /**
     * Builds the resolved route to the Comparison Mode screen for a conversation.
     *
     * @param conversationId The ID of the conversation to run comparison in.
     * @return A fully-resolved navigation route string.
     */
    fun comparison(conversationId: String) = "chat/comparison/$conversationId"
}

/**
 * Embeds the chat navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = ChatRoute.List) {
 *     chatNavGraph(
 *         navController = navController,
 *         onNavigateToDetail = { conversationId -> ... },
 *     )
 * }
 * ```
 *
 * @param navController               The root [NavHostController] shared with the app module.
 * @param onNavigateToDetail          Called when the user taps a conversation; receives the ID.
 * @param configuredProviders         All providers configured by the user (used by Comparison Mode).
 * @param selectedComparisonProviders Providers selected for comparison (2–4).
 */
fun NavGraphBuilder.chatNavGraph(
    navController: NavHostController,
    onNavigateToDetail: (String) -> Unit,
    configuredProviders: List<LlmProvider> = emptyList(),
    selectedComparisonProviders: List<LlmProvider> = emptyList()
) {
    // â”€â”€ Conversation List â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    composable(route = ChatRoute.List) {
        val viewModel: ChatViewModel = hiltViewModel()
        ChatListScreen(
            viewModel = viewModel,
            onConversationClick = { conversationId ->
                onNavigateToDetail(conversationId)
            }
        )
    }

    // â”€â”€ Conversation Detail â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    composable(
        route = ChatRoute.Detail,
        arguments = listOf(
            navArgument("conversationId") { type = NavType.StringType }
        )
    ) {
        val viewModel: ChatDetailViewModel = hiltViewModel()
        ChatDetailScreen(
            viewModel = viewModel,
            onNavigateUp = { navController.navigateUp() }
        )
    }
}
