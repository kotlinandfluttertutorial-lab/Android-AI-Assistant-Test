/**
 * SemanticSearchMapper.kt — data module
 *
 * Purpose: Mapping between [SemanticSearchResultDto] (Retrofit) and [SemanticSearchResult] (domain model).
 *
 * Architecture: data module — mapper layer. Pure functions, no side effects.
 * Dependencies: domain (SemanticSearchResult), data.remote.search (SemanticSearchResultDto)
 *
 * Requirements: 36.1, 36.3
 */
package com.aiassistant.data.mapper

import com.aiassistant.data.remote.search.SemanticSearchResultDto
import com.aiassistant.domain.model.SemanticSearchResult

/**
 * Maps a [SemanticSearchResultDto] (Retrofit) to a [SemanticSearchResult] (domain model).
 *
 * The [SemanticSearchResult.SourceType] is derived from the backend's lowercase string value.
 * Unknown source types fall back to [SemanticSearchResult.SourceType.CONVERSATION].
 */
fun SemanticSearchResultDto.toDomain(): SemanticSearchResult = SemanticSearchResult(
    sourceType = when (sourceType.lowercase()) {
        "conversation" -> SemanticSearchResult.SourceType.CONVERSATION
        "note" -> SemanticSearchResult.SourceType.NOTE
        "document" -> SemanticSearchResult.SourceType.DOCUMENT
        "memory" -> SemanticSearchResult.SourceType.MEMORY
        else -> SemanticSearchResult.SourceType.CONVERSATION
    },
    sourceName = sourceName,
    excerpt = excerpt.take(300), // enforce max length defensively
    relevanceScore = relevanceScore,
    deepLinkUri = deepLink
)
