/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : Conversation.kt
 * Purpose    : Conversation — domain module component
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
 * File       : Conversation.kt
 * Purpose    : Conversation — domain module component
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
 * Conversation.kt
 *
 * Purpose: Domain entity representing a named, persisted sequence of messages between
 *          a User and the AI_Orchestrator.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 11.1, 11.3, 11.4, 11.5
 */

package com.aiassistant.domain.model

import java.time.Instant

/**
 * Represents a conversation thread in the AI Assistant.
 *
 * THE AI_Assistant SHALL display a paginated list of all Conversations sorted by
 * last-modified date (Requirement 11.1). Conversations support soft-delete so the
 * Backend marks them as deleted without permanently removing the record (Requirement 11.4).
 *
 * @param id          Unique identifier for the conversation.
 * @param userId      Identifier of the owning user.
 * @param title       Human-readable title displayed in the conversation list.
 * @param isPinned    Whether the conversation is pinned to the top of the list.
 * @param isDeleted   Soft-delete flag. When true, the conversation is hidden from
 *                    the UI but retained in the database until fully purged.
 * @param provider    The LLM provider identifier used for this conversation.
 * @param createdAt   Timestamp when the conversation was first created.
 * @param updatedAt   Timestamp of the most recent message or modification. Used for
 *                    sorting (Requirement 11.1) and date-group categorisation (Requirement 11.5).
 */
data class Conversation(
    val id: String,
    val userId: String,
    val title: String,
    val isPinned: Boolean = false,
    val isDeleted: Boolean = false,
    val provider: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
