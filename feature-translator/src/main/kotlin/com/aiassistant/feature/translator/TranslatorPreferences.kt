/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-translator
 * File       : TranslatorPreferences.kt
 * Purpose    : TranslatorPreferences — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
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
 * Module     : feature-translator
 * File       : TranslatorPreferences.kt
 * Purpose    : TranslatorPreferences — feature-translator module component
 *
 * Architecture Layer : Feature (feature-translator)
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
 * TranslatorPreferences.kt
 *
 * Purpose: Persists the user's selected language pair (source + target codes) using
 *          Jetpack DataStore, following the same pattern as ThemePreferences in core-ui.
 * Architecture: feature-translator â€” persistence layer; injected into TranslatorViewModel
 *               via TranslatorModule.
 * Dependencies: androidx.datastore.preferences, kotlinx.coroutines
 *
 * Requirements: 10.5, 19.1
 *
 * Design decisions:
 * - Uses the preferencesDataStore Context extension delegate for singleton DataStore
 *   creation, matching the established pattern in ThemePreferences (core-ui).
 * - Stores only language codes; display names are resolved at read-time from
 *   SupportedLanguages to avoid stale display name data on app updates.
 * - languagePairFlow emits the default pair (EN â†’ ES) on first install (no stored pref).
 * - setLanguagePair is suspend so the ViewModel can await the write in a coroutine.
 */
package com.aiassistant.feature.translator

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// â”€â”€â”€ DataStore singleton â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Singleton DataStore instance for translator language pair preferences.
 * The file is named "translator_preferences" on disk.
 */
private val Context.translatorDataStore by preferencesDataStore(name = "translator_preferences")

// â”€â”€â”€ Preference keys â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private object TranslatorKeys {
    val SOURCE_CODE = stringPreferencesKey("translator_source_code")
    val TARGET_CODE = stringPreferencesKey("translator_target_code")
}

// â”€â”€â”€ Preferences wrapper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Thin DataStore wrapper that reads and writes the user's selected [LanguagePair].
 *
 * Designed to be constructed in [TranslatorModule] and injected into [TranslatorViewModel].
 *
 * ```kotlin
 * // Read
 * translatorPreferences.languagePairFlow.collect { pair -> ... }
 *
 * // Write
 * viewModelScope.launch { translatorPreferences.setLanguagePair(pair) }
 * ```
 *
 * @param context Application context used to access the DataStore instance.
 */
class TranslatorPreferences(private val context: Context) {

    /**
     * Cold [Flow] that emits the persisted [LanguagePair] on every change.
     * Emits [SupportedLanguages.defaultPair] when no preference has been written yet.
     */
    val languagePairFlow: Flow<LanguagePair> = context.translatorDataStore.data.map { prefs ->
        val sourceCode = prefs[TranslatorKeys.SOURCE_CODE] ?: SupportedLanguages.defaultSource.first
        val targetCode = prefs[TranslatorKeys.TARGET_CODE] ?: SupportedLanguages.defaultTarget.first
        LanguagePair(
            sourceCode = sourceCode,
            sourceName = SupportedLanguages.displayNameFor(sourceCode),
            targetCode = targetCode,
            targetName = SupportedLanguages.displayNameFor(targetCode)
        )
    }

    /**
     * Persists [pair] source and target codes to DataStore.
     *
     * @param pair The [LanguagePair] to persist.
     */
    suspend fun setLanguagePair(pair: LanguagePair) {
        context.translatorDataStore.edit { prefs ->
            prefs[TranslatorKeys.SOURCE_CODE] = pair.sourceCode
            prefs[TranslatorKeys.TARGET_CODE] = pair.targetCode
        }
    }
}
