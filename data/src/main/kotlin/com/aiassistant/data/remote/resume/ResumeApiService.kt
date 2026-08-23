/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ResumeApiService.kt
 * Purpose    : ResumeApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * Module     : data
 * File       : ResumeApiService.kt
 * Purpose    : ResumeApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * ResumeApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/email/...` REST endpoints used by the
 *          Resume and Email generation features.
 *          Consumed exclusively by [ResumeRemoteDataSource].
 *
 * Architecture: data module — remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 14.1, 14.2, 14.4, 14.5
 */
package com.aiassistant.data.remote.resume

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// ─── DTOs ─────────────────────────────────────────────────────────────────────

/** Request body for `POST /resume/generate`. */
@Serializable
data class ResumeGenerateRequest(
    @SerialName("professional_history") val professionalHistory: String,
    @SerialName("job_description") val jobDescription: String
)

/** Response from `POST /resume/generate`. */
@Serializable
data class ResumeGenerateResponse(@SerialName("resume_markdown") val resumeMarkdown: String)

/** Request body for `POST /resume/cover-letter`. */
@Serializable
data class CoverLetterGenerateRequest(
    @SerialName("professional_history") val professionalHistory: String,
    @SerialName("job_description") val jobDescription: String
)

/** Response from `POST /resume/cover-letter`. */
@Serializable
data class CoverLetterGenerateResponse(@SerialName("cover_letter_text") val coverLetterText: String)

/** Request body for `POST /email/generate`. */
@Serializable
data class EmailGenerateRequest(@SerialName("context") val context: String, @SerialName("intent") val intent: String)

/** Response from `POST /email/generate`. */
@Serializable
data class EmailGenerateResponse(@SerialName("email_text") val emailText: String)

/** Request body for `POST /email/grammar-correct`. */
@Serializable
data class GrammarCorrectRequest(@SerialName("draft_email") val draftEmail: String)

/** Response from `POST /email/grammar-correct`. */
@Serializable
data class GrammarCorrectResponse(@SerialName("corrected_text") val correctedText: String)

// ─── Retrofit service ─────────────────────────────────────────────────────────

/** Retrofit service for resume and email endpoints. */
interface ResumeApiService {

    /**
     * Generates an ATS-optimised resume in Markdown format (Requirement 14.1).
     *
     * @param body Request containing professional history and job description.
     * @return Response containing the generated resume in Markdown.
     */
    @POST("resume/generate")
    suspend fun generateResume(@Body body: ResumeGenerateRequest): ResumeGenerateResponse

    /**
     * Generates a tailored cover letter (≤ 400 words) (Requirement 14.2).
     *
     * @param body Request containing professional history and job description.
     * @return Response containing the generated cover letter text.
     */
    @POST("resume/cover-letter")
    suspend fun generateCoverLetter(@Body body: CoverLetterGenerateRequest): CoverLetterGenerateResponse

    /**
     * Generates a professional email with subject, greeting, body, and closing
     * (Requirement 14.4).
     *
     * @param body Request containing context and intent.
     * @return Response containing the fully structured email text.
     */
    @POST("email/generate")
    suspend fun generateEmail(@Body body: EmailGenerateRequest): EmailGenerateResponse

    /**
     * Corrects grammar in a draft email and returns the full corrected text
     * (Requirement 14.5).
     *
     * @param body Request containing the raw draft email.
     * @return Response containing the corrected email text.
     */
    @POST("email/grammar-correct")
    suspend fun correctGrammar(@Body body: GrammarCorrectRequest): GrammarCorrectResponse
}
