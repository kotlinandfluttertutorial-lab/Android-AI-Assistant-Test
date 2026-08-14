/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : SettingsFlowTest.kt
 * Purpose    : Compose UI integration tests for the settings navigation flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.ThemeMode
import com.aiassistant.feature.settings.LlmProvider
import com.aiassistant.feature.settings.NotificationCategory
import com.aiassistant.feature.settings.SettingsScreen
import com.aiassistant.feature.settings.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the Settings screen.
 *
 * Calls [SettingsScreen] directly — it is a public composable accessible from the app module.
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helper: build a default Settings state ────────────────────────────────

    private fun defaultSettings() = SettingsUiState.Settings(
        activeProvider = LlmProvider.OPENAI_GPT4O,
        themeMode = ThemeMode.SYSTEM,
        notificationCategories = listOf(
            NotificationCategory("rag", "Document Ingestion", enabled = true)
        ),
        privacyModeEnabled = false
    )

    // ── Helper: set SettingsScreen with given state ───────────────────────────

    private fun setSettings(
        uiState: SettingsUiState = defaultSettings(),
        onNavigateUp: () -> Unit = {},
        onProviderSelected: (LlmProvider) -> Unit = {},
        onThemeSelected: (ThemeMode) -> Unit = {},
        onNotificationToggle: (String, Boolean) -> Unit = { _, _ -> },
        onPrivacyModeToggle: (Boolean) -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SettingsScreen(
                    uiState = uiState,
                    onNavigateUp = onNavigateUp,
                    onProviderSelected = onProviderSelected,
                    onThemeSelected = onThemeSelected,
                    onNotificationToggle = onNotificationToggle,
                    onPrivacyModeToggle = onPrivacyModeToggle,
                    onRetry = onRetry
                )
            }
        }
    }

    // ── 1. Loading state shows spinner ────────────────────────────────────────

    @Test
    fun settings_loading_showsSpinner() {
        setSettings(uiState = SettingsUiState.Loading)

        composeTestRule.onNodeWithContentDescription("Loading settings").assertIsDisplayed()
    }

    // ── 2. Loaded state shows provider dropdown ───────────────────────────────

    @Test
    fun settings_loaded_showsProviderDropdown() {
        setSettings()

        composeTestRule.onNodeWithContentDescription("LLM provider selector").assertIsDisplayed()
    }

    // ── 3. Loaded state shows theme selector ──────────────────────────────────

    @Test
    fun settings_loaded_showsThemeSelector() {
        setSettings()

        composeTestRule.onNodeWithContentDescription("Theme mode selector").assertIsDisplayed()
    }

    // ── 4. Tapping Light chip fires onThemeSelected(LIGHT) ───────────────────

    @Test
    fun settings_lightChip_tap_firesCallback() {
        var selected: ThemeMode? = null

        setSettings(onThemeSelected = { selected = it })

        composeTestRule.onNodeWithContentDescription("Select light theme").performClick()

        assert(selected == ThemeMode.LIGHT) {
            "Expected ThemeMode.LIGHT but got $selected"
        }
    }

    // ── 5. Tapping Dark chip fires onThemeSelected(DARK) ─────────────────────

    @Test
    fun settings_darkChip_tap_firesCallback() {
        var selected: ThemeMode? = null

        setSettings(onThemeSelected = { selected = it })

        composeTestRule.onNodeWithContentDescription("Select dark theme").performClick()

        assert(selected == ThemeMode.DARK) {
            "Expected ThemeMode.DARK but got $selected"
        }
    }

    // ── 6. Tapping System chip fires onThemeSelected(SYSTEM) ─────────────────

    @Test
    fun settings_systemChip_tap_firesCallback() {
        var selected: ThemeMode? = null

        setSettings(onThemeSelected = { selected = it })

        composeTestRule.onNodeWithContentDescription("Use system default theme").performClick()

        assert(selected == ThemeMode.SYSTEM) {
            "Expected ThemeMode.SYSTEM but got $selected"
        }
    }

    // ── 7. Tapping privacy toggle fires onPrivacyModeToggle ───────────────────

    @Test
    fun settings_privacyToggle_tap_firesCallback() {
        var toggleCalled = false

        setSettings(onPrivacyModeToggle = { toggleCalled = true })

        composeTestRule.onNodeWithContentDescription("Toggle privacy mode").performClick()

        assert(toggleCalled) { "Expected privacy mode toggle callback to be triggered" }
    }

    // ── 8. Tapping back arrow fires onNavigateUp ──────────────────────────────

    @Test
    fun settings_navigateBack_tap_firesCallback() {
        var navigatedUp = false

        setSettings(onNavigateUp = { navigatedUp = true })

        composeTestRule.onNodeWithContentDescription("Navigate back from Settings").performClick()

        assert(navigatedUp) { "Expected navigate-up callback to be triggered" }
    }

    // ── 9. Error state shows retry button ────────────────────────────────────

    @Test
    fun settings_error_showsRetryButton() {
        setSettings(uiState = SettingsUiState.Error("Failed"))

        composeTestRule.onNodeWithContentDescription("Retry loading settings").assertIsDisplayed()
    }

    // ── 10. Tapping retry fires onRetry ──────────────────────────────────────

    @Test
    fun settings_error_tapRetry_firesCallback() {
        var retryCalled = false

        setSettings(
            uiState = SettingsUiState.Error("Failed"),
            onRetry = { retryCalled = true }
        )

        composeTestRule.onNodeWithContentDescription("Retry loading settings").performClick()

        assert(retryCalled) { "Expected onRetry callback to be triggered" }
    }
}
