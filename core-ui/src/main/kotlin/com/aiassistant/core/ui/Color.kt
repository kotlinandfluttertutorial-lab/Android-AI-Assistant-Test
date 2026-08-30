/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Color.kt
 * Purpose    : Color — core-ui module component
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
 * File       : Color.kt
 * Purpose    : Color — core-ui module component
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
 * Color.kt
 *
 * Purpose: Material Design 3 color tokens for the AI Assistant application.
 * Architecture: core-ui â€” shared design system.
 * Dependencies: Compose Material 3 color APIs.
 *
 * Design decisions:
 * - Two complete color schemes are defined (light + dark) so the [AppTheme] composable
 *   can swap them without any runtime computation.
 * - On Android 12+ (API 31+) these static schemes are replaced by a wallpaper-derived
 *   dynamic palette via [dynamicColorScheme]; these static tokens serve as the fallback
 *   for pre-Android-12 devices.
 * - All raw hex values are named after their Material 3 role (primary, secondary, etc.)
 *   rather than their visual appearance (e.g., "blue"). Roles are stable; colours change.
 * - Minimum contrast is validated externally via Compose UI tests (task 3.4).
 * - Requirements: 24.1, 24.3
 */

package com.aiassistant.core.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// â”€â”€â”€ Brand palette â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Raw hex values; these should only be referenced by the role-named tokens below.

private val Brand40 = Color(0xFF1B6EF5) // Primary-40
private val Brand80 = Color(0xFFADC6FF) // Primary-80  (dark-mode primary)
private val Brand20 = Color(0xFF0047CA) // Primary-20  (dark-mode on-primary-container)
private val Brand90 = Color(0xFFD8E2FF) // Primary-90  (primary-container)

private val Secondary40 = Color(0xFF565E7C)
private val Secondary80 = Color(0xFFBDC3E6)
private val Secondary20 = Color(0xFF3F4662)
private val Secondary90 = Color(0xFFDDE1FF)

private val Tertiary40 = Color(0xFF705572)
private val Tertiary80 = Color(0xFFDDB9DF)
private val Tertiary20 = Color(0xFF553D58)
private val Tertiary90 = Color(0xFFFBD7FD)

private val Error40 = Color(0xFFBA1A1A)
private val Error80 = Color(0xFFFFB4AB)
private val Error20 = Color(0xFF93000A)
private val Error90 = Color(0xFFFFDAD6)

private val Neutral10 = Color(0xFF1A1B1F) // Darkest surface
private val Neutral20 = Color(0xFF2F3033)
private val Neutral90 = Color(0xFFE3E2E6)
private val Neutral95 = Color(0xFFF1F0F4)
private val Neutral99 = Color(0xFFFEFBFF)
private val Neutral100 = Color(0xFFFFFFFF)

private val NeutralVariant30 = Color(0xFF44474F)
private val NeutralVariant50 = Color(0xFF74777F)
private val NeutralVariant60 = Color(0xFF8E9099)
private val NeutralVariant80 = Color(0xFFC4C6D0)
private val NeutralVariant90 = Color(0xFFE3E2EC)

// ── Warning / amber (Phase 14 — DevOps severity badges) ──────────────────────
// Material 3 does not have a built-in "warning" role; we add one as a custom
// extension token following the same naming convention (Amber40 / Amber80 / etc.)

internal val Warning40 = Color(0xFFB25B00) // warning on light background
internal val Warning80 = Color(0xFFFFB956) // warning on dark background
internal val Warning90 = Color(0xFFFFDDB3) // warning container (light)
internal val Warning20 = Color(0xFF5B2D00) // on-warning-container (light)
internal val WarningDark = Color(0xFF3A1D00) // warning container (dark)

// â”€â”€â”€ Light color scheme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Static light [ColorScheme] used on API < 31 or when Material You dynamic color is
 * disabled by the user.
 *
 * Role mapping follows the Material Design 3 specification exactly so tooling that
 * expects named roles (e.g., the M3 Theme Builder) remains compatible.
 */
internal val LightColorScheme = lightColorScheme(
    primary = Brand40,
    onPrimary = Neutral100,
    primaryContainer = Brand90,
    onPrimaryContainer = Brand20,

    secondary = Secondary40,
    onSecondary = Neutral100,
    secondaryContainer = Secondary90,
    onSecondaryContainer = Secondary20,

    tertiary = Tertiary40,
    onTertiary = Neutral100,
    tertiaryContainer = Tertiary90,
    onTertiaryContainer = Tertiary20,

    error = Error40,
    onError = Neutral100,
    errorContainer = Error90,
    onErrorContainer = Error20,

    background = Neutral99,
    onBackground = Neutral10,

    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,

    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,

    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Brand80,

    scrim = Neutral10
)

// â”€â”€â”€ Dark color scheme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Static dark [ColorScheme] used on API < 31 or when Material You dynamic color is
 * disabled.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = Brand80,
    onPrimary = Brand20,
    primaryContainer = Color(0xFF0047CA),
    onPrimaryContainer = Brand90,

    secondary = Secondary80,
    onSecondary = Secondary20,
    secondaryContainer = Color(0xFF3F4662),
    onSecondaryContainer = Secondary90,

    tertiary = Tertiary80,
    onTertiary = Tertiary20,
    tertiaryContainer = Color(0xFF553D58),
    onTertiaryContainer = Tertiary90,

    error = Error80,
    onError = Error20,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Error90,

    background = Neutral10,
    onBackground = Neutral90,

    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,

    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Brand40,

    scrim = Neutral10
)
