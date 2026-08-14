/**
 * SemanticSearchRepository.kt
 *
 * Purpose: Domain contract for performing semantic search across all content types.
 * Architecture: domain module — repository interface. Zero Android or framework dependencies.
 * Dependencies: core-common (ApiResult), domain (SemanticSearchResult)
 *
 * Requirements: 36.1, 36.3, 36.8
 */
package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.SemanticSearchResult

/**
 * Repository interface for AI-powered semantic search.
 *
 * Implementations submit a natural-language query to the backend `/search/semantic`
 * endpoint and return a ranked list of results across all content types.
 *
 * Results from the backend are filtered to only include items with a relevance
 * score ≥ 0.5 (Requirement 36.3).
 */
interface SemanticSearchRepository {
    /**
     * Perform a semantic search for the given [query].
     *
     * @param query Natural language search string.
     * @return [ApiResult.Success] with a list of [SemanticSearchResult] items sorted
     *         by relevance score descending, or an [ApiResult.Error] /
     *         [ApiResult.NetworkUnavailable] on failure.
     */
    suspend fun search(query: String): ApiResult<List<SemanticSearchResult>>
}
