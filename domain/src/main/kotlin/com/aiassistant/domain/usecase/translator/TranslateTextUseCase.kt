/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : TranslateTextUseCase.kt
 * Purpose    : Encapsulates the 'TranslateText' business operation
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
 * TranslateTextUseCase.kt
 *
 * Purpose: Translates text from one language to another, routing to the AI Orchestrator
 *          when online or to the bundled on-device model when offline.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), TranslationRepository
 *
 * Requirements: 10.5
 */

package com.aiassistant.domain.usecase.translator

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.TranslationRepository
import javax.inject.Inject

/**
 * Use case for translating text between language pairs.
 *
 * WHILE the device is offline, THE AI_Assistant SHALL fall back to the bundled
 * Offline_Translation_Model (Requirement 10.5). The data layer handles routing;
 * this use case only validates inputs and delegates.
 *
 * Language codes should follow BCP 47 format (e.g. "en", "fr", "de", "zh-Hans").
 *
 * @param translationRepository Repository providing the translation operation.
 */
class TranslateTextUseCase @Inject constructor(private val translationRepository: TranslationRepository) {

    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage].
     *
     * @param text           The text to translate. Must not be blank.
     * @param sourceLanguage BCP 47 source language code (e.g. "en"). Must not be blank.
     * @param targetLanguage BCP 47 target language code (e.g. "fr"). Must not be blank.
     * @return [ApiResult.Success] with the translated text on success,
     *         [ApiResult.Error] with [DomainError.ValidationError] if any input is blank
     *         or source equals target language.
     */
    suspend operator fun invoke(text: String, sourceLanguage: String, targetLanguage: String): ApiResult<String> {
        if (text.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Text to translate must not be blank.",
                    fields = mapOf(FIELD_TEXT to "Text is required.")
                )
            )
        }

        if (sourceLanguage.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Source language must not be blank.",
                    fields = mapOf(FIELD_SOURCE to "Source language code is required.")
                )
            )
        }

        if (targetLanguage.isBlank()) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Target language must not be blank.",
                    fields = mapOf(FIELD_TARGET to "Target language code is required.")
                )
            )
        }

        if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Source and target languages must be different.",
                    fields = mapOf(FIELD_TARGET to "Target language must differ from source.")
                )
            )
        }

        return translationRepository.translateText(
            text = text.trim(),
            sourceLanguage = sourceLanguage.trim(),
            targetLanguage = targetLanguage.trim()
        )
    }

    internal companion object {
        const val FIELD_TEXT = "text"
        const val FIELD_SOURCE = "sourceLanguage"
        const val FIELD_TARGET = "targetLanguage"
    }
}
