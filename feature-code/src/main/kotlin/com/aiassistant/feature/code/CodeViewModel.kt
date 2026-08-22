/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Code feature
 *
 * Architecture Layer : Feature (feature-code)
 * Pattern Used       : MVVM ViewModel
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
 * File       : CodeViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Code feature
 *
 * Architecture Layer : Feature (feature-code)
 * Pattern Used       : MVVM ViewModel
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
 * CodeViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for the code feature,
 *          including editor updates, language/action selection, AI analysis submission,
 *          and result/error state transitions.
 * Architecture: feature-code â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (AnalyzeCodeUseCase, CodeAnalysisRequest, CodeAction, SupportedLanguage),
 *               core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6
 */
package com.aiassistant.feature.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.usecase.code.AnalyzeCodeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the CodeEditor and CodeAnalysis screens.
 *
 * Exposes a [StateFlow] of [CodeUiState] that composables observe. All blocking work
 * (AI network calls) is dispatched on [DispatcherProvider.io].
 *
 * State machine transitions:
 * - [CodeUiState.Idle] â†’ [CodeUiState.Editing] (on first code/language update)
 * - [CodeUiState.Editing] â†’ [CodeUiState.Analyzing] (on submit)
 * - [CodeUiState.Analyzing] â†’ [CodeUiState.AnalysisResult] (on AI success)
 * - [CodeUiState.Analyzing] â†’ [CodeUiState.Error] (on AI failure)
 * - [CodeUiState.AnalysisResult] â†’ [CodeUiState.Editing] (on back to editor)
 * - Any â†’ [CodeUiState.Idle] (on reset)
 */
@HiltViewModel
class CodeViewModel @Inject constructor(
    private val analyzeCodeUseCase: AnalyzeCodeUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<CodeUiState>(CodeUiState.Idle)

    /** Observable code UI state. */
    val uiState: StateFlow<CodeUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Updates the code content and language, transitioning to [CodeUiState.Editing].
     *
     * If the current state is already [CodeUiState.Editing], only the code and language
     * fields are updated (selected action is preserved).
     *
     * @param code     The new code content typed by the user.
     * @param language The [SupportedLanguage] selected for this code snippet.
     */
    fun updateCode(code: String, language: SupportedLanguage) {
        val currentState = _uiState.value
        _uiState.value = if (currentState is CodeUiState.Editing) {
            currentState.copy(code = code, language = language)
        } else {
            CodeUiState.Editing(
                code = code,
                language = language,
                selectedAction = (currentState as? CodeUiState.Editing)?.selectedAction
                    ?: CodeAction.EXPLAIN
            )
        }
    }

    /**
     * Updates the selected language without changing the code content.
     *
     * Transitions to [CodeUiState.Editing] if not already there, preserving the current
     * code content and selected action (Requirement 12.1).
     *
     * @param language The [SupportedLanguage] to select.
     */
    fun selectLanguage(language: SupportedLanguage) {
        val currentState = _uiState.value
        _uiState.value = when (currentState) {
            is CodeUiState.Editing -> currentState.copy(language = language)
            else -> CodeUiState.Editing(
                code = "",
                language = language,
                selectedAction = CodeAction.EXPLAIN
            )
        }
    }

    /**
     * Updates the selected AI action without changing other state.
     *
     * No-op when the current state is not [CodeUiState.Editing].
     *
     * @param action The [CodeAction] to perform when the user submits.
     */
    fun selectAction(action: CodeAction) {
        val currentState = _uiState.value as? CodeUiState.Editing ?: return
        _uiState.value = currentState.copy(selectedAction = action)
    }

    /**
     * Submits the current code for AI analysis.
     *
     * Transitions to [CodeUiState.Analyzing] immediately, then calls [AnalyzeCodeUseCase].
     * On success transitions to [CodeUiState.AnalysisResult]; on failure to [CodeUiState.Error].
     *
     * No-op when the current state is not [CodeUiState.Editing] or when code is blank.
     */
    fun submitForAnalysis() {
        val currentState = _uiState.value as? CodeUiState.Editing ?: return
        if (currentState.code.isBlank()) return

        val request = CodeAnalysisRequest(
            code = currentState.code,
            language = currentState.language,
            action = currentState.selectedAction
        )

        _uiState.value = CodeUiState.Analyzing(
            code = currentState.code,
            language = currentState.language,
            action = currentState.selectedAction
        )

        viewModelScope.launch {
            val result = withContext(dispatchers.io) { analyzeCodeUseCase(request) }
            _uiState.value = when (result) {
                is ApiResult.Success -> CodeUiState.AnalysisResult(
                    request = request,
                    result = result.data
                )
                is ApiResult.Error -> CodeUiState.Error(
                    // The backend /code/analyze endpoint is not yet deployed.
                    // Show a clear message instead of a raw server error string.
                    if (result.error is com.aiassistant.core.common.DomainError.ServerError &&
                        (result.error as com.aiassistant.core.common.DomainError.ServerError).httpStatusCode == 404
                    ) {
                        "Code analysis is not yet available. Check back in a future update."
                    } else {
                        result.error.message
                    }
                )
                is ApiResult.NetworkUnavailable -> CodeUiState.Error(
                    "No network connection. AI features require internet access."
                )
                is ApiResult.Loading -> CodeUiState.Analyzing(
                    code = request.code,
                    language = request.language,
                    action = request.action
                )
            }
        }
    }

    /**
     * Resets the state to [CodeUiState.Idle], clearing all code and selection.
     */
    fun reset() {
        _uiState.value = CodeUiState.Idle
    }

    /**
     * Transitions from [CodeUiState.AnalysisResult] back to [CodeUiState.Editing],
     * restoring the original code and language from the analysis request.
     *
     * No-op when the current state is not [CodeUiState.AnalysisResult].
     */
    fun backToEditor() {
        val currentState = _uiState.value as? CodeUiState.AnalysisResult ?: return
        _uiState.value = CodeUiState.Editing(
            code = currentState.request.code,
            language = currentState.request.language,
            selectedAction = currentState.request.action
        )
    }
}
