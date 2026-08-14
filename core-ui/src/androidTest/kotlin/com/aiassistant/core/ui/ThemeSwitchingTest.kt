/**
 * ThemeSwitchingTest.kt
 *
 * Purpose: Compose UI tests verifying that [AppTheme] switches color tokens correctly
 *          between light and dark modes.
 * Architecture: core-ui androidTest — instrumented Compose UI tests.
 * Requirements: 24.2, 21.3
 *
 * Design decisions:
 * - Uses [createComposeRule] (no Activity host required) to set content directly.
 * - [dynamicColor = false] ensures the static [LightColorScheme] / [DarkColorScheme]
 *   tokens are active, giving deterministic color values to assert against.
 * - Captures the color scheme inside the composition via [MaterialTheme.colorScheme]
 *   and compares primary / background / onBackground tokens between modes.
 */

package com.aiassistant.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSwitchingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Captures the color scheme values inside the composition after [AppTheme] is applied.
     * Returns a snapshot triple (primary, background, onBackground) for assertion.
     */
    private data class ColorSnapshot(
        val primary: Color,
        val background: Color,
        val onBackground: Color,
        val surface: Color,
        val onSurface: Color
    )

    private fun captureColors(themeMode: ThemeMode): ColorSnapshot {
        var snapshot =
            ColorSnapshot(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)

        composeTestRule.setContent {
            AppTheme(themeMode = themeMode, dynamicColor = false) {
                // Capture colors from inside the composition
                val colorScheme = MaterialTheme.colorScheme
                snapshot = ColorSnapshot(
                    primary = colorScheme.primary,
                    background = colorScheme.background,
                    onBackground = colorScheme.onBackground,
                    surface = colorScheme.surface,
                    onSurface = colorScheme.onSurface
                )
                Text(text = "theme_probe", color = colorScheme.onBackground)
            }
        }

        // Ensure the composable actually rendered
        composeTestRule.onNodeWithText("theme_probe").assertIsDisplayed()

        return snapshot
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun lightTheme_rendersWithLightColorTokens() {
        val colors = captureColors(ThemeMode.LIGHT)

        // Light background should be very light — near-white (Neutral99 = 0xFFFEFBFF)
        // We verify it's not a dark color (luminance > 0.5 is a reasonable proxy)
        val bgLuminance = colors.background.luminance()
        assert(bgLuminance > 0.5f) {
            "Light theme background luminance should be > 0.5, was $bgLuminance"
        }
    }

    @Test
    fun darkTheme_rendersWithDarkColorTokens() {
        val colors = captureColors(ThemeMode.DARK)

        // Dark background should be very dark — near-black (Neutral10 = 0xFF1A1B1F)
        val bgLuminance = colors.background.luminance()
        assert(bgLuminance < 0.1f) {
            "Dark theme background luminance should be < 0.1, was $bgLuminance"
        }
    }

    @Test
    fun lightAndDarkTheme_haveDistinctPrimaryColors() {
        val light = captureColors(ThemeMode.LIGHT)
        val dark = captureColors(ThemeMode.DARK)

        // Primary colors must differ between modes (Brand40 vs Brand80)
        assertNotEquals(
            "Light and dark primary colors must differ",
            light.primary,
            dark.primary
        )
    }

    @Test
    fun lightAndDarkTheme_haveDistinctBackgroundColors() {
        val light = captureColors(ThemeMode.LIGHT)
        val dark = captureColors(ThemeMode.DARK)

        assertNotEquals(
            "Light and dark background colors must differ",
            light.background,
            dark.background
        )
    }

    @Test
    fun lightAndDarkTheme_haveDistinctOnBackgroundColors() {
        val light = captureColors(ThemeMode.LIGHT)
        val dark = captureColors(ThemeMode.DARK)

        assertNotEquals(
            "Light and dark onBackground colors must differ",
            light.onBackground,
            dark.onBackground
        )
    }

    @Test
    fun lightTheme_onBackgroundHasHighContrastOnBackground() {
        val colors = captureColors(ThemeMode.LIGHT)

        // In the light scheme, onBackground is Neutral10 (~#1A1B1F) on Neutral99 (~#FEFBFF)
        // This pair should pass 4.5:1 WCAG AA for normal text
        val ratio = calculateContrastRatio(colors.onBackground, colors.background)
        assert(ratio >= 4.5f) {
            "Light theme onBackground/background contrast ratio must be >= 4.5:1, was $ratio"
        }
    }

    @Test
    fun darkTheme_onBackgroundHasHighContrastOnBackground() {
        val colors = captureColors(ThemeMode.DARK)

        // In the dark scheme, onBackground is Neutral90 (~#E3E2E6) on Neutral10 (~#1A1B1F)
        val ratio = calculateContrastRatio(colors.onBackground, colors.background)
        assert(ratio >= 4.5f) {
            "Dark theme onBackground/background contrast ratio must be >= 4.5:1, was $ratio"
        }
    }

    @Test
    fun appTheme_withThemeModeLight_showsContent() {
        composeTestRule.setContent {
            AppTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Text(text = "Light content")
            }
        }
        composeTestRule.onNodeWithText("Light content").assertIsDisplayed()
    }

    @Test
    fun appTheme_withThemeModeDark_showsContent() {
        composeTestRule.setContent {
            AppTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                Text(text = "Dark content")
            }
        }
        composeTestRule.onNodeWithText("Dark content").assertIsDisplayed()
    }

    @Test
    fun appTheme_withThemeModeSystem_showsContent() {
        composeTestRule.setContent {
            AppTheme(themeMode = ThemeMode.SYSTEM, dynamicColor = false) {
                Text(text = "System content")
            }
        }
        composeTestRule.onNodeWithText("System content").assertIsDisplayed()
    }
}

// ── Contrast utilities (local — avoid android.graphics.Color dependency issues) ─

/**
 * Computes the relative luminance of a Compose [Color] using the WCAG formula.
 * https://www.w3.org/TR/WCAG20/#relativeluminancedef
 */
internal fun Color.luminance(): Float {
    fun linearize(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    val r = linearize(red)
    val g = linearize(green)
    val b = linearize(blue)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun Float.pow(exp: Double): Float = Math.pow(this.toDouble(), exp).toFloat()

/**
 * Computes the WCAG contrast ratio between [foreground] and [background].
 * Returns a value in [1.0, 21.0].
 */
internal fun calculateContrastRatio(foreground: Color, background: Color): Float {
    val l1 = foreground.luminance()
    val l2 = background.luminance()
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}
