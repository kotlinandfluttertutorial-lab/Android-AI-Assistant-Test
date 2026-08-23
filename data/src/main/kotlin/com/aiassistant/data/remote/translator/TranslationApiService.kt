/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : TranslationApiService.kt
 * Purpose    : TranslationApiService — data module component
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
 * File       : TranslationApiService.kt
 * Purpose    : TranslationApiService — data module component
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
 * TranslationApiService.kt â€” data module
 *
 * Purpose: Retrofit service interface for the `/translate` REST endpoint used by the
 *          Translator feature. Consumed exclusively by [TranslationRemoteDataSource].
 *          Includes [TranslationRequest] and [TranslationResponse] DTOs.
 *
 * Architecture: data module â€” remote data source layer.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - DTOs use @SerialName with snake_case to match typical REST API conventions.
 * - Language codes follow BCP 47 format (e.g. "en", "fr", "zh-Hans") per domain contract.
 */
package com.aiassistant.data.remote.translator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// â”€â”€â”€ DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Request body for `POST /translate`.
 *
 * @param text           The text to translate.
 * @param sourceLanguage BCP 47 source language code (e.g. "en").
 * @param targetLanguage BCP 47 target language code (e.g. "fr").
 */
@Serializable
data class TranslationRequest(
    @SerialName("text") val text: String,
    @SerialName("source_language") val sourceLanguage: String,
    @SerialName("target_language") val targetLanguage: String
)

/**
 * Response from `POST /translate`.
 *
 * @param translatedText The translated text returned by the AI Orchestrator.
 */
@Serializable
data class TranslationResponse(@SerialName("translated_text") val translatedText: String)

// â”€â”€â”€ Retrofit service â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Retrofit service for the translation endpoint.
 * Routes requests to the AI Orchestrator when the device is online.
 */
interface TranslationApiService {

    /**
     * Translates text via the AI Orchestrator (online path, Requirement 10.5 / 19.1).
     *
     * @param request Request body containing text and language pair.
     * @return Response containing the translated text.
     */
    @POST("translate")
    suspend fun translateText(@Body request: TranslationRequest): TranslationResponse
}
