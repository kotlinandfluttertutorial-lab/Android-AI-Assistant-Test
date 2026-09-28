/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : OnDeviceInferencePort.kt
 * Purpose    : Domain port (interface) for on-device inference streaming.
 *              Defined here so :data can depend on the abstraction without
 *              taking a forbidden dependency on :feature-on-device-ai.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Port (Hexagonal Architecture) / Interface
 *
 * Key Concepts:
 *   - Pure Kotlin — zero Android/framework dependencies
 *   - Implemented by OnDeviceInferenceClient in :feature-on-device-ai
 *   - Bound via Hilt in the app module (not in :data)
 *   - OnDeviceStreamEvent mirrors StreamEvent but without the WebSocket
 *     ToolCall type (on-device models do not invoke MCP tools)
 *   - isReady reflects model file availability (set by OnDeviceAiInitializer)
 *
 * Critical requirement: implementations MUST NOT make network calls.
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.coroutines.flow.Flow

// ── Streaming events ──────────────────────────────────────────────────────────

/**
 * Events emitted by [OnDeviceInferencePort.infer].
 *
 * Mirrors [com.aiassistant.core.ai.StreamEvent] but scoped to on-device inference:
 * - No [Token] tool-call variant — on-device models do not dispatch MCP tools.
 * - [Error] covers both RAM exhaustion and model-not-ready conditions.
 */
sealed class OnDeviceStreamEvent {

    /** An incremental text token from the local model. */
    data class Token(val text: String) : OnDeviceStreamEvent()

    /** Inference completed successfully. */
    data class Done(
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
    ) : OnDeviceStreamEvent()

    /**
     * Inference failed.
     *
     * @param message Human-readable cause (e.g. "model not ready", "RAM exhausted").
     * @param code    Machine-readable error code for routing decisions.
     */
    data class Error(
        val message: String,
        val code: OnDeviceErrorCode = OnDeviceErrorCode.UNKNOWN,
    ) : OnDeviceStreamEvent()
}

/**
 * Machine-readable error codes emitted by [OnDeviceStreamEvent.Error].
 */
enum class OnDeviceErrorCode {
    /** Model file is null or does not exist — not yet downloaded/verified. */
    MODEL_NOT_READY,

    /** Available RAM dropped below the 512 MB safety threshold during inference. */
    INSUFFICIENT_MEMORY,

    /** The [ModelRoutingMode] was [ModelRoutingMode.LOCAL_ONLY] but local is unavailable. */
    LOCAL_ONLY_UNAVAILABLE,

    /** Inference was cancelled by the caller. */
    CANCELLED,

    /** Any other error. */
    UNKNOWN,
}

// ── Port interface ────────────────────────────────────────────────────────────

/**
 * Domain port for on-device LLM inference.
 *
 * The concrete implementation ([com.aiassistant.feature.ondeviceai.OnDeviceInferenceClient])
 * lives in `:feature-on-device-ai` and is bound to this interface via Hilt.
 *
 * ## Contract
 * - Implementations MUST NOT open HTTP connections.
 * - Implementations MUST honour coroutine cancellation — stopping inference
 *   immediately when the collecting coroutine is cancelled.
 * - [infer] returns a cold [Flow]; each collection is an independent session.
 * - The Flow always terminates with [OnDeviceStreamEvent.Done] or
 *   [OnDeviceStreamEvent.Error]. It never throws.
 */
interface OnDeviceInferencePort {

    /**
     * `true` when the model file is downloaded, verified, and ready for inference.
     *
     * Callers should check this before calling [infer] with
     * [ModelRoutingMode.LOCAL_ONLY] — although [infer] emits
     * [OnDeviceStreamEvent.Error] with [OnDeviceErrorCode.MODEL_NOT_READY] gracefully
     * when `false`.
     */
    val isReady: Boolean

    /**
     * Stream inference tokens for [prompt] from the on-device model.
     *
     * @param prompt           The assembled prompt text.
     * @param conversationId   Optional conversation identifier for logging.
     * @param routingMode      [ModelRoutingMode.LOCAL_ONLY] prevents silent cloud
     *                         fallback — if the device is not ready the Flow emits
     *                         [OnDeviceStreamEvent.Error] with
     *                         [OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE].
     *
     * @return Cold [Flow] of [OnDeviceStreamEvent]; each collection is a new session.
     */
    fun infer(
        prompt: String,
        conversationId: String? = null,
        routingMode: ModelRoutingMode = ModelRoutingMode.AUTO,
    ): Flow<OnDeviceStreamEvent>
}
