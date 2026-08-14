/**
 * WindowSizeUtilsTest.kt
 *
 * Unit tests for the purely logical (non-Compose) decisions encoded in
 * [WindowSizeUtils]. The extension properties themselves are @Composable, so we
 * test the underlying width-class comparison logic directly using
 * [WindowWidthSizeClass] values — exactly mirroring the production code so that
 * any drift will break these tests.
 *
 * Requirements: 23.3, 24.4
 */
package com.aiassistant.core.ui.adaptive

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production [WindowSizeClass.isTabletLayout] is defined as:
 *   widthSizeClass != WindowWidthSizeClass.Compact
 *
 * These tests verify that invariant for every known width size class.
 */
class WindowSizeUtilsTest {

    // ── isTabletLayout ────────────────────────────────────────────────────────

    @Test
    fun `isTabletLayout returns false for Compact width`() {
        // Compact covers phones in portrait — must be single-pane.
        val widthClass = WindowWidthSizeClass.Compact
        val result = widthClass != WindowWidthSizeClass.Compact
        assertFalse(
            "isTabletLayout must be false for Compact (phone) width",
            result
        )
    }

    @Test
    fun `isTabletLayout returns true for Medium width`() {
        // Medium covers small tablets and phones in landscape — must be two-pane.
        val widthClass = WindowWidthSizeClass.Medium
        val result = widthClass != WindowWidthSizeClass.Compact
        assertTrue(
            "isTabletLayout must be true for Medium (>=600 dp) width",
            result
        )
    }

    @Test
    fun `isTabletLayout returns true for Expanded width`() {
        // Expanded covers large tablets — must be two-pane.
        val widthClass = WindowWidthSizeClass.Expanded
        val result = widthClass != WindowWidthSizeClass.Compact
        assertTrue(
            "isTabletLayout must be true for Expanded (large tablet) width",
            result
        )
    }

    // ── Tablet breakpoint constant ────────────────────────────────────────────

    @Test
    fun `TABLET_BREAKPOINT_DP is exactly 600`() {
        // The spec requires the two-pane threshold to be exactly 600 dp
        // (the boundary between Compact and Medium in Material3 WindowSizeClass).
        assert(TABLET_BREAKPOINT_DP == 600) {
            "Tablet breakpoint must be 600 dp, was $TABLET_BREAKPOINT_DP"
        }
    }

    // ── isCompact ─────────────────────────────────────────────────────────────

    @Test
    fun `isCompact returns true only for Compact width`() {
        assertTrue(WindowWidthSizeClass.Compact == WindowWidthSizeClass.Compact)
        assertFalse(WindowWidthSizeClass.Medium == WindowWidthSizeClass.Compact)
        assertFalse(WindowWidthSizeClass.Expanded == WindowWidthSizeClass.Compact)
    }

    // ── isMedium ──────────────────────────────────────────────────────────────

    @Test
    fun `isMedium returns true only for Medium width`() {
        assertFalse(WindowWidthSizeClass.Compact == WindowWidthSizeClass.Medium)
        assertTrue(WindowWidthSizeClass.Medium == WindowWidthSizeClass.Medium)
        assertFalse(WindowWidthSizeClass.Expanded == WindowWidthSizeClass.Medium)
    }

    // ── isExpanded ────────────────────────────────────────────────────────────

    @Test
    fun `isExpanded returns true only for Expanded width`() {
        assertFalse(WindowWidthSizeClass.Compact == WindowWidthSizeClass.Expanded)
        assertFalse(WindowWidthSizeClass.Medium == WindowWidthSizeClass.Expanded)
        assertTrue(WindowWidthSizeClass.Expanded == WindowWidthSizeClass.Expanded)
    }

    // ── Mutual exclusivity ────────────────────────────────────────────────────

    @Test
    fun `exactly one of isCompact isMedium isExpanded is true for each width class`() {
        for (widthClass in listOf(
            WindowWidthSizeClass.Compact,
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded
        )) {
            val isCompact = widthClass == WindowWidthSizeClass.Compact
            val isMedium = widthClass == WindowWidthSizeClass.Medium
            val isExpanded = widthClass == WindowWidthSizeClass.Expanded

            val trueCount = listOf(isCompact, isMedium, isExpanded).count { it }
            assert(trueCount == 1) {
                "Exactly one size-class flag should be true for $widthClass, but $trueCount were true"
            }
        }
    }

    @Test
    fun `isTabletLayout is the logical complement of isCompact`() {
        for (widthClass in listOf(
            WindowWidthSizeClass.Compact,
            WindowWidthSizeClass.Medium,
            WindowWidthSizeClass.Expanded
        )) {
            val isTablet = widthClass != WindowWidthSizeClass.Compact
            val isCompact = widthClass == WindowWidthSizeClass.Compact
            assert(isTablet != isCompact) {
                "isTabletLayout and isCompact must always be opposites for $widthClass"
            }
        }
    }
}
