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

import com.aiassistant.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates and returns the combined [OnDeviceCapabilityState] for the current device.
 */
@Singleton
class OnDeviceCapabilityChecker @Inject constructor(
    private val capabilityDetector: DeviceCapabilityDetector,
    private val modelManager: OnDeviceModelManager,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Checks device hardware capability and model readiness, then returns the resulting
     * [OnDeviceCapabilityState].
     *
     * This method should be called once at startup (e.g. from the Application class or
     * a Hilt EntryPoint) and the result cached. It runs on [DispatcherProvider.io].
     *
     * @return The resolved [OnDeviceCapabilityState].
     */
    suspend fun evaluate(): OnDeviceCapabilityState = withContext(dispatchers.io) {
        val hardwareSupported = capabilityDetector.isOnDeviceInferenceSupported()
        if (!hardwareSupported) {
            return@withContext OnDeviceCapabilityState.NotSupported
        }

        return@withContext when (val status = modelManager.checkModelStatus()) {
            is ModelStatus.Ready -> OnDeviceCapabilityState.SupportedAndReady(status.entry)
            is ModelStatus.Absent,
            is ModelStatus.VerificationFailed,
            is ModelStatus.Downloading,
            -> OnDeviceCapabilityState.SupportedButModelNotReady(status)
        }
    }
}
