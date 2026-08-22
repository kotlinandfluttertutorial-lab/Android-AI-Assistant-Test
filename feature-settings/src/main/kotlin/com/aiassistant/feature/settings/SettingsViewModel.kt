/**
 * SettingsViewModel.kt
 *
 * Purpose: Manages all UI state and orchestrates persistence calls for the settings
 *          feature: LLM provider selection, theme mode, notification category toggles,
 *          privacy mode toggle, account management (change password, Google OAuth2
 *          link/unlink, logout), Firebase Remote Config display, and on-device AI
 *          capability detection (Requirement 31.1).
 * Architecture: feature-settings — MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (UserRepository, AuthRepository), core-ui (ThemePreferences, ThemeMode),
 *               core-common (DispatcherProvider, ApiResult),
 *               feature-settings (SettingsPreferences),
 *               feature-on-device-ai (OnDeviceCapabilityChecker),
 *               Firebase Remote Config (FirebaseRemoteConfig)
 *
 * Design decisions:
 * - All DataStore reads are combined into a single [SettingsUiState.Settings] emission
 *   using [combine] so the UI observes a single coherent state snapshot.
 * - Provider and theme updates go through [UserRepository] so the backend is kept in
 *   sync; notification and privacy toggles are local-only (DataStore).
 * - Account management actions (change password, link/unlink Google, logout) use
 *   [AuthRepository] directly from the ViewModel rather than domain use cases because
 *   the operations are simple delegations with no additional business logic.
 * - Firebase Remote Config entries are fetched on init and appended to the Settings state.
 *   Only non-empty string keys defined in the SETTINGS_RC_KEYS set are surfaced.
 * - [OnDeviceCapabilityChecker] is called once at init on the IO dispatcher. The result
 *   populates [SettingsUiState.Settings.onDeviceCapability], which gates the visibility
 *   of the ON_DEVICE LlmProvider option in the provider selector.
 *
 * Requirements: 3.2, 3.7, 7.6, 16.4, 24.2, 28.3, 31.1
 */
package com.aiassistant.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ai.OnDeviceCapabilityProvider
import com.aiassistant.core.ai.OnDeviceCapabilityState
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.ui.ThemeMode
import com.aiassistant.core.ui.ThemePreferences
import com.aiassistant.domain.model.ThemeMode as DomainThemeMode
import com.aiassistant.domain.model.User
import com.aiassistant.domain.repository.AuthRepository
import com.aiassistant.domain.repository.UserRepository
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Keys of Firebase Remote Config parameters that are surfaced in the Settings screen.
 *
 * Any key listed here that has a non-empty value in Remote Config will be displayed as a
 * read-only info row under the "Remote Config" section (Requirement 28.3).
 *
 * Add new keys here as Admins publish them via the Firebase console.
 */
private val SETTINGS_RC_KEYS = setOf(
    "feature_max_context_tokens",
    "feature_default_provider",
    "feature_rag_chunk_size",
    "feature_enable_memory",
    "feature_rate_limit_per_minute",
    "app_maintenance_message",
    "app_min_version"
)

/**
 * Converts a snake_case key like "feature_max_context_tokens" to a display label like
 * "Feature Max Context Tokens".
 */
private fun String.toDisplayLabel(): String = replace('_', ' ').split(' ').joinToString(" ") { word ->
    word.replaceFirstChar { it.uppercaseChar() }
}

/**
 * ViewModel for the Settings screen.
 *
 * Exposes a [StateFlow] of [SettingsUiState] that composables observe. All blocking work
 * (DataStore reads/writes, network calls, Firebase calls) is dispatched on
 * [DispatcherProvider.io].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val themePreferences: ThemePreferences,
    private val settingsPreferences: SettingsPreferences,
    private val remoteConfig: FirebaseRemoteConfig,
    private val dispatchers: DispatcherProvider,
    private val onDeviceCapabilityChecker: OnDeviceCapabilityProvider
) : ViewModel() {

    // ── State ─────────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<SettingsUiState>(SettingsUiState.Loading)

    /** Observable settings UI state. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Cached last-known Settings state for dialog / action result transitions
    private var lastKnownSettings: SettingsUiState.Settings? = null

    private var observeSettingsJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        observeSettings()
        fetchRemoteConfig()
        evaluateOnDeviceCapability()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Combines [UserRepository.getCurrentUser], [ThemePreferences.themeMode], and all
     * [SettingsPreferences] flows into a single [SettingsUiState.Settings] emission.
     *
     * Also queries [AuthRepository.isGoogleAccountLinked] once per emission to determine
     * the current Google link status.
     *
     * Any error from the user repository transitions to [SettingsUiState.Error].
     */
    private fun observeSettings() {
        observeSettingsJob?.cancel()
        observeSettingsJob = viewModelScope.launch {
            combine(
                userRepository.getCurrentUser(),
                themePreferences.themeMode,
                settingsPreferences.chatMessagesEnabled,
                settingsPreferences.syncStatusEnabled,
                settingsPreferences.ragIngestionEnabled,
                settingsPreferences.remindersEnabled,
                settingsPreferences.privacyModeEnabled,
                settingsPreferences.contextSuggestionsEnabled
            ) { values ->
                // values[0] = ApiResult<User?>, [1] = ThemeMode, [2..5] = Boolean, [6] = Boolean, [7] = Boolean
                @Suppress("UNCHECKED_CAST")
                val userResult = values[0] as ApiResult<*>
                val themeMode = values[1] as ThemeMode
                val chatMessages = values[2] as Boolean
                val syncStatus = values[3] as Boolean
                val ragIngestion = values[4] as Boolean
                val reminders = values[5] as Boolean
                val privacyMode = values[6] as Boolean
                val contextSuggestions = values[7] as Boolean

                when (userResult) {
                    is ApiResult.Success -> {
                        @Suppress("UNCHECKED_CAST")
                        val user = (userResult as ApiResult.Success<User?>).data
                        val provider = if (user != null) {
                            LlmProvider.fromId(user.activeProvider)
                        } else {
                            LlmProvider.OPENAI_GPT4O
                        }
                        // Preserve existing remote config entries and google link status
                        val existing = lastKnownSettings
                        SettingsUiState.Settings(
                            activeProvider = provider,
                            themeMode = themeMode,
                            notificationCategories = buildNotificationCategories(
                                chatMessages = chatMessages,
                                syncStatus = syncStatus,
                                ragIngestion = ragIngestion,
                                reminders = reminders
                            ),
                            privacyModeEnabled = privacyMode,
                            contextSuggestionsEnabled = contextSuggestions,
                            isGoogleLinked = existing?.isGoogleLinked ?: false,
                            remoteConfigEntries = existing?.remoteConfigEntries ?: emptyList()
                        )
                    }
                    is ApiResult.Error -> SettingsUiState.Error(userResult.error.message)
                    is ApiResult.NetworkUnavailable -> {
                        // Offline — still show settings using DataStore defaults
                        val existing = lastKnownSettings
                        SettingsUiState.Settings(
                            activeProvider = LlmProvider.OPENAI_GPT4O,
                            themeMode = themeMode,
                            notificationCategories = buildNotificationCategories(
                                chatMessages = chatMessages,
                                syncStatus = syncStatus,
                                ragIngestion = ragIngestion,
                                reminders = reminders
                            ),
                            privacyModeEnabled = privacyMode,
                            contextSuggestionsEnabled = contextSuggestions,
                            isGoogleLinked = existing?.isGoogleLinked ?: false,
                            remoteConfigEntries = existing?.remoteConfigEntries ?: emptyList()
                        )
                    }
                    is ApiResult.Loading -> SettingsUiState.Loading
                }
            }.collect { state ->
                if (state is SettingsUiState.Settings) {
                    lastKnownSettings = state
                }
                _uiState.value = state
            }
        }

        // Fetch Google link status after state is established
        viewModelScope.launch {
            val isLinked = withContext(dispatchers.io) {
                when (val r = authRepository.isGoogleAccountLinked()) {
                    is ApiResult.Success -> r.data
                    else -> false
                }
            }
            val current = _uiState.value
            if (current is SettingsUiState.Settings) {
                val updated = current.copy(isGoogleLinked = isLinked)
                lastKnownSettings = updated
                _uiState.value = updated
            }
        }
    }

    /**
     * Evaluates on-device AI inference capability once at startup and injects the result
     * into the [SettingsUiState.Settings] state so the ON_DEVICE provider option is
     * only shown when the device meets the NPU/GPU threshold (Requirement 31.1).
     *
     * This is a best-effort, one-time check; failure is silently swallowed so the
     * settings screen remains functional even if the capability check errors out.
     */
    private fun evaluateOnDeviceCapability() {
        viewModelScope.launch {
            try {
                val capabilityState = withContext(dispatchers.io) {
                    onDeviceCapabilityChecker.evaluate()
                }
                val onDeviceAvailability = when (capabilityState) {
                    is OnDeviceCapabilityState.SupportedAndReady ->
                        OnDeviceCapabilityAvailability(
                            isAvailable = true,
                            modelDisplayName = capabilityState.modelDisplayName
                        )
                    else -> OnDeviceCapabilityAvailability(isAvailable = false)
                }
                val current = _uiState.value
                if (current is SettingsUiState.Settings) {
                    val updated = current.copy(onDeviceCapability = onDeviceAvailability)
                    lastKnownSettings = updated
                    _uiState.value = updated
                } else {
                    lastKnownSettings = lastKnownSettings?.copy(onDeviceCapability = onDeviceAvailability)
                }
            } catch (_: Exception) {
                // On-device capability check failure is non-fatal
            }
        }
    }

    /**
     * Fetches Firebase Remote Config and injects all key/value pairs from
     * [SETTINGS_RC_KEYS] that have non-empty values into the current [Settings] state.
     *
     * This is a best-effort operation: failure does not affect other settings.
     */
    private fun fetchRemoteConfig() {
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) {
                    remoteConfig.fetchAndActivate().await()
                }
                val entries = SETTINGS_RC_KEYS
                    .mapNotNull { key ->
                        val value = remoteConfig.getString(key)
                        if (value.isNotBlank()) {
                            RemoteConfigEntry(
                                key = key,
                                displayLabel = key.toDisplayLabel(),
                                value = value
                            )
                        } else {
                            null
                        }
                    }
                    .sortedBy { it.key }

                val current = _uiState.value
                if (current is SettingsUiState.Settings) {
                    val updated = current.copy(remoteConfigEntries = entries)
                    lastKnownSettings = updated
                    _uiState.value = updated
                } else {
                    // Store for later — will be merged on the next combine emission
                    lastKnownSettings = lastKnownSettings?.copy(remoteConfigEntries = entries)
                }
            } catch (_: Exception) {
                // Remote Config fetch failure is non-fatal — settings screen still usable
            }
        }
    }

    /**
     * Builds the ordered list of [NotificationCategory] items from individual toggle values.
     */
    private fun buildNotificationCategories(
        chatMessages: Boolean,
        syncStatus: Boolean,
        ragIngestion: Boolean,
        reminders: Boolean
    ): List<NotificationCategory> = listOf(
        NotificationCategory(
            key = "chat_messages",
            displayLabel = "Chat messages",
            enabled = chatMessages
        ),
        NotificationCategory(
            key = "sync_status",
            displayLabel = "Sync status",
            enabled = syncStatus
        ),
        NotificationCategory(
            key = "rag_ingestion",
            displayLabel = "Document processing",
            enabled = ragIngestion
        ),
        NotificationCategory(
            key = "reminders",
            displayLabel = "Reminders",
            enabled = reminders
        )
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Switches the active LLM provider without restarting the application (Requirement 3.2).
     *
     * Updates [UserRepository] so the backend is notified. On success the [userRepository]
     * flow re-emits and [_uiState] is updated automatically by [observeSettings].
     *
     * @param provider The new [LlmProvider] to activate.
     */
    fun selectProvider(provider: LlmProvider) {
        val current = _uiState.value as? SettingsUiState.Settings ?: return
        if (current.activeProvider == provider) return

        _uiState.value = current.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                userRepository.updateActiveProvider(provider.id)
            }
            if (result is ApiResult.Error) {
                _uiState.value = SettingsUiState.Error(result.error.message)
            }
            // On success the combine flow in observeSettings() emits the updated state
        }
    }

    /**
     * Persists the selected [themeMode] to both [ThemePreferences] and [UserRepository]
     * (Requirement 24.2).
     *
     * The [ThemePreferences] write takes effect immediately so [AppTheme] reacts without
     * waiting for the backend round-trip.
     *
     * @param themeMode The new [ThemeMode] to apply and persist.
     */
    fun selectTheme(themeMode: ThemeMode) {
        val current = _uiState.value as? SettingsUiState.Settings ?: return
        if (current.themeMode == themeMode) return

        _uiState.value = current.copy(isSaving = true)

        viewModelScope.launch {
            withContext(dispatchers.io) {
                // Write to local DataStore first so the UI updates immediately
                themePreferences.setThemeMode(themeMode)
                // Then sync to backend; failure is non-blocking — local change already applied
                val domainThemeMode = DomainThemeMode.fromValue(themeMode.key)
                userRepository.updateThemeMode(domainThemeMode)
            }
            // isSaving will clear on the next combine emission
        }
    }

    /**
     * Toggles a notification category and persists it to DataStore (Requirement 16.4).
     *
     * @param categoryKey The DataStore key identifying the notification category.
     * @param enabled     The new toggle value.
     */
    fun setNotificationEnabled(categoryKey: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                settingsPreferences.setNotificationEnabled(categoryKey, enabled)
            }
        }
    }

    /**
     * Toggles privacy mode for the Memory Service (Requirement 7.6).
     *
     * When enabled, the Memory Service stops capturing new memories for the session.
     * Existing memories are NOT deleted.
     *
     * @param enabled `true` to disable memory capture; `false` to re-enable it.
     */
    fun setPrivacyMode(enabled: Boolean) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                settingsPreferences.setPrivacyMode(enabled)
            }
        }
    }

    /**
     * Persists the context-aware suggestions global toggle to DataStore (Requirement 33.8).
     *
     * When [enabled] is `false`, [GetContextSuggestionsUseCase] returns an empty list
     * immediately without calling the AI Orchestrator.
     *
     * @param enabled `false` to globally disable context-aware suggestions.
     */
    fun setContextSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                settingsPreferences.setContextSuggestionsEnabled(enabled)
            }
        }
    }

    // ── Account management ────────────────────────────────────────────────────

    /**
     * Transitions to [SettingsUiState.ChangePasswordDialog] to show the change-password
     * dialog. The caller should restore [Settings] state by calling [onActionConsumed]
     * if the user cancels the dialog.
     */
    fun showChangePasswordDialog() {
        if (_uiState.value is SettingsUiState.Settings) {
            _uiState.value = SettingsUiState.ChangePasswordDialog
        }
    }

    /**
     * Submits a password change request to [AuthRepository] (Requirement 28.3).
     *
     * Validates that [newPassword] is at least 12 characters before hitting the network.
     * Emits [SettingsUiState.ActionResult] with success or failure message on completion.
     *
     * @param currentPassword The user's existing password.
     * @param newPassword      The desired new password (≥ 12 characters).
     */
    fun changePassword(currentPassword: String, newPassword: String) {
        if (newPassword.length < 12) {
            val settings = lastKnownSettings ?: return
            _uiState.value = SettingsUiState.ActionResult(
                message = "New password must be at least 12 characters.",
                isSuccess = false,
                previousSettings = settings
            )
            return
        }

        val settings = lastKnownSettings ?: return
        _uiState.value = settings.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                authRepository.changePassword(currentPassword, newPassword)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> SettingsUiState.ActionResult(
                    message = "Password changed successfully.",
                    isSuccess = true,
                    previousSettings = settings.copy(isSaving = false)
                )
                is ApiResult.Error -> SettingsUiState.ActionResult(
                    message = result.error.message,
                    isSuccess = false,
                    previousSettings = settings.copy(isSaving = false)
                )
                is ApiResult.NetworkUnavailable -> SettingsUiState.ActionResult(
                    message = "No network connection. Please check your connection and try again.",
                    isSuccess = false,
                    previousSettings = settings.copy(isSaving = false)
                )
                is ApiResult.Loading -> settings.copy(isSaving = true)
            }
        }
    }

    /**
     * Links a Google account to the currently authenticated user (Requirement 1.6).
     *
     * The [idToken] must be obtained from the Google Sign-In result before calling this
     * method.
     *
     * @param idToken The Google ID token from the Google Sign-In flow.
     */
    fun linkGoogleAccount(idToken: String) {
        val settings = (_uiState.value as? SettingsUiState.Settings) ?: return
        _uiState.value = settings.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                authRepository.linkGoogleAccount(idToken)
            }
            when (result) {
                is ApiResult.Success -> {
                    val updated = settings.copy(isSaving = false, isGoogleLinked = true)
                    lastKnownSettings = updated
                    _uiState.value = SettingsUiState.ActionResult(
                        message = "Google account linked successfully.",
                        isSuccess = true,
                        previousSettings = updated
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = SettingsUiState.ActionResult(
                        message = result.error.message,
                        isSuccess = false,
                        previousSettings = settings.copy(isSaving = false)
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = SettingsUiState.ActionResult(
                        message = "No network connection. Please check your connection and try again.",
                        isSuccess = false,
                        previousSettings = settings.copy(isSaving = false)
                    )
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Removes the Google OAuth2 link from the currently authenticated user's account.
     *
     * The user must have a password credential before unlinking is permitted (enforced
     * server-side). Emits [SettingsUiState.ActionResult] with outcome.
     */
    fun unlinkGoogleAccount() {
        val settings = (_uiState.value as? SettingsUiState.Settings) ?: return
        if (!settings.isGoogleLinked) return

        _uiState.value = settings.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                authRepository.unlinkGoogleAccount()
            }
            when (result) {
                is ApiResult.Success -> {
                    val updated = settings.copy(isSaving = false, isGoogleLinked = false)
                    lastKnownSettings = updated
                    _uiState.value = SettingsUiState.ActionResult(
                        message = "Google account unlinked.",
                        isSuccess = true,
                        previousSettings = updated
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = SettingsUiState.ActionResult(
                        message = result.error.message,
                        isSuccess = false,
                        previousSettings = settings.copy(isSaving = false)
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    _uiState.value = SettingsUiState.ActionResult(
                        message = "No network connection. Please check your connection and try again.",
                        isSuccess = false,
                        previousSettings = settings.copy(isSaving = false)
                    )
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Logs the user out by calling [AuthRepository.logout] which invalidates all active
     * refresh tokens server-side (Requirement 1.10).
     *
     * The caller is responsible for navigating to the login screen after this call
     * emits an [ApiResult.Success].
     *
     * @param onLoggedOut Callback invoked when logout completes successfully.
     */
    fun logout(onLoggedOut: () -> Unit) {
        val settings = lastKnownSettings ?: return
        _uiState.value = settings.copy(isSaving = true)

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                authRepository.logout()
            }
            when (result) {
                is ApiResult.Success -> onLoggedOut()
                is ApiResult.Error -> {
                    _uiState.value = SettingsUiState.ActionResult(
                        message = "Logout failed: ${result.error.message}",
                        isSuccess = false,
                        previousSettings = settings.copy(isSaving = false)
                    )
                }
                is ApiResult.NetworkUnavailable -> {
                    // Allow offline logout — clear local state on the UI side
                    onLoggedOut()
                }
                is ApiResult.Loading -> { /* no-op */ }
            }
        }
    }

    /**
     * Restores the [Settings] state after an [SettingsUiState.ActionResult] has been
     * consumed by the UI (snackbar shown, dialog dismissed, etc.).
     *
     * Call this from the composable's `LaunchedEffect` after displaying the result.
     */
    fun onActionConsumed() {
        val current = _uiState.value
        if (current is SettingsUiState.ActionResult) {
            lastKnownSettings = current.previousSettings
            _uiState.value = current.previousSettings
        } else if (current is SettingsUiState.ChangePasswordDialog) {
            _uiState.value = lastKnownSettings ?: SettingsUiState.Loading
        }
    }

    /**
     * Reloads settings by cancelling the current observation and restarting it.
     * Useful after a transient network error to retry loading user profile data.
     */
    fun retry() {
        _uiState.value = SettingsUiState.Loading
        observeSettings()
        fetchRemoteConfig()
    }
}
