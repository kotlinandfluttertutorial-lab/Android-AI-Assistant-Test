/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Voice feature
 *
 * Architecture Layer : Feature (feature-voice)
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
 * VoiceViewModel.kt
 *
 * Purpose: Manages the Voice Assistant state machine and orchestrates calls to
 *          SendMessageUseCase. Owns no Android framework objects (no Context,
 *          SpeechRecognizer, or TextToSpeech) â€” those live in VoiceAssistantManager.
 * Architecture: feature-voice â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (SendMessageUseCase), core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 *
 * Design decisions:
 * - ViewModel only manages state and use-case calls; Android-specific concerns (mic,
 *   TTS) are fully isolated in VoiceAssistantManager to keep ViewModel testable.
 * - State machine is a simple StateFlow<VoiceUiState>; transitions are explicit methods
 *   so callers (composable + tests) can drive them clearly.
 * - conversationId and provider default to empty strings; callers pass real values via
 *   setConversationContext() once navigation supplies them.
 * - All blocking work (SendMessageUseCase network call) dispatched on dispatchers.io.
 */
package com.aiassistant.feature.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Voice Assistant screen.
 *
 * Drives the state machine:
 * ```
 * Idle â”€â”€startListening()â”€â”€â–º Listening â”€â”€onSpeechResult()â”€â”€â–º Transcribing
 *   â–²                                                              â”‚
 *   â”‚                                                   SendMessageUseCase
 *   â”‚                                                              â”‚
 *   â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Idle â—„â”€â”€ Speaking â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ Success response
 * ```
 * Permission states (RequestingPermission / PermissionDenied) can interrupt from Idle.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle())

    /** Observable Voice Assistant UI state. Never exposes the mutable backing field. */
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Conversation context â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var conversationId: String = ""
    private var provider: String = ""
    private var wakeWordEnabled: Boolean = false

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides the conversation context needed for [SendMessageUseCase].
     * Called by the composable when the navigation back stack entry supplies values.
     */
    fun setConversationContext(conversationId: String, provider: String) {
        this.conversationId = conversationId
        this.provider = provider
    }

    /**
     * Transitions [Idle] â†’ [VoiceUiState.Listening].
     * The composable should also instruct [VoiceAssistantManager.startListening] after
     * this call so the microphone opens in sync with the state change.
     */
    fun startListening() {
        val currentState = _uiState.value
        if (currentState is VoiceUiState.Idle || currentState is VoiceUiState.Speaking) {
            _uiState.value = VoiceUiState.Listening(isWakeWordEnabled = wakeWordEnabled)
        }
    }

    /**
     * Called by the composable when the STT engine returns a final transcript.
     *
     * Transitions Listening â†’ Transcribing, then invokes [SendMessageUseCase]. On
     * success transitions to [VoiceUiState.Speaking]; on failure transitions to
     * [VoiceUiState.Error].
     *
     * @param transcript The final recognised text from the speech engine (Requirement 5.1).
     */
    fun onSpeechResult(transcript: String) {
        if (transcript.isBlank()) {
            _uiState.value = VoiceUiState.Idle(isWakeWordEnabled = wakeWordEnabled)
            return
        }

        _uiState.value = VoiceUiState.Transcribing(partialTranscript = transcript)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                sendMessageUseCase(
                    conversationId = conversationId,
                    content = transcript,
                    provider = provider
                )
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> VoiceUiState.Speaking(
                    responseText = result.data.content
                )
                is ApiResult.Error -> VoiceUiState.Error(
                    message = result.error.message
                )
                is ApiResult.NetworkUnavailable -> VoiceUiState.Error(
                    message = "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> VoiceUiState.Transcribing(partialTranscript = transcript)
            }
        }
    }

    /**
     * Called by the composable when the STT engine provides a partial result mid-speech.
     * Updates the [VoiceUiState.Transcribing] partial transcript text.
     *
     * @param partial The partial transcript so far (Requirement 5.1 â€” display best available).
     */
    fun onPartialSpeechResult(partial: String) {
        if (_uiState.value is VoiceUiState.Listening) {
            _uiState.value = VoiceUiState.Transcribing(partialTranscript = partial)
        } else if (_uiState.value is VoiceUiState.Transcribing) {
            _uiState.value = VoiceUiState.Transcribing(partialTranscript = partial)
        }
    }

    /**
     * Called when TTS playback completes normally.
     * Transitions Speaking â†’ Idle; if wake word mode is active the composable
     * will auto-restart listening from Idle.
     */
    fun onSpeakingComplete() {
        _uiState.value = VoiceUiState.Idle(
            isWakeWordEnabled = wakeWordEnabled
        )
    }

    /**
     * Interrupt control (Requirement 5.4): immediately stops TTS and re-activates
     * the microphone. Composable must also call [VoiceAssistantManager.stopSpeaking]
     * and then [VoiceAssistantManager.startListening] to coordinate the hardware.
     */
    fun stopSpeaking() {
        if (_uiState.value is VoiceUiState.Speaking) {
            _uiState.value = VoiceUiState.Listening(isWakeWordEnabled = wakeWordEnabled)
        }
    }

    /**
     * Called by the composable when microphone permission is granted.
     * Transitions RequestingPermission â†’ Listening.
     */
    fun onPermissionGranted() {
        if (_uiState.value is VoiceUiState.RequestingPermission) {
            _uiState.value = VoiceUiState.Listening(isWakeWordEnabled = wakeWordEnabled)
        }
    }

    /**
     * Called by the composable when microphone permission is denied.
     * Transitions to [VoiceUiState.PermissionDenied] (Requirement 5.6).
     */
    fun onPermissionDenied() {
        _uiState.value = VoiceUiState.PermissionDenied
    }

    /**
     * Requests mic permission check â€” moves to RequestingPermission from Idle.
     * The composable handles the actual system permission dialog.
     */
    fun requestPermission() {
        if (_uiState.value is VoiceUiState.Idle) {
            _uiState.value = VoiceUiState.RequestingPermission
        }
    }

    /**
     * Controls the continuous listening loop (wake word / hands-free mode).
     * Requirement 5.5: when enabled, after each result the composable restarts recognition.
     *
     * @param enabled True to enable continuous loop; false to stop after the next result.
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        wakeWordEnabled = enabled
        val current = _uiState.value
        if (current is VoiceUiState.Idle) {
            _uiState.value = current.copy(isWakeWordEnabled = enabled)
        } else if (current is VoiceUiState.Listening) {
            _uiState.value = current.copy(isWakeWordEnabled = enabled)
        }
    }

    /**
     * Called by the composable when the STT engine reports an error.
     * Transitions back to [VoiceUiState.Error] with a human-readable message.
     *
     * @param errorCode The [android.speech.SpeechRecognizer] error constant.
     */
    fun onSpeechError(errorCode: Int) {
        val message = speechErrorMessage(errorCode)
        _uiState.value = VoiceUiState.Error(message = message)
    }

    /**
     * Resets any Error or PermissionDenied state back to [VoiceUiState.Idle].
     * Called when the user taps "Retry" on the error banner.
     */
    fun reset() {
        _uiState.value = VoiceUiState.Idle(isWakeWordEnabled = wakeWordEnabled)
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Maps a [android.speech.SpeechRecognizer] error code to a human-readable string.
     * Kept in the ViewModel (not in the composable) for testability.
     */
    private fun speechErrorMessage(errorCode: Int): String = when (errorCode) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Please try again."
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "Client-side error. Please try again."
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for voice input."
        android.speech.SpeechRecognizer.ERROR_NETWORK ->
            "Network error during recognition. Please check your connection."
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network timeout. Please try again."
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try again."
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Please wait and try again."
        android.speech.SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error. Please try again."
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Please try again."
        else -> "Speech recognition failed. Please try again."
    }
}
