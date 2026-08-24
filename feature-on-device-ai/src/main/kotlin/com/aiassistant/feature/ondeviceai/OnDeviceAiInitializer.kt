/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceAiInitializer.kt
 * Purpose    : Startup orchestrator for the on-device AI feature.
 *              Runs at application start (or when triggered from SettingsViewModel)
 *              and performs:
 *                1. NPU/GPU capability detection
 *                2. Model manifest loading
 *                3. Model file integrity check (SHA-256)
 *                4. Updating OnDeviceInferenceClient.modelFile if the model is Ready
 *              Exposes reactive StateFlow so the UI layer can observe the outcome and
 *              show the "Running on device" indicator or the download prompt.
 *
 * Architecture Layer : Feature (feature-on-device-ai)
 * Pattern Used       : Facade / Startup Coordinator
 *
 * Key Concepts:
 *   - Coordinates HardwareCapabilityDetector + OnDeviceModelManager
 *   - Exposes OnDeviceAiState so feature-settings can conditionally show the
 *     on-device provider option (Requirement 31.1)
 *   - "Running on device" state drives the persistent indicator (Requirement 31.3)
 *
 * Requirements: 31.1, 31.3, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.util.Log
import androidx.annotation.VisibleForTesting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "OnDeviceAiInitializer"

/**
 * Represents the overall readiness state of the on-device AI feature.
 */
sealed class OnDeviceAiState {

    /** Capability check has not yet been performed. */
    object Idle : OnDeviceAiState()

    /** Running the hardware capability check and model integrity verification. */
    object Checking : OnDeviceAiState()

    /**
     * Device qualifies and the model is ready to use.
     *
     * @param capabilityInfo EGL vendor info string for diagnostics.
     * @param modelEntry     The verified [ModelEntry] that is loaded.
     */
    data class Ready(val capabilityInfo: String?, val modelEntry: ModelEntry) : OnDeviceAiState()

    /**
     * Device qualifies but the model file is absent or was corrupt.
     * The UI should show a download prompt and fall back to cloud inference.
     *
     * @param modelEntry The [ModelEntry] that should be downloaded.
     * @param wasCorrupt True if a corrupt file was found (vs. simply absent).
     */
    data class DownloadRequired(val modelEntry: ModelEntry, val wasCorrupt: Boolean) : OnDeviceAiState()

    /**
     * The device does not meet the NPU/GPU memory threshold — on-device inference is
     * not offered. The on-device provider must NOT appear in the settings list.
     */
    data class DeviceNotSupported(val availableBytes: Long) : OnDeviceAiState()

    /**
     * The model is downloading.
     *
     * @param modelEntry       The entry being downloaded.
     * @param progressFraction 0.0 – 1.0 download completion.
     */
    data class Downloading(val modelEntry: ModelEntry, val progressFraction: Float) : OnDeviceAiState()

    /**
     * A fatal error occurred during initialization (e.g. manifest parse failure).
     */
    data class Error(val message: String) : OnDeviceAiState()

    /**
     * On-device inference is currently active for a chat request.
     * The UI displays the "Running on device" persistent indicator (Requirement 31.3).
     */
    data class InferenceActive(val modelEntry: ModelEntry) : OnDeviceAiState()
}

/**
 * Startup coordinator for the on-device AI feature.
 *
 * Inject this singleton and call [initialize] once from the Application or from
 * the screen that first exposes the on-device provider option.
 *
 * Observe [state] in the SettingsViewModel to:
 * - Show the on-device LLM provider option only when [OnDeviceAiState.Ready] (Requirement 31.1)
 * - Show a download prompt when [OnDeviceAiState.DownloadRequired] (Requirement 31.6)
 * - Show the "Running on device" indicator when [OnDeviceAiState.InferenceActive] (Requirement 31.3)
 *
 * Requirements: 31.1, 31.3, 31.6, 31.7
 */
@Singleton
class OnDeviceAiInitializer @Inject constructor(
    private val capabilityDetector: HardwareCapabilityDetector,
    private val modelManager: OnDeviceModelManager,
    private val inferenceClient: OnDeviceInferenceClient
) {

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<OnDeviceAiState>(OnDeviceAiState.Idle)

    /**
     * Observable state of the on-device AI feature.
     * Collect in SettingsViewModel / feature-settings UI.
     */
    val state: StateFlow<OnDeviceAiState> = _state.asStateFlow()

    /**
     * Whether the device meets the NPU/GPU threshold AND the model is Ready.
     * Convenience property for `SettingsViewModel` to gate the provider list entry.
     *
     * Requirement: 31.1
     */
    val isAvailable: Boolean
        get() = _state.value is OnDeviceAiState.Ready

    // ─── Initialization ──────────────────────────────────────────────────────

    /**
     * Performs capability detection and model integrity check asynchronously.
     *
     * Safe to call multiple times — subsequent calls while [OnDeviceAiState.Checking]
     * or [OnDeviceAiState.Ready] are no-ops.
     */
    fun initialize() {
        val current = _state.value
        if (current is OnDeviceAiState.Checking || current is OnDeviceAiState.Ready) return

        _state.value = OnDeviceAiState.Checking
        initScope.launch {
            runCatching { performInitialization() }
                .onFailure { e ->
                    Log.e(TAG, "Initialization failed: ${e.message}", e)
                    _state.value = OnDeviceAiState.Error(e.message ?: "Unknown error")
                }
        }
    }

    private suspend fun performInitialization() {
        // Step 1: Hardware capability detection
        val capability = capabilityDetector.detect()
        if (!capability.isSupported) {
            Log.i(TAG, "Device not supported: available=${capability.availableBytes / (1024 * 1024)} MB")
            _state.value = OnDeviceAiState.DeviceNotSupported(capability.availableBytes)
            return
        }
        Log.i(TAG, "Device supported: ${capability.vendorInfo}")

        // Step 2: Load manifest and pick first available model
        val manifest = modelManager.loadManifest()
        if (manifest.models.isEmpty()) {
            _state.value = OnDeviceAiState.Error("Model manifest is empty")
            return
        }
        val entry = manifest.models.first()

        // Step 3: Check model file integrity
        val fileState = modelManager.checkModelState(entry)
        when (fileState) {
            is ModelFileState.Ready -> {
                Log.i(TAG, "Model '${entry.id}' is ready")
                inferenceClient.modelFile = fileState.file
                _state.value = OnDeviceAiState.Ready(
                    capabilityInfo = capability.vendorInfo,
                    modelEntry = entry
                )
            }
            is ModelFileState.Absent -> {
                Log.i(TAG, "Model '${entry.id}' absent — download required")
                _state.value = OnDeviceAiState.DownloadRequired(entry, wasCorrupt = false)
            }
            is ModelFileState.Corrupt -> {
                Log.w(TAG, "Model '${entry.id}' corrupt — download required")
                _state.value = OnDeviceAiState.DownloadRequired(entry, wasCorrupt = true)
            }
            else -> {
                // Downloading / DownloadFailed states managed by downloadSelectedModel()
            }
        }
    }

    // ─── Download ────────────────────────────────────────────────────────────

    /**
     * Initiates a download for the first model in the manifest.
     * Emits [OnDeviceAiState.Downloading] progress and transitions to
     * [OnDeviceAiState.Ready] on success or [OnDeviceAiState.Error] on failure.
     *
     * Requirement: 31.6
     */
    fun downloadModel(entry: ModelEntry) {
        initScope.launch {
            modelManager.downloadModel(entry).collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        _state.value = OnDeviceAiState.Downloading(entry, event.fraction)
                    }
                    is DownloadEvent.Success -> {
                        inferenceClient.modelFile = event.file
                        _state.value = OnDeviceAiState.Ready(
                            capabilityInfo = capabilityDetector.detect().vendorInfo,
                            modelEntry = entry
                        )
                    }
                    is DownloadEvent.Failure -> {
                        _state.value = OnDeviceAiState.Error(event.reason)
                    }
                }
            }
        }
    }

    // ─── Active inference indicator ──────────────────────────────────────────

    /**
     * Called by [OnDeviceInferenceClient]'s consumer (e.g. ChatViewModel) when
     * on-device inference begins, to display the "Running on device" indicator.
     *
     * Requirement: 31.3
     */
    fun onInferenceStarted(entry: ModelEntry) {
        _state.value = OnDeviceAiState.InferenceActive(entry)
    }

    /**
     * Called when on-device inference completes or is cancelled.
     * Returns to the [OnDeviceAiState.Ready] state.
     */
    fun onInferenceEnded(entry: ModelEntry) {
        _state.value = OnDeviceAiState.Ready(
            capabilityInfo = capabilityDetector.detect().vendorInfo,
            modelEntry = entry
        )
    }

    /**
     * Cancels the internal coroutine scope. Used for testing to prevent leaks.
     */
    @VisibleForTesting
    internal fun cancelScope() {
        initScope.cancel()
    }
}
