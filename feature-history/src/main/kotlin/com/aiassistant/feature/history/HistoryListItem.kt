/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : HistoryListItem.kt
 * Purpose    : HistoryListItem — feature-history module component
 *
 * Architecture Layer : Feature (feature-history)
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
 * Module     : feature-history
 * File       : HistoryListItem.kt
 * Purpose    : HistoryListItem — feature-history module component
 *
 * Architecture Layer : Feature (feature-history)
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
 * HistoryListItem.kt
 *
 * Purpose: Sealed class representing the flattened item types displayed in the
 *          history list [LazyColumn]. Each [GroupedConversations] bucket is
 *          projected into alternating [Header] + [ConversationItem] rows.
 * Architecture: feature-history â€” MVVM presentation layer.
 * Dependencies: domain (Conversation)
 *
 * Requirements: 11.1, 11.5
 */
package com.aiassistant.feature.history

import com.aiassistant.domain.model.Conversation

/**
 * Represents a single row in the flat history list [LazyColumn].
 *
 * The ViewModel projects a [com.aiassistant.domain.model.GroupedConversations] into a
 * flat [List] of [HistoryListItem] by inserting a [Header] before each non-empty group
 * and following it with one [ConversationItem] per conversation.
 */
sealed class HistoryListItem {

    /**
     * A non-interactive section header row (e.g. "Today", "Yesterday").
     *
     * @param label The human-readable label for this date group.
     */
    data class Header(val label: String) : HistoryListItem()

    /**
     * A tappable row representing a single conversation.
     *
     * @param conversation The domain model for the conversation to display.
     */
    data class ConversationItem(val conversation: Conversation) : HistoryListItem()
}
