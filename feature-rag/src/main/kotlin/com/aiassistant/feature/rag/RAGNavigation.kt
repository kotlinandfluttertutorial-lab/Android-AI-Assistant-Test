/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : RAGNavigation.kt
 * Purpose    : RAGNavigation — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
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
 * Module     : feature-rag
 * File       : RAGNavigation.kt
 * Purpose    : RAGNavigation — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
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
 * RAGNavigation.kt
 *
 * Purpose: Navigation graph for the RAG feature, connecting the Document List screen
 *          and the Document Chat screen.
 * Architecture: feature-rag — Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-rag screens, RAGViewModel, DocumentChatViewModel (Hilt),
 *               AndroidX Navigation Compose
 *
 * Design decisions:
 * - Route strings live on [RAGRoute] companion object for type-safety and easy
 *   refactoring — avoid hard-coded strings at call sites.
 * - [ragNavGraph] is a [NavGraphBuilder] extension so the app module can embed the RAG
 *   graph into its root [NavHost] without importing screen composables directly.
 * - [DocumentChatViewModel] receives the [documentId] via [SavedStateHandle] (injected
 *   automatically by Hilt Navigation Compose from the NavBackStackEntry arguments).
 *
 * Requirements: 4.1, 4.6, 4.7, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/**
 * Route string constants for the RAG navigation graph.
 */
object RAGRoute {

    /** Paginated list of all uploaded documents. */
    const val DOCUMENT_LIST = "rag/documents"

    /**
     * Document-specific RAG chat screen.
     * The [documentId] path segment identifies the document to query.
     */
    const val DOCUMENT_CHAT = "rag/documents/{documentId}/chat"

    /**
     * Builds the resolved route to a specific document chat screen.
     *
     * @param documentId The ID of the document to query.
     * @return A fully-resolved navigation route string.
     */
    fun documentChat(documentId: String) = "rag/documents/$documentId/chat"
}

/**
 * Embeds the RAG navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = RAGRoute.DOCUMENT_LIST) {
 *     ragNavGraph(navController = navController)
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 */
fun NavGraphBuilder.ragNavGraph(navController: NavHostController) {
    // ── Document List ────────────────────────────────────────────────────────────
    composable(route = RAGRoute.DOCUMENT_LIST) {
        val viewModel: RAGViewModel = hiltViewModel()
        DocumentListScreen(
            viewModel = viewModel,
            onDocumentClick = { documentId ->
                navController.navigate(RAGRoute.documentChat(documentId))
            }
        )
    }

    // ── Document Chat ────────────────────────────────────────────────────────────
    // The documentId argument is automatically forwarded to DocumentChatViewModel
    // via SavedStateHandle by Hilt Navigation Compose.
    composable(
        route = RAGRoute.DOCUMENT_CHAT,
        arguments = listOf(
            navArgument("documentId") { type = NavType.StringType }
        )
    ) {
        val viewModel: DocumentChatViewModel = hiltViewModel()
        DocumentChatScreen(
            viewModel = viewModel,
            onNavigateUp = { navController.popBackStack() }
        )
    }
}
