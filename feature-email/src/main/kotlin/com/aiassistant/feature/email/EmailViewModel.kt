/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Email feature
 *
 * Architecture Layer : Feature (feature-email)
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
 * Module     : feature-email
 * File       : EmailViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Email feature
 *
 * Architecture Layer : Feature (feature-email)
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
 * EmailViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates use case calls for email generation
 *          and grammar correction.
 * Architecture: feature-email â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (GenerateEmailUseCase, CorrectGrammarUseCase),
 *               core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 14.4, 14.5
 */
package com.aiassistant.feature.email

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.resume.CorrectGrammarUseCase
import com.aiassistant.domain.usecase.resume.GenerateEmailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the email composer and grammar correction flow.
 *
 * Exposes a [StateFlow] of [EmailUiState] that composables observe. All blocking work
 * (network calls) is dispatched on [DispatcherProvider.io].
 */
@HiltViewModel
class EmailViewModel @Inject constructor(
    private val generateEmailUseCase: GenerateEmailUseCase,
    private val correctGrammarUseCase: CorrectGrammarUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<EmailUiState>(EmailUiState.Idle)

    /** Observable email UI state. */
    val uiState: StateFlow<EmailUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Generates a professional email with subject line, greeting, body, and closing.
     *
     * THE AI_Orchestrator SHALL generate a professional email with subject line, greeting,
     * body, and closing from User-provided context and intent (Requirement 14.4).
     *
     * @param context A description of the email situation and relevant background.
     * @param intent  The purpose or goal of the email.
     */
    fun generateEmail(context: String, intent: String) {
        viewModelScope.launch {
            _uiState.value = EmailUiState.Loading("Generating emailâ€¦")
            val result = withContext(dispatchers.io) {
                generateEmailUseCase(context, intent)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> EmailUiState.EmailGenerated(result.data)
                is ApiResult.Error -> EmailUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> EmailUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> EmailUiState.Loading("Generating emailâ€¦")
            }
        }
    }

    /**
     * Corrects grammar in a draft email and computes the word-level diff.
     *
     * THE AI_Orchestrator SHALL return the corrected text with a diff highlighting all
     * changes (Requirement 14.5).
     *
     * @param draftEmail The raw draft email text to correct.
     */
    fun correctGrammar(draftEmail: String) {
        viewModelScope.launch {
            _uiState.value = EmailUiState.Loading("Correcting grammarâ€¦")
            val result = withContext(dispatchers.io) {
                correctGrammarUseCase(draftEmail)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    val corrected = result.data
                    val diffSpans = computeWordDiff(draftEmail, corrected)
                    EmailUiState.GrammarCorrected(
                        original = draftEmail,
                        corrected = corrected,
                        diffSpans = diffSpans
                    )
                }
                is ApiResult.Error -> EmailUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> EmailUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> EmailUiState.Loading("Correcting grammarâ€¦")
            }
        }
    }

    /**
     * Resets the ViewModel back to [EmailUiState.Idle].
     *
     * Call this when the user taps "Start Over" or navigates away.
     */
    fun resetState() {
        _uiState.value = EmailUiState.Idle
    }
}
