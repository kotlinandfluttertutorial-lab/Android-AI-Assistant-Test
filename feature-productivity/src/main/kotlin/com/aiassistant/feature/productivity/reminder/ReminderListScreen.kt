/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderListScreen.kt
 * Purpose    : Compose UI screen for the ReminderList feature
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
 * File       : ReminderListScreen.kt
 * Purpose    : Compose UI screen for the ReminderList feature
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
 * ReminderListScreen.kt
 *
 * Purpose: Compose screen displaying the list of upcoming Reminder objects sorted by
 *          trigger time, with swipe-to-delete + undo snackbar, a FAB for creating new
 *          reminders, and an "AI Suggest" chip that opens a natural-language dialog.
 *
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven by
 *               ReminderUiState from ProductivityViewModel.
 * Dependencies: core-ui (ErrorBanner, LoadingIndicator, MaterialTheme.spacing),
 *               domain (Reminder), ReminderUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks passed as parameters.
 * - SwipeToDismiss is used for swipe-to-delete with immediate optimistic removal and
 *   an undo action on the Snackbar.
 * - All interactive elements carry contentDescriptions (Requirement 23.1, 23.4).
 * - Minimum contrast 4.5:1 satisfied by using Material 3 color roles only.
 * - Empty state illustration with instructional text when no reminders exist.
 * - AI Suggest chip opens an AlertDialog for natural language input.
 *
 * Requirements: 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.reminder

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Reminder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Reminder list screen composable.
 *
 * Renders the full list of upcoming reminders with swipe-to-delete, undo snackbar,
 * AI Suggest chip, and FAB for new reminders.
 *
 * @param uiState          Current state from [ProductivityViewModel].
 * @param onReminderClick  Invoked when the user taps a reminder card to edit.
 * @param onNewReminder    Invoked when the user taps the FAB.
 * @param onDeleteReminder Invoked with the reminder id when the user swipes to delete.
 * @param onUndoDelete     Invoked when the user taps "Undo" in the snackbar.
 * @param onClearUndo      Invoked when the undo snackbar times out or is dismissed.
 * @param onAiSuggest      Invoked with the natural language prompt string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderListScreen(
    uiState: ReminderUiState,
    onReminderClick: (Reminder) -> Unit,
    onNewReminder: () -> Unit,
    onDeleteReminder: (String) -> Unit,
    onUndoDelete: () -> Unit,
    onClearUndo: () -> Unit,
    onAiSuggest: (String) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Show undo snackbar whenever there is a pending deleted reminder
    val deletedReminder = (uiState as? ReminderUiState.ReminderList)?.deletedReminder
    LaunchedEffect(deletedReminder) {
        if (deletedReminder != null) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "\"${deletedReminder.title}\" deleted",
                    actionLabel = "Undo"
                )
                when (result) {
                    SnackbarResult.ActionPerformed -> onUndoDelete()
                    SnackbarResult.Dismissed -> onClearUndo()
                }
            }
        }
    }

    // AI Suggest dialog state
    var showAiDialog by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }

    if (showAiDialog) {
        AiSuggestDialog(
            prompt = aiPrompt,
            onPromptChange = { aiPrompt = it },
            onConfirm = {
                showAiDialog = false
                onAiSuggest(aiPrompt)
                aiPrompt = ""
            },
            onDismiss = {
                showAiDialog = false
                aiPrompt = ""
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Reminders") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewReminder,
                modifier = Modifier.semantics {
                    contentDescription = "Create new reminder"
                }
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Error banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is ReminderUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // â”€â”€ Loading indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is ReminderUiState.Loading || uiState is ReminderUiState.AiSuggesting) {
                val label = if (uiState is ReminderUiState.AiSuggesting) {
                    "Getting AI suggestionâ€¦"
                } else {
                    "Loading reminders"
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = label }
                        )
                        Spacer(Modifier.height(MaterialTheme.spacing.sm))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@Column
            }

            if (uiState !is ReminderUiState.ReminderList) return@Column

            // â”€â”€ AI Suggest chip â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { showAiDialog = true },
                    label = { Text("AI Suggest") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Open AI reminder suggestion dialog"
                    }
                )
            }

            // â”€â”€ Empty state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState.reminders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No upcoming reminders",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap + or use AI Suggest to create one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // â”€â”€ Reminder list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    items(uiState.reminders, key = { it.id }) { reminder ->
                        SwipeableReminderCard(
                            reminder = reminder,
                            onClick = { onReminderClick(reminder) },
                            onDelete = { onDeleteReminder(reminder.id) }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Swipeable reminder card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A [Card] wrapped in [SwipeToDismissBox] so the user can swipe left to delete.
 *
 * The red background with a [Icons.Filled.Delete] icon appears during the swipe gesture
 * to communicate the action (icon + colour â€” not colour-only, Requirement 23.4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableReminderCard(reminder: Reminder, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = MaterialTheme.spacing.md),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete reminder ${reminder.title}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    ) {
        ReminderCard(reminder = reminder, onClick = onClick)
    }
}

// â”€â”€â”€ Reminder card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Displays a single [reminder] card with title, formatted trigger time,
 * recurrence badge, and completion status.
 */
@Composable
private fun ReminderCard(reminder: Reminder, onClick: () -> Unit) {
    val triggerFormatted = remember(reminder.triggerTime) {
        formatTriggerTime(reminder.triggerTime)
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Reminder: ${reminder.title}, due $triggerFormatted" },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = if (reminder.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(MaterialTheme.spacing.xs))
                Text(
                    text = reminder.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f)
                )
                // Recurrence badge
                if (reminder.recurrenceRule != null) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = "Recurring reminder",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = "Recurring" }
                    )
                }
            }

            Text(
                text = triggerFormatted,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (reminder.isCompleted) {
                Text(
                    text = "Completed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// â”€â”€â”€ AI Suggest dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A dialog that accepts a natural language prompt for AI reminder suggestion.
 */
@Composable
private fun AiSuggestDialog(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Reminder Suggestion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Text(
                    text = "Describe what you want to be reminded about in natural language.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    label = { Text("e.g. Remind me to review the PR before standup") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "AI reminder prompt input" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = prompt.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Get AI reminder suggestion" }
            ) {
                Text("Suggest")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel AI suggestion" }
            ) {
                Text("Cancel")
            }
        }
    )
}

// â”€â”€â”€ Date/time formatting helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private val DATE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())

/**
 * Formats [triggerTime] epoch milliseconds into a human-readable date+time string
 * (e.g. "Jun 5, 2025 at 9:30 AM") using [java.time] (API 26+).
 */
private fun formatTriggerTime(triggerTime: Long): String = try {
    val instant = Instant.ofEpochMilli(triggerTime)
    val local = instant.atZone(ZoneId.systemDefault())
    DATE_TIME_FORMATTER.format(local)
} catch (e: Exception) {
    triggerTime.toString()
}
