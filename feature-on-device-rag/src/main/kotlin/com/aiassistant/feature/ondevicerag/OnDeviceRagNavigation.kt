/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceRagNavigation.kt
 * Purpose    : Route constants and NavGraphBuilder extension for the
 *              on-device RAG feature.  Embeds all four screens into the
 *              app-level NavHost without exposing screen composables.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — navigation layer.
 *
 * Deep-link base: aiassistant://open/ondevicerag/…
 *
 * Requirements: 19.1, 30.2
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink

/**
 * Route string constants for the on-device RAG navigation graph.
 *
 * Registered in the app module's root [NavHost] via [onDeviceRagNavGraph].
 */
object OnDeviceRagRoute {

    /** List of locally ingested documents. */
    const val DOCUMENTS = "ondevicerag/documents"

    /**
     * RAG chat screen for a specific document.
     * The [documentId] path segment selects which document's index to query.
     */
    const val RAG_CHAT = "ondevicerag/documents/{documentId}/chat"

    /** On-device inference benchmark screen — accessible from Settings. */
    const val BENCHMARK = "ondevicerag/benchmark"

    /** Model file management screen — accessible from Settings. */
    const val MANAGE_MODELS = "ondevicerag/models"

    /** Resolves the RAG chat route for a specific document. */
    fun ragChat(documentId: String) = "ondevicerag/documents/$documentId/chat"
}

/**
 * Embeds the on-device RAG navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = nav, startDestination = OnDeviceRagRoute.DOCUMENTS) {
 *     onDeviceRagNavGraph(navController = nav)
 * }
 * ```
 *
 * @param navController The shared root [NavHostController].
 */
fun NavGraphBuilder.onDeviceRagNavGraph(navController: NavHostController) {

    // ── Document list ────────────────────────────────────────────────────
    composable(
        route = OnDeviceRagRoute.DOCUMENTS,
        deepLinks = listOf(navDeepLink {
            uriPattern = "aiassistant://open/ondevicerag/documents"
        }),
    ) {
        val viewModel: OnDeviceDocumentViewModel = hiltViewModel()
        OnDeviceDocumentsScreen(
            onNavigateToChat = { documentId ->
                navController.navigate(OnDeviceRagRoute.ragChat(documentId))
            },
            viewModel = viewModel,
        )
    }

    // ── RAG chat ─────────────────────────────────────────────────────────
    composable(
        route = OnDeviceRagRoute.RAG_CHAT,
        arguments = listOf(
            navArgument("documentId") { type = NavType.StringType }
        ),
        deepLinks = listOf(navDeepLink {
            uriPattern = "aiassistant://open/ondevicerag/documents/{documentId}/chat"
        }),
    ) {
        val viewModel: OnDeviceRagViewModel = hiltViewModel()
        OnDeviceRagChatScreen(
            onNavigateUp = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }

    // ── Benchmark ─────────────────────────────────────────────────────────
    composable(
        route = OnDeviceRagRoute.BENCHMARK,
        deepLinks = listOf(navDeepLink {
            uriPattern = "aiassistant://open/ondevicerag/benchmark"
        }),
    ) {
        val viewModel: BenchmarkViewModel = hiltViewModel()
        BenchmarkScreen(
            onNavigateUp = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }

    // ── Manage models ─────────────────────────────────────────────────────
    composable(
        route = OnDeviceRagRoute.MANAGE_MODELS,
        deepLinks = listOf(navDeepLink {
            uriPattern = "aiassistant://open/ondevicerag/models"
        }),
    ) {
        val viewModel: ManageModelsViewModel = hiltViewModel()
        ManageModelsScreen(
            onNavigateUp = { navController.popBackStack() },
            viewModel = viewModel,
        )
    }
}
