/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatListUiState.kt
 * Purpose    : ChatListUiState — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : UI State Data Class
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
 * File       : ChatListUiState.kt
 * Purpose    : ChatListUiState — feature-chat module component
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : UI State Data Class
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
 * ChatListUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the chat list screen.
 * Architecture: feature-chat â€” MVVM presentation layer.
 * Dependencies: domain (GroupedConversations)
 *
 * Requirements: 11.1, 11.3, 11.5, 10.4
 */
package com.aiassistant.feature.chat

import com.aiassistant.domain.model.GroupedConversations

/**
 * Represents every possible UI state for the conversation list screen.
 *
 * [ChatViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed class.
 * Composables observe it and render accordingly.
 */
sealed class ChatListUiState {

    /**
     * Data is being loaded for the first time.
     * Show a loading indicator to the user.
     */
    data object Loading : ChatListUiState()

    /**
     * Conversations loaded successfully.
     *
     * @param groupedConversations The date-grouped conversation data ready for display.
     * @param isOffline            When `true`, the device has no network connectivity and
     *                             the persistent [OfflineBanner] should be shown.
     */
    data class Success(val groupedConversations: GroupedConversations, val isOffline: Boolean) : ChatListUiState()

    /**
     * A non-recoverable error occurred loading conversations.
     *
     * @param message Human-readable description of the error for display.
     */
    data class Error(val message: String) : ChatListUiState()

    /**
     * The user has no conversations yet (empty state).
     *
     * @param isOffline When `true`, the device has no network connectivity and
     *                  the persistent [OfflineBanner] should be shown.
     */
    data class Empty(val isOffline: Boolean) : ChatListUiState()
}
