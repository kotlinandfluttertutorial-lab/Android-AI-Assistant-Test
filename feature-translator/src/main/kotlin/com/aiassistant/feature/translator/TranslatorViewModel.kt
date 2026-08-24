/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Translator feature
 *
 * Architecture Layer : Feature (feature-translator)
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
 * Module     : feature-translator
 * File       : TranslatorViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Translator feature
 *
 * Architecture Layer : Feature (feature-translator)
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
 * TranslatorViewModel.kt
 *
 * Purpose: Manages translation state, language pair selection, and routes requests to
 *          the appropriate translation backend (online AI Orchestrator or offline model).
 * Architecture: feature-translator â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (TranslateTextUseCase), core-network (ConnectivityObserver),
 *               core-common (DispatcherProvider, ApiResult),
 *               TranslatorPreferences (language pair persistence via DataStore)
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - ViewModel holds no Context reference; SpeechRecognizer concerns live in the composable.
 * - Language pair selection is persisted via TranslatorPreferences (DataStore), loaded
 *   lazily on ViewModel init. DataStore reads are dispatched on dispatchers.io.
 * - Online/offline routing is fully handled in TranslationRepositoryImpl; the ViewModel
 *   only inspects ApiResult.NetworkUnavailable to set isOffline=true in the UI state.
 * - isOffline StateFlow is derived from ConnectivityObserver.isConnectedFlow so the
 *   offline banner stays in sync with network changes without extra work.
 * - All coroutine work is dispatched on dispatchers.io per project convention.
 */
package com.aiassistant.feature.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.usecase.translator.TranslateTextUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Translator screen.
 *
 * Exposes three state flows:
 * - [uiState] â€” the current translation UI state
 * - [isOffline] â€” live connectivity flag for the offline banner
 * - [selectedLanguagePair] â€” the user's current source/target selection
 *
 * The online/offline routing decision is handled inside [TranslateTextUseCase] /
 * [TranslationRepositoryImpl]; this ViewModel only reacts to [ApiResult.NetworkUnavailable]
 * to mark results as offline.
 */
@HiltViewModel
class TranslatorViewModel @Inject constructor(
    private val translateTextUseCase: TranslateTextUseCase,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider,
    private val translatorPreferences: TranslatorPreferences
) : ViewModel() {

    // â”€â”€â”€ UI State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<TranslatorUiState>(TranslatorUiState.Idle)

    /** Observable Translator UI state. Never exposes the mutable backing field. */
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Offline banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * `true` when the device has no network connectivity.
     * Derived from [ConnectivityObserver.isConnectedFlow].
     */
    val isOffline: StateFlow<Boolean> = connectivityObserver.isConnectedFlow
        .map { isConnected -> !isConnected }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = !connectivityObserver.isConnected()
        )

    // â”€â”€â”€ Language pair â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _selectedLanguagePair = MutableStateFlow(SupportedLanguages.defaultPair)

    /**
     * The currently selected source/target language pair.
     * Persisted in DataStore via [TranslatorPreferences] and restored on ViewModel creation.
     */
    val selectedLanguagePair: StateFlow<LanguagePair> = _selectedLanguagePair.asStateFlow()

    private var initJob: Job? = null
    private var translateJob: Job? = null

    init {
        // Restore persisted language pair from DataStore on startup.
        initJob = viewModelScope.launch {
            withContext(dispatchers.io) {
                translatorPreferences.languagePairFlow.collect { pair ->
                    _selectedLanguagePair.value = pair
                }
            }
        }
    }

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Translates [text] using the currently selected language pair.
     *
     * - Validates that [text] is not blank before calling the use case.
     * - Transitions to [TranslatorUiState.Translating] while the call is in-flight.
     * - Maps [ApiResult.NetworkUnavailable] to [TranslatorUiState.Error] with
     *   `isOffline = true` when the device has no connectivity.
     */
    fun translate(text: String) {
        if (text.isBlank()) return

        val pair = _selectedLanguagePair.value
        _uiState.value = TranslatorUiState.Translating

        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                translateTextUseCase(
                    text = text,
                    sourceLanguage = pair.sourceCode,
                    targetLanguage = pair.targetCode
                )
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> TranslatorUiState.Success(
                    sourceText = text,
                    translatedText = result.data,
                    sourceLang = pair.sourceCode,
                    targetLang = pair.targetCode,
                    isOffline = false
                )
                is ApiResult.Error -> TranslatorUiState.Error(
                    message = result.error.message,
                    isOffline = false
                )
                is ApiResult.NetworkUnavailable -> TranslatorUiState.Error(
                    message = "No network connection. Translation requires an internet connection.",
                    isOffline = true
                )
                is ApiResult.Loading -> _uiState.value // no-op
            }
        }
    }

    /**
     * Transitions to [TranslatorUiState.Listening].
     * The composable launches the SpeechRecognizer activity result and calls
     * [onSpeechResult] or [onSpeechError] when it returns.
     */
    fun startListening() {
        _uiState.value = TranslatorUiState.Listening
    }

    /**
     * Called when the SpeechRecognizer returns a transcript.
     * Immediately triggers translation with the recognised text.
     *
     * @param transcript Best-match transcript string from the recogniser.
     */
    fun onSpeechResult(transcript: String) {
        translate(transcript)
    }

    /**
     * Called when the SpeechRecognizer reports an error or the user cancels.
     * Transitions to [TranslatorUiState.Error] with a descriptive message.
     */
    fun onSpeechError() {
        _uiState.value = TranslatorUiState.Error(
            message = "Speech recognition failed. Please try typing instead."
        )
    }

    /**
     * Persists [pair] to DataStore via [TranslatorPreferences] and updates
     * the [selectedLanguagePair] state immediately.
     *
     * @param pair The new source/target language pair to apply.
     */
    fun selectLanguagePair(pair: LanguagePair) {
        _selectedLanguagePair.value = pair
        viewModelScope.launch {
            withContext(dispatchers.io) {
                translatorPreferences.setLanguagePair(pair)
            }
        }
    }

    /**
     * Swaps source and target languages and persists the updated pair.
     */
    fun swapLanguages() {
        val current = _selectedLanguagePair.value
        val swapped = LanguagePair(
            sourceCode = current.targetCode,
            sourceName = current.targetName,
            targetCode = current.sourceCode,
            targetName = current.sourceName
        )
        selectLanguagePair(swapped)
    }

    /**
     * Resets the UI state back to [TranslatorUiState.Idle].
     */
    fun reset() {
        translateJob?.cancel()
        _uiState.value = TranslatorUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        initJob?.cancel()
        translateJob?.cancel()
    }
}
