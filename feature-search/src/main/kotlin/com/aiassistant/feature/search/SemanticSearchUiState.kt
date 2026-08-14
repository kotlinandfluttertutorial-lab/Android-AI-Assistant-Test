/**
 * SemanticSearchUiState.kt
 *
 * Purpose: Sealed class hierarchy representing all UI states for the semantic search screen.
 * Architecture: feature-search — UI state model.
 * Dependencies: domain (SemanticSearchResult)
 *
 * Requirements: 36.1, 36.3, 36.4, 36.5, 36.8
 */
package com.aiassistant.feature.search

import com.aiassistant.domain.model.SemanticSearchResult

/**
 * All possible UI states for the [SemanticSearchScreen].
 *
 * - [Idle]    — Initial state before the user submits a query.
 * - [Loading] — Query is in flight; show a loading indicator.
 * - [Success] — Results grouped by [SemanticSearchResult.SourceType], with at least one result.
 * - [Empty]   — Query completed successfully but no results scored ≥ 0.5.
 * - [Error]   — A non-empty-results error occurred (network failure, server error, etc.).
 */
sealed class SemanticSearchUiState {

    /** Initial state before any search has been submitted. */
    object Idle : SemanticSearchUiState()

    /** Query is in progress; display a loading indicator. */
    object Loading : SemanticSearchUiState()

    /**
     * Search completed with at least one result above the 0.5 threshold.
     *
     * Results are pre-grouped by [SemanticSearchResult.SourceType] and sorted by
     * relevance score descending within each group.
     *
     * @param groupedResults Map from source type to its list of results. Groups with
     *                       no results are excluded entirely.
     */
    data class Success(val groupedResults: Map<SemanticSearchResult.SourceType, List<SemanticSearchResult>>) :
        SemanticSearchUiState()

    /**
     * Search completed but returned zero results above the 0.5 threshold.
     * The screen shows a "No results found" message with a rephrase suggestion.
     *
     * Requirements: 36.4
     */
    object Empty : SemanticSearchUiState()

    /**
     * A network or server error occurred.
     *
     * @param message Human-readable error message to display.
     */
    data class Error(val message: String) : SemanticSearchUiState()
}
