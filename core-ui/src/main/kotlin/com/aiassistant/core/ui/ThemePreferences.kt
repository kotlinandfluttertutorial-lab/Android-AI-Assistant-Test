/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ThemePreferences.kt
 * Purpose    : ThemePreferences — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * Module     : core-ui
 * File       : ThemePreferences.kt
 * Purpose    : ThemePreferences — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * ThemePreferences.kt
 *
 * Purpose: Persists the user's selected [ThemeMode] using Jetpack DataStore.
 * Architecture: core-ui â€” shared design system; depends on core-common only.
 * Dependencies: androidx.datastore.preferences, kotlinx.coroutines.
 *
 * Design decisions:
 * - DataStore (Preferences) is chosen over SharedPreferences because it is
 *   coroutine-based, type-safe, and recommended by Google for simple key-value
 *   persistence in Compose/architecture-aware apps.
 * - Only a single preference key is managed here to keep the class focused. Settings
 *   such as the selected LLM provider are managed in the feature-settings module's own
 *   DataStore (to keep concerns separated).
 * - The [themeMode] Flow emits immediately on collection with the persisted value (or
 *   [ThemeMode.SYSTEM] as the first-run default), so the UI never has to deal with a
 *   null/uninitialized state.
 * - [setThemeMode] is a suspend function so the caller (ViewModel) can await the write
 *   or launch it in a coroutine scope without blocking the UI thread.
 * - Requirements: 24.2
 */

package com.aiassistant.core.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// â”€â”€â”€ DataStore singleton â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Extension property that creates or retrieves the singleton DataStore instance for
 * theme preferences on the application [Context].
 *
 * The file is named "theme_preferences" on disk. Only one instance is created per
 * application process thanks to the Kotlin property delegate mechanism.
 */
private val Context.themeDataStore by preferencesDataStore(name = "theme_preferences")

// â”€â”€â”€ Preference keys â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private object Keys {
    /** DataStore key for the persisted [ThemeMode.key] string. */
    val THEME_MODE = stringPreferencesKey("theme_mode")
}

// â”€â”€â”€ Repository â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Thin DataStore wrapper that reads and writes the user's preferred [ThemeMode].
 *
 * Designed to be injected by Hilt at the feature level. In the [feature-settings]
 * module, [SettingsViewModel] calls [setThemeMode]; [AppTheme] (in [core-ui]) observes
 * [themeMode] to apply the correct color scheme.
 *
 * ```kotlin
 * // Read
 * val mode: StateFlow<ThemeMode> = themePreferences.themeMode
 *     .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
 *
 * // Write
 * viewModelScope.launch { themePreferences.setThemeMode(ThemeMode.DARK) }
 * ```
 *
 * @param context Application context used to access the DataStore instance.
 */
class ThemePreferences(private val context: Context) {

    /**
     * A cold [Flow] that emits the currently persisted [ThemeMode] every time the
     * underlying DataStore value changes.
     *
     * Emits [ThemeMode.SYSTEM] when no preference has been written yet (first install).
     */
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        ThemeMode.fromKey(prefs[Keys.THEME_MODE])
    }

    /**
     * Persists [mode] to DataStore asynchronously.
     *
     * This function is safe to call from a background coroutine or directly from a
     * ViewModel's [kotlinx.coroutines.CoroutineScope].
     *
     * @param mode The [ThemeMode] to persist.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.key
        }
    }
}
