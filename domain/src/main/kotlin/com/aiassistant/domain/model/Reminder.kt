/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Reminder.kt
 * Purpose    : Reminder — domain module component
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
 * Reminder.kt
 *
 * Purpose: Domain entity representing a scheduled reminder in the Productivity Suite.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 16.3, 16.4, 19.2
 */

package com.aiassistant.domain.model

/**
 * Represents a scheduled reminder that triggers a local notification at [triggerTime].
 *
 * THE AI_Assistant SHALL deliver local notifications via NotificationManager and
 * AlarmManager at the scheduled trigger time (Requirement 16.3). Reminders can recur
 * using an iCal RRULE string (Requirement 16.4) and can optionally be linked to a
 * [TodoItem] for cross-feature integration.
 *
 * @param id               Unique identifier for the reminder.
 * @param userId           Identifier of the owning user.
 * @param title            Short description of what to be reminded about.
 * @param triggerTime      Epoch milliseconds at which the notification should fire.
 * @param recurrenceRule   Optional iCal RRULE string defining a recurrence pattern
 *                         (e.g. "FREQ=DAILY;INTERVAL=1"). Null for one-time reminders.
 * @param linkedTodoId     Optional identifier of a [TodoItem] this reminder is associated
 *                         with (e.g. a reminder to complete a specific task).
 * @param isCompleted      Whether the reminder has been acknowledged/dismissed.
 * @param syncStatus       Current backend synchronisation state.
 * @param createdAt        Epoch milliseconds when the reminder was created.
 * @param updatedAt        Epoch milliseconds of the most recent change.
 */
data class Reminder(
    val id: String,
    val userId: String,
    val title: String,
    val triggerTime: Long,
    val recurrenceRule: String? = null,
    val linkedTodoId: String? = null,
    val isCompleted: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long
)
