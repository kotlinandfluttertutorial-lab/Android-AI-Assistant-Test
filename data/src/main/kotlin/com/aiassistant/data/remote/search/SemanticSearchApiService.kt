/**
 * SemanticSearchApiService.kt — data module
 *
 * Purpose: Retrofit service interface for the `/search/semantic` REST endpoint.
 *          Consumed exclusively by [SemanticSearchRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 36.1, 36.3
 */
package com.aiassistant.data.remote.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// ─── Request / Response DTOs ──────────────────────────────────────────────────

/**
 * Request body for `POST /search/semantic`.
 */
@Serializable
data class SemanticSearchRequest(@SerialName("query") val query: String)

/**
 * Response DTO for a single semantic search result item.
 */
@Serializable
data class SemanticSearchResultDto(
    @SerialName("source_type") val sourceType: String, // "conversation"|"note"|"document"|"memory"
    @SerialName("source_name") val sourceName: String,
    @SerialName("excerpt") val excerpt: String, // ≤300 chars
    @SerialName("relevance_score") val relevanceScore: Float, // 0.0–1.0, 2 dp
    @SerialName("deep_link") val deepLink: String
)

/**
 * Response DTO wrapping the list of semantic search results.
 */
@Serializable
data class SemanticSearchResponseDto(
    @SerialName("results") val results: List<SemanticSearchResultDto>,
    @SerialName("total") val total: Int
)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for the semantic search endpoint.
 *
 * Requirements: 36.1, 36.2, 36.3
 */
interface SemanticSearchApiService {

    /**
     * Submit a natural language query for semantic search across all content types.
     *
     * @param body The search request body containing the query string.
     * @return List of [SemanticSearchResultDto] sorted by relevance score descending.
     */
    @POST("search/semantic")
    suspend fun search(@Body body: SemanticSearchRequest): SemanticSearchResponseDto
}
