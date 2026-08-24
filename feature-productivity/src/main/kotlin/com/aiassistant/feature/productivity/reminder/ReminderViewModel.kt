/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Reminders
 *              sub-feature of the Productivity Suite.
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * File       : ReminderViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Reminders
 *              sub-feature of the Productivity Suite.
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : MVVM ViewModel
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
 * ReminderViewModel.kt
 *
 * Purpose: Shared ViewModel for the Productivity Suite sub-features â€” Reminders
 *          (this task), with StateFlow-backed state management, CRUD operations,
 *          AI suggestion, and AlarmManager scheduling delegated to
 *          ReminderNotificationManager.
 *
 * Architecture: feature-productivity â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (CreateReminderUseCase, UpdateReminderUseCase, DeleteReminderUseCase,
 *               GetRemindersUseCase, SuggestReminderUseCase, GetTodosUseCase),
 *               ReminderNotificationManager, core-common (DispatcherProvider, ApiResult).
 *
 * Requirements: 16.3, 16.4, 19.1
 */
package com.aiassistant.feature.productivity.reminder

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.usecase.productivity.CreateReminderUseCase
import com.aiassistant.domain.usecase.productivity.DeleteReminderUseCase
import com.aiassistant.domain.usecase.productivity.GetRemindersUseCase
import com.aiassistant.domain.usecase.productivity.GetTodosUseCase
import com.aiassistant.domain.usecase.productivity.SuggestReminderUseCase
import com.aiassistant.domain.usecase.productivity.UpdateReminderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared ViewModel for the Productivity Suite, currently handling the Reminders
 * sub-feature.
 *
 * Exposes a [StateFlow] of [ReminderUiState] observed by [ReminderListScreen] and
 * [ReminderEditorScreen]. All blocking I/O is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class ReminderViewModel @Inject constructor(
    private val getRemindersUseCase: GetRemindersUseCase,
    private val createReminderUseCase: CreateReminderUseCase,
    private val updateReminderUseCase: UpdateReminderUseCase,
    private val deleteReminderUseCase: DeleteReminderUseCase,
    private val suggestReminderUseCase: SuggestReminderUseCase,
    private val getTodosUseCase: GetTodosUseCase,
    private val notificationManager: ReminderNotificationManager,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<ReminderUiState>(ReminderUiState.Loading)

    /** Observable reminders UI state. */
    val uiState: StateFlow<ReminderUiState> = _uiState.asStateFlow()

    // â”€â”€ Init â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    init {
        notificationManager.ensureNotificationChannel()
        loadReminders()
    }

    // â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Loads all reminders from Room and emits [ReminderUiState.ReminderList].
     *
     * Subscribes to the [GetRemindersUseCase] Flow; each emission from Room replaces
     * the current state (offline-first, reactive).
     */
    fun loadReminders() {
        viewModelScope.launch {
            _uiState.value = ReminderUiState.Loading
            getRemindersUseCase().collect { result ->
                _uiState.value = when (result) {
                    is ApiResult.Success -> ReminderUiState.ReminderList(
                        reminders = result.data
                    )
                    is ApiResult.Error -> ReminderUiState.Error(result.error.message)
                    is ApiResult.NetworkUnavailable -> ReminderUiState.ReminderList(
                        reminders = emptyList()
                    )
                    is ApiResult.Loading -> ReminderUiState.Loading
                }
            }
        }
    }

    /**
     * Transitions to [ReminderUiState.ReminderEditor] with a new blank reminder.
     *
     * Loads the list of existing [TodoItem] objects for the linked-todo picker.
     * Checks [ReminderNotificationManager.canScheduleExactAlarms] for Android 12+.
     */
    fun openNewReminder() {
        val now = Instant.now().toEpochMilli()
        val empty = Reminder(
            id = UUID.randomUUID().toString(),
            userId = "",
            title = "",
            triggerTime = now + 60 * 60 * 1000L, // default: 1 hour from now
            recurrenceRule = null,
            linkedTodoId = null,
            isCompleted = false,
            syncStatus = SyncStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        openEditor(reminder = empty, isNew = true)
    }

    /**
     * Transitions to [ReminderUiState.ReminderEditor] for an existing [reminder].
     */
    fun openEditReminder(reminder: Reminder) {
        openEditor(reminder = reminder, isNew = false)
    }

    /**
     * Updates the draft reminder in [ReminderUiState.ReminderEditor] without persisting.
     *
     * @param title           Updated reminder title.
     * @param triggerTime     Updated trigger time (epoch ms).
     * @param recurrenceRule  Updated iCal RRULE string or null for one-time.
     * @param linkedTodoId    Updated linked TodoItem id, or null.
     */
    fun updateDraft(title: String, triggerTime: Long, recurrenceRule: String?, linkedTodoId: String?) {
        val current = _uiState.value as? ReminderUiState.ReminderEditor ?: return
        _uiState.value = current.copy(
            reminder = current.reminder.copy(
                title = title,
                triggerTime = triggerTime,
                recurrenceRule = recurrenceRule,
                linkedTodoId = linkedTodoId,
                updatedAt = Instant.now().toEpochMilli()
            ),
            titleError = null,
            triggerTimeError = null
        )
    }

    /**
     * Validates and saves the current reminder draft.
     *
     * - For new reminders: calls [CreateReminderUseCase] then schedules alarm.
     * - For existing reminders: cancels old alarm, calls [UpdateReminderUseCase], then
     *   schedules updated alarm.
     *
     * On success, transitions back to [ReminderUiState.ReminderList].
     * On validation failure, sets inline field errors in [ReminderUiState.ReminderEditor].
     */
    fun saveReminder() {
        val editorState = _uiState.value as? ReminderUiState.ReminderEditor ?: return
        val reminder = editorState.reminder

        // â”€â”€ Client-side validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        val titleError = if (reminder.title.isBlank()) "Title is required." else null
        val triggerTimeError = if (reminder.triggerTime <= System.currentTimeMillis()) {
            "Trigger time must be in the future."
        } else {
            null
        }

        if (titleError != null || triggerTimeError != null) {
            _uiState.value = editorState.copy(
                titleError = titleError,
                triggerTimeError = triggerTimeError
            )
            return
        }

        _uiState.value = editorState.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                if (editorState.isNew) {
                    createReminderUseCase(reminder)
                } else {
                    // Cancel old alarm before rescheduling with new time
                    notificationManager.cancelAlarm(reminder.id, reminder.title)
                    updateReminderUseCase(reminder)
                }
            }
            when (result) {
                is ApiResult.Success -> {
                    // Schedule alarm on IO thread â€” safe since it only calls system services
                    withContext(dispatchers.io) {
                        notificationManager.scheduleAlarm(result.data)
                    }
                    loadReminders()
                }
                is ApiResult.Error -> _uiState.value = ReminderUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> {
                    // Still schedule alarm locally; sync will happen when connectivity restores
                    withContext(dispatchers.io) {
                        notificationManager.scheduleAlarm(reminder)
                    }
                    loadReminders()
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Deletes the reminder with [reminderId], cancels its alarm, and holds it in
     * [ReminderUiState.ReminderList.deletedReminder] for undo.
     *
     * @param reminderId The unique identifier of the reminder to delete.
     */
    fun deleteReminder(reminderId: String) {
        val currentList = _uiState.value as? ReminderUiState.ReminderList ?: return
        val target = currentList.reminders.firstOrNull { it.id == reminderId } ?: return

        viewModelScope.launch {
            withContext(dispatchers.io) {
                notificationManager.cancelAlarm(reminderId, target.title)
                deleteReminderUseCase(reminderId)
            }
            // Immediately optimistically remove from list and keep for undo
            _uiState.value = currentList.copy(
                reminders = currentList.reminders.filter { it.id != reminderId },
                deletedReminder = target
            )
        }
    }

    /**
     * Undoes the most recent delete by re-creating the reminder and rescheduling its alarm.
     */
    fun undoDelete() {
        val currentList = _uiState.value as? ReminderUiState.ReminderList ?: return
        val deleted = currentList.deletedReminder ?: return

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                createReminderUseCase(deleted)
            }
            if (result is ApiResult.Success) {
                withContext(dispatchers.io) {
                    notificationManager.scheduleAlarm(result.data)
                }
            }
            // Clear the undo state regardless of outcome; loadReminders refreshes the list
            loadReminders()
        }
    }

    /**
     * Clears the pending undo state without restoring the deleted reminder.
     */
    fun clearUndoState() {
        val current = _uiState.value as? ReminderUiState.ReminderList ?: return
        _uiState.value = current.copy(deletedReminder = null)
    }

    /**
     * Requests an AI-suggested [Reminder] from a natural language [prompt].
     *
     * Transitions through [ReminderUiState.AiSuggesting] then opens the editor
     * pre-populated with the returned suggestion for the user to review and save.
     *
     * @param prompt Natural language text (e.g. "Remind me to review the PR before standup").
     */
    fun suggestReminder(prompt: String) {
        _uiState.value = ReminderUiState.AiSuggesting(prompt = prompt)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                suggestReminderUseCase(prompt)
            }
            when (result) {
                is ApiResult.Success -> {
                    // Pre-populate the editor with the AI suggestion
                    openEditor(reminder = result.data, isNew = true)
                }
                is ApiResult.Error -> _uiState.value = ReminderUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> _uiState.value = ReminderUiState.Error(
                    "No network connection. AI features require internet access."
                )
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Navigates back to the reminder list without saving.
     */
    fun backToList() {
        loadReminders()
    }

    /**
     * Returns the [Intent] to open the system exact-alarm settings page on Android 12+.
     *
     * Returns null on earlier API levels where the settings page does not exist.
     */
    fun buildExactAlarmSettingsIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    } else {
        null
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Opens the editor, loading available todos for the linked-todo picker.
     */
    private fun openEditor(reminder: Reminder, isNew: Boolean) {
        val canSchedule = notificationManager.canScheduleExactAlarms()
        _uiState.value = ReminderUiState.ReminderEditor(
            reminder = reminder,
            isNew = isNew,
            canScheduleExact = canSchedule
        )
        // Load todos for the linked-todo picker asynchronously
        viewModelScope.launch {
            getTodosUseCase().collect { result ->
                val current = _uiState.value as? ReminderUiState.ReminderEditor ?: return@collect
                if (result is ApiResult.Success) {
                    _uiState.value = current.copy(availableTodos = result.data)
                }
                // Cancel collection after first meaningful emission
                return@collect
            }
        }
    }
}
