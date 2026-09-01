/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : AppTheme.kt
 * Purpose    : AppTheme — core-ui module component
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
 * File       : AppTheme.kt
 * Purpose    : AppTheme — core-ui module component
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
 * AppTheme.kt
 *
 * Purpose: Root Material Design 3 theme composable for the AI Assistant application.
 * Architecture: core-ui â€” shared design system; consumed by every feature module.
 * Dependencies: Compose Material 3, [LightColorScheme], [DarkColorScheme],
 *               [MaterialTypography], [MaterialShapes], [LocalSpacing].
 *
 * Design decisions:
 * - [AppTheme] is the single entry point for theming. Every screen in every feature
 *   module should be wrapped in [AppTheme] (or inherit it from the root Activity's
 *   [setContent] call).
 * - Material You dynamic color ([dynamicColorScheme]) is applied on Android 12+
 *   (API level 31+) using the wallpaper-derived palette. The static [LightColorScheme]
 *   / [DarkColorScheme] are used as the fallback on older devices and as the preview
 *   default so that Compose Preview renders sensibly without a real context.
 * - Theme mode selection follows the [ThemeMode] enum: LIGHT forces light, DARK forces
 *   dark, SYSTEM defers to [isSystemInDarkTheme()].
 * - [LocalSpacing] is provided here so every composable inside the theme hierarchy can
 *   access spacing tokens without a direct import.
 * - The status bar and navigation bar are not styled here; edge-to-edge handling is the
 *   responsibility of each Activity via [enableEdgeToEdge()].
 * - Requirements: 24.1, 24.2, 24.3
 */

package com.aiassistant.core.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import com.aiassistant.core.ui.motion.rememberReducedMotion

/**
 * The root application theme composable.
 *
 * Wraps [content] in a fully configured [MaterialTheme] providing:
 * - Color scheme (dynamic Material You on API 31+, static fallback otherwise)
 * - Typography scale ([MaterialTypography])
 * - Shape system ([MaterialShapes])
 * - Spacing tokens ([LocalSpacing])
 *
 * @param themeMode   The [ThemeMode] to apply. Use [ThemeMode.SYSTEM] (the default) to
 *                    follow the device dark-mode switch.
 * @param dynamicColor When `true` (the default), enables Material You dynamic color on
 *                    Android 12+. Pass `false` to always use the static brand palette
 *                    (e.g., in Compose Previews or instrumented tests that need
 *                    deterministic colours).
 * @param content     The composable content rendered inside the theme.
 *
 * Usage in an Activity:
 * ```kotlin
 * setContent {
 *     val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
 *     AppTheme(themeMode = themeMode) {
 *         AppNavHost()
 *     }
 * }
 * ```
 *
 * Usage in a Compose Preview:
 * ```kotlin
 * @Preview
 * @Composable
 * private fun MyScreenPreview() {
 *     AppTheme(dynamicColor = false) {
 *         MyScreen()
 *     }
 * }
 * ```
 */
@Composable
fun AppTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current

    // â”€â”€â”€ Color scheme resolution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Material You dynamic color is available on Android 12+ (API 31+).
    // On older devices the static brand palette is used as the fallback.
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    // â”€â”€â”€ Composition locals â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val reducedMotion = rememberReducedMotion()

    // Task 50.8: Crossfade wraps the entire MaterialTheme so switching between
    // Light / Dark / System-default modes animates as a 400 ms fade rather than
    // an instant cut.  The colorScheme is the key � a new scheme object triggers
    // the transition.  When reduced motion is active the Crossfade duration
    // collapses to 0 ms (no animation) via the reducedMotion branch.
    val crossfadeDurationMs = if (reducedMotion) 0 else 400

    CompositionLocalProvider(
        LocalSpacing provides Spacing.Default,
        LocalElevation provides Elevation.Default,
        LocalReducedMotionEnabled provides reducedMotion,
    ) {
        Crossfade(
            targetState = colorScheme,
            animationSpec = tween(durationMillis = crossfadeDurationMs),
            label = "themeColorSchemeCrossfade",
        ) { animatedColorScheme ->
            MaterialTheme(
                colorScheme = animatedColorScheme,
                typography = MaterialTypography,
                shapes = MaterialShapes,
                content = content,
            )
        }
    }
}

// â”€â”€â”€ MaterialTheme convenience extensions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Retrieves the [Spacing] tokens from the current composition.
 *
 * Usage:
 * ```kotlin
 * val spacing = MaterialTheme.spacing
 * Modifier.padding(horizontal = spacing.md)
 * ```
 */
val MaterialTheme.spacing: Spacing
    @Composable
    @ReadOnlyComposable
    get() = LocalSpacing.current
