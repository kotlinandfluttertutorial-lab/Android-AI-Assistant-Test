/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ProductivityApiService.kt
 * Purpose    : ProductivityApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * File       : ProductivityApiService.kt
 * Purpose    : ProductivityApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * ProductivityApiService.kt â€” data module
 *
 * Purpose: Retrofit service interface for all Productivity Suite REST endpoints.
 *          Consumed exclusively by [ProductivityRemoteDataSource].
 *
 * Architecture: data module â€” remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 13.1, 16.3, 16.4
 */
package com.aiassistant.data.remote.productivity

import com.aiassistant.core.network.model.PaginatedResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// â”€â”€â”€ TodoItem DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
data class TodoItemDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String = "",
    @SerialName("is_completed") val isCompleted: Boolean = false,
    @SerialName("due_date") val dueDate: Long? = null,
    @SerialName("priority") val priority: String = "medium",
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("sync_status") val syncStatus: String = "synced",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

@Serializable
data class SaveTodoRequest(
    @SerialName("title") val title: String,
    @SerialName("description") val description: String = "",
    @SerialName("is_completed") val isCompleted: Boolean = false,
    @SerialName("due_date") val dueDate: Long? = null,
    @SerialName("priority") val priority: String = "medium",
    @SerialName("tags") val tags: List<String> = emptyList()
)

@Serializable
data class GenerateTodosRequest(@SerialName("prompt") val prompt: String)

// â”€â”€â”€ CalendarEvent DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
data class CalendarEventDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String = "",
    @SerialName("start_time") val startTime: Long,
    @SerialName("end_time") val endTime: Long,
    @SerialName("location") val location: String? = null,
    @SerialName("is_all_day") val isAllDay: Boolean = false,
    @SerialName("source") val source: String = "local",
    @SerialName("sync_status") val syncStatus: String = "synced",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

@Serializable
data class SaveCalendarEventRequest(
    @SerialName("title") val title: String,
    @SerialName("description") val description: String = "",
    @SerialName("start_time") val startTime: Long,
    @SerialName("end_time") val endTime: Long,
    @SerialName("location") val location: String? = null,
    @SerialName("is_all_day") val isAllDay: Boolean = false
)

// â”€â”€â”€ Reminder DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
data class ReminderDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("title") val title: String,
    @SerialName("trigger_time") val triggerTime: Long,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    @SerialName("linked_todo_id") val linkedTodoId: String? = null,
    @SerialName("is_completed") val isCompleted: Boolean = false,
    @SerialName("sync_status") val syncStatus: String = "synced",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

@Serializable
data class SaveReminderRequest(
    @SerialName("title") val title: String,
    @SerialName("trigger_time") val triggerTime: Long,
    @SerialName("recurrence_rule") val recurrenceRule: String? = null,
    @SerialName("linked_todo_id") val linkedTodoId: String? = null
)

@Serializable
data class SuggestReminderRequest(@SerialName("prompt") val prompt: String)

// ─── Meeting time suggestion DTOs ─────────────────────────────────────────────

/**
 * Request body for `POST /calendar/suggest-times`.
 *
 * @param prompt          Natural language description of the meeting requirements.
 * @param durationMinutes Duration of the meeting in minutes (default 60).
 */
@Serializable
data class SuggestMeetingTimesRequest(
    @SerialName("prompt") val prompt: String,
    @SerialName("duration_minutes") val durationMinutes: Int = 60
)

/**
 * Response from `POST /calendar/suggest-times`.
 *
 * @param suggestions List of ISO 8601 datetime strings representing suggested start times.
 * @param prompt      The original prompt echoed back by the backend.
 */
@Serializable
data class SuggestMeetingTimesResponse(
    @SerialName("suggestions") val suggestions: List<String> = emptyList(),
    @SerialName("prompt") val prompt: String = ""
)

// â”€â”€â”€ Habit DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
data class HabitDefinitionDto(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("recurrence") val recurrence: String = "daily",
    @SerialName("target_frequency") val targetFrequency: Int = 1,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long
)

@Serializable
data class HabitEntryDto(
    @SerialName("id") val id: String,
    @SerialName("habit_id") val habitId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("completed_at") val completedAt: Long,
    @SerialName("note") val note: String? = null
)

@Serializable
data class SaveHabitRequest(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String = "",
    @SerialName("recurrence") val recurrence: String = "daily",
    @SerialName("target_frequency") val targetFrequency: Int = 1
)

@Serializable
data class LogHabitEntryRequest(
    @SerialName("completed_at") val completedAt: Long,
    @SerialName("note") val note: String? = null
)

@Serializable
data class HabitInsightsResponse(@SerialName("insights") val insights: String)

// â”€â”€â”€ Retrofit service interface â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Retrofit service for all Productivity Suite endpoints. */
interface ProductivityApiService {

    // â”€â”€ Todos â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("todos")
    suspend fun getTodos(): PaginatedResponse<TodoItemDto>

    @POST("todos")
    suspend fun createTodo(@Body body: SaveTodoRequest): TodoItemDto

    @PUT("todos/{todoId}")
    suspend fun updateTodo(@Path("todoId") todoId: String, @Body body: SaveTodoRequest): TodoItemDto

    @DELETE("todos/{todoId}")
    suspend fun deleteTodo(@Path("todoId") todoId: String)

    @POST("todos/generate")
    suspend fun generateTodos(@Body body: GenerateTodosRequest): PaginatedResponse<TodoItemDto>

    // â”€â”€ Calendar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("calendar")
    suspend fun getCalendarEvents(
        @Query("start") startMs: Long,
        @Query("end") endMs: Long
    ): PaginatedResponse<CalendarEventDto>

    @POST("calendar")
    suspend fun createCalendarEvent(@Body body: SaveCalendarEventRequest): CalendarEventDto

    @DELETE("calendar/{eventId}")
    suspend fun deleteCalendarEvent(@Path("eventId") eventId: String)

    /** Fetches events from the Google Calendar MCP connector. */
    @GET("calendar/google")
    suspend fun getGoogleCalendarEvents(
        @Query("start") startMs: Long,
        @Query("end") endMs: Long
    ): PaginatedResponse<CalendarEventDto>

    /**
     * Requests AI-powered meeting time suggestions (Requirement 8.2).
     *
     * @param body Request containing natural language prompt and duration.
     * @return Response containing a list of ISO 8601 suggested start times.
     */
    @POST("calendar/suggest-times")
    suspend fun suggestMeetingTimes(@Body body: SuggestMeetingTimesRequest): SuggestMeetingTimesResponse

    // â”€â”€ Reminders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("reminders")
    suspend fun getReminders(): PaginatedResponse<ReminderDto>

    @POST("reminders")
    suspend fun createReminder(@Body body: SaveReminderRequest): ReminderDto

    @PUT("reminders/{reminderId}")
    suspend fun updateReminder(@Path("reminderId") reminderId: String, @Body body: SaveReminderRequest): ReminderDto

    @DELETE("reminders/{reminderId}")
    suspend fun deleteReminder(@Path("reminderId") reminderId: String)

    @POST("reminders/suggest")
    suspend fun suggestReminder(@Body body: SuggestReminderRequest): ReminderDto

    // â”€â”€ Habits â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GET("habits")
    suspend fun getHabits(): PaginatedResponse<HabitDefinitionDto>

    @POST("habits")
    suspend fun createHabit(@Body body: SaveHabitRequest): HabitDefinitionDto

    @DELETE("habits/{habitId}")
    suspend fun deleteHabit(@Path("habitId") habitId: String)

    @GET("habits/{habitId}/entries")
    suspend fun getHabitEntries(@Path("habitId") habitId: String): PaginatedResponse<HabitEntryDto>

    @POST("habits/{habitId}/entries")
    suspend fun logHabitEntry(@Path("habitId") habitId: String, @Body body: LogHabitEntryRequest): HabitEntryDto

    @GET("habits/{habitId}/insights")
    suspend fun getHabitInsights(@Path("habitId") habitId: String): HabitInsightsResponse
}
