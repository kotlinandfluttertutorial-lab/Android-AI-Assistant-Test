/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SpeechProvider.kt
 * Purpose    : Provider-independent STT and TTS contracts so
 *              VoiceAgent is decoupled from Android platform APIs
 *              (SpeechRecognizer, TextToSpeech) and from the
 *              existing VoiceAssistantManager (composable-owned).
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Strategy / Provider)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - SpeechToTextProvider emits a Flow<SttEvent> so the caller
 *     can handle partial results incrementally
 *   - TextToSpeechProvider returns a Flow<TtsEvent> so completion
 *     and errors are observable without callbacks
 *   - VoiceAssistantManager (composable-owned, platform-specific)
 *     continues to exist unchanged — VoiceAgent uses these
 *     lighter domain contracts for unit-testable orchestration
 *
 * Dependencies: kotlinx.coroutines.flow
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

// ── STT events ────────────────────────────────────────────────────────────────

/** Events emitted by [SpeechToTextProvider.listen]. */
sealed class SttEvent {
    /** An intermediate partial transcript (not final). */
    data class Partial(val text: String) : SttEvent()

    /** The final transcript for the utterance. */
    data class Final(val text: String) : SttEvent()

    /** Listening stopped with a platform error code. */
    data class Error(val code: Int, val message: String) : SttEvent()

    /** Listening ended without a final result (silence, timeout, etc.). */
    data object EndOfSpeech : SttEvent()
}

/** Events emitted by [TextToSpeechProvider.speak]. */
sealed class TtsEvent {
    /** TTS playback started. */
    data object Started : TtsEvent()

    /** TTS playback finished successfully. */
    data object Done : TtsEvent()

    /** TTS playback failed. */
    data class Error(val message: String) : TtsEvent()
}

// ── Provider interfaces ───────────────────────────────────────────────────────

/**
 * Provider-independent Speech-to-Text contract.
 *
 * Returns a cold [Flow] of [SttEvent] values.  The flow completes after
 * [SttEvent.Final], [SttEvent.EndOfSpeech], or [SttEvent.Error].
 * Cancelling the collecting coroutine must stop listening immediately.
 */
interface SpeechToTextProvider {

    /** True when STT is available and the microphone permission is granted. */
    val isAvailable: Boolean

    /**
     * Start listening and stream [SttEvent] values.
     *
     * @param languageTag BCP-47 language tag, e.g. `"en-US"`.
     */
    fun listen(languageTag: String = "en-US"): Flow<SttEvent>
}

/**
 * Provider-independent Text-to-Speech contract.
 *
 * Returns a cold [Flow] of [TtsEvent] values.  Cancelling the
 * collecting coroutine must stop speech playback immediately.
 */
interface TextToSpeechProvider {

    /** True when TTS is available and initialised. */
    val isAvailable: Boolean

    /**
     * Speak [text] and stream [TtsEvent] values.
     *
     * @param text        Text to synthesise.
     * @param languageTag BCP-47 language tag, e.g. `"en-US"`.
     */
    fun speak(text: String, languageTag: String = "en-US"): Flow<TtsEvent>

    /** Stop any ongoing speech immediately. */
    fun stop()
}
