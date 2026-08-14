/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : TodoEditorScreen.kt
 * Purpose    : Compose UI screen for the TodoEditor feature
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
 * File       : TodoEditorScreen.kt
 * Purpose    : Compose UI screen for the TodoEditor feature
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
 * TodoEditorScreen.kt
 *
 * Purpose: Stateless Compose screen for creating and editing todo items, including
 *          title, description, due date, priority selection, and tags.
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven by
 *               ProductivityUiState from ProductivityViewModel.
 * Dependencies: core-ui (MaterialTheme.spacing), domain (TodoItem, Priority), ProductivityUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Due date is selected via a Material3 DatePickerDialog.
 * - Priority is selected via a row of FilterChips (one selected at a time).
 * - Tags are entered via a text field; pressing Enter or comma adds them as deletable chips.
 * - A full-screen CircularProgressIndicator overlay is shown while saving.
 * - All interactive elements carry contentDescriptions (requirement 23.1).
 *
 * Requirements: 13.1, 19.1, 23.1
 */
package com.aiassistant.feature.productivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.TodoItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Todo editor screen composable.
 *
 * Renders title, description, due date selector, priority chips, and tags for a
 * todo item. Validates that the title is not blank before allowing save.
 *
 * @param uiState         Current state from [ProductivityViewModel].
 * @param onUpdateDraft   Invoked on every field change with the full updated values.
 * @param onSave          Invoked with the current todo when the user confirms save.
 * @param onBack          Invoked when the user taps the back navigation icon.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditorScreen(
    uiState: ProductivityUiState,
    onUpdateDraft: (title: String, description: String, dueDate: Long?, priority: Priority, tags: List<String>) -> Unit,
    onSave: (todo: TodoItem) -> Unit,
    onBack: () -> Unit
) {
    val editorState = uiState as? ProductivityUiState.TodoEditor
    val todo = editorState?.todo
    val isSaving = editorState?.isSaving == true
    val isNew = editorState?.isNew == true

    // Local state for tag input field
    var tagInput by remember { mutableStateOf("") }
    // Local state for title error
    var titleError by remember { mutableStateOf(false) }
    // Local state for date picker visibility
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = todo?.dueDate
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val selectedMs = datePickerState.selectedDateMillis
                        if (todo != null) {
                            onUpdateDraft(todo.title, todo.description, selectedMs, todo.priority, todo.tags)
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm due date"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New To-Do" else "Edit To-Do") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (todo != null) {
                                if (todo.title.isBlank()) {
                                    titleError = true
                                } else {
                                    titleError = false
                                    onSave(todo)
                                }
                            }
                        },
                        enabled = !isSaving && todo != null,
                        modifier = Modifier.semantics {
                            contentDescription = "Save to-do"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (todo != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
                ) {
                    // â”€â”€ Title field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    OutlinedTextField(
                        value = todo.title,
                        onValueChange = { newTitle ->
                            titleError = false
                            onUpdateDraft(newTitle, todo.description, todo.dueDate, todo.priority, todo.tags)
                        },
                        label = { Text("Title *") },
                        singleLine = true,
                        isError = titleError,
                        supportingText = if (titleError) {
                            { Text("Title is required") }
                        } else {
                            null
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "To-do title input" }
                    )

                    // â”€â”€ Description field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    OutlinedTextField(
                        value = todo.description,
                        onValueChange = { newDesc ->
                            onUpdateDraft(todo.title, newDesc, todo.dueDate, todo.priority, todo.tags)
                        },
                        label = { Text("Description") },
                        minLines = 3,
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "To-do description input" }
                    )

                    // â”€â”€ Due date selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    OutlinedCard(
                        onClick = { if (!isSaving) showDatePicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Select due date: ${
                                    todo.dueDate?.let { ms ->
                                        Instant.ofEpochMilli(ms)
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate()
                                            .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                                    } ?: "No due date"
                                }"
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(MaterialTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CalendarToday,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val dueDateText = todo.dueDate?.let { ms ->
                                Instant.ofEpochMilli(ms)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                    .format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                            } ?: "No due date"
                            Text(
                                text = dueDateText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (todo.dueDate != null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (todo.dueDate != null) {
                                IconButton(
                                    onClick = {
                                        onUpdateDraft(todo.title, todo.description, null, todo.priority, todo.tags)
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Clear due date"
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // â”€â”€ Priority selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Text(
                            text = "Priority",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                        ) {
                            Priority.entries.forEach { priority ->
                                FilterChip(
                                    selected = todo.priority == priority,
                                    onClick = {
                                        if (!isSaving) {
                                            onUpdateDraft(
                                                todo.title,
                                                todo.description,
                                                todo.dueDate,
                                                priority,
                                                todo.tags
                                            )
                                        }
                                    },
                                    label = {
                                        Text(priority.value.replaceFirstChar { it.uppercaseChar() })
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Set priority to ${priority.value}"
                                    }
                                )
                            }
                        }
                    }

                    // â”€â”€ Tags field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { input ->
                                // Split on comma to support comma-separated tag entry
                                if (input.endsWith(",")) {
                                    val newTag = input.dropLast(1).trim()
                                    if (newTag.isNotEmpty() && newTag !in todo.tags) {
                                        onUpdateDraft(
                                            todo.title,
                                            todo.description,
                                            todo.dueDate,
                                            todo.priority,
                                            todo.tags + newTag
                                        )
                                        tagInput = ""
                                    } else {
                                        tagInput = ""
                                    }
                                } else {
                                    tagInput = input
                                }
                            },
                            label = { Text("Add tags (press Enter or comma)") },
                            singleLine = true,
                            enabled = !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Tag input field" }
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.key == Key.Enter) {
                                        val newTag = tagInput.trim()
                                        if (newTag.isNotEmpty() && newTag !in todo.tags) {
                                            onUpdateDraft(
                                                todo.title,
                                                todo.description,
                                                todo.dueDate,
                                                todo.priority,
                                                todo.tags + newTag
                                            )
                                            tagInput = ""
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                        )

                        // Existing tags as deletable chips
                        if (todo.tags.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                            ) {
                                todo.tags.forEach { tag ->
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = { Text(tag) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    onUpdateDraft(
                                                        todo.title,
                                                        todo.description,
                                                        todo.dueDate,
                                                        todo.priority,
                                                        todo.tags - tag
                                                    )
                                                },
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .semantics {
                                                        contentDescription = "Remove tag: $tag"
                                                    }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Tag: $tag"
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    // â”€â”€ Bottom save button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Button(
                        onClick = {
                            if (todo.title.isBlank()) {
                                titleError = true
                            } else {
                                titleError = false
                                onSave(todo)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Save to-do" }
                    ) {
                        Text("Save")
                    }
                }
            }

            // â”€â”€ Saving overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isSaving) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Saving to-do"
                            }
                        )
                    }
                }
            }
        }
    }
}
