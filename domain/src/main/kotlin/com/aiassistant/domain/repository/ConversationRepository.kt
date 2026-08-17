/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ConversationRepository.kt
 * Purpose    : Domain contract defining data access operations for Conversation entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * ConversationRepository.kt
 *
 * Purpose: Domain-layer repository interface for all conversation operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain models (Conversation, ExportFormat)
 *
 * Requirements: 11.1, 11.3, 11.4, 11.5, 11.6
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import kotlinx.coroutines.flow.Flow

/**
 * Contract for conversation operations between the domain and data layers.
 *
 * The data module provides a concrete implementation backed by Room (local) and
 * Retrofit (remote). All Flow-returning functions emit local data first, then
 * refresh from the network when connectivity is available (offline-first pattern).
 */
interface ConversationRepository {

    /**
     * Returns a [Flow] of all non-deleted conversations belonging to the authenticated user,
     * sorted by [Conversation.updatedAt] descending (Requirement 11.1).
     *
     * The data layer emits the local Room cache immediately, then re-emits after a
     * background sync with the backend.
     *
     * @return Cold [Flow] emitting [ApiResult.Success] with the full sorted list, or an
     *         error variant when the local database cannot be read.
     */
    fun getConversations(): Flow<ApiResult<List<Conversation>>>

    /**
     * Creates a new conversation on the backend and persists it locally.
     *
     * @param title    The human-readable title for the new conversation.
     * @param provider The LLM provider identifier to use for this conversation.
     * @return [ApiResult.Success] with the created [Conversation] on success.
     */
    suspend fun createConversation(title: String, provider: String): ApiResult<Conversation>

    /**
     * Soft-deletes a conversation by marking it as deleted on the backend and removing
     * it from the local cache within 5 seconds (Requirement 11.4).
     *
     * @param conversationId The unique identifier of the conversation to delete.
     * @return [ApiResult.Success] with [Unit] on success.
     */
    suspend fun deleteConversation(conversationId: String): ApiResult<Unit>

    /**
     * Searches conversations by matching [query] against conversation titles and message
     * content within 300 ms using the local Room FTS index (Requirement 11.2).
     *
     * An empty [query] returns all conversations (equivalent to [getConversations]).
     *
     * @param query The search string to match against titles and message content.
     * @return Cold [Flow] emitting [ApiResult.Success] with matching conversations.
     */
    fun searchConversations(query: String): Flow<ApiResult<List<Conversation>>>

    /**
     * Exports a conversation in the specified [format] and returns the file path or
     * content string of the exported file (Requirement 11.6).
     *
     * @param conversationId The unique identifier of the conversation to export.
     * @param format         The desired export format ([ExportFormat.MARKDOWN] or [ExportFormat.PDF]).
     * @return [ApiResult.Success] with the absolute file path (for PDF) or Markdown
     *         content string (for Markdown) on success.
     */
    suspend fun exportConversation(conversationId: String, format: ExportFormat): ApiResult<String>

    /**
     * Renames a conversation by updating its [title] on the backend and in the local cache.
     *
     * @param conversationId The unique identifier of the conversation to rename.
     * @param newTitle       The new human-readable title for the conversation.
     * @return [ApiResult.Success] with [Unit] on success.
     *
     * Requirements: 11.3
     */
    suspend fun renameConversation(conversationId: String, newTitle: String): ApiResult<Unit>

    /**
     * Pins or unpins a conversation.
     *
     * When [isPinned] is `true` the conversation is pinned to the top of the list;
     * when `false` it is unpinned and sorted by [Conversation.updatedAt] as normal.
     *
     * @param conversationId The unique identifier of the conversation to pin/unpin.
     * @param isPinned       `true` to pin, `false` to unpin.
     * @return [ApiResult.Success] with [Unit] on success.
     *
     * Requirements: 11.3
     */
    suspend fun pinConversation(conversationId: String, isPinned: Boolean): ApiResult<Unit>
}
