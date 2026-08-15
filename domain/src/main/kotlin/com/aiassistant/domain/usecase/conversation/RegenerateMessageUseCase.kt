/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RegenerateMessageUseCase.kt
 * Purpose    : Encapsulates the 'RegenerateMessage' business operation
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
 * RegenerateMessageUseCase.kt
 *
 * Purpose: Requests a new AI response for an existing message, appending it as an
 *          alternative to the original.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (MessageRepository),
 *               domain model (Message)
 *
 * Requirements: 2.6
 *
 * Design decisions:
 * - The domain layer performs no local state mutation; the repository implementation handles
 *   persisting the new alternative message and linking it to the original.
 * - A single suspend call keeps the use case surface area minimal.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.repository.MessageRepository
import javax.inject.Inject

/**
 * Use case for regenerating an AI response for an existing message.
 *
 * WHEN a User selects regenerate on a Message, THE AI_Orchestrator SHALL produce a new
 * response using the same input context and append it as an alternative to the existing
 * Message (Requirement 2.6).
 *
 * @param messageRepository Repository providing the regenerate operation.
 */
class RegenerateMessageUseCase @Inject constructor(private val messageRepository: MessageRepository) {

    /**
     * Executes the regeneration request.
     *
     * Delegates to [MessageRepository.regenerateMessage]. The backend uses the same
     * original user input context to generate a fresh response, which is returned as a
     * new [Message] entity appended as an alternative alongside the original.
     *
     * @param conversationId    The unique identifier of the conversation.
     * @param originalMessageId The unique identifier of the assistant message to regenerate.
     * @return [ApiResult.Success] with the new alternative [Message] on success,
     *         [ApiResult.Error] when the regeneration call fails,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(conversationId: String, originalMessageId: String): ApiResult<Message> =
        messageRepository.regenerateMessage(
            conversationId = conversationId,
            originalMessageId = originalMessageId
        )
}
