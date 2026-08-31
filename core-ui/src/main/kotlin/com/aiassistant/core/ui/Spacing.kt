/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Spacing.kt
 * Purpose    : Spacing — core-ui module component
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
 * File       : Spacing.kt
 * Purpose    : Spacing — core-ui module component
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
 * Spacing.kt
 *
 * Purpose: 8 dp-grid spacing system constants for the AI Assistant application.
 * Architecture: core-ui â€” shared design system.
 * Dependencies: Compose ui.unit.dp (only).
 *
 * Design decisions:
 * - All spacing values are multiples of 8 dp (the Material baseline grid unit). This
 *   ensures visual consistency: every margin, padding, gap, and component size aligns
 *   to the same grid, making the UI feel cohesive without additional design tooling.
 * - The [Spacing] object is accessible from within composables via the [MaterialTheme]
 *   extension property defined below, or directly as [Spacing.md] etc. from non-Compose
 *   Kotlin code.
 * - Half-step values (4 dp) are intentionally kept at the lowest end of the scale
 *   because some dense UI contexts (e.g., chip labels, icon padding) legitimately
 *   require sub-8-dp spacing. Values below 4 dp are not provided to discourage misuse.
 * - Requirements: 24.1, 24.3
 *
 * Usage inside a composable:
 * ```kotlin
 * Modifier.padding(
 *     horizontal = Spacing.lg,
 *     vertical   = Spacing.md,
 * )
 * ```
 */

package com.aiassistant.core.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Immutable container holding all spacing tokens aligned to the 8 dp baseline grid.
 *
 * Token naming convention:
 * | Token | Value | Use case |
 * |-------|-------|----------|
 * | [none]  |  0 dp | No spacing (explicit zero for clarity) |
 * | [xs]    |  4 dp | Dense UI: chip inner padding, icon inset |
 * | [sm]    |  8 dp | List item vertical padding, compact card inset |
 * | [md]    | 16 dp | Standard screen edge padding, card content padding |
 * | [lg]    | 24 dp | Section spacing, large card inset |
 * | [xl]    | 32 dp | Hero / display section vertical spacing |
 * | [xxl]   | 48 dp | Coarse vertical rhythm between major sections |
 * | [xxxl]  | 64 dp | Top/bottom of full-screen introductory screens |
 */
@Immutable
data class Spacing(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val screenEdge: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val xxxl: Dp = 64.dp
) {
    companion object {
        /** Default spacing instance â€” use this outside of a [androidx.compose.runtime.CompositionLocal] context. */
        val Default = Spacing()
    }
}

/**
 * [androidx.compose.runtime.CompositionLocal] providing the current [Spacing] in the composition tree.
 *
 * [AppTheme] provides a [Spacing.Default] instance so all composables in the theme
 * hierarchy can access spacing via [LocalSpacing].current without passing it explicitly.
 *
 * ```kotlin
 * val spacing = LocalSpacing.current
 * Modifier.padding(horizontal = spacing.md)
 * ```
 */
val LocalSpacing = staticCompositionLocalOf { Spacing.Default }
