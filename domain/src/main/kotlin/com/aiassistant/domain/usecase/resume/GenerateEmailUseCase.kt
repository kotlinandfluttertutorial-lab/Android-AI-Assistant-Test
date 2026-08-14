/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GenerateEmailUseCase.kt
 * Purpose    : Encapsulates the 'GenerateEmail' business operation
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GenerateEmailUseCase.kt
 * Purpose    : Encapsulates the 'GenerateEmail' business operation
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
 * GenerateEmailUseCase.kt
 *
 * Purpose: Generates a professional email (subject, greeting, body, closing) from the
 *          user-provided context and intent.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ResumeRepository
 *
 * Requirements: 14.4
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ResumeRepository
import javax.inject.Inject

/**
 * Use case for generating a professional email.
 *
 * WHEN a User provides context and intent for an email, THE AI_Orchestrator SHALL generate
 * a professional email with subject line, greeting, body, and closing (Requirement 14.4).
 *
 * @param resumeRepository Repository providing the AI email generation operation.
 */
class GenerateEmailUseCase @Inject constructor(private val resumeRepository: ResumeRepository) {

    /**
     * Generates a professional email from the supplied inputs.
     *
     * @param context A description of the email situation and relevant background.
     *                Must not be blank.
     * @param intent  The purpose or goal of the email. Must not be blank.
     * @return [ApiResult.Success] with the generated email text on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if either input is blank.
     */
    suspend operator fun invoke(context: String, intent: String): ApiResult<String> {
        if (context.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Email context must not be blank.",
                    fields = mapOf(FIELD_CONTEXT to "Context is required.")
                )
            )
        }

        if (intent.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Email intent must not be blank.",
                    fields = mapOf(FIELD_INTENT to "A clear intent is required.")
                )
            )
        }

        return resumeRepository.generateEmail(
            context = context.trim(),
            intent = intent.trim()
        )
    }

    internal companion object {
        const val FIELD_CONTEXT = "context"
        const val FIELD_INTENT = "intent"
    }
}
