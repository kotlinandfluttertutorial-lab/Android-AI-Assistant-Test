/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ChatBubbleTest.kt
 * Purpose    : Compose UI tests for ChatBubble components (Phase 14.1).
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performLongClick
import org.junit.Rule
import org.junit.Test
import com.aiassistant.core.ui.components.AiMode
import com.aiassistant.core.ui.components.AssistantMessageBubble
import com.aiassistant.core.ui.components.ChatBubble
import com.aiassistant.core.ui.components.ChatBubbleRole
import com.aiassistant.core.ui.components.UserMessageBubble
import org.junit.Assert.assertTrue

class ChatBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── UserMessageBubble ─────────────────────────────────────────────────────

    @Test
    fun userBubble_displaysText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                UserMessageBubble(text = "Hello there!")
            }
        }
        composeTestRule.onNodeWithText("Hello there!").assertIsDisplayed()
    }

    @Test
    fun userBubble_hasUserContentDescription() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                UserMessageBubble(text = "Hello there!")
            }
        }
        composeTestRule.onNodeWithContentDescription("You: Hello there!").assertIsDisplayed()
    }

    @Test
    fun userBubble_longPress_triggersCallback() {
        var longPressed = false
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                UserMessageBubble(
                    text = "Long press me",
                    onLongPress = { longPressed = true }
                )
            }
        }
        composeTestRule.onNodeWithText("Long press me").performLongClick()
        assertTrue("onLongPress should be triggered", longPressed)
    }

    // ── AssistantMessageBubble ────────────────────────────────────────────────

    @Test
    fun assistantBubble_displaysText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                AssistantMessageBubble(text = "I can help you with that.")
            }
        }
        composeTestRule.onNodeWithText("I can help you with that.").assertIsDisplayed()
    }

    @Test
    fun assistantBubble_firstInTurn_showsAiModeIndicator() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                AssistantMessageBubble(
                    text = "Here is my response.",
                    aiMode = AiMode.GEMMA,
                    isFirstInTurn = true
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("On-device Gemma").assertIsDisplayed()
    }

    @Test
    fun assistantBubble_notFirstInTurn_doesNotShowModeIndicator() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                AssistantMessageBubble(
                    text = "Continuation of response.",
                    aiMode = AiMode.GEMMA,
                    isFirstInTurn = false
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("On-device Gemma").assertDoesNotExist()
    }

    @Test
    fun assistantBubble_streaming_showsTypingIndicatorText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                AssistantMessageBubble(
                    text = "Partial response so far",
                    isStreaming = true
                )
            }
        }
        // The StreamingMessage should show the partial text
        composeTestRule.onNodeWithText("Partial response so far").assertIsDisplayed()
    }

    // ── Unified ChatBubble ────────────────────────────────────────────────────

    @Test
    fun chatBubble_userRole_displaysText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ChatBubble(text = "User message", role = ChatBubbleRole.USER)
            }
        }
        composeTestRule.onNodeWithText("User message").assertIsDisplayed()
    }

    @Test
    fun chatBubble_assistantRole_displaysText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ChatBubble(text = "Assistant response", role = ChatBubbleRole.ASSISTANT)
            }
        }
        composeTestRule.onNodeWithText("Assistant response").assertIsDisplayed()
    }
}
