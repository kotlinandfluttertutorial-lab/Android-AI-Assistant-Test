/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : HistoryUiState.kt
 * Purpose    : HistoryUiState — feature-history module component
 *
 * Architecture Layer : Feature (feature-history)
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
 * Module     : feature-history
 * File       : HistoryUiState.kt
 * Purpose    : HistoryUiState — feature-history module component
 *
 * Architecture Layer : Feature (feature-history)
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
 * HistoryUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the conversation
 *          history feature, including list, search, export progress, export success,
 *          loading, and error states.
 * Architecture: feature-history â€” MVVM presentation layer.
 * Dependencies: domain (GroupedConversations, Conversation, ExportFormat)
 *
 * Requirements: 11.1, 11.2, 11.6
 */
package com.aiassistant.feature.history

import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.GroupedConversations

/**
 * Represents every possible UI state in the conversation history feature.
 *
 * The [HistoryViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class HistoryUiState {

    // â”€â”€â”€ Terminal / transient states â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** A data load or navigation transition is in progress. */
    data object Loading : HistoryUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : HistoryUiState()

    // â”€â”€â”€ Steady states â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * The conversation history list is active.
     *
     * Conversations are pre-grouped by date bucket (today, yesterday, last 7 days,
     * older) and sorted by [Conversation.updatedAt] descending within each group
     * (Requirement 11.1 / 11.5).
     *
     * @param groupedConversations Date-bucketed, sorted conversation data.
     * @param isSearchActive       When `true` the inline search bar is shown in the top
     *                             app bar and the list may be filtered.
     */
    data class HistoryList(val groupedConversations: GroupedConversations, val isSearchActive: Boolean = false) :
        HistoryUiState()

    /**
     * A full-text search is active and has produced results.
     *
     * Results are a flat, relevance-ordered list of matching conversations (Requirement
     * 11.2 â€” FTS response within 300 ms, enforced at the data layer).
     *
     * @param query   The current search query string.
     * @param results Matching conversations, sorted by [Conversation.updatedAt] descending.
     */
    data class SearchResults(val query: String, val results: List<Conversation>) : HistoryUiState()

    /**
     * A conversation export operation is in progress.
     *
     * The UI should show a blocking progress dialog while in this state (Requirement 11.6).
     *
     * @param conversationId The identifier of the conversation being exported.
     * @param format         The requested export format.
     */
    data class Exporting(val conversationId: String, val format: ExportFormat) : HistoryUiState()

    /**
     * A conversation export completed successfully.
     *
     * The UI should surface a Snackbar or confirmation with a share action (Requirement 11.6).
     *
     * @param filePath The absolute file path (PDF) or Markdown content string returned by
     *                 the export use case.
     * @param format   The format that was exported.
     */
    data class ExportSuccess(val filePath: String, val format: ExportFormat) : HistoryUiState()
}
