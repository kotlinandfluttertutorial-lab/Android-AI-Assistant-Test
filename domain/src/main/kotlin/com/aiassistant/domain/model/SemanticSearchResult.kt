/**
 * SemanticSearchResult.kt
 *
 * Purpose: Domain entity representing a single result from the AI-powered semantic
 *          search across conversations, notes, documents, and memories.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 36.1, 36.3, 36.8
 */

package com.aiassistant.domain.model

/**
 * A single result item returned by the semantic search across all content types.
 *
 * @param sourceType     The content type this result originates from.
 * @param sourceName     Human-readable name of the source item (e.g. conversation title, note title).
 * @param excerpt        A short excerpt of the matching content (≤300 chars).
 * @param relevanceScore Cosine similarity score (0.0–1.0); only results ≥0.5 are surfaced.
 * @param deepLinkUri    URI to navigate directly to the source item (e.g. aiassistant://notes/{id}).
 */
data class SemanticSearchResult(
    val sourceType: SourceType,
    val sourceName: String,
    val excerpt: String, // ≤300 chars, enforced by use case
    val relevanceScore: Float, // 0.0–1.0
    val deepLinkUri: String
) {
    /**
     * Content type classification for a [SemanticSearchResult].
     */
    enum class SourceType {
        CONVERSATION,
        NOTE,
        DOCUMENT,
        MEMORY
    }
}
