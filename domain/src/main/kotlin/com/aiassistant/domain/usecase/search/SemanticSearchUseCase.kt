/**
 * SemanticSearchUseCase.kt
 *
 * Purpose: Submits a natural language query to the backend semantic search endpoint
 *          and returns ranked results filtered to relevance score ≥ 0.5.
 * Architecture: domain module — pure Kotlin, zero Android or third-party dependencies.
 * Dependencies: core-common (ApiResult), SemanticSearchRepository, SemanticSearchResult
 *
 * Requirements: 36.1, 36.3, 36.8
 */

package com.aiassistant.domain.usecase.search

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.repository.SemanticSearchRepository
import javax.inject.Inject

/**
 * Use case for AI-powered semantic search across conversations, notes, documents, and memories.
 *
 * Delegates to [SemanticSearchRepository] and filters results to only return items with a
 * relevance score ≥ 0.5 (Requirement 36.3). Returns an empty list (not an error) when
 * no results meet the threshold (no-op return per spec).
 *
 * @param repository Repository that submits the search query to the backend.
 */
class SemanticSearchUseCase @Inject constructor(private val repository: SemanticSearchRepository) {

    /**
     * Execute a semantic search for the given [query].
     *
     * @param query Natural language search string (must be non-blank).
     * @return [ApiResult.Success] with a filtered, ranked list of [SemanticSearchResult] items,
     *         or an error result if the network call fails. Returns an empty list if no
     *         results meet the 0.5 threshold.
     *
     * Requirements: 36.1, 36.3
     */
    suspend operator fun invoke(query: String): ApiResult<List<SemanticSearchResult>> {
        val result = repository.search(query)
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(
                result.data.filter { it.relevanceScore >= 0.5f }
            )
            else -> result
        }
    }
}
