/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderEditorScreen.kt
 * Purpose    : Compose UI screen for the ReminderEditor feature
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
 * File       : ReminderEditorScreen.kt
 * Purpose    : Compose UI screen for the ReminderEditor feature
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
 * ReminderEditorScreen.kt
 *
 * Purpose: Compose screen for creating and editing a Reminder. Provides a title field,
 *          Material 3 DatePicker + TimePicker for trigger time, a recurrence rule
 *          selector (None / Daily / Weekly / Custom), an optional linked TodoItem
 *          dropdown, and Save / Cancel actions with inline validation.
 *
 * Architecture: feature-productivity â€” Compose UI layer; stateless composable driven by
 *               ReminderUiState from ProductivityViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               domain (Reminder, TodoItem), ReminderUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - A banner is shown when canScheduleExact is false with a button to open system settings.
 * - All interactive elements carry contentDescriptions (Requirement 23.1).
 * - iCal RRULE mapping: None â†’ null, Daily â†’ "FREQ=DAILY", Weekly â†’ "FREQ=WEEKLY",
 *   Custom â†’ free-text RRULE input.
 *
 * Requirements: 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.reminder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// â”€â”€â”€ Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Reminder editor screen composable.
 *
 * @param uiState            Current state from [ProductivityViewModel].
 * @param onUpdateDraft      Invoked whenever any field changes.
 * @param onSave             Invoked when the user taps Save.
 * @param onBack             Invoked when the user taps the back arrow / Cancel.
 * @param onOpenExactAlarmSettings Invoked to open system SCHEDULE_EXACT_ALARM settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEditorScreen(
    uiState: ReminderUiState,
    onUpdateDraft: (title: String, triggerTime: Long, recurrenceRule: String?, linkedTodoId: String?) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit
) {
    val editorState = uiState as? ReminderUiState.ReminderEditor ?: return
    val reminder = editorState.reminder

    // â”€â”€ Date / Time picker dialogs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val triggerInstant = remember(reminder.triggerTime) {
        Instant.ofEpochMilli(reminder.triggerTime).atZone(ZoneId.systemDefault())
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = triggerInstant.toInstant().toEpochMilli()
    )
    val timePickerState = rememberTimePickerState(
        initialHour = triggerInstant.hour,
        initialMinute = triggerInstant.minute
    )

    // â”€â”€ Recurrence state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var recurrencePreset by remember(reminder.recurrenceRule) {
        mutableStateOf(recurrencePresetFromRule(reminder.recurrenceRule))
    }
    var customRrule by remember(reminder.recurrenceRule) {
        mutableStateOf(
            if (recurrencePresetFromRule(reminder.recurrenceRule) == RecurrencePreset.CUSTOM) {
                reminder.recurrenceRule ?: ""
            } else {
                ""
            }
        )
    }

    // â”€â”€ Todo picker state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var todoDropdownExpanded by remember { mutableStateOf(false) }
    var recurrenceDropdownExpanded by remember { mutableStateOf(false) }

    // â”€â”€ Date picker dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                        val selectedDateMillis = datePickerState.selectedDateMillis
                        if (selectedDateMillis != null) {
                            // Combine selected date with current time
                            val newInstant = Instant.ofEpochMilli(selectedDateMillis)
                                .atZone(ZoneId.systemDefault())
                                .withHour(timePickerState.hour)
                                .withMinute(timePickerState.minute)
                                .withSecond(0)
                                .toInstant()
                            onUpdateDraft(
                                reminder.title,
                                newInstant.toEpochMilli(),
                                currentRrule(recurrencePreset, customRrule),
                                reminder.linkedTodoId
                            )
                        }
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // â”€â”€ Time picker dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = {
                TimePicker(
                    state = timePickerState,
                    modifier = Modifier.semantics { contentDescription = "Time picker" }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTimePicker = false
                        // Combine stored date with newly chosen time
                        val base = Instant.ofEpochMilli(reminder.triggerTime)
                            .atZone(ZoneId.systemDefault())
                        val newInstant = base
                            .withHour(timePickerState.hour)
                            .withMinute(timePickerState.minute)
                            .withSecond(0)
                            .toInstant()
                        onUpdateDraft(
                            reminder.title,
                            newInstant.toEpochMilli(),
                            currentRrule(recurrencePreset, customRrule),
                            reminder.linkedTodoId
                        )
                    }
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    // â”€â”€ Screen scaffold â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editorState.isNew) "New Reminder" else "Edit Reminder") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = !editorState.isSaving,
                        modifier = Modifier.semantics { contentDescription = "Save reminder" }
                    ) {
                        Text("Save")
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
                // â”€â”€ Exact alarm permission banner (Android 12+) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                if (!editorState.canScheduleExact) {
                    ErrorBanner(
                        message = "Exact alarm permission is required to deliver reminders at the exact time. " +
                            "Tap to enable.",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onOpenExactAlarmSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Open exact alarm settings" }
                    ) {
                        Text("Enable Exact Alarms")
                    }
                }

                // â”€â”€ Title â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                OutlinedTextField(
                    value = reminder.title,
                    onValueChange = { newTitle ->
                        onUpdateDraft(
                            newTitle,
                            reminder.triggerTime,
                            currentRrule(recurrencePreset, customRrule),
                            reminder.linkedTodoId
                        )
                    },
                    label = { Text("Title *") },
                    singleLine = true,
                    isError = editorState.titleError != null,
                    supportingText = editorState.titleError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Reminder title input" }
                )

                // â”€â”€ Date / Time pickers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Text(
                    text = "Trigger Time *",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (editorState.triggerTimeError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    OutlinedTextField(
                        value = formatDate(reminder.triggerTime),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        trailingIcon = {
                            IconButton(
                                onClick = { showDatePicker = true },
                                modifier = Modifier.semantics { contentDescription = "Pick date" }
                            ) {
                                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                            }
                        },
                        isError = editorState.triggerTimeError != null,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Trigger date: ${formatDate(reminder.triggerTime)}" }
                    )
                    OutlinedTextField(
                        value = formatTime(reminder.triggerTime),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        trailingIcon = {
                            IconButton(
                                onClick = { showTimePicker = true },
                                modifier = Modifier.semantics { contentDescription = "Pick time" }
                            ) {
                                Icon(Icons.Filled.Schedule, contentDescription = null)
                            }
                        },
                        isError = editorState.triggerTimeError != null,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Trigger time: ${formatTime(reminder.triggerTime)}" }
                    )
                }
                editorState.triggerTimeError?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // â”€â”€ Recurrence rule selector â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Text(
                    text = "Recurrence",
                    style = MaterialTheme.typography.labelMedium
                )
                ExposedDropdownMenuBox(
                    expanded = recurrenceDropdownExpanded,
                    onExpandedChange = { recurrenceDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = recurrencePreset.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Repeat") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = recurrenceDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .semantics { contentDescription = "Recurrence: ${recurrencePreset.label}" }
                    )
                    ExposedDropdownMenu(
                        expanded = recurrenceDropdownExpanded,
                        onDismissRequest = { recurrenceDropdownExpanded = false }
                    ) {
                        RecurrencePreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    recurrencePreset = preset
                                    recurrenceDropdownExpanded = false
                                    onUpdateDraft(
                                        reminder.title,
                                        reminder.triggerTime,
                                        currentRrule(preset, customRrule),
                                        reminder.linkedTodoId
                                    )
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Select recurrence: ${preset.label}"
                                }
                            )
                        }
                    }
                }

                // Custom RRULE input (shown only when CUSTOM is selected)
                if (recurrencePreset == RecurrencePreset.CUSTOM) {
                    OutlinedTextField(
                        value = customRrule,
                        onValueChange = { newRrule ->
                            customRrule = newRrule
                            onUpdateDraft(
                                reminder.title,
                                reminder.triggerTime,
                                newRrule.ifBlank { null },
                                reminder.linkedTodoId
                            )
                        },
                        label = { Text("iCal RRULE string") },
                        placeholder = { Text("e.g. FREQ=WEEKLY;BYDAY=MO,WE,FR") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Custom RRULE input" }
                    )
                }

                // â”€â”€ Linked TodoItem picker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                Text(
                    text = "Link to Todo (optional)",
                    style = MaterialTheme.typography.labelMedium
                )
                val selectedTodo = editorState.availableTodos.firstOrNull {
                    it.id == reminder.linkedTodoId
                }
                ExposedDropdownMenuBox(
                    expanded = todoDropdownExpanded,
                    onExpandedChange = { todoDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedTodo?.title ?: "None",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Linked Todo") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = todoDropdownExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Linked todo: ${selectedTodo?.title ?: "None"}"
                            }
                    )
                    ExposedDropdownMenu(
                        expanded = todoDropdownExpanded,
                        onDismissRequest = { todoDropdownExpanded = false }
                    ) {
                        // "None" option
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                todoDropdownExpanded = false
                                onUpdateDraft(
                                    reminder.title,
                                    reminder.triggerTime,
                                    currentRrule(recurrencePreset, customRrule),
                                    null
                                )
                            },
                            modifier = Modifier.semantics { contentDescription = "No linked todo" }
                        )
                        editorState.availableTodos.forEach { todo ->
                            DropdownMenuItem(
                                text = { Text(todo.title) },
                                onClick = {
                                    todoDropdownExpanded = false
                                    onUpdateDraft(
                                        reminder.title,
                                        reminder.triggerTime,
                                        currentRrule(recurrencePreset, customRrule),
                                        todo.id
                                    )
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Link to todo: ${todo.title}"
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(MaterialTheme.spacing.xl))

                // â”€â”€ Cancel button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Cancel and go back" }
                ) {
                    Text("Cancel")
                }
            }

            // â”€â”€ Saving overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
                            modifier = Modifier.semantics { contentDescription = "Saving reminderâ€¦" }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Returns the effective iCal RRULE string for the given [preset] and [customRrule].
 */
// â”€â”€â”€ iCal RRULE presets â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Represents a recurrence preset that maps to an optional iCal RRULE string. */
enum class RecurrencePreset(val label: String, val rrule: String?) {
    NONE("None", null),
    DAILY("Daily", "FREQ=DAILY"),
    WEEKLY("Weekly", "FREQ=WEEKLY"),
    CUSTOM("Custom (RRULE)â€¦", null) // handled separately via free-text input
}

/**
 * Infers a [RecurrencePreset] from a raw iCal RRULE [rule] string.
 */
private fun recurrencePresetFromRule(rule: String?): RecurrencePreset = when (rule) {
    null -> RecurrencePreset.NONE
    "FREQ=DAILY" -> RecurrencePreset.DAILY
    "FREQ=WEEKLY" -> RecurrencePreset.WEEKLY
    else -> RecurrencePreset.CUSTOM
}

private fun currentRrule(preset: RecurrencePreset, customRrule: String): String? = when (preset) {
    RecurrencePreset.NONE -> null
    RecurrencePreset.DAILY -> "FREQ=DAILY"
    RecurrencePreset.WEEKLY -> "FREQ=WEEKLY"
    RecurrencePreset.CUSTOM -> customRrule.ifBlank { null }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

private fun formatDate(epochMs: Long): String = try {
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DATE_FORMAT)
} catch (e: Exception) {
    ""
}

private fun formatTime(epochMs: Long): String = try {
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
} catch (e: Exception) {
    ""
}
