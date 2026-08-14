/**
 * OnDeviceCapabilityChecker.kt
 *
 * Purpose: Combines [DeviceCapabilityDetector] and [OnDeviceModelManager] to compute the
 *          overall [OnDeviceCapabilityState] at app startup. This result is provided to
 *          SettingsViewModel so the on-device provider option is shown only when the
 *          hardware threshold is met (Requirement 31.1).
 *
 * Architecture: feature-on-device-ai — orchestration class; injectable.
 * Dependencies: DeviceCapabilityDetector, OnDeviceModelManager, DispatcherProvider
 *
 * Requirements: 31.1, 31.6, 31.7
 */
package com.aiassistant.feature.ondeviceai

import com.aiassistant.core.ai.OnDeviceCapabilityProvider
import com.aiassistant.core.ai.OnDeviceCapabilityState
import com.aiassistant.core.common.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Evaluates and returns the combined [OnDeviceCapabilityState] for the current device.
 *
 * Implements [OnDeviceCapabilityProvider] so feature-settings can inject this via the
 * interface without creating a forbidden feature→feature dependency.
 */
@Singleton
class OnDeviceCapabilityChecker @Inject constructor(
    private val capabilityDetector: DeviceCapabilityDetector,
    private val modelManager: OnDeviceModelManager,
    private val dispatchers: DispatcherProvider
) : OnDeviceCapabilityProvider {

    /**
     * Checks device hardware capability and model readiness, then returns the resulting
     * [OnDeviceCapabilityState].
     */
    override suspend fun evaluate(): OnDeviceCapabilityState = withContext(dispatchers.io) {
        val hardwareSupported = capabilityDetector.isOnDeviceInferenceSupported()
        if (!hardwareSupported) {
            return@withContext OnDeviceCapabilityState.NotSupported
        }

        return@withContext when (val status = modelManager.checkModelStatus()) {
            is ModelStatus.Ready -> OnDeviceCapabilityState.SupportedAndReady(status.entry.displayName)
            is ModelStatus.Absent,
            is ModelStatus.VerificationFailed,
            is ModelStatus.Downloading
            -> OnDeviceCapabilityState.SupportedButModelNotReady
        }
    }
}
