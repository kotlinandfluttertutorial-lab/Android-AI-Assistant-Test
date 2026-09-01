/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Elevation.kt
 * Purpose    : Six-tier elevation constant system for the AI Assistant design
 *              system.  Provides consistent dp values for every shadow / tonal
 *              elevation layer used across all feature screens.
 *
 * Architecture Layer : Core-UI — design system foundation.
 *                      Consumed by all feature composables via
 *                      MaterialTheme.elevation or the LocalElevation composition
 *                      local.  Never referenced from domain or data layers.
 *
 * Dependencies       : Compose ui.unit.dp only.
 *
 * Design Decision    : M3 specifies elevation through tonal color overlays rather
 *                      than drop shadows.  These six values map directly to M3's
 *                      five named elevation levels (0, 1, 2, 3, 4, 5) plus a
 *                      "toast" outlier used for snackbars and overlays.  Using
 *                      named constants (none/low/mid/high/modal/toast) instead of
 *                      raw dp values prevents accidental use of off-system values
 *                      and communicates intent at the call site.
 *
 * Requirements       : 24.1, 24.3
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Six-tier elevation system aligned to Material Design 3's tonal elevation model.
 *
 * | Token  | Value | Use case |
 * |--------|-------|----------|
 * | [none]  |  0 dp | Flat surfaces: list rows, bottom sheets at rest |
 * | [low]   |  1 dp | Cards at rest, surface tonal level 1 |
 * | [mid]   |  3 dp | Raised cards, FAB shadow, tonal level 2 |
 * | [high]  |  6 dp | Pressed state cards, navigation drawer |
 * | [modal] | 12 dp | Dialogs, bottom sheets while animating, menus |
 * | [toast] | 24 dp | Snackbars, toasts — highest layer |
 *
 * Usage inside a composable:
 * ```kotlin
 * Card(elevation = CardDefaults.cardElevation(defaultElevation = MaterialTheme.elevation.low))
 * ```
 */
@Immutable
data class Elevation(
    val none: Dp = 0.dp,
    val low: Dp = 1.dp,
    val mid: Dp = 3.dp,
    val high: Dp = 6.dp,
    val modal: Dp = 12.dp,
    val toast: Dp = 24.dp
) {
    companion object {
        /** Default elevation instance — use this outside a [CompositionLocal] context. */
        val Default = Elevation()
    }
}

/**
 * [CompositionLocal] providing the current [Elevation] in the composition tree.
 *
 * [AppTheme] provides [Elevation.Default] so all composables in the theme
 * hierarchy can access elevation tokens without explicit parameter passing.
 *
 * ```kotlin
 * val elevation = LocalElevation.current
 * Card(elevation = CardDefaults.cardElevation(defaultElevation = elevation.low))
 * ```
 */
val LocalElevation = compositionLocalOf { Elevation.Default }

/**
 * Retrieves [Elevation] tokens from the current composition via a [MaterialTheme]
 * extension property — mirrors the pattern used by [MaterialTheme.spacing].
 *
 * ```kotlin
 * Card(elevation = CardDefaults.cardElevation(MaterialTheme.elevation.mid))
 * ```
 */
val MaterialTheme.elevation: Elevation
    @Composable
    @ReadOnlyComposable
    get() = LocalElevation.current
