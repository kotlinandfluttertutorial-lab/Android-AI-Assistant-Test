/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Note.kt
 * Purpose    : Note — domain module component
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
 * File       : Note.kt
 * Purpose    : Note — domain module component
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
 * Note.kt
 *
 * Purpose: Domain entity representing a user-authored note that supports Markdown
 *          formatting, tagging, and AI-assisted summarisation and rewriting.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 19.2
 */

package com.aiassistant.domain.model

/**
 * Sync lifecycle state shared by [Note] and other locally-persisted entities.
 *
 * This enum is also used by [TodoItem] and other productivity entities.
 */
enum class SyncStatus(val value: String) {
    /** Entity has been successfully synchronised with the backend. */
    SYNCED("synced"),

    /** Entity has local changes that have not yet been sent to the backend. */
    PENDING("pending"),

    /** The last sync attempt failed; will be retried on the next sync cycle. */
    FAILED("failed");

    companion object {
        fun fromValue(value: String): SyncStatus = entries.firstOrNull { it.value == value } ?: PENDING
    }
}

/**
 * Represents a user-authored note in the Notes feature.
 *
 * THE AI_Assistant SHALL persist notes locally and sync to the backend when connected
 * (Requirement 13.4). THE AI_Assistant SHALL allow the user to filter notes by tag
 * (Requirement 13.5).
 *
 * @param id          Unique identifier for the note.
 * @param userId      Identifier of the note's author.
 * @param title       The note title displayed in the notes list.
 * @param content     The full note body in plain text or Markdown format.
 * @param tags        List of user-defined tag labels for filtering (Requirement 13.5).
 * @param syncStatus  Current backend synchronisation state.
 * @param createdAt   Epoch milliseconds when the note was created.
 * @param updatedAt   Epoch milliseconds of the most recent edit.
 */
data class Note(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val createdAt: Long,
    val updatedAt: Long
)
