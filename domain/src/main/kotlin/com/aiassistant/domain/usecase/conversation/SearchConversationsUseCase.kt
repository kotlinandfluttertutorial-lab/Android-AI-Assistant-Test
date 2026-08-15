/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SearchConversationsUseCase.kt
 * Purpose    : Encapsulates the 'SearchConversations' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * SearchConversationsUseCase.kt
 *
 * Purpose: Searches conversations by matching a query against conversation titles and
 *          message content, returning all conversations when the query is empty.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (ConversationRepository),
 *               domain model (Conversation)
 *
 * Requirements: 11.2
 *
 * Design decisions:
 * - An empty query returns all conversations (delegates to the repository which may fall
 *   back to getConversations() for an empty query). This avoids a dead state in the UI
 *   when the user clears the search field.
 * - The 300 ms response-time constraint (Requirement 11.2) is enforced by the data layer
 *   via the Room FTS4 virtual table; the use case does not add any additional processing.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.repository.ConversationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Use case for searching conversations by title and message content.
 *
 * WHEN a User enters a search query, THE AI_Assistant SHALL filter Conversations by
 * matching the query against Conversation titles and Message content within 300 ms
 * using the local Room FTS index (Requirement 11.2).
 *
 * An empty [query] returns all conversations.
 *
 * @param conversationRepository Repository providing the full-text search operation.
 */
class SearchConversationsUseCase @Inject constructor(private val conversationRepository: ConversationRepository) {

    /**
     * Executes the search.
     *
     * When [query] is blank or empty the repository is expected to return all
     * conversations, maintaining a consistent experience when the search field is cleared.
     *
     * @param query The search string to match against conversation titles and message content.
     *              An empty or blank value returns all conversations.
     * @return Cold [Flow] emitting [ApiResult.Success] with matching [Conversation] objects,
     *         sorted by [Conversation.updatedAt] descending, or an error variant on failure.
     */
    operator fun invoke(query: String): Flow<ApiResult<List<Conversation>>> =
        conversationRepository.searchConversations(query.trim())
}
