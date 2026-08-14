/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Memory.kt
 * Purpose    : Memory — domain module component
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
 * File       : Memory.kt
 * Purpose    : Memory — domain module component
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
 * Memory.kt
 *
 * Purpose: Domain entity representing a piece of learned context about the user,
 *          stored and retrieved by the Memory Service.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 7.3, 7.4, 19.2
 */

package com.aiassistant.domain.model

/**
 * Classifies what kind of information a [Memory] captures about the user.
 */
enum class MemoryType(val value: String) {
    /** A user preference (e.g. "prefers concise responses"). */
    PREFERENCE("preference"),

    /** A factual piece of information about the user (e.g. "works at Acme Corp"). */
    FACT("fact"),

    /** A writing or communication style observation (e.g. "writes in British English"). */
    STYLE("style");

    companion object {
        fun fromValue(value: String): MemoryType = entries.firstOrNull { it.value == value } ?: FACT
    }
}

/**
 * Represents a single memory item captured by the Memory Service about a user.
 *
 * THE AI_Assistant SHALL display, allow editing, and allow deletion of individual
 * memories from the Profile screen (Requirement 7.3). Deleting a memory removes
 * its embedding from ChromaDB within 10 seconds (Requirement 7.4).
 *
 * Memories are not cached locally (sensitive data) â€” they are always fetched from
 * the remote Memory Service.
 *
 * @param id          Unique identifier for the memory.
 * @param userId      Identifier of the user this memory belongs to.
 * @param content     The textual content of the memory.
 * @param memoryType  The category of information this memory represents.
 * @param createdAt   Epoch milliseconds when the memory was captured.
 */
data class Memory(
    val id: String,
    val userId: String,
    val content: String,
    val memoryType: MemoryType = MemoryType.FACT,
    val createdAt: Long
)
