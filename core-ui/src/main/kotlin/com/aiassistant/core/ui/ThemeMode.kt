/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ThemeMode.kt
 * Purpose    : ThemeMode — core-ui module component
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
 * File       : ThemeMode.kt
 * Purpose    : ThemeMode — core-ui module component
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
 * ThemeMode.kt
 *
 * Purpose: Defines the three supported theme modes for the AI Assistant application.
 * Architecture: core-ui â€” shared design system, no Android framework dependencies.
 * Dependencies: None (pure Kotlin enum)
 *
 * Design decisions:
 * - Three variants mirror Android's system-level dark-mode API: Light, Dark, and
 *   System (which defers to the device-level preference at runtime).
 * - The string [key] values are stored verbatim in DataStore, so they must remain
 *   stable across releases. Never rename or remove a key.
 * - Requirements: 24.1, 24.2
 */

package com.aiassistant.core.ui

/**
 * Represents the three supported application theme modes.
 *
 * The [key] is persisted in DataStore (see [ThemePreferences]) and must never change
 * between application versions because existing user preferences would break silently.
 *
 * Usage:
 * ```kotlin
 * val mode = ThemeMode.fromKey("dark") // ThemeMode.DARK
 * dataStore.edit { it[THEME_KEY] = mode.key }
 * ```
 */
enum class ThemeMode(val key: String) {
    /** Force-light regardless of device setting. */
    LIGHT("light"),

    /** Force-dark regardless of device setting. */
    DARK("dark"),

    /**
     * Follow the device system dark-mode switch.
     *
     * This is the default for new installs so that users on Android 10+ who have already
     * configured a system-level preference do not need to configure the app separately.
     */
    SYSTEM("system");

    companion object {
        /** Returns the [ThemeMode] whose [key] matches [value], or [SYSTEM] as the safe default. */
        fun fromKey(value: String?): ThemeMode = entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}
