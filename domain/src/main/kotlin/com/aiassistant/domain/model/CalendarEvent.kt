/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CalendarEvent.kt
 * Purpose    : CalendarEvent — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * File       : CalendarEvent.kt
 * Purpose    : CalendarEvent — domain module component
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Class
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
 * CalendarEvent.kt
 *
 * Purpose: Domain entity representing a calendar event in the Productivity Suite.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 8.2, 13.1, 19.2
 */

package com.aiassistant.domain.model

/**
 * The origin source of a [CalendarEvent].
 *
 * Events can originate from the local app or be imported from an external provider
 * such as Google Calendar via the MCP connector.
 */
enum class CalendarEventSource(val value: String) {
    /** Event was created locally within the AI Assistant app. */
    LOCAL("local"),

    /** Event was sourced from the user's Google Calendar via the MCP connector. */
    GOOGLE_CALENDAR("google_calendar");

    companion object {
        fun fromValue(value: String): CalendarEventSource = entries.firstOrNull { it.value == value } ?: LOCAL
    }
}

/**
 * Represents a calendar event in the Productivity Suite's Calendar feature.
 *
 * THE AI_Assistant SHALL display events from the local Room database and optionally
 * merge events from the Google Calendar MCP connector when connected (Requirement 8.2).
 *
 * @param id          Unique identifier for the event.
 * @param userId      Identifier of the owning user.
 * @param title       Event title displayed in the calendar grid.
 * @param description Optional detailed description of the event.
 * @param startTime   Epoch milliseconds of the event start time.
 * @param endTime     Epoch milliseconds of the event end time.
 * @param location    Optional physical or virtual location for the event.
 * @param isAllDay    Whether the event spans the full day (start/end times ignored for display).
 * @param source      The origin of this event; defaults to [CalendarEventSource.LOCAL].
 * @param syncStatus  Current backend synchronisation state.
 * @param createdAt   Epoch milliseconds when the event was created.
 * @param updatedAt   Epoch milliseconds of the most recent change.
 */
data class CalendarEvent(
    val id: String,
    val userId: String,
    val title: String,
    val description: String = "",
    val startTime: Long,
    val endTime: Long,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val source: CalendarEventSource = CalendarEventSource.LOCAL,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long
)
