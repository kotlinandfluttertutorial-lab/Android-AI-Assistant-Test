/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : HabitDefinition.kt
 * Purpose    : HabitDefinition — domain module component
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
 * File       : HabitDefinition.kt
 * Purpose    : HabitDefinition — domain module component
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
 * HabitDefinition.kt
 *
 * Purpose: Domain entity representing a habit the user wants to track in the
 *          Productivity Suite's Habit Tracker feature.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 13.1, 19.2
 */

package com.aiassistant.domain.model

/**
 * How often a habit should be completed.
 */
enum class HabitRecurrence(val value: String) {
    /** The habit should be completed every day. */
    DAILY("daily"),

    /** The habit should be completed a set number of times per week. */
    WEEKLY("weekly");

    companion object {
        fun fromValue(value: String): HabitRecurrence = entries.firstOrNull { it.value == value } ?: DAILY
    }
}

/**
 * Defines a habit the user wants to track, including its recurrence schedule and
 * target frequency.
 *
 * Completion events are recorded as [HabitEntry] objects. AI-generated insights
 * about completion patterns are surfaced on the HabitInsights screen.
 *
 * @param id               Unique identifier for the habit definition.
 * @param userId           Identifier of the owning user.
 * @param name             Short habit name displayed in the habit list.
 * @param description      Optional longer description of the habit.
 * @param recurrence       How often the habit repeats; defaults to [HabitRecurrence.DAILY].
 * @param targetFrequency  The number of times the habit should be completed per recurrence
 *                         period (e.g. 1 for daily, 3 for three times per week).
 * @param createdAt        Epoch milliseconds when the habit was created.
 * @param updatedAt        Epoch milliseconds of the most recent change.
 */
data class HabitDefinition(
    val id: String,
    val userId: String,
    val name: String,
    val description: String = "",
    val recurrence: HabitRecurrence = HabitRecurrence.DAILY,
    val targetFrequency: Int = 1,
    val createdAt: Long,
    val updatedAt: Long
)
