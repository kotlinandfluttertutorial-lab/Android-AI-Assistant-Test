/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceInferenceClient.kt
 * Purpose    : AIStreamClient implementation that runs inference entirely on the device
 *              using a loaded GGUF model — making ZERO network calls to the Backend or
 *              any external LLM provider endpoint.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Strategy / Adapter (implements AIStreamClient)
 *
 * Key Concepts:
 *   - Implements AIStreamClient so it is interchangeable with the WebSocket client
 *   - Simulates token streaming by chunking the simulated model output (production
 *     code would call an on-device inference engine like llama.cpp via JNI)
 *   - Monitors RAM via RamMonitor; cancels and emits StreamEvent.Error when RAM < 512 MB
 *   - sendMessage() is synchronous / fire-and-forget; inference result arrives via Flow
 *   - Zero OkHttp / Retrofit usage — satisfies Requirement 31.2
 *
 * Design note on the inference engine:
 *   Real on-device inference would delegate to a JNI bridge to llama.cpp or a similar
 *   framework. Since this project does not bundle native libraries, this implementation
 *   contains the complete interface, monitoring, lifecycle, and error-handling logic.
 *   Integrating an actual GGUF engine requires only replacing the body of
 *   [runLocalInference] with a JNI call.
 *
 * Dependencies:
 *   - core-ai (AIStreamClient, StreamEvent, MessagePayload, TokenUsage)
 *   - RamMonitor (this module)
 *   - kotlinx.coroutines
 *
 * Requirements: 31.2, 31.3, 31.4, 31.5, 31.8
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.util.Log
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.ai.TokenUsage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "OnDeviceInferenceClient"

/**
 * [AIStreamClient] implementation backed by an on-device GGUF model.
 *
 * Contract guarantees:
 * - Zero outbound HTTP calls (Requirement 31.2).
 * - Emits first token within ≤2,000 ms on qualified hardware (Requirement 31.5).
 * - Monitors RAM; cancels and emits [StreamEvent.Error] when free RAM < 512 MB (Requirement 31.4).
 * - Works when the device is offline (Requirement 31.8).
 *
 * @param ramMonitor    Polls free RAM during inference.
 * @param modelFile     The verified GGUF model [File] to load. Nullable — when null every
 *                      inference call immediately emits [StreamEvent.Error] (model not ready).
 */
class OnDeviceInferenceClient @Inject constructor(private val ramMonitor: RamMonitor, @JvmField var modelFile: File?) :
    AIStreamClient {

    /** Channel used by [sendMessage] to push payloads into the active [connect] flow. */
    private val pendingMessages = Channel<MessagePayload>(Channel.BUFFERED)

    /** Whether [disconnect] has been called. */
    private val disconnected = AtomicBoolean(false)

    /** Currently active inference job, if any. */
    private val activeInferenceJob = AtomicReference<Job?>(null)

    // ─── AIStreamClient implementation ───────────────────────────────────────

    /**
     * Returns a cold [Flow] that:
     * 1. Waits for a [MessagePayload] via [sendMessage].
     * 2. Runs local inference (or simulation) emitting [StreamEvent.Token] events.
     * 3. Concurrently monitors RAM; on threshold breach cancels inference and emits
     *    [StreamEvent.Error].
     * 4. Emits [StreamEvent.Done] on completion.
     *
     * A new collection starts a new on-device session; calling [disconnect] cancels it.
     *
     * Requirements: 31.2, 31.4, 31.5, 31.8
     */
    override fun connect(conversationId: String, jwt: String): Flow<StreamEvent> = callbackFlow {
        disconnected.set(false)

        if (modelFile == null || !modelFile!!.exists()) {
            Log.w(TAG, "Model file not available — emitting error")
            send(StreamEvent.Error("On-device model not available. Please download the model first."))
            close()
            return@callbackFlow
        }

        // Collect incoming messages and process each sequentially
        val job = launch {
            for (payload in pendingMessages) {
                if (disconnected.get()) break

                // ── RAM pre-flight check ────────────────────────────────────
                val availableRam = ramMonitor.availableMemoryBytes()
                if (availableRam < RAM_THRESHOLD_BYTES) {
                    Log.w(TAG, "RAM below threshold before inference: ${availableRam / (1024 * 1024)} MB")
                    trySend(StreamEvent.Error(INSUFFICIENT_RESOURCES_MESSAGE))
                    break
                }

                // ── Start inference and RAM monitoring concurrently ─────────
                var inferenceCompleted = false

                val ramJob = launch {
                    ramMonitor.observe()
                        .filter { it is RamEvent.BelowThreshold }
                        .first() // suspends until threshold crossed

                    if (!inferenceCompleted) {
                        Log.w(TAG, "RAM dropped below 512 MB during inference — cancelling")
                        trySend(StreamEvent.Error(INSUFFICIENT_RESOURCES_MESSAGE))
                        // Signal outer loop to stop
                        this@callbackFlow.close()
                    }
                }

                val inferenceJob = launch {
                    try {
                        runLocalInference(payload) { event ->
                            trySend(event)
                        }
                    } finally {
                        inferenceCompleted = true
                        ramJob.cancel()
                    }
                }

                activeInferenceJob.set(inferenceJob)
                inferenceJob.join()
                activeInferenceJob.set(null)
            }
            close()
        }

        // awaitClose suspends until the channel is closed or the flow is cancelled,
        // and then cancels the job — the correct callbackFlow contract.
        awaitClose {
            job.cancel()
        }
    }

    /**
     * Queues [payload] for processing by the active [connect] flow.
     *
     * Fire-and-forget: errors surface as [StreamEvent.Error] emissions on the Flow.
     *
     * Requirement: 31.2 (no network calls — payload is handled on-device only).
     */
    override fun sendMessage(payload: MessagePayload) {
        if (!disconnected.get()) {
            pendingMessages.trySend(payload)
        }
    }

    /**
     * Cancels any in-flight inference and closes the message channel.
     */
    override fun disconnect() {
        disconnected.set(true)
        activeInferenceJob.get()?.cancel()
        // Don't close the channel here; it may be reused after re-connect
    }

    // ─── Local inference ─────────────────────────────────────────────────────

    /**
     * Performs (or delegates) local GGUF inference for [payload].
     *
     * **Current implementation:** Simulates token streaming from an on-device model.
     * Replace this body with a JNI call to llama.cpp or a similar engine when native
     * libraries are available.
     *
     * The simulation:
     * - Returns the first synthetic token within ~50 ms (well under the 2,000 ms limit).
     * - Emits ~10 tokens before [StreamEvent.Done].
     *
     * @param onEvent Callback invoked for each [StreamEvent] produced.
     */
    private suspend fun runLocalInference(payload: MessagePayload, onEvent: suspend (StreamEvent) -> Unit) {
        Log.d(TAG, "Starting on-device inference for conversation '${payload.conversationId}'")

        /*
         * TODO: Replace with JNI call to on-device GGUF inference engine, e.g.:
         *
         *   val session = LlamaCppBridge.createSession(modelFile!!.absolutePath)
         *   session.tokenize(payload.content).forEach { token ->
         *       onEvent(StreamEvent.Token(token))
         *   }
         *   val usage = session.getUsage()
         *   onEvent(StreamEvent.Done(TokenUsage(usage.inputTokens, usage.outputTokens)))
         *   session.close()
         *
         * Until the native bridge is wired up the simulation below demonstrates the
         * streaming contract to the rest of the codebase.
         */

        // Simulate streaming tokens (placeholder — replace with real JNI bridge)
        val simulatedResponse = buildSimulatedResponse(payload.content)
        val words = simulatedResponse.split(" ")
        var outputTokens = 0

        for (word in words) {
            if (disconnected.get()) return
            onEvent(StreamEvent.Token("$word "))
            outputTokens++
            kotlinx.coroutines.delay(50L) // ~50 ms per token
        }

        onEvent(
            StreamEvent.Done(
                TokenUsage(
                    inputTokens = payload.content.length / 4, // rough estimate
                    outputTokens = outputTokens
                )
            )
        )
    }

    private fun buildSimulatedResponse(prompt: String): String =
        "[On-device] Responding to: \"${prompt.take(80)}\" — " +
            "This response was generated entirely on-device with no network calls."

    companion object {
        const val INSUFFICIENT_RESOURCES_MESSAGE =
            "Insufficient resources — switching to cloud"
    }
}
