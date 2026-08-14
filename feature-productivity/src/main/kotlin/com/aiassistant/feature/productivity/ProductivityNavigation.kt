/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ProductivityNavigation.kt
 * Purpose    : ProductivityNavigation — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * Module     : feature-productivity
 * File       : ProductivityNavigation.kt
 * Purpose    : ProductivityNavigation — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
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
 * ProductivityNavigation.kt
 *
 * Purpose: Navigation graph for the productivity feature, exposing TodoList and
 *          TodoEditor screens backed by a single shared ProductivityViewModel scoped
 *          to the nav graph.
 * Architecture: feature-productivity â€” Navigation layer; consumed by the app module's
 *               root NavHost.
 * Dependencies: feature-productivity screens, ProductivityViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [ProductivityRoute] for type-safety and easy refactoring.
 * - [productivityNavGraph] is a [NavGraphBuilder] extension so the app module embeds the
 *   productivity graph into its root [NavHost] without importing screen composables directly.
 * - A single [ProductivityViewModel] instance is scoped to the productivity navigation
 *   graph so state is shared between list and editor screens without being leaked to the
 *   app scope.
 * - The editor route uses an optional `todoId` query parameter; its absence signals the
 *   new todo flow (openNewTodo) rather than editing an existing one (openTodo).
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation

/**
 * Route string constants for the productivity navigation sub-graph.
 */
object ProductivityRoute {
    /** Productivity root navigation graph route. */
    const val Graph = "productivity"

    /** Todo list screen route. */
    const val TodoList = "productivity/todos"

    /** Todo editor screen route â€” [todoId] query param is optional. */
    const val TodoEditor = "productivity/todos/editor?todoId={todoId}"
}

/**
 * Embeds the productivity navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "productivity") {
 *     productivityNavGraph(navController = navController)
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the productivity graph.
 */
fun NavGraphBuilder.productivityNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = ProductivityRoute.TodoList,
        route = ProductivityRoute.Graph
    ) {
        // â”€â”€ Todo List â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = ProductivityRoute.TodoList) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(ProductivityRoute.Graph)
            }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            TodoListScreen(
                uiState = uiState,
                onTodoClick = { todo ->
                    viewModel.openTodo(todo)
                    navController.navigate("productivity/todos/editor?todoId=${todo.id}")
                },
                onNewTodo = {
                    viewModel.openNewTodo()
                    navController.navigate("productivity/todos/editor")
                },
                onDeleteTodo = { todoId -> viewModel.deleteTodo(todoId) },
                onApplyFilter = { filterState -> viewModel.applyFilter(filterState) },
                onGenerateFromPrompt = { prompt -> viewModel.generateTodosFromPrompt(prompt) },
                onAcceptSuggested = { todo -> viewModel.acceptSuggestedTodo(todo) },
                onDismissSuggestions = { viewModel.dismissSuggestedTodos() }
            )
        }

        // â”€â”€ Todo Editor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(
            route = ProductivityRoute.TodoEditor,
            arguments = listOf(
                navArgument("todoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(ProductivityRoute.Graph)
            }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            TodoEditorScreen(
                uiState = uiState,
                onUpdateDraft = { title, description, dueDate, priority, tags ->
                    viewModel.updateDraft(title, description, dueDate, priority, tags)
                },
                onSave = { todo ->
                    viewModel.saveTodo(todo)
                    navController.popBackStack()
                },
                onBack = {
                    viewModel.backToList()
                    navController.popBackStack()
                }
            )
        }
    }
}
