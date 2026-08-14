/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceEngine.kt
 * Purpose    : Interface for the native on-device inference engine.
 *              The default binding is [StubOnDeviceEngine]; production builds that
 *              bundle llama.cpp or an equivalent GGUF runtime can swap in a real
 *              implementation via Hilt without changing any callers.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Strategy / Abstraction over JNI bridge
 *
 * Requirements: 31.2, 31.5
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

/**
 * Contract for a synchronous-but-streaming on-device GGUF inference engine.
 *
 * Implementations are expected to call [onToken] for each generated text token,
 * [onComplete] once generation ends, and [onError] on any fatal failure.
 *
 * All callbacks are invoked on the caller's thread; the implementation is responsible
 * for running heavy work off the main thread.
 *
 * Replace the default [StubOnDeviceEngine] binding with a JNI-backed class when
 * llama.cpp or an equivalent library is available.
 */
interface OnDeviceEngine {

    /**
     * Runs inference on the loaded model for the given [prompt].
     *
     * @param modelPath   Absolute path to the verified GGUF model file.
     * @param prompt      The full prompt string to send to the model.
     * @param onToken     Called for each generated token (may be called concurrently
     *                    from a background thread).
     * @param onComplete  Called when generation finishes with input and output token counts.
     * @param onError     Called on fatal failure with a human-readable message.
     */
    fun runInference(
        modelPath: String,
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (inputTokens: Int, outputTokens: Int) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Cancels any in-progress inference. Safe to call from any thread.
     * A no-op if no inference is running.
     */
    fun cancel()
}
