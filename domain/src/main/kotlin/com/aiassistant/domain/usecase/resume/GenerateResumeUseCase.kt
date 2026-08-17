/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GenerateResumeUseCase.kt
 * Purpose    : Encapsulates the 'GenerateResume' business operation
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
 * GenerateResumeUseCase.kt
 *
 * Purpose: Generates an ATS-optimised resume in Markdown format from the user's
 *          professional history and a target job description.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), ResumeRepository
 *
 * Requirements: 14.1
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ResumeRepository
import javax.inject.Inject

/**
 * Use case for generating an ATS-optimised resume.
 *
 * WHEN a User provides their professional history and a target job description, THE
 * AI_Orchestrator SHALL generate an ATS-optimized resume in Markdown format within
 * 30 seconds (Requirement 14.1).
 *
 * @param resumeRepository Repository providing the AI resume generation operation.
 */
class GenerateResumeUseCase @Inject constructor(private val resumeRepository: ResumeRepository) {

    /**
     * Generates a resume from the supplied inputs.
     *
     * @param professionalHistory A description of the user's work experience, skills, and
     *                            education. Must not be blank.
     * @param jobDescription      The target job posting text. Must not be blank.
     * @return [ApiResult.Success] with the generated resume Markdown on success,
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

        return resumeRepository.generateResume(
            professionalHistory = professionalHistory.trim(),
            jobDescription = jobDescription.trim()
        )
    }

    internal companion object {
        const val FIELD_HISTORY = "professionalHistory"
        const val FIELD_JOB_DESCRIPTION = "jobDescription"
    }
}
