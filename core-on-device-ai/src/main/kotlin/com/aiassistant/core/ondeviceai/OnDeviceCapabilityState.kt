/**
 * OnDeviceCapabilityState.kt
 *
 * Purpose: Sealed class representing the on-device inference capability status that is
 *          computed at app startup and observed by SettingsViewModel to decide whether
 *          to show the on-device provider option.
 *
 * Architecture: feature-on-device-ai — shared presentation state, no Android deps.
 * Requirements: 31.1, 31.6
 */
package com.aiassistant.feature.ondeviceai

/**
 * Describes the overall on-device AI readiness of the current device.
 */
sealed class OnDeviceCapabilityState {

    /** Capability check has not yet run. */
    data object Unknown : OnDeviceCapabilityState()

    /**
     * Device hardware meets the NPU/GPU memory threshold but the model has not been
     * downloaded or has failed verification. The UI should show a download prompt and
     * continue using the cloud fallback provider until the model is ready.
     */
    data class SupportedButModelNotReady(val modelStatus: ModelStatus) : OnDeviceCapabilityState()

    /**
     * Device hardware meets the NPU/GPU memory threshold and the model is verified and
     * ready for inference.
     */
    data class SupportedAndReady(val modelEntry: ModelEntry) : OnDeviceCapabilityState()

    /**
     * Device hardware does not meet the NPU/GPU threshold (< 4 GB available memory or
     * no recognised NPU/GPU). On-device inference is not available.
     */
    data object NotSupported : OnDeviceCapabilityState()
}
