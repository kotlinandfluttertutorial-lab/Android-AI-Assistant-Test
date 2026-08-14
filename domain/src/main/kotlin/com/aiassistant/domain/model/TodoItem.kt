/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : TodoItem.kt
 * Purpose    : TodoItem — domain module component
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
 * File       : TodoItem.kt
 * Purpose    : TodoItem — domain module component
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
 * TodoItem.kt
 *
 * Purpose: Domain entity representing a single to-do task in the Productivity Suite.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None (SyncStatus defined in Note.kt, but we reuse the same enum)
 *
 * Requirements: 13.1, 19.2
 */

package com.aiassistant.domain.model

/**
 * Priority level for a [TodoItem], controlling its visual prominence and sort order.
 */
enum class Priority(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromValue(value: String): Priority = entries.firstOrNull { it.value == value } ?: MEDIUM
    }
}

/**
 * Represents a single to-do task in the Productivity Suite's To-Do List feature.
 *
 * THE AI_Assistant SHALL persist todo items locally and sync to the backend when connected
 * (Requirement 13.1). THE AI_Orchestrator SHALL generate a list of [TodoItem] objects from
 * a natural language description entered by the user.
 *
 * @param id            Unique identifier for the to-do item.
 * @param userId        Identifier of the owning user.
 * @param title         Short summary of the task.
 * @param description   Optional detailed description of the task.
 * @param isCompleted   Whether the task has been marked as done.
 * @param dueDate       Optional epoch milliseconds deadline for the task.
 * @param priority      Task importance level; defaults to [Priority.MEDIUM].
 * @param tags          User-defined labels for grouping/filtering tasks.
 * @param syncStatus    Current backend synchronisation state.
 * @param createdAt     Epoch milliseconds when the task was created.
 * @param updatedAt     Epoch milliseconds of the most recent change.
 */
data class TodoItem(
    val id: String,
    val userId: String,
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val dueDate: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val tags: List<String> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long
)
