/**
 * OnDeviceCapabilityState.kt
 *
 * Purpose: Sealed class representing the on-device inference capability status.
 *          Moved to core-ai so feature-settings can observe it without depending
 *          on feature-on-device-ai (Requirement 19.2 — no feature→feature deps).
 *
 * Architecture: core-ai — shared AI infrastructure types.
 * Requirements: 31.1, 31.6
 */
package com.aiassistant.core.ai

/**
 * Describes the overall on-device AI readiness of the current device.
 *
 * Implementations are resolved by [feature-on-device-ai's OnDeviceCapabilityChecker]
 * and consumed by [feature-settings's SettingsViewModel].
 */
sealed class OnDeviceCapabilityState {

    /** Capability check has not yet run. */
    data object Unknown : OnDeviceCapabilityState()

    /**
     * Device hardware meets the NPU/GPU memory threshold but the model has not been
     * downloaded or has failed verification.
     */
    data object SupportedButModelNotReady : OnDeviceCapabilityState()

    /**
     * Device hardware meets the NPU/GPU memory threshold and the model is verified and
     * ready for inference.
     *
     * @param modelDisplayName Human-readable model name, e.g. "Llama 3.2 1B (INT4)".
     */
    data class SupportedAndReady(val modelDisplayName: String) : OnDeviceCapabilityState()

    /**
     * Device hardware does not meet the NPU/GPU threshold. On-device inference is not available.
     */
    data object NotSupported : OnDeviceCapabilityState()

    /** Whether on-device inference is currently available for use. */
    val isAvailable: Boolean get() = this is SupportedAndReady
}
