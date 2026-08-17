/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteConversationUseCase.kt
 * Purpose    : Encapsulates the 'DeleteConversation' business operation
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
 * DeleteConversationUseCase.kt
 *
 * Purpose: Soft-deletes a conversation by delegating to the repository, which marks it
 *          as deleted on the backend and removes it from the local cache within 5 seconds.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (ConversationRepository)
 *
 * Requirements: 11.3, 11.4
 *
 * Design decisions:
 * - The domain layer performs no local state mutation; the repository implementation is
 *   responsible for the 5-second local cache removal guarantee (Requirement 11.4).
 * - A single suspend call keeps the use case surface area minimal.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Use case for soft-deleting a conversation.
 *
 * WHEN a User deletes a Conversation, THE Backend SHALL mark the Conversation as
 * soft-deleted and THE AI_Assistant SHALL remove it from the local cache within
 * 5 seconds (Requirement 11.4).
 *
 * @param conversationRepository Repository providing the delete operation.
 */
class DeleteConversationUseCase @Inject constructor(private val conversationRepository: ConversationRepository) {

    /**
     * Executes the soft-delete operation.
     *
     * Delegates directly to [ConversationRepository.deleteConversation]. The data layer
     * implementation is responsible for:
     * 1. Sending the soft-delete request to the backend.
     * 2. Marking the conversation as [Conversation.isDeleted] = true in local Room.
     * 3. Completing the local cache update within 5 seconds.
     *
     * @param conversationId The unique identifier of the conversation to delete.
     * @return [ApiResult.Success] with [Unit] on success,
     *         [ApiResult.Error] when the delete call fails,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(conversationId: String): ApiResult<Unit> =
        conversationRepository.deleteConversation(conversationId)
}
