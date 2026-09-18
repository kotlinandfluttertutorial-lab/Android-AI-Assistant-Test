/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ModelStatusCardTest.kt
 * Purpose    : Compose UI tests for ModelStatusCard (Phase 14.5).
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import com.aiassistant.core.ui.components.ModelStatus
import com.aiassistant.core.ui.components.ModelStatusCard
import org.junit.Assert.assertTrue

class ModelStatusCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun readyState_displaysReadyBadge() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Ready
                )
            }
        }
        composeTestRule.onNodeWithText("Gemma 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun downloadingState_displaysDownloadingBadgeAndProgress() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Downloading(progressPercent = 45)
                )
            }
        }
        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Downloading: 45%").assertIsDisplayed()
    }

    @Test
    fun loadingState_displaysLoadingBadge() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Loading
                )
            }
        }
        composeTestRule.onNodeWithText("Loading").assertIsDisplayed()
    }

    @Test
    fun errorState_displaysErrorBadgeAndRetryButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Error("Download failed"),
                    onManage = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun unavailableState_displaysUnavailableBadge_noActionButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Unavailable
                )
            }
        }
        composeTestRule.onNodeWithText("Unavailable").assertIsDisplayed()
        // Action buttons should not be shown for unavailable
        composeTestRule.onNodeWithText("Download").assertDoesNotExist()
    }

    @Test
    fun readyState_manageButton_callsOnManage() {
        var manageCalled = false
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Ready,
                    onManage = { manageCalled = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Manage").performClick()
        assertTrue(manageCalled)
    }

    @Test
    fun storageSizeLabel_isDisplayedWhenProvided() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ModelStatusCard(
                    modelDisplayName = "Gemma 3",
                    status = ModelStatus.Ready,
                    storageSizeLabel = "1.8 GB"
                )
            }
        }
        composeTestRule.onNodeWithText("1.8 GB").assertIsDisplayed()
    }
}
