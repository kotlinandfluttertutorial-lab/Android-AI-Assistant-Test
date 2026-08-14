/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GroupedConversations.kt
 * Purpose    : GroupedConversations — domain module component
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
 * File       : GroupedConversations.kt
 * Purpose    : GroupedConversations — domain module component
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
 * GroupedConversations.kt
 *
 * Purpose: Domain model representing conversations organised into date-based buckets for
 *          display in the conversation list screen.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: domain model (Conversation)
 *
 * Requirements: 11.5
 */

package com.aiassistant.domain.model

/**
 * Holds four ordered lists of [Conversation] objects, each corresponding to a date
 * category shown in the conversation history UI.
 *
 * THE AI_Assistant SHALL group Conversations by date category: Today, Yesterday,
 * Last 7 Days, and Older (Requirement 11.5).
 *
 * Each conversation appears in exactly one list. Within each list conversations are
 * sorted by [Conversation.updatedAt] descending (most recent first).
 *
 * @param today      Conversations last modified on the current calendar day.
 * @param yesterday  Conversations last modified on the previous calendar day.
 * @param last7Days  Conversations last modified 2â€“6 days ago (i.e. within the past week
 *                   but not today or yesterday).
 * @param older      Conversations last modified 7 or more days ago.
 */
data class GroupedConversations(
    val today: List<Conversation> = emptyList(),
    val yesterday: List<Conversation> = emptyList(),
    val last7Days: List<Conversation> = emptyList(),
    val older: List<Conversation> = emptyList()
) {
    /** Returns the total number of conversations across all groups. */
    val totalCount: Int get() = today.size + yesterday.size + last7Days.size + older.size

    /** Returns `true` when all groups are empty. */
    val isEmpty: Boolean get() = totalCount == 0
}
