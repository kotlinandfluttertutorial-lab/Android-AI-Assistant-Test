/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceUiState.kt
 * Purpose    : VoiceUiState — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
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
 * Module     : feature-voice
 * File       : VoiceUiState.kt
 * Purpose    : VoiceUiState — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
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
 * VoiceUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the Voice Assistant screen.
 * Architecture: feature-voice â€” MVVM presentation layer; consumed by VoiceScreen composable.
 * Dependencies: None (pure Kotlin)
 *
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6
 *
 * Design decisions:
 * - States model the full lifecycle of a voice interaction: permission check â†’ listening
 *   â†’ STT transcription â†’ AI call â†’ TTS playback â†’ back to idle.
 * - `Transcribing` carries the partial transcript so the UI can show live recognition
 *   results even before the final result arrives (Requirement 5.1 â€” display best available
 *   transcript even when accuracy < 90%).
 * - `Speaking` carries the full response text so the screen can display it while the TTS
 *   engine reads it aloud (Requirement 5.3).
 * - `PermissionDenied` is a terminal state from which the user must navigate to system
 *   settings; it is distinct from `Error` (Requirement 5.6).
 */
package com.aiassistant.feature.voice

/**
 * Represents every possible UI state in the Voice Assistant flow.
 *
 * The [VoiceViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed class.
 * VoiceScreen observes it and renders the appropriate UI.
 */
sealed class VoiceUiState {

    /**
     * The initial idle state. A microphone FAB is shown.
     * When wake word / hands-free mode is enabled the screen auto-starts listening from here.
     *
     * @param isWakeWordSupported True when [android.speech.SpeechRecognizer.isRecognitionAvailable]
     *                            returns true on the device (Requirement 5.5).
     * @param isWakeWordEnabled   Whether the user has enabled the continuous listening loop.
     */
    data class Idle(val isWakeWordSupported: Boolean = false, val isWakeWordEnabled: Boolean = false) : VoiceUiState()

    /**
     * The app is asking the user to grant RECORD_AUDIO permission.
     * The rationale dialog is shown from this state (Requirement 5.6).
     */
    data object RequestingPermission : VoiceUiState()

    /**
     * The user declined (and possibly checked "don't ask again") the RECORD_AUDIO permission.
     * The screen shows an [com.aiassistant.core.ui.components.ErrorBanner] and a button
     * deep-linking to system app settings (Requirement 5.6).
     */
    data object PermissionDenied : VoiceUiState()

    /**
     * The microphone is active and the speech recognizer is capturing audio.
     * An animated mic icon and "Listeningâ€¦" label are shown with a stop button.
     *
     * @param isWakeWordEnabled Whether continuous loop is active (so the stop button
     *                          also disables the loop).
     */
    data class Listening(val isWakeWordEnabled: Boolean = false) : VoiceUiState()

    /**
     * A transcript has been (partially) received from the speech recognizer and the
     * ViewModel is now sending it to the AI Orchestrator.
     *
     * @param partialTranscript The best available transcript text so far (Requirement 5.1).
     */
    data class Transcribing(val partialTranscript: String) : VoiceUiState()

    /**
     * The AI response has been received and the TTS engine is playing it back.
     *
     * @param responseText The full assistant response text displayed on screen (Requirement 5.3).
     */
    data class Speaking(val responseText: String) : VoiceUiState()

    /**
     * A recoverable error occurred (e.g. STT engine error, network failure).
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : VoiceUiState()
}
