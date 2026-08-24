/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : HabitEditorScreen.kt
 * Purpose    : Compose UI screen for the HabitEditor feature
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
 * File       : HabitEditorScreen.kt
 * Purpose    : Compose UI screen for the HabitEditor feature
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
 * HabitEditorScreen.kt
 *
 * Purpose: Compose screen for creating and editing a HabitDefinition. Provides name
 *          and description fields, a recurrence selector (Daily / Weekly), a target
 *          frequency stepper, and Save / Back actions with inline validation.
 *
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven
 *               by HabitUiState from HabitViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               domain (HabitDefinition, HabitRecurrence), HabitUiState.
 *
 * Design decisions:
 * - Stateless composable: all state + callbacks passed as parameters.
 * - FilterChip pair for recurrence â€” only one selected at a time.
 * - Target-frequency stepper: Row with âˆ’ / + buttons, min 1, max 99.
 * - All interactive elements carry contentDescription (Requirements 23.1, 23.4).
 * - All spacing via MaterialTheme.spacing; all colors via MaterialTheme.colorScheme.
 *
 * Requirements: 13.1, 19.1
 */
package com.aiassistant.feature.productivity.habit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.HabitRecurrence

/**
 * Habit editor screen composable.
 *
 * @param uiState        Current state from [HabitViewModel].
 * @param onUpdateDraft  Invoked whenever any field changes.
 * @param onSave         Invoked when the user taps Save.
 * @param onBack         Invoked when the user taps the back arrow.
 */
@Composable
fun HabitEditorScreen(
    uiState: HabitUiState,
    onUpdateDraft: (name: String, description: String, recurrence: HabitRecurrence, targetFrequency: Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val editorState = uiState as? HabitUiState.HabitEditor ?: return
    val habit = editorState.habit

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editorState.isNew) "New Habit" else "Edit Habit") },
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
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
            ) {
                // â”€â”€ Name field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                OutlinedTextField(
                    value = habit.name,
                    onValueChange = { newName ->
                        onUpdateDraft(
                            newName,
                            habit.description,
                            habit.recurrence,
                            habit.targetFrequency
                        )
                    },
                    label = { Text("Name *") },
                    singleLine = true,
                    isError = editorState.nameError != null,
                    supportingText = editorState.nameError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Habit name input" }
                )

                // â”€â”€ Description field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                OutlinedTextField(
                    value = habit.description,
                    onValueChange = { newDesc ->
                        onUpdateDraft(
                            habit.name,
                            newDesc,
                            habit.recurrence,
                            habit.targetFrequency
                        )
                    },
                    label = { Text("Description (optional)") },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Habit description input" }
                )

                // â”€â”€ Recurrence selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Text(
                    text = "Recurrence",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    FilterChip(
                        selected = habit.recurrence == HabitRecurrence.DAILY,
                        onClick = {
                            onUpdateDraft(
                                habit.name,
                                habit.description,
                                HabitRecurrence.DAILY,
                                habit.targetFrequency
                            )
                        },
                        label = { Text("Daily") },
                        modifier = Modifier.semantics {
                            contentDescription = "Select daily recurrence"
                        }
                    )
                    FilterChip(
                        selected = habit.recurrence == HabitRecurrence.WEEKLY,
                        onClick = {
                            onUpdateDraft(
                                habit.name,
                                habit.description,
                                HabitRecurrence.WEEKLY,
                                habit.targetFrequency
                            )
                        },
                        label = { Text("Weekly") },
                        modifier = Modifier.semantics {
                            contentDescription = "Select weekly recurrence"
                        }
                    )
                }

                // â”€â”€ Target frequency stepper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Text(
                    text = "Target frequency",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    IconButton(
                        onClick = {
                            val newFreq = (habit.targetFrequency - 1).coerceAtLeast(1)
                            onUpdateDraft(
                                habit.name,
                                habit.description,
                                habit.recurrence,
                                newFreq
                            )
                        },
                        enabled = habit.targetFrequency > 1,
                        modifier = Modifier.semantics {
                            contentDescription = "Decrease target frequency"
                        }
                    ) {
                        Text(
                            text = "âˆ’",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Text(
                        text = habit.targetFrequency.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "Target frequency: ${habit.targetFrequency}"
                        }
                    )

                    IconButton(
                        onClick = {
                            val newFreq = (habit.targetFrequency + 1).coerceAtMost(99)
                            onUpdateDraft(
                                habit.name,
                                habit.description,
                                habit.recurrence,
                                newFreq
                            )
                        },
                        enabled = habit.targetFrequency < 99,
                        modifier = Modifier.semantics {
                            contentDescription = "Increase target frequency"
                        }
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                Spacer(Modifier.height(MaterialTheme.spacing.md))

                // â”€â”€ Save button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Button(
                    onClick = onSave,
                    enabled = !editorState.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Save habit" }
                ) {
                    if (editorState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(MaterialTheme.spacing.md)
                                .semantics { contentDescription = "Saving habitâ€¦" },
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.sm))
                    }
                    Text(if (editorState.isSaving) "Savingâ€¦" else "Save")
                }
            }

            // â”€â”€ Saving overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (editorState.isSaving) {
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
                                contentDescription = "Saving habitâ€¦"
                            }
                        )
                    }
                }
            }
        }
    }
}
