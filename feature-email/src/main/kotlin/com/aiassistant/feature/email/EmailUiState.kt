/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailUiState.kt
 * Purpose    : EmailUiState — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
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
 * Module     : feature-email
 * File       : EmailUiState.kt
 * Purpose    : EmailUiState — feature-email module component
 *
 * Architecture Layer : Feature (feature-email)
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
 * EmailUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the email composer
 *          and grammar correction flow.
 * Architecture: feature-email â€” MVVM presentation layer.
 * Dependencies: EmailDiff (DiffSpan)
 *
 * Requirements: 14.4, 14.5
 */
package com.aiassistant.feature.email

/**
 * Represents every possible UI state in the email composer / grammar correction flow.
 *
 * The [EmailViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class EmailUiState {

    /** Initial state before any operation has started. */
    data object Idle : EmailUiState()

    /**
     * An AI operation is in progress.
     *
     * @param message Human-readable progress message shown alongside the spinner.
     */
    data class Loading(val message: String) : EmailUiState()

    /**
     * Email generation succeeded (Requirement 14.4).
     *
     * @param emailText The generated professional email text including subject line,
     *                  greeting, body, and closing.
     */
    data class EmailGenerated(val emailText: String) : EmailUiState()

    /**
     * Grammar correction succeeded (Requirement 14.5).
     *
     * @param original   The original draft email submitted for correction.
     * @param corrected  The full corrected email text returned by the AI.
     * @param diffSpans  Word-level diff spans for the inline diff view.
     */
    data class GrammarCorrected(val original: String, val corrected: String, val diffSpans: List<DiffSpan>) :
        EmailUiState()

    /**
     * An operation failed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : EmailUiState()
}
