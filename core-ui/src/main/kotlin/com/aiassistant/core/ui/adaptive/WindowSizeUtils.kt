/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : WindowSizeUtils.kt
 * Purpose    : WindowSizeUtils — core-ui module component
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
 * File       : WindowSizeUtils.kt
 * Purpose    : WindowSizeUtils — core-ui module component
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
 * WindowSizeUtils.kt
 *
 * Purpose: Utilities for adaptive layout decisions based on window size class.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: Compose Material3 WindowSizeClass.
 * Requirements: 23.3, 24.4
 */
package com.aiassistant.core.ui.adaptive

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/** Minimum width in dp for the tablet two-pane layout. */
const val TABLET_BREAKPOINT_DP = 600

/**
 * Returns true when the window width is Medium or Expanded (>=600 dp).
 * Used to switch between single-pane (phone) and two-pane (tablet) layouts.
 */
val WindowSizeClass.isTabletLayout: Boolean
    @Composable
    @ReadOnlyComposable
    get() = widthSizeClass != WindowWidthSizeClass.Compact

/** Returns true when the window width is Compact (phones in portrait). */
val WindowSizeClass.isCompact: Boolean
    @Composable
    @ReadOnlyComposable
    get() = widthSizeClass == WindowWidthSizeClass.Compact

/** Returns true when the window width is Medium (small tablets, phones in landscape). */
val WindowSizeClass.isMedium: Boolean
    @Composable
    @ReadOnlyComposable
    get() = widthSizeClass == WindowWidthSizeClass.Medium

/** Returns true when the window width is Expanded (large tablets). */
val WindowSizeClass.isExpanded: Boolean
    @Composable
    @ReadOnlyComposable
    get() = widthSizeClass == WindowWidthSizeClass.Expanded
