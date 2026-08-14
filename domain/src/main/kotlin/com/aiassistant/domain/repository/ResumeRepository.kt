/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ResumeRepository.kt
 * Purpose    : Domain contract defining data access operations for Resume entities
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
 * File       : ResumeRepository.kt
 * Purpose    : Domain contract defining data access operations for Resume entities
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
 * ResumeRepository.kt
 *
 * Purpose: Domain-layer repository interface for Resume and Email generation features.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult)
 *
 * Requirements: 14.1, 14.2, 14.4, 14.5
 *
 * Design decisions:
 * - Resume, cover letter, and email generation are grouped in a single repository because
 *   they share the same AI Orchestrator backend endpoint and have similar request/response
 *   shapes.
 * - All operations return String (Markdown text) which the feature layer formats for
 *   export or display.
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult

/**
 * Contract for Resume and Email generation between the domain and data layers.
 */
interface ResumeRepository {

    /**
     * Generates an ATS-optimised resume in Markdown format.
     *
     * THE AI_Orchestrator SHALL generate an ATS-optimized resume in Markdown format within
     * 30 seconds (Requirement 14.1).
     *
     * @param professionalHistory A description of the user's work experience, skills, and
     *                            education.
     * @param jobDescription      The target job posting text used to tailor the resume.
     * @return [ApiResult.Success] with the generated resume in Markdown format on success.
     */
    suspend fun generateResume(professionalHistory: String, jobDescription: String): ApiResult<String>

    /**
     * Generates a tailored cover letter (â‰¤ 400 words) from professional history and
     * job description.
     *
     * WHEN a User requests a cover letter, THE AI_Orchestrator SHALL generate one
     * tailored to the provided job description and resume data, not exceeding 400 words
     * (Requirement 14.2).
     *
     * @param professionalHistory A description of the user's work experience, skills, and
     *                            education.
     * @param jobDescription      The target job posting text used to tailor the cover letter.
     * @return [ApiResult.Success] with the generated cover letter text on success.
     */
    suspend fun generateCoverLetter(professionalHistory: String, jobDescription: String): ApiResult<String>

    /**
     * Generates a professional email from the user-provided context and intent.
     *
     * THE AI_Orchestrator SHALL generate a professional email with subject line, greeting,
     * body, and closing (Requirement 14.4).
     *
     * @param context A description of the email situation and relevant background.
     * @param intent  The purpose or goal of the email (e.g. "Request a project deadline
     *                extension").
     * @return [ApiResult.Success] with the generated email text (including subject, greeting,
     *         body, and closing sections) on success.
     */
    suspend fun generateEmail(context: String, intent: String): ApiResult<String>

    /**
     * Corrects grammar in the provided draft email and returns the corrected text.
     *
     * WHEN a User requests grammar correction on a draft email, THE AI_Orchestrator SHALL
     * return the corrected text with a diff highlighting all changes (Requirement 14.5).
     *
     * @param draftEmail The raw draft email text to correct. Must not be blank.
     * @return [ApiResult.Success] with the full corrected email text on success.
     */
    suspend fun correctGrammar(draftEmail: String): ApiResult<String>
}
