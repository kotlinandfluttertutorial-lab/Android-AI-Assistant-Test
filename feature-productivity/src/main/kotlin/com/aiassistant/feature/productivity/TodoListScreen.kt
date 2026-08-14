/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : TodoListScreen.kt
 * Purpose    : Compose UI screen for the TodoList feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : TodoListScreen.kt
 * Purpose    : Compose UI screen for the TodoList feature
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Jetpack Compose Screen
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
 * TodoListScreen.kt
 *
 * Purpose: Stateless Compose screen displaying the paginated, filterable list of todo
 *          items, with an AI prompt section for generating todos from natural language.
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven by
 *               ProductivityUiState from ProductivityViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing), domain (TodoItem, SyncStatus,
 *               Priority), ProductivityUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Filter chips in a horizontally scrollable row (completion, priority, due date).
 * - AI prompt section is collapsed by default, expanded on tap.
 * - Each todo card shows title, description preview, due date, priority (icon + text),
 *   tags as SuggestionChip, and sync status badge (icon-based â€” requirement 23.4).
 * - Delete confirmation dialog prevents accidental deletion.
 * - All interactive elements carry contentDescriptions (requirement 23.1).
 *
 * Requirements: 13.1, 19.1, 23.1, 23.4
 */
package com.aiassistant.feature.productivity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Todo list screen composable.
 *
 * Renders the top-level todo list with filter chips, todo item cards, and a collapsible
 * AI prompt section at the bottom.
 *
 * @param uiState                Current state from [ProductivityViewModel].
 * @param onTodoClick            Invoked when the user taps a todo card.
 * @param onNewTodo              Invoked when the user taps the FAB to create a new todo.
 * @param onDeleteTodo           Invoked with the todo id after the user confirms deletion.
 * @param onApplyFilter          Invoked when the user changes the filter selection.
 * @param onGenerateFromPrompt   Invoked with the AI prompt text when the user taps Generate.
 * @param onAcceptSuggested      Invoked when the user taps Add on an AI-suggested todo.
 * @param onDismissSuggestions   Invoked when the user dismisses all AI suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListScreen(
    uiState: ProductivityUiState,
    onTodoClick: (TodoItem) -> Unit,
    onNewTodo: () -> Unit,
    onDeleteTodo: (String) -> Unit,
    onApplyFilter: (TodoFilterState) -> Unit,
    onGenerateFromPrompt: (String) -> Unit,
    onAcceptSuggested: (TodoItem) -> Unit,
    onDismissSuggestions: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("To-Do") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewTodo,
                modifier = Modifier.semantics {
                    contentDescription = "Create new to-do"
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Error banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is ProductivityUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // â”€â”€ Loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is ProductivityUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading to-dos"
                        }
                    )
                }
                return@Column
            }

            if (uiState !is ProductivityUiState.TodoList) return@Column

            // â”€â”€ Filter chips row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            FilterRow(
                filterState = uiState.filterState,
                onApplyFilter = onApplyFilter
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            // â”€â”€ Todo list + AI section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.todos.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No to-dos yet. Tap + to create one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            horizontal = MaterialTheme.spacing.md,
                            vertical = MaterialTheme.spacing.sm
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                    ) {
                        items(uiState.todos, key = { it.id }) { todo ->
                            TodoItemCard(
                                todo = todo,
                                onClick = { onTodoClick(todo) },
                                onDelete = { onDeleteTodo(todo.id) }
                            )
                        }
                    }
                }
            }

            // â”€â”€ AI prompt section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            AiPromptSection(
                isGeneratingAi = uiState.isGeneratingAi,
                aiSuggestedTodos = uiState.aiSuggestedTodos,
                onGenerateFromPrompt = onGenerateFromPrompt,
                onAcceptSuggested = onAcceptSuggested,
                onDismissSuggestions = onDismissSuggestions
            )
        }
    }
}

// â”€â”€â”€ Filter row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(filterState: TodoFilterState, onApplyFilter: (TodoFilterState) -> Unit) {
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val selectedMs = datePickerState.selectedDateMillis
                        onApplyFilter(filterState.copy(dueBefore = selectedMs))
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm due before date"
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel date selection"
                    }
                ) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Completion filter
        FilterChip(
            selected = filterState.showCompleted,
            onClick = { onApplyFilter(filterState.copy(showCompleted = true)) },
            label = { Text("All") },
            modifier = Modifier.semantics {
                contentDescription = "Show all to-dos"
            }
        )
        FilterChip(
            selected = !filterState.showCompleted,
            onClick = { onApplyFilter(filterState.copy(showCompleted = false)) },
            label = { Text("Pending") },
            modifier = Modifier.semantics {
                contentDescription = "Show pending to-dos only"
            }
        )

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

        // Priority filter chips
        FilterChip(
            selected = filterState.priority == null,
            onClick = { onApplyFilter(filterState.copy(priority = null)) },
            label = { Text("All priorities") },
            modifier = Modifier.semantics {
                contentDescription = "Show all priorities"
            }
        )
        Priority.entries.forEach { priority ->
            FilterChip(
                selected = filterState.priority == priority,
                onClick = { onApplyFilter(filterState.copy(priority = priority)) },
                label = { Text(priority.value.replaceFirstChar { it.uppercaseChar() }) },
                modifier = Modifier.semantics {
                    contentDescription = "Filter by ${priority.value} priority"
                }
            )
        }

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

        // Due before filter chip
        val dueDateLabel = filterState.dueBefore?.let { ms ->
            val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            "Due before: ${date.format(DateTimeFormatter.ofPattern("MMM d"))}"
        } ?: "Any date"

        FilterChip(
            selected = filterState.dueBefore != null,
            onClick = { showDatePicker = true },
            label = { Text(dueDateLabel) },
            trailingIcon = if (filterState.dueBefore != null) {
                {
                    IconButton(
                        onClick = { onApplyFilter(filterState.copy(dueBefore = null)) },
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Clear due before filter" }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.semantics {
                contentDescription = "Filter by due date: $dueDateLabel"
            }
        )
    }
}

// â”€â”€â”€ Todo item card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A card displaying the summary of a single [todo] item.
 *
 * Shows the title, description preview (2 lines max), optional due date, priority
 * badge (icon + text), tags as SuggestionChip, a sync status badge, and a trailing
 * delete button that triggers a confirmation dialog.
 *
 * @param todo     The todo item to display.
 * @param onClick  Invoked when the card body is tapped.
 * @param onDelete Invoked after the user confirms deletion.
 */
@Composable
private fun TodoItemCard(todo: TodoItem, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete to-do?") },
            text = { Text("\"${todo.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete to-do ${todo.title}"
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete to-do"
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "To-do: ${todo.title}" },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            // â”€â”€ Header row: title + sync badge + delete â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = todo.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

                // Sync status badge (icon-based â€” not color-only, requirement 23.4)
                TodoSyncStatusBadge(syncStatus = todo.syncStatus)

                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete to-do: ${todo.title}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // â”€â”€ Description preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (todo.description.isNotBlank()) {
                Text(
                    text = todo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // â”€â”€ Due date â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            todo.dueDate?.let { dueDateMs ->
                val formatted = Instant.ofEpochMilli(dueDateMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarToday,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Due: $formatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // â”€â”€ Priority badge (icon + text) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            PriorityBadge(priority = todo.priority)

            // â”€â”€ Tag chips â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (todo.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    todo.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.semantics {
                                contentDescription = "Tag: $tag"
                            }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Priority badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun PriorityBadge(priority: Priority) {
    val (icon, label, tint) = when (priority) {
        Priority.HIGH -> Triple(
            Icons.Filled.PriorityHigh,
            "High priority",
            MaterialTheme.colorScheme.error
        )
        Priority.MEDIUM -> Triple(
            Icons.Filled.Remove,
            "Medium priority",
            MaterialTheme.colorScheme.tertiary
        )
        Priority.LOW -> Triple(
            Icons.Filled.ArrowDownward,
            "Low priority",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

// â”€â”€â”€ Sync status badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Small icon communicating the todo's [syncStatus].
 *
 * Uses icons (not color alone) so users with color-blindness can distinguish states
 * (Requirement 23.4):
 * - SYNCED  â†’ check circle
 * - PENDING â†’ clock
 * - FAILED  â†’ error outline
 */
@Composable
private fun TodoSyncStatusBadge(syncStatus: SyncStatus) {
    val (icon, description, tint) = when (syncStatus) {
        SyncStatus.SYNCED -> Triple(
            Icons.Filled.CheckCircle,
            "Synced",
            MaterialTheme.colorScheme.primary
        )
        SyncStatus.PENDING -> Triple(
            Icons.Filled.Schedule,
            "Sync pending",
            MaterialTheme.colorScheme.tertiary
        )
        SyncStatus.FAILED -> Triple(
            Icons.Filled.ErrorOutline,
            "Sync failed",
            MaterialTheme.colorScheme.error
        )
    }

    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description }
    )
}

// â”€â”€â”€ AI prompt section â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun AiPromptSection(
    isGeneratingAi: Boolean,
    aiSuggestedTodos: List<TodoItem>,
    onGenerateFromPrompt: (String) -> Unit,
    onAcceptSuggested: (TodoItem) -> Unit,
    onDismissSuggestions: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.md)
            .padding(bottom = MaterialTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
    ) {
        // Toggle button
        TextButton(
            onClick = { isExpanded = !isExpanded },
            modifier = Modifier.semantics {
                contentDescription = if (isExpanded) "Collapse AI prompt" else "Generate to-dos with AI"
            }
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text("âœ¨ Generate with AI")
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Describe your tasksâ€¦") },
                    minLines = 2,
                    enabled = !isGeneratingAi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "AI prompt input" }
                )

                Button(
                    onClick = {
                        if (promptText.isNotBlank()) onGenerateFromPrompt(promptText)
                    },
                    enabled = !isGeneratingAi && promptText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Generate to-dos from prompt" }
                ) {
                    Text("Generate")
                }

                if (isGeneratingAi) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Generating to-dos" }
                    )
                }

                // AI suggestions section
                if (aiSuggestedTodos.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Suggestions",
                            style = MaterialTheme.typography.titleSmall
                        )
                        IconButton(
                            onClick = onDismissSuggestions,
                            modifier = Modifier.semantics {
                                contentDescription = "Dismiss all AI suggestions"
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null
                            )
                        }
                    }

                    aiSuggestedTodos.forEach { suggested ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MaterialTheme.spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = suggested.title,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (suggested.description.isNotBlank()) {
                                        Text(
                                            text = suggested.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { onAcceptSuggested(suggested) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Add suggested to-do: ${suggested.title}"
                                    }
                                ) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
