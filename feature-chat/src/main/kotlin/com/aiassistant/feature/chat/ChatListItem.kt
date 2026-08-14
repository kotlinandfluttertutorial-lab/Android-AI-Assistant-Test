/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatListItem.kt
 * Purpose    : ChatListItem — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
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
 * Module     : feature-chat
 * File       : ChatListItem.kt
 * Purpose    : ChatListItem — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
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
 * ChatListItem.kt
 *
 * Purpose: Sealed class representing the flattened item types displayed in the
 *          conversation list [LazyColumn]. Each [GroupedConversations] bucket is
 *          projected into alternating [Header] + [ConversationItem] rows.
 * Architecture: feature-chat â€” MVVM presentation layer.
 * Dependencies: domain (Conversation)
 *
 * Requirements: 11.1, 11.5
 */
package com.aiassistant.feature.chat

import com.aiassistant.domain.model.Conversation

/**
 * Represents a single row in the flat conversation list [LazyColumn].
 *
 * The ViewModel projects a [com.aiassistant.domain.model.GroupedConversations] into a
 * flat [List] of [ChatListItem] by inserting a [Header] before each non-empty group and
 * following it with one [ConversationItem] per conversation.
 */
sealed class ChatListItem {

    /**
     * A non-interactive section header row (e.g. "Today", "Yesterday").
     *
     * @param label The human-readable label for this date group.
     */
    data class Header(val label: String) : ChatListItem()

    /**
     * A tappable row representing a single conversation.
     *
     * @param conversation The domain model for the conversation to display.
     */
    data class ConversationItem(val conversation: Conversation) : ChatListItem()
}
