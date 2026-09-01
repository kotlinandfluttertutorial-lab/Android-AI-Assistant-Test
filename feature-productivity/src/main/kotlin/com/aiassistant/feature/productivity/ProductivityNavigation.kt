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

object ProductivityRoute {
    const val GRAPH = "productivity"
    const val TODO_LIST = "productivity/todos"
    const val TODO_EDITOR = "productivity/todos/editor?todoId={todoId}"
    const val TICKETS = "productivity/tickets"
    const val TICKET_DETAIL = "productivity/tickets/{ticketId}"
    fun ticketDetail(ticketId: String) = "productivity/tickets/$ticketId"
}

fun NavGraphBuilder.productivityNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(startDestination = ProductivityRoute.TODO_LIST, route = ProductivityRoute.GRAPH) {
        composable(route = ProductivityRoute.TODO_LIST) { backStackEntry ->
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(ProductivityRoute.GRAPH) }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            TodoListScreen(
                uiState = uiState,
                onTodoClick = { todo ->
                    viewModel.openTodo(todo)
                    navController.navigate("productivity/todos/editor?todoId=${'$'}{todo.id}")
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

        composable(
            route = ProductivityRoute.TODO_EDITOR,
            arguments = listOf(navArgument("todoId") { type = NavType.StringType; nullable = true; defaultValue = null })
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(ProductivityRoute.GRAPH) }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            TodoEditorScreen(
                uiState = uiState,
                onUpdateDraft = { title, description, dueDate, priority, tags ->
                    viewModel.updateDraft(title, description, dueDate, priority, tags)
                },
                onSave = { todo ->
                    viewModel.saveTodo(todo)
                    onNavigateUp()
                },
                onBack = {
                    viewModel.backToList()
                    onNavigateUp()
                }
            )
        }

        composable(
            route = ProductivityRoute.TICKET_DETAIL,
            arguments = listOf(navArgument("ticketId") { type = NavType.StringType })
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(ProductivityRoute.GRAPH) }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val ticketId = backStackEntry.arguments?.getString("ticketId") ?: return@composable
            val uiState by viewModel.uiState.collectAsState()
            val ticket = (uiState as? ProductivityUiState.TodoList)?.todos?.firstOrNull { it.id == ticketId } ?: return@composable

            TicketDetailScreen(
                ticket = ticket,
                onNavigateUp = onNavigateUp,
                onEditTicket = { t ->
                    viewModel.openTodo(t)
                    navController.navigate("productivity/todos/editor?todoId=${'$'}{t.id}")
                },
                onStatusChange = { t, newStatus ->
                    val updatedTags = t.tags.toMutableList().apply {
                        remove("in_progress")
                        if (newStatus == "in_progress") add("in_progress")
                    }
                    viewModel.saveTodo(t.copy(isCompleted = newStatus == "closed", tags = updatedTags))
                },
                onAiSummarise = { t -> viewModel.generateTodosFromPrompt("Summarise: ${'$'}{t.title}") },
                onAiExpand = { t -> viewModel.generateTodosFromPrompt("Expand: ${'$'}{t.description}") },
                onAiAddActionItems = { t -> viewModel.generateTodosFromPrompt("Action items for: ${'$'}{t.title}") },
            )
        }

        composable(route = ProductivityRoute.TICKETS) { backStackEntry ->
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(ProductivityRoute.GRAPH) }
            val viewModel: ProductivityViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            TicketsScreen(
                uiState = uiState,
                onTicketClick = { ticket -> navController.navigate(ProductivityRoute.ticketDetail(ticket.id)) },
                onNewTicket = {
                    viewModel.openNewTodo()
                    navController.navigate("productivity/todos/editor")
                },
                onDeleteTicket = { id -> viewModel.deleteTodo(id) },
                onMoveTicket = { ticket, newStatus ->
                    val updatedTags = ticket.tags.toMutableList().apply {
                        remove("in_progress")
                        if (newStatus == "in_progress") add("in_progress")
                    }
                    viewModel.saveTodo(ticket.copy(isCompleted = newStatus == "closed", tags = updatedTags))
                },
                onApplyFilter = { filter -> viewModel.applyFilter(filter) },
            )
        }
    }
}
