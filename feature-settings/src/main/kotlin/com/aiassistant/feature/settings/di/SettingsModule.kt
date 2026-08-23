/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsModule.kt
 * Purpose    : Hilt module providing Settings dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-settings)
 * Pattern Used       : Hilt DI Module
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
 * File       : SettingsModule.kt
 * Purpose    : Hilt module providing Settings dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-settings)
 * Pattern Used       : Hilt DI Module
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
 * SettingsModule.kt â€” feature-settings module
 *
 * Purpose: Hilt [dagger.Module] providing [ThemePreferences] and [SettingsPreferences]
 *          instances needed by [SettingsViewModel]. Both DataStore wrappers require an
 *          application [Context], which Hilt provides via [@ApplicationContext].
 *
 * Architecture: feature-settings â€” installs into [dagger.hilt.components.SingletonComponent]
 *               so the same DataStore instances are shared across any ViewModel or
 *               composable that injects them within the application process.
 * Dependencies: core-ui (ThemePreferences), feature-settings (SettingsPreferences)
 *
 * Design decisions:
 * - @Singleton scope ensures a single DataStore file is opened per [Context], which is
 *   the strongly recommended pattern by the AndroidX DataStore documentation.
 * - @Provides rather than @Binds because both classes require a Context constructor
 *   argument that must be supplied by the module.
 * - [ThemePreferences] is provided here (feature-settings) rather than in core-ui to
 *   avoid adding a Hilt dependency to the core-ui module. The instance is scoped at
 *   [SingletonComponent] so it is effectively global, which is correct for a theme
 *   preference that the root Activity also observes.
 *
 * Requirements: 3.2, 24.2, 16.4, 7.6
 */
package com.aiassistant.feature.settings.di

import android.content.Context
import com.aiassistant.core.ui.ThemePreferences
import com.aiassistant.feature.settings.SettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing DataStore preference wrappers for the settings feature.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    /**
     * Provides the singleton [ThemePreferences] DataStore wrapper.
     *
     * Used by [SettingsViewModel] to read/write the user's theme mode preference and by
     * the root Activity to apply the current theme on every recomposition.
     *
     * @param context Application context for accessing the "theme_preferences" DataStore file.
     */
    @Provides
    @Singleton
    fun provideThemePreferences(@ApplicationContext context: Context): ThemePreferences = ThemePreferences(context)

    /**
     * Provides the singleton [SettingsPreferences] DataStore wrapper.
     *
     * Used by [SettingsViewModel] to read/write notification category toggles and the
     * privacy mode flag. The Memory Service reads [SettingsPreferences.privacyModeEnabled]
     * via dependency injection to decide whether to capture new memories.
     *
     * @param context Application context for accessing the "settings_preferences" DataStore file.
     */
    @Provides
    @Singleton
    fun provideSettingsPreferences(@ApplicationContext context: Context): SettingsPreferences =
        SettingsPreferences(context)
}
