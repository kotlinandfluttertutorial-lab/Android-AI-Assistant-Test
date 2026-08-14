/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : TranslationRepository.kt
 * Purpose    : Domain contract defining data access operations for Translation entities
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
 * File       : TranslationRepository.kt
 * Purpose    : Domain contract defining data access operations for Translation entities
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
 * TranslationRepository.kt
 *
 * Purpose: Domain-layer repository interface for the Translator feature.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult)
 *
 * Requirements: 10.5
 *
 * Design decisions:
 * - The data layer implementation handles online/offline routing internally: when the
 *   device is connected, it calls the AI Orchestrator; when offline, it falls back to
 *   the bundled on-device Offline_Translation_Model (Requirement 10.5).
 * - The use case does not need to know which path was taken; the result is always a
 *   translated string.
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult

/**
 * Contract for text translation operations between the domain and data layers.
 */
interface TranslationRepository {

    /**
     * Translates the given [text] from [sourceLanguage] to [targetLanguage].
     *
     * WHILE the device is offline, THE AI_Assistant SHALL fall back to the bundled
     * Offline_Translation_Model (Requirement 10.5).
     *
     * Language codes should follow BCP 47 format (e.g. "en", "fr", "de", "zh-Hans").
     * The data layer routes the request to the appropriate translation provider based on
     * device connectivity.
     *
     * @param text           The text to translate.
     * @param sourceLanguage BCP 47 language code of the source text (e.g. "en").
     * @param targetLanguage BCP 47 language code of the desired output (e.g. "fr").
     * @return [ApiResult.Success] with the translated text on success.
     */
    suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): ApiResult<String>
}
