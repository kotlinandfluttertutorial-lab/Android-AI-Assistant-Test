/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ProductivityRepository.kt
 * Purpose    : Domain contract defining data access operations for Productivity entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * Module     : domain
 * File       : ProductivityRepository.kt
 * Purpose    : Domain contract defining data access operations for Productivity entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * ProductivityRepository.kt
 *
 * Purpose: Domain-layer repository interface for all Productivity Suite operations
 *          (To-Do List, Calendar, Reminders, Habit Tracker).
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain models (TodoItem, CalendarEvent, Reminder,
 *               HabitDefinition, HabitEntry, Priority, SyncStatus)
 *
 * Requirements: 13.1, 16.3, 16.4, 19.2
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.Priority
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.TodoItem
import kotlinx.coroutines.flow.Flow

// â”€â”€â”€ To-Do List filter â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Filter criteria for querying [TodoItem] objects.
 *
 * @param showCompleted    If `true`, include completed items; if `false`, only pending items.
 * @param dueBefore        Optional epoch milliseconds upper bound for [TodoItem.dueDate].
 * @param priority         Optional priority level filter.
 * @param tag              Optional tag filter â€” only items containing this tag are returned.
 */
data class TodoFilter(
    val showCompleted: Boolean = true,
    val dueBefore: Long? = null,
    val priority: Priority? = null,
    val tag: String? = null
)

// â”€â”€â”€ Calendar date range â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A closed time range expressed as epoch milliseconds, used to query [CalendarEvent]
 * objects within a specific window (e.g. a calendar month or week).
 *
 * @param start  Epoch milliseconds of the range start (inclusive).
 * @param end    Epoch milliseconds of the range end (inclusive).
 */
data class DateRange(val start: Long, val end: Long)

// â”€â”€â”€ ProductivityRepository interface â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Unified contract for all four Productivity Suite sub-features:
 * To-Do List, Calendar, Reminders, and Habit Tracker.
 *
 * All four sub-features follow the same local-first, server-synced pattern:
 * Room is the single source of truth on the device; backend sync is gated by
 * [com.aiassistant.domain.repository.ConnectivityObserver] in the data layer.
 *
 * Requirement 13.1: All sub-features persist data locally and sync to the backend
 * when the device is connected.
 */
interface ProductivityRepository {

    // â”€â”€ To-Do List â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of [TodoItem] objects for the authenticated user, filtered by
     * [filter] criteria, sorted by [TodoItem.dueDate] ascending (nulls last) then
     * by [TodoItem.priority] descending.
     *
     * @param filter Criteria to narrow the result set.
     * @return Cold [Flow] emitting [ApiResult.Success] with the filtered list.
     */
    fun getTodos(filter: TodoFilter = TodoFilter()): Flow<ApiResult<List<TodoItem>>>

    /**
     * Creates a new [TodoItem] in the local database with
     * [com.aiassistant.domain.model.SyncStatus.PENDING] and submits it to the backend
     * when connected.
     *
     * @param todo The to-do item to create.
     * @return [ApiResult.Success] with the persisted [TodoItem] on success.
     */
    suspend fun createTodo(todo: TodoItem): ApiResult<TodoItem>

    /**
     * Updates an existing [TodoItem] in the local database and queues a remote sync.
     *
     * @param todo The modified [TodoItem] to persist.
     * @return [ApiResult.Success] with the updated [TodoItem] on success.
     */
    suspend fun updateTodo(todo: TodoItem): ApiResult<TodoItem>

    /**
     * Permanently deletes a [TodoItem] from the local database and the backend.
     *
     * @param todoId The unique identifier of the to-do item to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteTodo(todoId: String): ApiResult<Unit>

    /**
     * Requests AI-generated [TodoItem] objects from a natural language description.
     *
     * The AI Orchestrator parses [prompt] and returns a list of structured to-do items
     * the user can accept, modify, or discard before saving.
     *
     * @param prompt Natural language description (e.g. "Plan a product launch").
     * @return [ApiResult.Success] with the list of suggested [TodoItem] objects on success.
     */
    suspend fun generateTodosFromPrompt(prompt: String): ApiResult<List<TodoItem>>

    // â”€â”€ Calendar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of [CalendarEvent] objects within the given [range], sourced from
     * local Room. When connected and configured, events from the Google Calendar MCP
     * connector are merged into the emission.
     *
     * @param range The date/time window to query.
     * @return Cold [Flow] emitting [ApiResult.Success] with the events in the range.
     */
    fun getCalendarEvents(range: DateRange): Flow<ApiResult<List<CalendarEvent>>>

    /**
     * Creates a new [CalendarEvent] in the local database and syncs to the backend.
     *
     * @param event The event to create.
     * @return [ApiResult.Success] with the persisted [CalendarEvent] on success.
     */
    suspend fun createCalendarEvent(event: CalendarEvent): ApiResult<CalendarEvent>

    /**
     * Permanently deletes a [CalendarEvent] from the local database and the backend.
     *
     * @param eventId The unique identifier of the event to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteCalendarEvent(eventId: String): ApiResult<Unit>

    // â”€â”€ Reminders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of all [Reminder] objects for the authenticated user, sorted by
     * [Reminder.triggerTime] ascending.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full sorted reminder list.
     */
    fun getReminders(): Flow<ApiResult<List<Reminder>>>

    /**
     * Creates a new [Reminder] in the local database and queues AlarmManager scheduling
     * in the use case layer (Requirement 16.3).
     *
     * @param reminder The reminder to create.
     * @return [ApiResult.Success] with the persisted [Reminder] on success.
     */
    suspend fun createReminder(reminder: Reminder): ApiResult<Reminder>

    /**
     * Updates an existing [Reminder] in the local database.
     *
     * The use case layer is responsible for rescheduling AlarmManager after update.
     *
     * @param reminder The modified [Reminder] to persist.
     * @return [ApiResult.Success] with the updated [Reminder] on success.
     */
    suspend fun updateReminder(reminder: Reminder): ApiResult<Reminder>

    /**
     * Permanently deletes a [Reminder] from the local database and cancels any
     * scheduled AlarmManager alarm.
     *
     * @param reminderId The unique identifier of the reminder to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteReminder(reminderId: String): ApiResult<Unit>

    /**
     * Requests an AI-suggested [Reminder] from a natural language description.
     *
     * @param prompt Natural language prompt (e.g. "Remind me to review the PR before
     *               tomorrow's standup").
     * @return [ApiResult.Success] with a pre-populated [Reminder] the user can confirm
     *         or modify before saving.
     */
    suspend fun suggestReminder(prompt: String): ApiResult<Reminder>

    // â”€â”€ Habit Tracker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of all [HabitDefinition] objects for the authenticated user,
     * sorted by creation date descending.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full habit list.
     */
    fun getHabits(): Flow<ApiResult<List<HabitDefinition>>>

    /**
     * Creates a new [HabitDefinition] in the local database and syncs to the backend.
     *
     * @param habit The habit definition to create.
     * @return [ApiResult.Success] with the persisted [HabitDefinition] on success.
     */
    suspend fun createHabit(habit: HabitDefinition): ApiResult<HabitDefinition>

    /**
     * Permanently deletes a [HabitDefinition] and all its associated [HabitEntry]
     * records from the local database and the backend.
     *
     * @param habitId The unique identifier of the habit to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteHabit(habitId: String): ApiResult<Unit>

    /**
     * Returns a [Flow] of [HabitEntry] objects for a specific habit, sorted by
     * [HabitEntry.completedAt] descending.
     *
     * @param habitId The unique identifier of the [HabitDefinition] to query entries for.
     * @return Cold [Flow] emitting [ApiResult.Success] with the habit's completion entries.
     */
    fun getHabitEntries(habitId: String): Flow<ApiResult<List<HabitEntry>>>

    /**
     * Records a habit completion event in the local database and queues a remote sync.
     *
     * @param entry The [HabitEntry] to log.
     * @return [ApiResult.Success] with the persisted [HabitEntry] on success.
     */
    suspend fun logHabitEntry(entry: HabitEntry): ApiResult<HabitEntry>

    /**
     * Requests AI-generated insights about a habit's completion patterns.
     *
     * The AI Orchestrator analyses the habit's [HabitEntry] history and returns a
     * natural language summary covering completion rate, best/worst days, and streak
     * predictions.
     *
     * @param habitId The unique identifier of the habit to analyse.
     * @return [ApiResult.Success] with the insights text on success.
     */
    suspend fun getHabitInsights(habitId: String): ApiResult<String>
}
