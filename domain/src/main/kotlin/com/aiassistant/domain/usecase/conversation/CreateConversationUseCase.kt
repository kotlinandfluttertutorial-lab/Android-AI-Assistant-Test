/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CreateConversationUseCase.kt
 * Purpose    : Encapsulates the 'CreateConversation' business operation
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
 * CreateConversationUseCase.kt
 *
 * Purpose: Creates a new conversation after validating the title is not blank.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repository (ConversationRepository),
 *               domain model (Conversation)
 *
 * Requirements: 11.3
 *
 * Design decisions:
 * - Title validation is performed in the domain layer so the UI receives a typed
 *   DomainError.ValidationError and can render an inline field error without further parsing.
 * - Provider is passed through without local validation; the backend enforces provider existence.
 */

package com.aiassistant.domain.usecase.conversation

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.repository.ConversationRepository
import javax.inject.Inject

/**
 * Use case for creating a new conversation.
 *
 * THE AI_Assistant SHALL allow the User to pin, rename, and delete individual Conversations
 * (Requirement 11.3). Creating a conversation is the entry point for that lifecycle.
 *
 * Validates that [title] is not blank before delegating to [ConversationRepository].
 *
 * @param conversationRepository Repository providing the conversation creation call.
 */
class CreateConversationUseCase @Inject constructor(private val conversationRepository: ConversationRepository) {

    /**
     * Executes the conversation creation.
     *
     * Returns [ApiResult.Error] with [DomainError.ValidationError] immediately when
     * [title] is blank. Otherwise delegates to [ConversationRepository.createConversation].
     *
     * @param title    The desired title for the new conversation. Must not be blank.
     * @param provider The LLM provider identifier to associate with this conversation.
     * @return [ApiResult.Success] with the newly created [Conversation] on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] when title is blank,
     *         other [ApiResult.Error] or [ApiResult.NetworkUnavailable] on data layer failure.
     */
    suspend operator fun invoke(title: String, provider: String): ApiResult<Conversation> {
        if (title.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Conversation title must not be blank.",
                    fields = mapOf(FIELD_TITLE to "Title is required.")
                )
            )
        }

        return conversationRepository.createConversation(title.trim(), provider)
    }

    internal companion object {
        /** Form field name used in [DomainError.ValidationError.fields] for title errors. */
        const val FIELD_TITLE = "title"
    }
}
