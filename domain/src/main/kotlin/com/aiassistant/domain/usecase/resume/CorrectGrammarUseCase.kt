/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CorrectGrammarUseCase.kt
 * Purpose    : Encapsulates the 'CorrectGrammar' business operation
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
 * CorrectGrammarUseCase.kt
 *
 * Purpose: Corrects grammar in a draft email and returns the full corrected text.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ResumeRepository
 *
 * Requirements: 14.5
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ResumeRepository
import javax.inject.Inject

/**
 * Use case for correcting grammar in a draft email.
 *
 * WHEN a User requests grammar correction on a draft email, THE AI_Orchestrator SHALL
 * return the corrected text with a diff highlighting all changes (Requirement 14.5).
 *
 * @param resumeRepository Repository providing the AI grammar correction operation.
 */
class CorrectGrammarUseCase @Inject constructor(private val resumeRepository: ResumeRepository) {

    /**
     * Corrects grammar in the supplied draft email.
     *
     * @param draftEmail The raw draft email text to correct. Must not be blank.
     * @return [ApiResult.Success] with the full corrected email text on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if the input is blank.
     */
    suspend operator fun invoke(draftEmail: String): ApiResult<String> {
        if (draftEmail.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Draft email must not be blank.",
                    fields = mapOf(FIELD_DRAFT to "A non-empty draft email is required.")
                )
            )
        }

        return resumeRepository.correctGrammar(draftEmail.trim())
    }

    internal companion object {
        const val FIELD_DRAFT = "draftEmail"
    }
}
