/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : MessageInputBarTest.kt
 * Purpose    : Compose UI tests for MessageInputBar (Phase 14.2).
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import com.aiassistant.core.ui.components.MessageInputBar
import org.junit.Assert.assertTrue

class MessageInputBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sendButton_disabledWhenEmpty() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = "",
                    onValueChange = {},
                    onSend = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun sendButton_enabledWhenTextEntered() {
        var value by mutableStateOf("")
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = value,
                    onValueChange = { value = it },
                    onSend = {}
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Message input field")
            .performTextInput("Hello AI")
        composeTestRule.onNodeWithContentDescription("Send message").assertIsEnabled()
    }

    @Test
    fun generatingState_showsStopButton() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    isGenerating = true
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Stop generating").assertIsEnabled()
        // Send button should not exist in generating state
        composeTestRule.onNodeWithContentDescription("Send message").assertDoesNotExist()
    }

    @Test
    fun stopButton_callsOnStopGenerating() {
        var stopped = false
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    isGenerating = true,
                    onStopGenerating = { stopped = true }
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Stop generating").performClick()
        assertTrue("onStopGenerating should be called", stopped)
    }

    @Test
    fun sendButton_callsOnSend_whenTextPresent() {
        var sent = false
        var value by mutableStateOf("test message")
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = value,
                    onValueChange = { value = it },
                    onSend = { sent = true }
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        assertTrue("onSend should be called", sent)
    }

    @Test
    fun disabledState_inputIsNotEditable() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MessageInputBar(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    isEnabled = false
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Message input field").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }
}
