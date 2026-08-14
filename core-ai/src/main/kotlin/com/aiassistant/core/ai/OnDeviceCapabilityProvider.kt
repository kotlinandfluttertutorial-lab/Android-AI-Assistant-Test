/**
 * OnDeviceCapabilityProvider.kt
 *
 * Purpose: Interface for checking on-device AI capability. Defined in core-ai so that
 *          feature-settings can depend on this abstraction without creating a forbidden
 *          feature→feature dependency on feature-on-device-ai (Requirement 19.2).
 *
 * Architecture: core-ai — shared AI infrastructure interfaces.
 *
 * Implementation: [feature-on-device-ai's OnDeviceCapabilityChecker] implements this
 *                 interface and is bound via Hilt in that module's DI module.
 */
package com.aiassistant.core.ai

/**
 * Evaluates and returns the on-device AI capability state for the current device.
 */
interface OnDeviceCapabilityProvider {
    /**
     * Suspending evaluation of hardware capability and model readiness.
     *
     * Should be called once at startup; results should be cached by the caller.
     *
     * @return The resolved [OnDeviceCapabilityState].
     */
    suspend fun evaluate(): OnDeviceCapabilityState
}
