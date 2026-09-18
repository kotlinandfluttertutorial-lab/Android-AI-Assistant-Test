/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ConnectivityStatusBarTest.kt
 * Purpose    : Compose UI tests for ConnectivityStatusBar (Phase 14.7).
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import com.aiassistant.core.ui.components.ConnectivityState
import com.aiassistant.core.ui.components.ConnectivityStatusBar

class ConnectivityStatusBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun offlineState_visible_displaysOfflineMessage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ConnectivityStatusBar(
                    state = ConnectivityState.OFFLINE,
                    visible = true
                )
            }
        }
        composeTestRule.onNodeWithText("You're offline").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("You are offline. Some features are unavailable.")
            .assertIsDisplayed()
    }

    @Test
    fun offlineState_notVisible_isHidden() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ConnectivityStatusBar(
                    state = ConnectivityState.OFFLINE,
                    visible = false
                )
            }
        }
        composeTestRule.onNodeWithText("You're offline").assertDoesNotExist()
    }

    @Test
    fun syncingState_visible_displaysSyncingMessage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ConnectivityStatusBar(
                    state = ConnectivityState.SYNCING,
                    visible = true
                )
            }
        }
        composeTestRule.onNodeWithText("Syncing\u2026").assertIsDisplayed()
    }

    @Test
    fun onlineState_visible_displaysBackOnlineMessage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ConnectivityStatusBar(
                    state = ConnectivityState.ONLINE,
                    visible = true,
                    onDismiss = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Back online").assertIsDisplayed()
    }
}
