/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ProductivityRemoteDataSource.kt
 * Purpose    : ProductivityRemoteDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * File       : ProductivityRemoteDataSource.kt
 * Purpose    : ProductivityRemoteDataSource — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Data Source (local or remote)
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
 * ProductivityRemoteDataSource.kt â€” data module
 *
 * Purpose: Wraps [ProductivityApiService] Retrofit calls in a typed, testable class.
 *          All calls return [ApiResult] so callers never receive raw exceptions.
 *
 * Architecture: data module â€” remote data source layer. Consumed by
 *               [com.aiassistant.data.repository.ProductivityRepositoryImpl].
 * Dependencies: ProductivityApiService, ApiResult, DomainError, DispatcherProvider
 *
 * Requirements: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.remote.productivity

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Remote data source for all Productivity Suite network operations.
 *
 * @param api         Retrofit service for productivity endpoints.
 * @param dispatchers Injectable dispatcher provider for I/O work.
 */
@Singleton
class ProductivityRemoteDataSource @Inject constructor(
    private val api: ProductivityApiService,
    private val dispatchers: DispatcherProvider
) {

    // â”€â”€ Todos â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun getTodos(): ApiResult<List<TodoItemDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getTodos().items } }

    suspend fun createTodo(dto: SaveTodoRequest): ApiResult<TodoItemDto> =
        withContext(dispatchers.io) { safeApiCall { api.createTodo(dto) } }

    suspend fun updateTodo(todoId: String, dto: SaveTodoRequest): ApiResult<TodoItemDto> =
        withContext(dispatchers.io) { safeApiCall { api.updateTodo(todoId, dto) } }

    suspend fun deleteTodo(todoId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteTodo(todoId) } }

    suspend fun generateTodos(prompt: String): ApiResult<List<TodoItemDto>> =
        withContext(dispatchers.io) { safeApiCall { api.generateTodos(GenerateTodosRequest(prompt)).items } }

    // â”€â”€ Calendar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun getCalendarEvents(startMs: Long, endMs: Long): ApiResult<List<CalendarEventDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getCalendarEvents(startMs, endMs).items } }

    suspend fun createCalendarEvent(dto: SaveCalendarEventRequest): ApiResult<CalendarEventDto> =
        withContext(dispatchers.io) { safeApiCall { api.createCalendarEvent(dto) } }

    suspend fun deleteCalendarEvent(eventId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteCalendarEvent(eventId) } }

    /** Fetches Google Calendar events via MCP connector. Returns empty list on failure. */
    suspend fun getGoogleCalendarEvents(startMs: Long, endMs: Long): ApiResult<List<CalendarEventDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getGoogleCalendarEvents(startMs, endMs).items } }

    // â”€â”€ Reminders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun getReminders(): ApiResult<List<ReminderDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getReminders().items } }

    suspend fun createReminder(dto: SaveReminderRequest): ApiResult<ReminderDto> =
        withContext(dispatchers.io) { safeApiCall { api.createReminder(dto) } }

    suspend fun updateReminder(reminderId: String, dto: SaveReminderRequest): ApiResult<ReminderDto> =
        withContext(dispatchers.io) { safeApiCall { api.updateReminder(reminderId, dto) } }

    suspend fun deleteReminder(reminderId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteReminder(reminderId) } }

    suspend fun suggestReminder(prompt: String): ApiResult<ReminderDto> =
        withContext(dispatchers.io) { safeApiCall { api.suggestReminder(SuggestReminderRequest(prompt)) } }

    // â”€â”€ Habits â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun getHabits(): ApiResult<List<HabitDefinitionDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getHabits().items } }

    suspend fun createHabit(dto: SaveHabitRequest): ApiResult<HabitDefinitionDto> =
        withContext(dispatchers.io) { safeApiCall { api.createHabit(dto) } }

    suspend fun deleteHabit(habitId: String): ApiResult<Unit> =
        withContext(dispatchers.io) { safeApiCall { api.deleteHabit(habitId) } }

    suspend fun getHabitEntries(habitId: String): ApiResult<List<HabitEntryDto>> =
        withContext(dispatchers.io) { safeApiCall { api.getHabitEntries(habitId).items } }

    suspend fun logHabitEntry(habitId: String, completedAt: Long, note: String?): ApiResult<HabitEntryDto> =
        withContext(dispatchers.io) {
            safeApiCall { api.logHabitEntry(habitId, LogHabitEntryRequest(completedAt, note)) }
        }

    suspend fun getHabitInsights(habitId: String): ApiResult<String> =
        withContext(dispatchers.io) { safeApiCall { api.getHabitInsights(habitId).insights } }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(e.toDomainError())
    } catch (e: IOException) {
        ApiResult.Error(
            DomainError.NetworkError(message = e.message ?: "A network I/O error occurred.", cause = e)
        )
    }

    private fun HttpException.toDomainError(): DomainError = when (code()) {
        401 -> DomainError.Unauthorized(cause = this)
        403 -> DomainError.Forbidden(cause = this)
        in 400..499 -> DomainError.ValidationError(message = "Invalid request (HTTP ${code()}).", cause = this)
        in 500..599 -> DomainError.ServerError(
            message = "Server error (HTTP ${code()}).",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(message = "Unexpected HTTP response: ${code()}.", cause = this)
    }
}
