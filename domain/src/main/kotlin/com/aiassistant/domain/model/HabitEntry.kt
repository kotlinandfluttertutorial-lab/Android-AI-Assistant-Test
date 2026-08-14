/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : HabitEntry.kt
 * Purpose    : HabitEntry — domain module component
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
 * File       : HabitEntry.kt
 * Purpose    : HabitEntry — domain module component
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
 * HabitEntry.kt
 *
 * Purpose: Domain entity representing a single completion event for a tracked habit.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 13.1, 19.2
 */

package com.aiassistant.domain.model

/**
 * Records a single instance of a habit being completed by the user.
 *
 * Each [HabitEntry] is linked to a [HabitDefinition] via [habitId]. The collection of
 * entries for a habit provides the raw data for streak calculations and AI-generated
 * insights about completion patterns on the HabitInsights screen.
 *
 * @param id           Unique identifier for this completion entry.
 * @param habitId      Identifier of the parent [HabitDefinition].
 * @param userId       Identifier of the user who logged this entry.
 * @param completedAt  Epoch milliseconds when the habit was marked as completed.
 * @param note         Optional note the user added alongside this completion entry.
 */
data class HabitEntry(
    val id: String,
    val habitId: String,
    val userId: String,
    val completedAt: Long,
    val note: String? = null
)
