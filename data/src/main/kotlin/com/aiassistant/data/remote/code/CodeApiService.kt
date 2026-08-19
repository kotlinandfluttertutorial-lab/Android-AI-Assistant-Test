/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : CodeApiService.kt
 * Purpose    : Retrofit service interface for the /code/analyze REST endpoint used by
 *              the Code Assistant feature.
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
 *
 * Dependencies: Retrofit, kotlinx.serialization
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.6
 * ============================================================
 */
package com.aiassistant.data.remote.code

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// ─── DTOs ─────────────────────────────────────────────────────────────────────

/**
 * Request body for `POST /code/analyze`.
 *
 * @param code       The source code submitted by the user.
 * @param languageId Lowercase language identifier matching backend convention
 *                   (e.g. "kotlin", "python", "javascript").
 * @param action     One of: "explain", "fix_bug", "generate_tests".
 */
@Serializable
data class CodeAnalysisRequestDto(
    @SerialName("code") val code: String,
    @SerialName("language_id") val languageId: String,
    @SerialName("action") val action: String
)

/**
 * Response from `POST /code/analyze`.
 *
 * @param languageId   Language identifier echoed back by the backend (used for
 *                     syntax highlighting, Requirement 12.6).
 * @param originalCode The original code submitted (echoed for reference).
 * @param action       The analysis action that was performed.
 * @param content      AI-generated result text (explanation / fixed code / test suite).
 */
@Serializable
data class CodeAnalysisResponseDto(
    @SerialName("language_id") val languageId: String,
    @SerialName("original_code") val originalCode: String,
    @SerialName("action") val action: String,
    @SerialName("content") val content: String
)

// ─── Retrofit service ─────────────────────────────────────────────────────────

/**
 * Retrofit service for the code analysis endpoint.
 *
 * Consumed exclusively by [CodeRemoteDataSource].
 */
interface CodeApiService {

    /**
     * Submits code for AI analysis (Requirement 12.1–12.4, 12.6).
     *
     * The backend AI Orchestrator performs the requested action and returns a structured
     * result. Supported actions:
     * - `explain`        → Markdown explanation with what/why/improvements (Req 12.2)
     * - `fix_bug`        → Corrected code with inline change comments (Req 12.3)
     * - `generate_tests` → Full test suite in the same language, AAA pattern (Req 12.4)
     *
     * @param body Request containing code, language, and action.
     * @return Response containing the AI-generated result.
     */
    @POST("code/analyze")
    suspend fun analyzeCode(@Body body: CodeAnalysisRequestDto): CodeAnalysisResponseDto
}
