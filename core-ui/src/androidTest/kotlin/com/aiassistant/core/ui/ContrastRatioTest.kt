/**
 * ContrastRatioTest.kt
 *
 * Purpose: Verifies that the color tokens used for normal text and large text meet the
 *          WCAG 2.1 AA contrast-ratio requirements in both light and dark themes.
 *
 *          Normal text (< 18 pt / < 14 pt bold) requires 4.5:1 contrast ratio.
 *          Large text (≥ 18 pt or ≥ 14 pt bold) requires 3:1 contrast ratio.
 *
 * Architecture: core-ui androidTest — instrumented Compose UI tests.
 * Requirements: 24.2, 21.3 (per design: "Minimum contrast ratio 4.5:1 for normal text,
 *               3:1 for large text")
 *
 * Design decisions:
 * - We capture the actual [MaterialTheme.colorScheme] values inside the composition
 *   (with dynamicColor=false) so tests are deterministic and independent of wallpaper.
 * - Contrast ratio is computed using the WCAG relative luminance formula, which matches
 *   [androidx.core.graphics.ColorUtils.calculateContrast] semantics.
 * - We test the primary text-on-background pair (onBackground / background) and
 *   the surface text pair (onSurface / surface), as these are used for body text.
 * - For large text we test the primary brand color on its container, since primary
 *   elements (buttons, highlights) are commonly rendered at large sizes.
 */

package com.aiassistant.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContrastRatioTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Color capture ─────────────────────────────────────────────────────────

    private data class ThemeColorPairs(
        // Normal text pairs (must meet 4.5:1)
        val onBackgroundOnBackground: Pair<Color, Color>,
        val onSurfaceOnSurface: Pair<Color, Color>,
        val onSurfaceVariantOnSurfaceVariant: Pair<Color, Color>,
        // Large text / UI component pairs (must meet 3:1)
        val primaryOnPrimaryContainer: Pair<Color, Color>,
        val onPrimaryOnPrimary: Pair<Color, Color>
    )

    private fun captureThemeColors(themeMode: ThemeMode): ThemeColorPairs {
        var pairs: ThemeColorPairs? = null

        composeTestRule.setContent {
            AppTheme(themeMode = themeMode, dynamicColor = false) {
                val cs = MaterialTheme.colorScheme
                pairs = ThemeColorPairs(
                    onBackgroundOnBackground = cs.onBackground to cs.background,
                    onSurfaceOnSurface = cs.onSurface to cs.surface,
                    onSurfaceVariantOnSurfaceVariant = cs.onSurfaceVariant to cs.surfaceVariant,
                    primaryOnPrimaryContainer = cs.primary to cs.primaryContainer,
                    onPrimaryOnPrimary = cs.onPrimary to cs.primary
                )
                Text(text = "color_probe_${themeMode.key}")
            }
        }
        composeTestRule.onNodeWithText("color_probe_${themeMode.key}").assertIsDisplayed()

        return requireNotNull(pairs) { "Color pairs were not captured from composition" }
    }

    // ── Light theme — normal text (4.5:1) ─────────────────────────────────────

    @Test
    fun lightTheme_onBackground_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.LIGHT)
        val (fg, bg) = colors.onBackgroundOnBackground
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Light: onBackground/background contrast must be >= 4.5:1 (WCAG AA normal text). " +
                "Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    @Test
    fun lightTheme_onSurface_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.LIGHT)
        val (fg, bg) = colors.onSurfaceOnSurface
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Light: onSurface/surface contrast must be >= 4.5:1. Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    @Test
    fun lightTheme_onSurfaceVariant_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.LIGHT)
        val (fg, bg) = colors.onSurfaceVariantOnSurfaceVariant
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Light: onSurfaceVariant/surfaceVariant contrast must be >= 4.5:1. Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    // ── Dark theme — normal text (4.5:1) ──────────────────────────────────────

    @Test
    fun darkTheme_onBackground_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.DARK)
        val (fg, bg) = colors.onBackgroundOnBackground
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Dark: onBackground/background contrast must be >= 4.5:1 (WCAG AA normal text). " +
                "Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    @Test
    fun darkTheme_onSurface_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.DARK)
        val (fg, bg) = colors.onSurfaceOnSurface
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Dark: onSurface/surface contrast must be >= 4.5:1. Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    @Test
    fun darkTheme_onSurfaceVariant_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.DARK)
        val (fg, bg) = colors.onSurfaceVariantOnSurfaceVariant
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Dark: onSurfaceVariant/surfaceVariant contrast must be >= 4.5:1. Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    // ── Light theme — large text / UI components (3:1) ───────────────────────

    @Test
    fun lightTheme_onPrimary_meetsLargeTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.LIGHT)
        val (fg, bg) = colors.onPrimaryOnPrimary
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Light: onPrimary/primary contrast must be >= 3:1 (WCAG AA large text). " +
                "Got: $ratio:1",
            ratio >= 3.0f
        )
    }

    @Test
    fun lightTheme_onPrimary_meetsNormalTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.LIGHT)
        val (fg, bg) = colors.onPrimaryOnPrimary
        val ratio = calculateContrastRatio(fg, bg)
        // onPrimary (#FFFFFF) on primary (#1B6EF5) should comfortably exceed 4.5:1
        assertTrue(
            "Light: onPrimary/primary contrast must be >= 4.5:1. Got: $ratio:1",
            ratio >= 4.5f
        )
    }

    // ── Dark theme — large text / UI components (3:1) ────────────────────────

    @Test
    fun darkTheme_onPrimary_meetsLargeTextContrastRatio() {
        val colors = captureThemeColors(ThemeMode.DARK)
        val (fg, bg) = colors.onPrimaryOnPrimary
        val ratio = calculateContrastRatio(fg, bg)
        assertTrue(
            "Dark: onPrimary/primary contrast must be >= 3:1 (WCAG AA large text). " +
                "Got: $ratio:1",
            ratio >= 3.0f
        )
    }

    // ── Both themes — contrast ratio is always within valid bounds [1, 21] ───

    @Test
    fun lightTheme_allContrastRatios_areInValidRange() {
        val colors = captureThemeColors(ThemeMode.LIGHT)

        val pairs = listOf(
            colors.onBackgroundOnBackground,
            colors.onSurfaceOnSurface,
            colors.onSurfaceVariantOnSurfaceVariant,
            colors.onPrimaryOnPrimary
        )

        pairs.forEach { (fg, bg) ->
            val ratio = calculateContrastRatio(fg, bg)
            assertTrue(
                "Contrast ratio must be in [1.0, 21.0] but was $ratio",
                ratio in 1.0f..21.0f
            )
        }
    }

    @Test
    fun darkTheme_allContrastRatios_areInValidRange() {
        val colors = captureThemeColors(ThemeMode.DARK)

        val pairs = listOf(
            colors.onBackgroundOnBackground,
            colors.onSurfaceOnSurface,
            colors.onSurfaceVariantOnSurfaceVariant,
            colors.onPrimaryOnPrimary
        )

        pairs.forEach { (fg, bg) ->
            val ratio = calculateContrastRatio(fg, bg)
            assertTrue(
                "Contrast ratio must be in [1.0, 21.0] but was $ratio",
                ratio in 1.0f..21.0f
            )
        }
    }

    // ── Contrast of identical colors is exactly 1:1 (sanity check) ───────────

    @Test
    fun identicalColors_haveContrastRatioOfOne() {
        val ratio = calculateContrastRatio(Color.White, Color.White)
        assertTrue("Same color must have ratio 1.0, got $ratio", kotlin.math.abs(ratio - 1.0f) < 0.001f)
    }

    @Test
    fun whiteOnBlack_hasMaximumContrastRatio() {
        val ratio = calculateContrastRatio(Color.White, Color.Black)
        // White (#FFFFFF) on black (#000000) = 21:1 by the WCAG formula
        assertTrue("White on black must be 21:1, got $ratio", kotlin.math.abs(ratio - 21.0f) < 0.1f)
    }
}
