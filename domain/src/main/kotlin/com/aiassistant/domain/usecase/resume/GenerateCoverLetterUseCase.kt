/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GenerateCoverLetterUseCase.kt
 * Purpose    : Encapsulates the 'GenerateCoverLetter' business operation
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
 * File       : GenerateCoverLetterUseCase.kt
 * Purpose    : Encapsulates the 'GenerateCoverLetter' business operation
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
 * GenerateCoverLetterUseCase.kt
 *
 * Purpose: Generates a cover letter tailored to a target job description and the user's
 *          professional history, constrained to â‰¤ 400 words.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ResumeRepository
 *
 * Requirements: 14.2
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ResumeRepository
import javax.inject.Inject

/**
 * Use case for generating a tailored cover letter (â‰¤ 400 words).
 *
 * WHEN a User requests a cover letter, THE AI_Orchestrator SHALL generate one tailored
 * to the provided job description and resume data, not exceeding 400 words
 * (Requirement 14.2).
 *
 * @param resumeRepository Repository providing the AI cover letter generation operation.
 */
class GenerateCoverLetterUseCase @Inject constructor(private val resumeRepository: ResumeRepository) {

    /**
     * Generates a cover letter from the supplied inputs.
     *
     * @param professionalHistory A description of the user's work experience, skills, and
     *                            education. Must not be blank.
     * @param jobDescription      The target job posting text used to tailor the cover letter.
     *                            Must not be blank.
     * @return [ApiResult.Success] with the generated cover letter text on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if either input is blank.
     */
    suspend operator fun invoke(professionalHistory: String, jobDescription: String): ApiResult<String> {
        if (professionalHistory.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Professional history must not be blank.",
                    fields = mapOf(FIELD_HISTORY to "Professional history is required.")
                )
            )
        }

        if (jobDescription.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Job description must not be blank.",
                    fields = mapOf(FIELD_JOB_DESCRIPTION to "A target job description is required.")
                )
            )
        }

        return resumeRepository.generateCoverLetter(
            professionalHistory = professionalHistory.trim(),
            jobDescription = jobDescription.trim()
        )
    }

    internal companion object {
        const val FIELD_HISTORY = "professionalHistory"
        const val FIELD_JOB_DESCRIPTION = "jobDescription"
    }
}
