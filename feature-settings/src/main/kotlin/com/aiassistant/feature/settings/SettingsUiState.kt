/**
 * SettingsUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the settings feature,
 *          including loading, the main settings form, account management dialogs, and
 *          error states.
 * Architecture: feature-settings — MVVM presentation layer.
 * Dependencies: core-ui (ThemeMode), domain (User)
 *
 * Requirements: 3.2, 3.7, 7.6, 16.4, 24.2, 28.3, 31.1
 */
package com.aiassistant.feature.settings

import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ui.ThemeMode

/**
 * Carries the on-device capability availability flag so [SettingsUiState.Settings] can
 * gate the on-device provider option in the provider selector.
 *
 * @param isAvailable     `true` when the device meets the NPU/GPU threshold AND the model
 *                        is verified and ready (Requirement 31.1).
 * @param modelDisplayName Display name of the active on-device model, or `null` when not ready.
 */
data class OnDeviceCapabilityAvailability(val isAvailable: Boolean, val modelDisplayName: String? = null)

/**
 * Notification category toggle state persisted to DataStore.
 *
 * @param key          DataStore preference key string.
 * @param displayLabel Human-readable label shown in the Settings screen.
 * @param enabled      Whether the notification category is currently enabled.
 */
data class NotificationCategory(val key: String, val displayLabel: String, val enabled: Boolean)

/**
 * A single Firebase Remote Config entry displayed in the Settings screen.
 *
 * Only entries published by an Admin via Firebase Remote Config are surfaced here
 * (Requirement 28.3).
 *
 * @param key          The Remote Config parameter key.
 * @param displayLabel Human-readable label derived from the key (snake_case → Title Case).
 * @param value        The current string value fetched from Remote Config.
 */
data class RemoteConfigEntry(val key: String, val displayLabel: String, val value: String)

/**
 * Represents every possible UI state in the settings feature.
 *
 * The [SettingsViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Composables observe it and render accordingly.
 */
sealed class SettingsUiState {

    /** A data load or save operation is in progress. */
    data object Loading : SettingsUiState()

    /**
     * The main settings screen is ready and displaying current values.
     *
     * @param activeProvider         The currently selected LLM provider identifier.
     * @param themeMode              The currently persisted theme mode.
     * @param notificationCategories Ordered list of notification toggle states.
     * @param privacyModeEnabled     Whether Memory Service capture is currently disabled.
     * @param isGoogleLinked         Whether the user has a Google account linked.
     * @param remoteConfigEntries    Admin-published Remote Config key/value pairs to display.
     * @param isSaving               True while a settings write operation is in flight.
     * @param onDeviceCapability     On-device inference availability for the current device.
     *                               When [OnDeviceCapabilityAvailability.isAvailable] is true
     *                               the [ON_DEVICE] option is shown in the provider selector
     *                               (Requirement 31.1).
     */
    data class Settings(
        val activeProvider: LlmProvider,
        val themeMode: ThemeMode,
        val notificationCategories: List<NotificationCategory>,
        val privacyModeEnabled: Boolean,
        val isGoogleLinked: Boolean = false,
        val remoteConfigEntries: List<RemoteConfigEntry> = emptyList(),
        val isSaving: Boolean = false,
        val onDeviceCapability: OnDeviceCapabilityAvailability = OnDeviceCapabilityAvailability(isAvailable = false),
        /** Whether context-aware AI suggestions are globally enabled (Requirement 33.8). */
        val contextSuggestionsEnabled: Boolean = true
    ) : SettingsUiState() {

        /**
         * The filtered list of [LlmProvider] values to show in the provider selector.
         *
         * [LlmProvider.ON_DEVICE] is only included when the device meets the NPU/GPU
         * threshold (Requirement 31.1).
         */
        val availableProviders: List<LlmProvider>
            get() = LlmProvider.entries.filter { provider ->
                provider != LlmProvider.ON_DEVICE || onDeviceCapability.isAvailable
            }
    }

    /**
     * A settings operation failed.
     *
     * @param message Human-readable error description for the error banner.
     */
    data class Error(val message: String) : SettingsUiState()

    // ── Account management dialog states ──────────────────────────────────────

    /** The change-password dialog is open and awaiting user input. */
    data object ChangePasswordDialog : SettingsUiState()

    /**
     * A transient one-time action result to communicate back to the UI after an
     * account management operation completes (success or failure message banner).
     *
     * After the UI consumes this event it should call [SettingsViewModel.onActionConsumed]
     * to restore the [Settings] state.
     *
     * @param message        A human-readable description of the outcome.
     * @param isSuccess      Whether the operation succeeded.
     * @param previousSettings The [Settings] state to restore after consumption.
     */
    data class ActionResult(val message: String, val isSuccess: Boolean, val previousSettings: Settings) :
        SettingsUiState()
}
