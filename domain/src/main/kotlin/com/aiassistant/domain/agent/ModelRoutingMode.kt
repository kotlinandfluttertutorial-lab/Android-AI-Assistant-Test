/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ModelRoutingMode.kt
 * Purpose    : Caller-controlled routing mode for on-device vs. cloud inference.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Enum (value object)
 *
 * Key Concepts:
 *   - Distinct from the existing [InferencePath] enum (which is an internal
 *     routing decision type used by ModelRouter).
 *   - ModelRoutingMode is a caller-facing preference passed in AgentRequest
 *     metadata or directly to OnDeviceAgent.
 *   - LOCAL_ONLY never falls back silently — emits a clear error when
 *     the device is not ready, preserving user privacy intent.
 *
 * Phase 7 requirement: "For LOCAL_ONLY: never fall back to cloud silently."
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * Controls how [OnDeviceAgent] and [ModelRouter] choose the inference path.
 *
 * ## Values
 * - **AUTO** — prefer on-device when ready; silently fall back to cloud when not.
 * - **LOCAL_ONLY** — on-device only.  If the model is not ready, emit a clear
 *   error rather than routing to the cloud. Preserves the user's privacy intent.
 * - **CLOUD** — skip on-device entirely; always route to the cloud provider.
 *
 * ## Usage in request metadata
 * ```kotlin
 * AgentRequest(
 *     userId = "u1",
 *     input  = "Summarise this",
 *     metadata = mapOf(
 *         OnDeviceAgent.METADATA_ROUTING_MODE to ModelRoutingMode.LOCAL_ONLY.name,
 *     ),
 * )
 * ```
 */
enum class ModelRoutingMode {

    /**
     * Prefer on-device inference when the model is ready; fall back to cloud silently
     * when on-device is unavailable.  This is the default.
     */
    AUTO,

    /**
     * Require on-device inference.  Never fall back to cloud.
     *
     * If the device does not meet hardware requirements or the model is not downloaded,
     * [OnDeviceAgent] emits [OnDeviceStreamEvent.Error] with
     * [OnDeviceErrorCode.LOCAL_ONLY_UNAVAILABLE] immediately.
     */
    LOCAL_ONLY,

    /**
     * Skip on-device inference; always use a cloud provider.
     *
     * Equivalent to omitting [AgentCapability.ON_DEVICE_INFERENCE] from the capability set.
     */
    CLOUD,
    ;

    companion object {
        /** Returns the [ModelRoutingMode] for [name], defaulting to [AUTO]. */
        fun fromName(name: String?): ModelRoutingMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}
