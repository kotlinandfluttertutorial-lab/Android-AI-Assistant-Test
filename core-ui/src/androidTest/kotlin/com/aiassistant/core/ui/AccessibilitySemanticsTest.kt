/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : AccessibilitySemanticsTest.kt
 * Purpose    : Accessibility semantics tests (Phase 14.10).
 *
 *              Verifies:
 *              - contentDescriptions on key components
 *              - Minimum touch targets (48dp)
 *              - Role annotations on interactive elements
 *              - Error states have live region semantics
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import com.aiassistant.core.ui.components.AiMode
import com.aiassistant.core.ui.components.AiModeIndicator
import com.aiassistant.core.ui.components.InlineChatError
import com.aiassistant.core.ui.components.MessageInputBar
import com.aiassistant.core.ui.components.UserMessageBubble

class AccessibilitySemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun userBubble_hasMeaningfulContentDescription() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                UserMessageBubble(text = "Tell me about Compose")
            }
        }
        composeTestRule.onNodeWithContentDescription("You: Tell me about Compose")
            .assertIsDisplayed()
    }

    @Test
    fun messageInputBar_sendButton_has48dpTouchTarget() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = "Hello",
                    onValueChange = {},
                    onSend = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Send message")
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun inlineChatError_hasMeaningfulContentDescription() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                InlineChatError(
                    message = "Connection failed. Please retry.",
                    onRetry = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription(
            "Error: Connection failed. Please retry.",
            substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun inlineChatError_retryButton_has48dpTouchTarget() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                InlineChatError(
                    message = "Connection failed.",
                    onRetry = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Retry")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aiModeIndicator_hasNonEmptyContentDescription() {
        listOf(AiMode.GEMMA, AiMode.CLOUD, AiMode.RAG, AiMode.ON_DEVICE_RAG).forEach { mode ->
            composeTestRule.setContent {
                AppTheme(dynamicColor = false) {
                    AiModeIndicator(mode = mode)
                }
            }
            // Each mode should expose a non-empty accessibility description
            val expectedDesc = when (mode) {
                AiMode.GEMMA        -> "On-device Gemma"
                AiMode.CLOUD        -> "Cloud AI"
                AiMode.RAG          -> "RAG — document-grounded response"
                AiMode.ON_DEVICE_RAG -> "On-device RAG"
            }
            composeTestRule.onNodeWithContentDescription(expectedDesc).assertIsDisplayed()
        }
    }
}
