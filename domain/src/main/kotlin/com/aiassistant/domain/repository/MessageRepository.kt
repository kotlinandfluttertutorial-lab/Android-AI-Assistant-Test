/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MessageRepository.kt
 * Purpose    : Domain contract defining data access operations for Message entities
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : MessageRepository.kt
 * Purpose    : Domain contract defining data access operations for Message entities
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
 * MessageRepository.kt
 *
 * Purpose: Domain-layer repository interface for all message operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (Message)
 *
 * Requirements: 2.6, 2.7, 10.2
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Message

/**
 * Contract for message operations between the domain and data layers.
 *
 * The data module provides a concrete implementation backed by Room (local) and
 * Retrofit (remote). Messages with [Message.syncStatus] of "pending" are queued
 * locally and submitted to the backend via WorkManager when connectivity is restored
 * (Requirement 10.2).
 */
interface MessageRepository {

    /**
     * Sends a new user message within a conversation.
     *
     * The data layer persists the message locally with [Message.syncStatus] = "pending"
     * before attempting the remote call. On success, status transitions to "synced".
     * On permanent failure after retries, status transitions to "failed" (Requirement 10.6).
     *
     * @param conversationId The unique identifier of the conversation to send the message into.
     * @param content        The text content of the message.
     * @param provider       The LLM provider identifier to use for generating the response.
     * @return [ApiResult.Success] with the persisted [Message] on success.
     */
    suspend fun sendMessage(conversationId: String, content: String, provider: String): ApiResult<Message>

    /**
     * Requests a regenerated response for an existing assistant message.
     *
     * WHEN a User selects regenerate on a Message, THE AI_Orchestrator SHALL produce a new
     * response using the same input context and append it as an alternative to the existing
     * Message (Requirement 2.6).
     *
     * @param conversationId    The unique identifier of the conversation.
     * @param originalMessageId The unique identifier of the assistant message to regenerate.
     * @return [ApiResult.Success] with the new alternative [Message] on success.
     */
    suspend fun regenerateMessage(conversationId: String, originalMessageId: String): ApiResult<Message>

    /**
     * Submits all queued (pending) messages to the backend in their original order.
     *
     * Called by WorkManager's `SyncMessagesWorker` when device connectivity is restored
     * (Requirement 10.2). Messages that fail delivery after [MAX_RETRY_ATTEMPTS] are
     * marked "failed" and the user is notified.
     *
     * @return [ApiResult.Success] with the count of successfully synced messages on success.
     */
    suspend fun syncOfflineQueue(): ApiResult<Int>
}
