/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorUiState.kt
 * Purpose    : TranslatorUiState — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
 * Pattern Used       : UI State Data Class
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
 * Module     : feature-translator
 * File       : TranslatorUiState.kt
 * Purpose    : TranslatorUiState — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
 * Pattern Used       : UI State Data Class
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
 * TranslatorUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the Translator feature.
 *          Drives TranslatorScreen rendering and expresses online/offline routing results.
 * Architecture: feature-translator â€” MVVM presentation layer; consumed by TranslatorScreen.
 * Dependencies: None (pure Kotlin)
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Idle is the initial state â€” no prior translation result to show.
 * - Listening state signals active SpeechRecognizer session.
 * - Translating is the in-flight state while the use case call is pending.
 * - Success carries both original and translated text plus the offline flag so the UI
 *   can show an offline result badge when the translation came from the on-device model.
 * - Error carries the offline flag for the same reason.
 * - LanguagePair is a pure-data value class defined here alongside the state so the
 *   domain module stays clean (no UI concerns).
 * - SupportedLanguages is a top-level singleton listing all languages available through
 *   the online AI Orchestrator; a sub-set is implicitly available offline.
 */
package com.aiassistant.feature.translator

/**
 * A source + target language pair used by the translator.
 *
 * @param sourceCode BCP 47 code of the source language (e.g. "en").
 * @param sourceName Human-readable display name (e.g. "English").
 * @param targetCode BCP 47 code of the target language (e.g. "es").
 * @param targetName Human-readable display name (e.g. "Spanish").
 */
data class LanguagePair(val sourceCode: String, val sourceName: String, val targetCode: String, val targetName: String)

/**
 * Catalogue of all languages supported by the online AI Orchestrator.
 *
 * Language codes follow BCP 47 format.
 */
object SupportedLanguages {

    /** Each entry is a Pair of (BCP-47 code, display name). */
    val all: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "ru" to "Russian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh-Hans" to "Chinese (Simplified)",
        "ar" to "Arabic"
    )

    /** Default source language. */
    val defaultSource: Pair<String, String> = "en" to "English"

    /** Default target language. */
    val defaultTarget: Pair<String, String> = "es" to "Spanish"

    /** Default language pair (EN â†’ ES). */
    val defaultPair: LanguagePair = LanguagePair(
        sourceCode = defaultSource.first,
        sourceName = defaultSource.second,
        targetCode = defaultTarget.first,
        targetName = defaultTarget.second
    )

    /**
     * Looks up a display name by BCP 47 code. Returns the code itself when unknown.
     */
    fun displayNameFor(code: String): String = all.firstOrNull { it.first == code }?.second ?: code
}

/**
 * Represents every possible UI state in the Translator flow.
 *
 * State machine:
 * ```
 * Idle â”€â”€translate()â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º Translating â”€â”€â–º Success | Error
 *    â””â”€â”€startListening()â”€â”€â–º Listening â”€â”€â–º Idle (via onSpeechResult / onSpeechError)
 * ```
 */
sealed class TranslatorUiState {

    /** Initial state â€” ready for text or speech input. No prior translation result. */
    data object Idle : TranslatorUiState()

    /** SpeechRecognizer session is active; microphone is open. */
    data object Listening : TranslatorUiState()

    /** A translation API call is in-flight. */
    data object Translating : TranslatorUiState()

    /**
     * Translation completed successfully.
     *
     * @param sourceText     The original text that was translated.
     * @param translatedText The translated result.
     * @param sourceLang     BCP 47 code of the source language.
     * @param targetLang     BCP 47 code of the target language.
     * @param isOffline      `true` when the result was produced by the offline on-device
     *                       model rather than the online AI Orchestrator.
     */
    data class Success(
        val sourceText: String,
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String,
        val isOffline: Boolean
    ) : TranslatorUiState()

    /**
     * A recoverable error occurred.
     *
     * @param message   Human-readable error description shown to the user.
     * @param isOffline `true` when the failure is related to an offline-specific condition.
     */
    data class Error(val message: String, val isOffline: Boolean = false) : TranslatorUiState()
}
