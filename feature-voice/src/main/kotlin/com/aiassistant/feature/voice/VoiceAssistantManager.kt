/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-voice
 * File       : VoiceAssistantManager.kt
 * Purpose    : VoiceAssistantManager — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
 * Pattern Used       : Kotlin Class
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
 * File       : VoiceAssistantManager.kt
 * Purpose    : VoiceAssistantManager — feature-voice module component
 *
 * Architecture Layer : Feature (feature-voice)
 * Pattern Used       : Kotlin Class
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
 * VoiceAssistantManager.kt
 *
 * Purpose: Android-aware helper class that wraps SpeechRecognizer (STT) and TextToSpeech
 *          (TTS). Isolates all Android framework dependencies so VoiceViewModel stays
 *          context-free and fully testable.
 * Architecture: feature-voice â€” composable layer helper; created/remembered in VoiceScreen
 *               and released via DisposableEffect when the composable leaves composition.
 * Dependencies: android.speech.SpeechRecognizer, android.speech.tts.TextToSpeech,
 *               android.speech.RecognitionListener
 *
 * Requirements: 5.1, 5.3, 5.4, 5.5
 *
 * Design decisions:
 * - This is NOT a ViewModel. It is a plain class that the composable creates via
 *   `remember { VoiceAssistantManager(context) }` and disposes via `DisposableEffect`.
 * - Implements RecognitionListener directly so no extra adapter class is needed.
 * - TTS initialization is asynchronous; speak() queues speech until the engine is ready.
 * - isWakeWordSupported() delegates to SpeechRecognizer.isRecognitionAvailable(); true
 *   wake-word hotword detection requires device-specific APIs, so the feature is implemented
 *   as a "continuous listening loop" toggle instead (Requirement 5.5).
 * - release() must be called when the composable is disposed to free native resources.
 */
package com.aiassistant.feature.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Helper that owns the [SpeechRecognizer] and [TextToSpeech] engine instances.
 *
 * All methods are safe to call from the main thread (the Android speech APIs require it).
 *
 * Lifecycle:
 * 1. Create: `val manager = VoiceAssistantManager(context)` â€” engines initialise lazily.
 * 2. Use: `startListening(...)`, `speak(...)`, `stopListening()`, `stopSpeaking()`.
 * 3. Destroy: `release()` â€” frees both engines. Must be called to avoid resource leaks.
 */
class VoiceAssistantManager(private val context: Context) : RecognitionListener {

    // â”€â”€â”€ TTS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var ttsEngine: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var pendingSpeech: Pair<String, () -> Unit>? = null

    init {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val result = ttsEngine?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    // Fall back to US English if the device locale isn't available.
                    ttsEngine?.setLanguage(Locale.US)
                }
                // Flush any speech that was queued before the engine was ready.
                pendingSpeech?.let { (text, onDone) ->
                    pendingSpeech = null
                    speak(text, onDone)
                }
            }
        }
    }

    // â”€â”€â”€ STT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private var speechRecognizer: SpeechRecognizer? = null
    private var onPartialResultCallback: ((String) -> Unit)? = null
    private var onFinalResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((Int) -> Unit)? = null

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns true when the device's speech recognition engine is available.
     * Used by the composable to decide whether to show the "Hands-free mode" toggle
     * (Requirement 5.5).
     */
    fun isWakeWordSupported(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts on-device speech recognition.
     *
     * @param onPartialResult Invoked with partial transcript strings as the user speaks
     *                        (Requirement 5.1 â€” display best available transcript).
     * @param onFinalResult   Invoked with the final best-match transcript string.
     * @param onError         Invoked with a [SpeechRecognizer] error code on failure.
     */
    fun startListening(onPartialResult: (String) -> Unit, onFinalResult: (String) -> Unit, onError: (Int) -> Unit) {
        onPartialResultCallback = onPartialResult
        onFinalResultCallback = onFinalResult
        onErrorCallback = onError

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        speechRecognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    /**
     * Stops listening and cancels any active STT session.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
    }

    /**
     * Converts [text] to speech using the TTS engine with the current voice profile.
     *
     * If the TTS engine is not yet initialised, the speech is queued and played as soon
     * as the engine is ready.
     *
     * @param text   The text to speak (Requirement 5.3).
     * @param onDone Invoked when playback completes (used to transition back to Idle).
     */
    fun speak(text: String, onDone: () -> Unit) {
        if (!ttsReady) {
            pendingSpeech = text to onDone
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        ttsEngine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onDone()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onDone()
            override fun onError(utteranceId: String?, errorCode: Int) = onDone()
        })
        ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Immediately stops TTS playback (Requirement 5.4 â€” interrupt control).
     */
    fun stopSpeaking() {
        ttsEngine?.stop()
    }

    /**
     * Releases both the SpeechRecognizer and TextToSpeech engines.
     * Must be called from the composable's [DisposableEffect] onDispose block.
     */
    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        ttsEngine = null
        ttsReady = false
    }

    // â”€â”€â”€ RecognitionListener â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        onErrorCallback?.invoke(error)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val bestResult = matches?.firstOrNull().orEmpty()
        onFinalResultCallback?.invoke(bestResult)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (partial.isNotEmpty()) {
            onPartialResultCallback?.invoke(partial)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
