/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SendMessageUseCase.kt
 * Purpose    : Encapsulates the 'SendMessage' business operation
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
 * SendMessageUseCase.kt
 *
 * Purpose: Validates a user message and sends it within a conversation, creating a
 *          pending Message locally before the backend responds.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repository (MessageRepository),
 *               domain model (Message)
 *
 * Requirements: 2.1, 10.2
 *
 * Design decisions:
 * - Content validation (non-blank) is performed in the domain layer to prevent empty
 *   messages from reaching the repository or the network layer.
 * - The repository is responsible for creating the local "pending" Message record and
 *   transitioning its syncStatus after the network response.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Message
import com.aiassistant.domain.repository.MessageRepository
import javax.inject.Inject

/**
 * Use case for sending a new user message within a conversation.
 *
 * THE AI_Orchestrator SHALL accept a Conversation request containing a User message,
 * a Conversation ID, and a selected LLM_Provider (Requirement 2.1).
 *
 * WHILE the device is offline, THE AI_Assistant SHALL queue outgoing Messages and
 * submit them when connectivity is restored using WorkManager (Requirement 10.2).
 * The data layer implements this queuing behaviour; this use case only validates and
 * delegates.
 *
 * @param messageRepository Repository providing the message send operation.
 */
class SendMessageUseCase @Inject constructor(private val messageRepository: MessageRepository) {

    /**
     * Executes the message send operation.
     *
     * Validates that [content] is not blank before delegating to the repository.
     * The data layer persists the message locally with [Message.syncStatus] = "pending"
     * and submits it to the backend, or queues it for WorkManager delivery when offline.
     *
     * @param conversationId The unique identifier of the conversation to send the message into.
     * @param content        The text content of the user's message. Must not be blank.
     * @param provider       The LLM provider identifier to generate the AI response.
     * @return [ApiResult.Success] with the persisted [Message] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] when content is blank,
     *         other [ApiResult.Error] or [ApiResult.NetworkUnavailable] on data layer failure.
     */
    suspend operator fun invoke(conversationId: String, content: String, provider: String): ApiResult<Message> {
        if (content.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Message content must not be blank.",
                    fields = mapOf(FIELD_CONTENT to "Message content is required.")
                )
            )
        }

        return messageRepository.sendMessage(
            conversationId = conversationId,
            content = content.trim(),
            provider = provider
        )
    }

    internal companion object {
        /** Form field name used in [DomainError.ValidationError.fields] for content errors. */
        const val FIELD_CONTENT = "content"
    }
}
