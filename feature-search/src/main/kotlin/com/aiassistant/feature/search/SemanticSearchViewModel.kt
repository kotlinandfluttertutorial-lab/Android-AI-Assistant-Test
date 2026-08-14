/**
 * SemanticSearchViewModel.kt
 *
 * Purpose: Manages UI state and orchestrates [SemanticSearchUseCase] for the semantic search feature.
 * Architecture: feature-search — MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (SemanticSearchUseCase, SemanticSearchResult), core-common (ApiResult)
 *
 * Requirements: 36.1, 36.3, 36.4, 36.5, 36.8
 */
package com.aiassistant.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.usecase.search.SemanticSearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the [SemanticSearchScreen].
 *
 * Exposes a [StateFlow] of [SemanticSearchUiState] that composables observe.
 * Results are grouped by [SemanticSearchResult.SourceType] with empty groups omitted.
 */
@HiltViewModel
class SemanticSearchViewModel @Inject constructor(private val semanticSearchUseCase: SemanticSearchUseCase) :
    ViewModel() {

    // ─── State ────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<SemanticSearchUiState>(SemanticSearchUiState.Idle)

    /** Observable semantic search UI state. */
    val uiState: StateFlow<SemanticSearchUiState> = _uiState.asStateFlow()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Submit a semantic search query.
     *
     * Transitions through [SemanticSearchUiState.Loading] while the request is in flight,
     * then emits either [SemanticSearchUiState.Success], [SemanticSearchUiState.Empty],
     * or [SemanticSearchUiState.Error].
     *
     * Results are grouped by [SemanticSearchResult.SourceType]; groups with no results
     * are omitted from the map (Requirement 36.5).
     *
     * @param query Natural language search string (non-blank).
     *
     * Requirements: 36.1, 36.3, 36.4, 36.5
     */
    fun search(query: String) {
        if (query.isBlank()) return

        _uiState.value = SemanticSearchUiState.Loading

        viewModelScope.launch {
            when (val result = semanticSearchUseCase(query)) {
                is ApiResult.Success -> {
                    val results = result.data
                    if (results.isEmpty()) {
                        _uiState.value = SemanticSearchUiState.Empty
                    } else {
                        // Group by source type, sort each group by relevance descending
                        // Omit source types that have no results
                        val grouped = results
                            .groupBy { it.sourceType }
                            .mapValues { (_, items) ->
                                items.sortedByDescending { it.relevanceScore }
                            }
                            .filterValues { it.isNotEmpty() }
                        _uiState.value = SemanticSearchUiState.Success(grouped)
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = SemanticSearchUiState.Error(
                        result.error.message ?: "An error occurred during search."
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = SemanticSearchUiState.Error(
                        "No network connection. Semantic search requires internet access."
                    )
                }
                is ApiResult.Loading -> {
                    // Remain in Loading state
                }
            }
        }
    }

    /**
     * Reset the UI state back to [SemanticSearchUiState.Idle].
     *
     * Called when the user clears the search query.
     */
    fun reset() {
        _uiState.value = SemanticSearchUiState.Idle
    }
}
