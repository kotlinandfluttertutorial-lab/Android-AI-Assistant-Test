/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsPreferences.kt
 * Purpose    : SettingsPreferences — feature-settings module component
 *
 * Architecture Layer : Feature (feature-settings)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsPreferences.kt
 * Purpose    : SettingsPreferences — feature-settings module component
 *
 * Architecture Layer : Feature (feature-settings)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * SettingsPreferences.kt
 *
 * Purpose: DataStore wrapper for notification category toggles and privacy mode.
 *          Separate from [ThemePreferences] (core-ui) to keep concerns isolated â€”
 *          notification and privacy preferences belong to the settings feature, not
 *          the global design system.
 * Architecture: feature-settings â€” data persistence layer; injected via Hilt.
 * Dependencies: androidx.datastore.preferences, kotlinx.coroutines.
 *
 * Design decisions:
 * - Each notification category is stored as an individual Boolean key so toggling
 *   one category does not cause a read-modify-write conflict with another.
 * - Privacy mode is a single Boolean key with a safe default of `false` (capture enabled)
 *   matching the spec: "disabled by default, user must explicitly opt in".
 * - All reads are exposed as cold [Flow]s so ViewModels can use [stateIn] to create
 *   hot [StateFlow]s that survive recomposition.
 * - [setNotificationEnabled] and [setPrivacyMode] are suspend functions so callers can
 *   await the write or fire-and-forget inside a coroutine scope.
 *
 * Requirements: 16.4, 7.6
 */
package com.aiassistant.feature.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// â”€â”€â”€ DataStore singleton â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Extension property that creates or retrieves the singleton DataStore instance for
 * settings preferences on the application [Context].
 *
 * The file is named "settings_preferences" on disk.
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings_preferences")

// â”€â”€â”€ Preference keys â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private object Keys {
    /** Notification category: new chat messages. */
    val NOTIF_CHAT_MESSAGES = booleanPreferencesKey("notif_chat_messages")

    /** Notification category: offline queue sync success/failure. */
    val NOTIF_SYNC_STATUS = booleanPreferencesKey("notif_sync_status")

    /** Notification category: document processing complete. */
    val NOTIF_RAG_INGESTION = booleanPreferencesKey("notif_rag_ingestion")

    /** Notification category: reminder alerts. */
    val NOTIF_REMINDERS = booleanPreferencesKey("notif_reminders")

    /** Privacy mode toggle â€” disables Memory Service capture when true. */
    val PRIVACY_MODE_ENABLED = booleanPreferencesKey("privacy_mode_enabled")

    /** Context-aware AI suggestions toggle: disables all context suggestion calls when false (Requirement 33.8). */
    val CONTEXT_SUGGESTIONS_ENABLED = booleanPreferencesKey("context_suggestions_enabled")
}

/**
 * DataStore wrapper managing notification category toggles and the privacy mode flag.
 *
 * Inject this via [SettingsModule] in [feature-settings]. The downstream Memory Service
 * reads [privacyModeEnabled] to decide whether to capture new memories.
 *
 * ```kotlin
 * // Read in ViewModel
 * val privacyMode: StateFlow<Boolean> = settingsPreferences.privacyModeEnabled
 *     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
 *
 * // Write from ViewModel
 * viewModelScope.launch { settingsPreferences.setPrivacyMode(true) }
 * ```
 *
 * @param context Application context used to access the DataStore instance.
 */
class SettingsPreferences(private val context: Context) {

    // â”€â”€â”€ Notification categories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Flow emitting the current enabled state for the "chat_messages" notification category.
     * Default: `true` (enabled).
     */
    val chatMessagesEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.NOTIF_CHAT_MESSAGES] ?: true }

    /**
     * Flow emitting the current enabled state for the "sync_status" notification category.
     * Default: `true` (enabled).
     */
    val syncStatusEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.NOTIF_SYNC_STATUS] ?: true }

    /**
     * Flow emitting the current enabled state for the "rag_ingestion" notification category.
     * Default: `true` (enabled).
     */
    val ragIngestionEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.NOTIF_RAG_INGESTION] ?: true }

    /**
     * Flow emitting the current enabled state for the "reminders" notification category.
     * Default: `true` (enabled).
     */
    val remindersEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.NOTIF_REMINDERS] ?: true }

    // â”€â”€â”€ Privacy mode â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Flow emitting whether privacy mode is currently enabled.
     *
     * When `true`, the Memory Service disables new memory capture for the session.
     * Existing memories are unaffected (Requirement 7.6).
     * Default: `false` (capture enabled).
     */
    val privacyModeEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.PRIVACY_MODE_ENABLED] ?: false }

    /**
     * Flow emitting whether context-aware AI suggestions are globally enabled.
     *
     * When alse, [GetContextSuggestionsUseCase] returns an empty list immediately
     * without calling the AI backend (Requirement 33.8).
     * Default:     rue (suggestions enabled).
     */
    val contextSuggestionsEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { prefs -> prefs[Keys.CONTEXT_SUGGESTIONS_ENABLED] ?: true }

    // â”€â”€â”€ Write operations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Persists the enabled state for a notification category identified by [categoryKey].
     *
     * Silently ignores unknown keys so callers do not need guard logic.
     *
     * @param categoryKey One of: "chat_messages", "sync_status", "rag_ingestion", "reminders".
     * @param enabled     The new toggle value.
     */
    suspend fun setNotificationEnabled(categoryKey: String, enabled: Boolean) {
        val prefKey = when (categoryKey) {
            "chat_messages" -> Keys.NOTIF_CHAT_MESSAGES
            "sync_status" -> Keys.NOTIF_SYNC_STATUS
            "rag_ingestion" -> Keys.NOTIF_RAG_INGESTION
            "reminders" -> Keys.NOTIF_REMINDERS
            else -> return // unknown category â€” no-op
        }
        context.settingsDataStore.edit { prefs -> prefs[prefKey] = enabled }
    }

    /**
     * Persists the privacy mode flag.
     *
     * Setting this to `true` signals the Memory Service to stop capturing new memories
     * for the current session; existing memories are NOT deleted (Requirement 7.6).
     *
     * @param enabled `true` to disable memory capture; `false` to re-enable it.
     */
    suspend fun setPrivacyMode(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PRIVACY_MODE_ENABLED] = enabled
        }
    }

    /**
     * Persists the context-aware AI suggestions enabled flag (Requirement 33.8).
     *
     * When alse, all context suggestion calls are suppressed at the use-case level.
     *
     * @param enabled     rue to enable context suggestions; alse to disable them.
     */
    suspend fun setContextSuggestionsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.CONTEXT_SUGGESTIONS_ENABLED] = enabled
        }
    }
}
