/**
 * HistoryNavigation.kt
 *
 * Purpose: Navigation graph for the history feature, connecting the HistoryListScreen
 *          (paginated conversation list) and SearchHistoryScreen (FTS search), with a
 *          two-pane layout on tablets (>=600 dp) showing both panes simultaneously.
 * Architecture: feature-history -- Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-history screens, HistoryViewModel (Hilt),
 *               AndroidX Navigation Compose, Paging 3 Compose,
 *               core-ui (TwoPaneLayout, WindowSizeUtils)
 *
 * Design decisions:
 * - Route strings are defined on [HistoryRoute] for type-safety and easy refactoring.
 * - [historyNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   history graph into its root [NavHost] without importing screen composables directly.
 * - A single [HistoryViewModel] instance is scoped to the history navigation graph so
 *   state (search query, export status) is shared between list and search screens.
 * - The paged conversation flow is collected inside the nav graph composable via
 *   [collectAsLazyPagingItems] to avoid lifecycle issues when navigating between screens.
 * - On tablets (>=600 dp) a [TwoPaneLayout] is used: the list pane (38%) stays permanently
 *   visible alongside the detail (search) pane (62%). On phones, only one pane is shown
 *   at a time and navigation is driven by [navController] push/pop (Requirement 24.4).
 *
 * Requirements: 11.1, 11.2, 11.6, 24.4
 */
package com.aiassistant.feature.history

import androidx.activity.ComponentActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.paging.compose.collectAsLazyPagingItems
import com.aiassistant.core.ui.adaptive.TwoPaneLayout
import com.aiassistant.core.ui.adaptive.isTabletLayout
import com.aiassistant.domain.model.Conversation

/**
 * Route string constants for the history navigation sub-graph.
 */
object HistoryRoute {
    /** History root navigation graph route. */
    const val Graph = "history"

    /** History list screen route. */
    const val List = "history/list"

    /** Search history screen route. */
    const val Search = "history/search"
}

/**
 * Embeds the history navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * **Tablet layout (>=600 dp):** Both [HistoryListScreen] and [SearchHistoryScreen] are
 * shown simultaneously using [TwoPaneLayout]. Tapping the search icon opens the search
 * content in the detail (right) pane without pushing a new back-stack entry. The history
 * list remains permanently visible in the left pane.
 *
 * **Phone layout (<600 dp):** Standard single-pane navigation. The list is the start
 * destination; tapping the search icon navigates to [HistoryRoute.Search].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = HistoryRoute.List) {
 *     historyNavGraph(
 *         navController = navController,
 *         onConversationClick = { conversationId -> ... },
 *     )
 * }
 * ```
 *
 * @param navController       The root [NavHostController] shared with the app module.
 * @param onConversationClick Called when the user opens a conversation from history.
 *                            Receives the conversation id.
 * @param windowSizeClass     Optional override for the window size class. When `null`
 *                            the class is calculated from the current activity window.
 *                            Pass a non-null value in tests to control the layout.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
fun NavGraphBuilder.historyNavGraph(
    navController: NavHostController,
    onConversationClick: (String) -> Unit = {},
    windowSizeClass: WindowSizeClass? = null
) {
    navigation(
        startDestination = HistoryRoute.List,
        route = HistoryRoute.Graph
    ) {
        // -- History List (also acts as the two-pane host on tablets) ----------
        composable(route = HistoryRoute.List) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(HistoryRoute.Graph)
            }
            val viewModel: HistoryViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
            val pagedItems = viewModel.pagedConversations.collectAsLazyPagingItems()

            // Resolve the window size class: use the override (useful in tests/previews)
            // or calculate it from the current ComponentActivity.
            val activity = LocalContext.current as? ComponentActivity
            val resolvedWindowSizeClass: WindowSizeClass = windowSizeClass
                ?: if (activity != null) {
                    calculateWindowSizeClass(activity)
                } else {
                    WindowSizeClass.calculateFromSize(DpSize.Zero)
                }

            val isTablet = resolvedWindowSizeClass.isTabletLayout

            // On tablets the right pane starts with search inactive; the user opens it
            // by tapping the search icon. rememberSaveable preserves this across rotations.
            var showDetailPane by rememberSaveable { mutableStateOf(false) }

            if (isTablet) {
                // -- Tablet: two-pane side-by-side layout (Requirement 24.4) ------
                TwoPaneLayout(
                    listPane = {
                        HistoryListScreen(
                            uiState = uiState,
                            pagedItems = pagedItems,
                            isOffline = isOffline,
                            onSearchClick = {
                                // Open search in the detail pane without navigating away
                                showDetailPane = true
                                viewModel.search(viewModel.currentSearchQuery)
                            },
                            onConversationClick = onConversationClick,
                            onPinConversation = { id, pinned ->
                                viewModel.pinConversation(id, pinned)
                            },
                            onRenameConversation = { id, title ->
                                viewModel.renameConversation(id, title)
                            },
                            onDeleteConversation = { id ->
                                viewModel.deleteConversation(id)
                            },
                            onExportConversation = { id, format ->
                                viewModel.exportConversation(id, format)
                            },
                            onDismissExportResult = {
                                viewModel.dismissExportSuccess()
                            }
                        )
                    },
                    detailPane = {
                        val searchQuery = when (val state = uiState) {
                            is HistoryUiState.SearchResults -> state.query
                            else -> viewModel.currentSearchQuery
                        }
                        val searchResults = when (val state = uiState) {
                            is HistoryUiState.SearchResults -> state.results
                            else -> emptyList<Conversation>()
                        }
                        SearchHistoryScreen(
                            searchQuery = searchQuery,
                            searchResults = searchResults,
                            isLoading = uiState is HistoryUiState.Loading,
                            onSearchQueryChange = { query -> viewModel.search(query) },
                            onConversationClick = { conversationId ->
                                onConversationClick(conversationId)
                            },
                            onBack = {
                                // On tablet, "back" from detail pane collapses the search
                                viewModel.clearSearch()
                                showDetailPane = false
                            }
                        )
                    },
                    showDetailPane = showDetailPane,
                    windowSizeClass = resolvedWindowSizeClass
                )
            } else {
                // -- Phone: single-pane list only -----------------------------------
                HistoryListScreen(
                    uiState = uiState,
                    pagedItems = pagedItems,
                    isOffline = isOffline,
                    onSearchClick = {
                        navController.navigate(HistoryRoute.Search)
                    },
                    onConversationClick = onConversationClick,
                    onPinConversation = { id, pinned ->
                        viewModel.pinConversation(id, pinned)
                    },
                    onRenameConversation = { id, title ->
                        viewModel.renameConversation(id, title)
                    },
                    onDeleteConversation = { id ->
                        viewModel.deleteConversation(id)
                    },
                    onExportConversation = { id, format ->
                        viewModel.exportConversation(id, format)
                    },
                    onDismissExportResult = {
                        viewModel.dismissExportSuccess()
                    }
                )
            }
        }

        // -- Search History (phone only; tablet uses inline detail pane) ---------
        composable(route = HistoryRoute.Search) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(HistoryRoute.Graph)
            }
            val viewModel: HistoryViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val searchQuery = when (val state = uiState) {
                is HistoryUiState.SearchResults -> state.query
                else -> viewModel.currentSearchQuery
            }

            val searchResults = when (val state = uiState) {
                is HistoryUiState.SearchResults -> state.results
                else -> emptyList<Conversation>()
            }

            val isLoading = uiState is HistoryUiState.Loading

            SearchHistoryScreen(
                searchQuery = searchQuery,
                searchResults = searchResults,
                isLoading = isLoading,
                onSearchQueryChange = { query ->
                    viewModel.search(query)
                },
                onConversationClick = { conversationId ->
                    onConversationClick(conversationId)
                },
                onBack = {
                    viewModel.clearSearch()
                    navController.popBackStack()
                }
            )
        }
    }
}
