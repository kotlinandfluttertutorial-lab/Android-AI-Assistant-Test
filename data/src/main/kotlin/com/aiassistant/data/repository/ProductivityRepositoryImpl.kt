/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ProductivityRepositoryImpl.kt
 * Purpose    : Implements ProductivityRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * Module     : data
 * File       : ProductivityRepositoryImpl.kt
 * Purpose    : Implements ProductivityRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * ProductivityRepositoryImpl.kt â€” data module
 *
 * Purpose: Production implementation of [ProductivityRepository]. Orchestrates all four
 *          Productivity Suite sub-features (TodoItem, CalendarEvent, Reminder,
 *          HabitDefinition/HabitEntry) using a local-first strategy backed by Room DAOs
 *          and synced to the backend via [ProductivityRemoteDataSource].
 *
 * Architecture: data module â€” repository layer. Bridges domain contracts
 *               ([ProductivityRepository]) with Room DAOs and Retrofit. Wired at
 *               runtime via [ProductivityDataModule] Hilt bindings.
 *
 * Offline-first rules (Requirement 13.1):
 *   - All Flow-returning functions emit from Room immediately.
 *   - Create/update/delete operations write to Room first with SyncStatus.PENDING,
 *     then sync to the backend when connected.
 *   - [ConnectivityObserver] gates all remote calls.
 *
 * CalendarEvent special case:
 *   - When connected, [getCalendarEvents] merges events from the Google Calendar
 *     MCP connector (via [ProductivityRemoteDataSource.getGoogleCalendarEvents]) into
 *     the local Room cache alongside locally created events.
 *
 * Requirements: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.repository

import android.util.Log
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.database.dao.CalendarEventDao
import com.aiassistant.core.database.dao.HabitDefinitionDao
import com.aiassistant.core.database.dao.HabitEntryDao
import com.aiassistant.core.database.dao.ReminderDao
import com.aiassistant.core.database.dao.TodoItemDao
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.mapper.toDomain
import com.aiassistant.data.mapper.toEntity
import com.aiassistant.data.remote.productivity.ProductivityRemoteDataSource
import com.aiassistant.data.remote.productivity.SaveCalendarEventRequest
import com.aiassistant.data.remote.productivity.SaveHabitRequest
import com.aiassistant.data.remote.productivity.SaveReminderRequest
import com.aiassistant.data.remote.productivity.SaveTodoRequest
import com.aiassistant.domain.model.CalendarEvent
import com.aiassistant.domain.model.CalendarEventSource
import com.aiassistant.domain.model.HabitDefinition
import com.aiassistant.domain.model.HabitEntry
import com.aiassistant.domain.model.Reminder
import com.aiassistant.domain.model.SyncStatus
import com.aiassistant.domain.model.TodoItem
import com.aiassistant.domain.repository.DateRange
import com.aiassistant.domain.repository.ProductivityRepository
import com.aiassistant.domain.repository.TodoFilter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProductivityRepository"

/**
 * Local-first implementation of [ProductivityRepository].
 *
 * Room is the single source of truth for all four sub-features. Backend sync is gated
 * by [ConnectivityObserver].
 *
 * @param todoItemDao          DAO for TodoItem persistence.
 * @param calendarEventDao     DAO for CalendarEvent persistence.
 * @param reminderDao          DAO for Reminder persistence.
 * @param habitDefinitionDao   DAO for HabitDefinition persistence.
 * @param habitEntryDao        DAO for HabitEntry persistence.
 * @param remoteSource         Retrofit-backed remote data source for all sub-features.
 * @param connectivityObserver Synchronous connectivity check.
 * @param secureStorage        Credential store used to resolve the authenticated user ID.
 * @param dispatchers          Injectable dispatcher provider.
 */
@Singleton
class ProductivityRepositoryImpl @Inject constructor(
    private val todoItemDao: TodoItemDao,
    private val calendarEventDao: CalendarEventDao,
    private val reminderDao: ReminderDao,
    private val habitDefinitionDao: HabitDefinitionDao,
    private val habitEntryDao: HabitEntryDao,
    private val remoteSource: ProductivityRemoteDataSource,
    private val connectivityObserver: ConnectivityObserver,
    private val secureStorage: SecureStorage,
    private val dispatchers: DispatcherProvider
) : ProductivityRepository {

    /** Application-scoped scope for fire-and-forget background sync operations. */
    private val syncScope = CoroutineScope(dispatchers.io + SupervisorJob())

    // â”€â”€ To-Do List â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of [TodoItem] objects filtered by [filter], sorted by due date then priority.
     *
     * Emits from Room immediately. [TodoFilter.showCompleted] controls whether completed items
     * are included.
     */
    override fun getTodos(filter: TodoFilter): Flow<ApiResult<List<TodoItem>>> {
        val userId = resolveUserId()
        return todoItemDao.getTodosByCompletion(userId, filter.showCompleted)
            .map { entities ->
                var todos = entities.map { it.toDomain() }
                // Apply optional additional filter criteria
                filter.priority?.let { p -> todos = todos.filter { it.priority == p } }
                filter.tag?.let { t -> todos = todos.filter { t in it.tags } }
                filter.dueBefore?.let { d -> todos = todos.filter { dd -> dd.dueDate?.let { it <= d } ?: true } }
                ApiResult.Success(todos)
            }
    }

    /**
     * Creates a [TodoItem] locally with [SyncStatus.PENDING] and syncs when connected.
     */
    override suspend fun createTodo(todo: TodoItem): ApiResult<TodoItem> = withContext(dispatchers.io) {
        val pending = todo.copy(syncStatus = SyncStatus.PENDING, updatedAt = Instant.now().toEpochMilli())
        todoItemDao.insert(pending.toEntity())
        if (connectivityObserver.isConnected()) syncTodoRemote(pending)
        ApiResult.Success(pending)
    }

    /**
     * Updates a [TodoItem] locally and queues a remote sync.
     */
    override suspend fun updateTodo(todo: TodoItem): ApiResult<TodoItem> = withContext(dispatchers.io) {
        val pending = todo.copy(syncStatus = SyncStatus.PENDING, updatedAt = Instant.now().toEpochMilli())
        todoItemDao.update(pending.toEntity())
        if (connectivityObserver.isConnected()) syncTodoRemote(pending)
        ApiResult.Success(pending)
    }

    /**
     * Deletes a [TodoItem] from Room and the backend.
     */
    override suspend fun deleteTodo(todoId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        // Build a minimal entity just for Room @Delete (needs PK only)
        val placeholder = com.aiassistant.core.database.entity.TodoItemEntity(
            id = todoId, userId = "", title = "", description = "",
            isCompleted = false, dueDate = null, priority = "medium",
            tags = "[]", syncStatus = "synced", createdAt = 0L, updatedAt = 0L
        )
        todoItemDao.delete(placeholder)
        if (connectivityObserver.isConnected()) {
            remoteSource.deleteTodo(todoId)
        }
        ApiResult.Success(Unit)
    }

    /**
     * Requests AI-generated [TodoItem] list from a natural language prompt.
     * Requires connectivity.
     */
    override suspend fun generateTodosFromPrompt(prompt: String): ApiResult<List<TodoItem>> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
            when (val result = remoteSource.generateTodos(prompt)) {
                is ApiResult.Success -> ApiResult.Success(result.data.map { it.toDomain() })
                is ApiResult.Error -> result
                is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
                is ApiResult.Loading -> ApiResult.Loading
            }
        }

    // â”€â”€ Calendar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of [CalendarEvent] objects within [range].
     *
     * Emits from Room immediately. When connected, merges Google Calendar MCP events
     * into Room in the background so subsequent emissions include external events.
     */
    override fun getCalendarEvents(range: DateRange): Flow<ApiResult<List<CalendarEvent>>> {
        val userId = resolveUserId()
        syncScope.launch { mergeGoogleCalendarEvents(userId, range) }
        return calendarEventDao.getEventsInRange(userId, range.start, range.end)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }
    }

    /**
     * Creates a [CalendarEvent] locally and syncs to the backend when connected.
     */
    override suspend fun createCalendarEvent(event: CalendarEvent): ApiResult<CalendarEvent> =
        withContext(dispatchers.io) {
            val pending = event.copy(syncStatus = SyncStatus.PENDING, updatedAt = Instant.now().toEpochMilli())
            calendarEventDao.insert(pending.toEntity())
            if (connectivityObserver.isConnected()) {
                val dto = SaveCalendarEventRequest(
                    title = pending.title,
                    description = pending.description,
                    startTime = pending.startTime,
                    endTime = pending.endTime,
                    location = pending.location,
                    isAllDay = pending.isAllDay
                )
                when (val result = remoteSource.createCalendarEvent(dto)) {
                    is ApiResult.Success -> {
                        val synced = result.data.toEntity().copy(syncStatus = SyncStatus.SYNCED.value)
                        calendarEventDao.update(synced)
                    }
                    else -> Log.w(TAG, "createCalendarEvent: remote sync failed")
                }
            }
            ApiResult.Success(pending)
        }

    /**
     * Deletes a [CalendarEvent] from Room and the backend.
     */
    override suspend fun deleteCalendarEvent(eventId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        val placeholder = com.aiassistant.core.database.entity.CalendarEventEntity(
            id = eventId, userId = "", title = "", description = "",
            startTime = 0L, endTime = 0L, location = null, isAllDay = false,
            source = "local", syncStatus = "synced", createdAt = 0L, updatedAt = 0L
        )
        calendarEventDao.delete(placeholder)
        if (connectivityObserver.isConnected()) remoteSource.deleteCalendarEvent(eventId)
        ApiResult.Success(Unit)
    }

    // â”€â”€ Reminders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of all [Reminder] objects sorted by [Reminder.triggerTime] ascending.
     */
    override fun getReminders(): Flow<ApiResult<List<Reminder>>> {
        val userId = resolveUserId()
        return reminderDao.getAllSortedByTriggerTime(userId)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }
    }

    /**
     * Creates a [Reminder] locally and syncs to the backend when connected.
     */
    override suspend fun createReminder(reminder: Reminder): ApiResult<Reminder> = withContext(dispatchers.io) {
        val pending = reminder.copy(syncStatus = SyncStatus.PENDING, updatedAt = Instant.now().toEpochMilli())
        reminderDao.insert(pending.toEntity())
        if (connectivityObserver.isConnected()) syncReminderRemote(pending, isUpdate = false)
        ApiResult.Success(pending)
    }

    /**
     * Updates a [Reminder] locally and queues a remote sync.
     */
    override suspend fun updateReminder(reminder: Reminder): ApiResult<Reminder> = withContext(dispatchers.io) {
        val pending = reminder.copy(syncStatus = SyncStatus.PENDING, updatedAt = Instant.now().toEpochMilli())
        reminderDao.update(pending.toEntity())
        if (connectivityObserver.isConnected()) syncReminderRemote(pending, isUpdate = true)
        ApiResult.Success(pending)
    }

    /**
     * Deletes a [Reminder] from Room and the backend.
     */
    override suspend fun deleteReminder(reminderId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        val placeholder = com.aiassistant.core.database.entity.ReminderEntity(
            id = reminderId, userId = "", title = "", triggerTime = 0L,
            recurrenceRule = null, linkedTodoId = null, isCompleted = false,
            syncStatus = "synced", createdAt = 0L, updatedAt = 0L
        )
        reminderDao.delete(placeholder)
        if (connectivityObserver.isConnected()) remoteSource.deleteReminder(reminderId)
        ApiResult.Success(Unit)
    }

    /**
     * Requests an AI-suggested [Reminder] from a natural language prompt. Requires connectivity.
     */
    override suspend fun suggestReminder(prompt: String): ApiResult<Reminder> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        when (val result = remoteSource.suggestReminder(prompt)) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }

    // â”€â”€ Habit Tracker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a [Flow] of all [HabitDefinition] objects for the authenticated user.
     */
    override fun getHabits(): Flow<ApiResult<List<HabitDefinition>>> {
        val userId = resolveUserId()
        return habitDefinitionDao.getAll(userId)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }
    }

    /**
     * Creates a [HabitDefinition] locally and syncs to the backend when connected.
     */
    override suspend fun createHabit(habit: HabitDefinition): ApiResult<HabitDefinition> = withContext(dispatchers.io) {
        habitDefinitionDao.insert(habit.toEntity())
        if (connectivityObserver.isConnected()) {
            val dto = SaveHabitRequest(
                name = habit.name,
                description = habit.description,
                recurrence = habit.recurrence.value,
                targetFrequency = habit.targetFrequency
            )
            when (val result = remoteSource.createHabit(dto)) {
                is ApiResult.Success -> habitDefinitionDao.update(result.data.toEntity())
                else -> Log.w(TAG, "createHabit: remote sync failed")
            }
        }
        ApiResult.Success(habit)
    }

    /**
     * Deletes a [HabitDefinition] (and all its [HabitEntry] records via Room cascade) from
     * Room and the backend.
     */
    override suspend fun deleteHabit(habitId: String): ApiResult<Unit> = withContext(dispatchers.io) {
        val placeholder = com.aiassistant.core.database.entity.HabitDefinitionEntity(
            id = habitId,
            userId = "",
            name = "",
            description = "",
            recurrence = "daily",
            targetFrequency = 1,
            createdAt = 0L,
            updatedAt = 0L
        )
        habitDefinitionDao.delete(placeholder)
        if (connectivityObserver.isConnected()) remoteSource.deleteHabit(habitId)
        ApiResult.Success(Unit)
    }

    /**
     * Returns a [Flow] of [HabitEntry] objects for a specific habit, sorted by completion date.
     */
    override fun getHabitEntries(habitId: String): Flow<ApiResult<List<HabitEntry>>> =
        habitEntryDao.getEntriesForHabit(habitId)
            .map { entities -> ApiResult.Success(entities.map { it.toDomain() }) }

    /**
     * Records a habit completion event in Room and queues a remote sync.
     */
    override suspend fun logHabitEntry(entry: HabitEntry): ApiResult<HabitEntry> = withContext(dispatchers.io) {
        habitEntryDao.insert(entry.toEntity())
        if (connectivityObserver.isConnected()) {
            remoteSource.logHabitEntry(entry.habitId, entry.completedAt, entry.note)
        }
        ApiResult.Success(entry)
    }

    /**
     * Requests AI-generated insights about a habit's completion patterns. Requires connectivity.
     */
    override suspend fun getHabitInsights(habitId: String): ApiResult<String> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        remoteSource.getHabitInsights(habitId)
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Pushes a [TodoItem] to the backend and marks it as [SyncStatus.SYNCED] in Room on success.
     */
    private suspend fun syncTodoRemote(todo: TodoItem) {
        val dto = SaveTodoRequest(
            title = todo.title,
            description = todo.description,
            isCompleted = todo.isCompleted,
            dueDate = todo.dueDate,
            priority = todo.priority.value,
            tags = todo.tags
        )
        val result = remoteSource.updateTodo(todo.id, dto)
        val syncedStatus = if (result is ApiResult.Success) SyncStatus.SYNCED else SyncStatus.FAILED
        todoItemDao.update(todo.copy(syncStatus = syncedStatus).toEntity())
    }

    /**
     * Pushes a [Reminder] to the backend (create or update) and updates Room on success.
     */
    private suspend fun syncReminderRemote(reminder: Reminder, isUpdate: Boolean) {
        val dto = SaveReminderRequest(
            title = reminder.title,
            triggerTime = reminder.triggerTime,
            recurrenceRule = reminder.recurrenceRule,
            linkedTodoId = reminder.linkedTodoId
        )
        val result = if (isUpdate) {
            remoteSource.updateReminder(reminder.id, dto)
        } else {
            remoteSource.createReminder(dto)
        }
        val syncedStatus = if (result is ApiResult.Success) SyncStatus.SYNCED else SyncStatus.FAILED
        reminderDao.update(reminder.copy(syncStatus = syncedStatus).toEntity())
    }

    /**
     * Fetches Google Calendar events from the MCP connector and upserts them into Room so that
     * subsequent [calendarEventDao.getEventsInRange] emissions include external events.
     *
     * Silently swallows errors so the local-first flow is never blocked by MCP failures.
     */
    private suspend fun mergeGoogleCalendarEvents(@Suppress("UnusedParameter") userId: String, range: DateRange) {
        if (!connectivityObserver.isConnected()) return
        when (val result = remoteSource.getGoogleCalendarEvents(range.start, range.end)) {
            is ApiResult.Success -> {
                result.data
                    .filter { it.source == CalendarEventSource.GOOGLE_CALENDAR.value }
                    .forEach { dto ->
                        calendarEventDao.insert(dto.toEntity())
                    }
            }
            else -> Log.d(TAG, "mergeGoogleCalendarEvents: skipped (offline or error)")
        }
    }

    /** Resolves the authenticated user's ID from [SecureStorage]. */
    private fun resolveUserId(): String = secureStorage.getJwt()?.substringAfterLast('.') ?: ""
}
