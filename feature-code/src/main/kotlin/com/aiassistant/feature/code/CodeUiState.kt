/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeUiState.kt
 * Purpose    : CodeUiState — feature-code module component
 *
 * Architecture Layer : Feature (feature-code)
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
 * Module     : feature-code
 * File       : CodeUiState.kt
 * Purpose    : CodeUiState — feature-code module component
 *
 * Architecture Layer : Feature (feature-code)
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
 * CodeUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the code feature,
 *          covering idle, editing, analyzing, result, and error states.
 * Architecture: feature-code â€” MVVM presentation layer.
 * Dependencies: domain (CodeAction, CodeAnalysisRequest, CodeAnalysisResult, SupportedLanguage)
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 */
package com.aiassistant.feature.code

import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage

/**
 * Represents every possible UI state in the code feature.
 *
 * The [CodeViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render the appropriate screen.
 */
sealed class CodeUiState {

    /**
     * Initial state â€” the editor is empty and ready for input.
     *
     * Displayed when the user first opens the Code Assistant or after calling [CodeViewModel.reset].
     */
    data object Idle : CodeUiState()

    /**
     * The user is actively editing code in the editor.
     *
     * @param code           The current code content in the editor.
     * @param language       The currently selected [SupportedLanguage] for syntax highlighting
     *                       and AI analysis (Requirement 12.1).
     * @param selectedAction The currently selected [CodeAction] to perform on submit.
     */
    data class Editing(val code: String, val language: SupportedLanguage, val selectedAction: CodeAction) :
        CodeUiState()

    /**
     * An AI analysis call is in flight â€” show loading indicator.
     *
     * @param code     The code that was submitted for analysis.
     * @param language The language of the submitted code.
     * @param action   The [CodeAction] being performed.
     */
    data class Analyzing(val code: String, val language: SupportedLanguage, val action: CodeAction) : CodeUiState()

    /**
     * The AI analysis completed and the result is ready for display.
     *
     * @param request The original [CodeAnalysisRequest] that produced this result.
     * @param result  The [CodeAnalysisResult] from the AI, including [CodeAnalysisResult.languageId]
     *                used to render the correct syntax highlighting (Requirement 12.6).
     */
    data class AnalysisResult(val request: CodeAnalysisRequest, val result: CodeAnalysisResult) : CodeUiState()

    /**
     * An operation failed and an error message should be displayed.
     *
     * @param message Human-readable error message for the error banner.
     */
    data class Error(val message: String) : CodeUiState()
}
