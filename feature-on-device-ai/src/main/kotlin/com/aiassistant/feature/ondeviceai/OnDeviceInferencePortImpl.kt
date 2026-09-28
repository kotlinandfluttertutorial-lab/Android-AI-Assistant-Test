/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceInferencePortImpl.kt
 * Purpose    : Implements the domain [OnDeviceInferencePort] interface by
 *              delegating to the existing [OnDeviceInferenceClient].
 *
 * Architecture Layer : Feature (feature-on-device-ai) — adapter
 * Pattern Used       : Adapter (implements domain port, wraps feature class)
 *
 * Key Concepts:
 *   - This class is the ONLY place that bridges domain ↔ feature-on-device-ai.
 *   - It translates [StreamEvent] (core-ai) → [OnDeviceStreamEvent] (domain).
 *   - Network isolation is guaranteed by delegation: OnDeviceInferenceClient
 *     already contains zero OkHttp / Retrofit imports (Requirement 31.2).
 *   - MODEL_NOT_READY: emitted immediately when modelFile is null (no cold start
 *     penalty for callers that checked isReady beforehand).
 *   - LOCAL_ONLY_UNAVAILABLE: emitted when routingMode == LOCAL_ONLY but isReady
 *     is false — preserves the user's explicit privacy intent.
 *   - Cancellation: coroutine cancellation propagates through the delegate Flow
 *     into OnDeviceInferenceClient's awaitClose { job.cancel() } block.
 *
 * Hilt wiring:
 *   Bound in [OnDeviceEngineModule] as:
 *     @Binds OnDeviceInferencePort → OnDeviceInferencePortImpl
 *
 * Dependencies:
 *   - domain (OnDeviceInferencePort, OnDeviceStreamEvent, OnDeviceErrorCode,
 *             ModelRoutingMode)
 *   - core-ai (AIStreamClient, StreamEvent)
 *   - feature-on-device-ai (OnDeviceInferenceClient, ON_DEVICE_PROVIDER_ID)
 *
 * Requirements: 31.2, 31.3, 31.4, 31.5, 31.8
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.domain.agent.ModelRoutingMode
import com.aiassistant.domain.agent.OnDeviceErrorCode
import com.aiassistant.domain.agent.OnDeviceInferencePort
import com.aiassistant.domain.agent.OnDeviceStreamEvent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Adapter that implements [OnDeviceInferencePort] by delegating to
 * [OnDeviceInferenceClient].
 *
 * This is the boundary between `:domain` (the port) and `:feature-on-device-ai`
 * (the implementation). No class outside this module may reference
 * [OnDeviceInferenceClient] directly — they depend on [OnDeviceInferencePort].
 *
 * ## Network isolation
 * [OnDeviceInferenceClient] contains zero OkHttp / Retrofit imports. All inference
 * is performed locally, satisfying Requirement 31.2.
 *
 * ## Cancellation
 * When the collecting coroutine is cancelled, the [Flow] built by
 * [OnDeviceInferenceClient.connect] is cancelled via its `awaitClose { job.cancel() }`
 * block, which sets the `disconnected` flag and cancels the active inference job.
 */
@Singleton
class OnDeviceInferencePortImpl @Inject constructor(
    private val inferenceClient: OnDeviceInferenceClient,
) : OnDeviceInferencePort {

    /**
     * `true` when the model file has been set by [OnDeviceAiInitializer]
     * (i.e. downloaded and SHA-256 verified).
     */
    override val isReady: Boolean
        get() = inferenceClient.modelFile?.exists() == true

    /**
     * Stream inference for [prompt] from the on-device model.
     *
     * ## Routing modes
     * - **LOCAL_ONLY** — if [isReady] is `false`, emits [OnDeviceStreamEvent.Error]
     *   with [OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE] immediately.
     * - **AUTO** — if [isReady] is `false`, emits [OnDeviceStreamEvent.Error]
     *   with [OnDeviceErrorCode.MODEL_NOT_READY]; callers can fall back to cloud.
     * - **CLOUD** — emits [OnDeviceStreamEvent.Error] with
     *   [OnDeviceErrorCode.MODEL_NOT_READY] (callers should not route here in CLOUD mode).
     *
     * ## StreamEvent translation
     * | [StreamEvent]         | [OnDeviceStreamEvent]          |
     * |-----------------------|--------------------------------|
     * | Token(text)           | Token(text)                    |
     * | Done(usage)           | Done(inputTokens,outputTokens) |
     * | Error(msg)            | Error(msg, UNKNOWN or INSUFFICIENT_MEMORY) |
     * | ToolCall(...)         | ignored (not applicable on-device) |
     */
    override fun infer(
        prompt: String,
        conversationId: String?,
        routingMode: ModelRoutingMode,
    ): Flow<OnDeviceStreamEvent> = flow {

        // ── LOCAL_ONLY gate ───────────────────────────────────────────────────
        if (!isReady) {
            val code = if (routingMode == ModelRoutingMode.LOCAL_ONLY) {
                OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE
            } else {
                OnDeviceErrorCode.MODEL_NOT_READY
            }
            emit(
                OnDeviceStreamEvent.Error(
                    message = when (code) {
                        OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE ->
                            "On-device inference is not available. " +
                                "Download the model in Settings → On-Device AI."
                        else ->
                            "On-device model not ready. Please download the model first."
                    },
                    code = code,
                )
            )
            return@flow
        }

        // ── CLOUD routing — short-circuit ─────────────────────────────────────
        if (routingMode == ModelRoutingMode.CLOUD) {
            emit(
                OnDeviceStreamEvent.Error(
                    message = "On-device inference skipped: routing mode is CLOUD.",
                    code = OnDeviceErrorCode.MODEL_NOT_READY,
                )
            )
            return@flow
        }

        // ── Delegate to OnDeviceInferenceClient ───────────────────────────────
        val convId = conversationId ?: UUID.randomUUID().toString()

        // connect() returns a cold callbackFlow — collecting here starts inference
        val streamFlow = inferenceClient.connect(
            conversationId = convId,
            jwt = "",   // on-device path does not use JWT — ignored by client
        )

        // Send the prompt immediately after connecting
        inferenceClient.sendMessage(
            MessagePayload(
                conversationId = convId,
                content = prompt,
                provider = ON_DEVICE_PROVIDER_ID,
            )
        )

        // Translate StreamEvent → OnDeviceStreamEvent
        streamFlow.collect { event ->
            when (event) {
                is StreamEvent.Token -> emit(OnDeviceStreamEvent.Token(event.text))

                is StreamEvent.Done -> emit(
                    OnDeviceStreamEvent.Done(
                        inputTokens = event.usage.inputTokens,
                        outputTokens = event.usage.outputTokens,
                    )
                )

                is StreamEvent.Error -> {
                    val code = when {
                        event.message.contains("resources", ignoreCase = true) ||
                            event.message.contains("RAM", ignoreCase = true) ->
                            OnDeviceErrorCode.INSUFFICIENT_MEMORY
                        event.message.contains("not available", ignoreCase = true) ||
                            event.message.contains("not ready", ignoreCase = true) ->
                            OnDeviceErrorCode.MODEL_NOT_READY
                        else -> OnDeviceErrorCode.UNKNOWN
                    }
                    emit(OnDeviceStreamEvent.Error(message = event.message, code = code))
                }

                is StreamEvent.ToolCall -> {
                    // On-device models do not invoke MCP tools — silently drop
                }
            }
        }
    }
}

/** Provider ID used in [MessagePayload] for on-device inference (mirrors core-ai constant). */
private const val ON_DEVICE_PROVIDER_ID = "on_device"
