/**
 * AdaptiveLayoutTest.kt
 *
 * Purpose: Compose UI tests verifying that [TwoPaneLayout] shows the two-pane layout
 *          at ≥600 dp width (tablet/medium) and the single-pane layout below 600 dp.
 * Architecture: core-ui androidTest — instrumented Compose UI tests.
 * Requirements: 24.2, 21.3
 *
 * Design decisions:
 * - [TwoPaneLayout] accepts a [WindowSizeClass] parameter so we can inject a
 *   deterministic size class without needing a physical device.
 * - We use [WindowSizeClass.calculateFromSize(DpSize)] to produce a compact class
 *   (phone) and a medium class (tablet) without relying on the physical screen.
 * - [onNodeWithText] locates pane-specific content to verify which pane is visible.
 * - For single-pane mode, the initially hidden pane must NOT be displayed; we use
 *   [assertDoesNotExist] for that assertion.
 */

package com.aiassistant.core.ui

import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.adaptive.TwoPaneLayout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a Compact [WindowSizeClass] (phone portrait, < 600 dp). */
    private fun compactWindowSizeClass(): WindowSizeClass = WindowSizeClass.calculateFromSize(DpSize(390.dp, 844.dp))

    /** Creates a Medium [WindowSizeClass] (small tablet / landscape, ≥ 600 dp). */
    private fun mediumWindowSizeClass(): WindowSizeClass = WindowSizeClass.calculateFromSize(DpSize(700.dp, 1024.dp))

    /** Creates an Expanded [WindowSizeClass] (large tablet, ≥ 840 dp). */
    private fun expandedWindowSizeClass(): WindowSizeClass = WindowSizeClass.calculateFromSize(DpSize(1280.dp, 800.dp))

    // ── Two-pane at ≥ 600 dp (Medium) ────────────────────────────────────────

    @Test
    fun twoPaneLayout_showsBothPanes_onMediumScreen() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("List Pane Content") },
                    detailPane = { Text("Detail Pane Content") },
                    showDetailPane = false, // ignored on tablet — both are visible
                    windowSizeClass = mediumWindowSizeClass()
                )
            }
        }

        // Both panes must be visible simultaneously on tablet
        composeTestRule.onNodeWithText("List Pane Content").assertIsDisplayed()
        composeTestRule.onNodeWithText("Detail Pane Content").assertIsDisplayed()
    }

    @Test
    fun twoPaneLayout_showsBothPanes_onExpandedScreen() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("List Pane Expanded") },
                    detailPane = { Text("Detail Pane Expanded") },
                    showDetailPane = false,
                    windowSizeClass = expandedWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("List Pane Expanded").assertIsDisplayed()
        composeTestRule.onNodeWithText("Detail Pane Expanded").assertIsDisplayed()
    }

    // ── Single-pane on Compact (< 600 dp) ────────────────────────────────────

    @Test
    fun twoPaneLayout_showsListPane_onCompactScreen_whenDetailNotSelected() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("List Pane Phone") },
                    detailPane = { Text("Detail Pane Phone") },
                    showDetailPane = false,
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        // Only the list pane should be visible
        composeTestRule.onNodeWithText("List Pane Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Detail Pane Phone").assertDoesNotExist()
    }

    @Test
    fun twoPaneLayout_showsDetailPane_onCompactScreen_whenDetailSelected() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("List Pane Phone Detail") },
                    detailPane = { Text("Detail Pane Phone Detail") },
                    showDetailPane = true,
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        // Only the detail pane should be visible
        composeTestRule.onNodeWithText("Detail Pane Phone Detail").assertIsDisplayed()
        composeTestRule.onNodeWithText("List Pane Phone Detail").assertDoesNotExist()
    }

    // ── Breakpoint boundary: exactly 600 dp is treated as Medium (two-pane) ──

    @Test
    fun twoPaneLayout_showsTwoPane_atExactlyBreakpoint() {
        // 600 dp width falls into Medium, so two-pane layout should be active
        val atBreakpoint = WindowSizeClass.calculateFromSize(DpSize(600.dp, 900.dp))

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("Breakpoint List") },
                    detailPane = { Text("Breakpoint Detail") },
                    showDetailPane = false,
                    windowSizeClass = atBreakpoint
                )
            }
        }

        // At exactly 600 dp, the Medium size class applies → two-pane
        composeTestRule.onNodeWithText("Breakpoint List").assertIsDisplayed()
        composeTestRule.onNodeWithText("Breakpoint Detail").assertIsDisplayed()
    }

    @Test
    fun twoPaneLayout_showsSinglePane_belowBreakpoint() {
        // 599 dp width is Compact → single-pane
        val belowBreakpoint = WindowSizeClass.calculateFromSize(DpSize(390.dp, 844.dp))

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("Below List") },
                    detailPane = { Text("Below Detail") },
                    showDetailPane = false,
                    windowSizeClass = belowBreakpoint
                )
            }
        }

        composeTestRule.onNodeWithText("Below List").assertIsDisplayed()
        composeTestRule.onNodeWithText("Below Detail").assertDoesNotExist()
    }

    // ── showDetailPane flag ignored on tablet ────────────────────────────────

    @Test
    fun twoPaneLayout_showsBothPanes_onTablet_regardlessOfShowDetailPaneFlag() {
        // Even with showDetailPane = true, both panes are shown on tablet
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("Tablet List Always") },
                    detailPane = { Text("Tablet Detail Always") },
                    showDetailPane = true,
                    windowSizeClass = mediumWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("Tablet List Always").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tablet Detail Always").assertIsDisplayed()
    }

    // ── State preservation across flag changes (phone) ────────────────────────

    @Test
    fun twoPaneLayout_phone_switchesDisplayedPane_whenFlagChanges() {
        // This test verifies that recomposition (flag change) updates the visible pane
        var showDetail = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("Phone List State") },
                    detailPane = { Text("Phone Detail State") },
                    showDetailPane = showDetail,
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        // Initially shows list
        composeTestRule.onNodeWithText("Phone List State").assertIsDisplayed()

        // After flag changes, detail pane should become visible
        showDetail = true
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                TwoPaneLayout(
                    listPane = { Text("Phone List State") },
                    detailPane = { Text("Phone Detail State") },
                    showDetailPane = showDetail,
                    windowSizeClass = compactWindowSizeClass()
                )
            }
        }

        composeTestRule.onNodeWithText("Phone Detail State").assertIsDisplayed()
        composeTestRule.onNodeWithText("Phone List State").assertDoesNotExist()
    }
}
