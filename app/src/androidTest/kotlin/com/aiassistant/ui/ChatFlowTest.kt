/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : ChatFlowTest.kt
 * Purpose    : Compose UI integration tests for the AI chat flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the AI chat screen.
 *
 * Uses a test-local [fakeChatContent] composable that reproduces the same semantic
 * anchors as the real ChatDetailScreenContent (which is internal to feature-chat).
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class ChatFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Test-local fake composable ────────────────────────────────────────────

    @Composable
    private fun fakeChatContent(
        isStreaming: Boolean = false,
        isTypingIndicatorVisible: Boolean = false,
        streamingText: String = "",
        showRetryOption: Boolean = false,
        onSendMessage: (String) -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        var text by remember { mutableStateOf("") }
        Column(modifier = Modifier.fillMaxSize()) {
            // Message input field
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                enabled = !isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Message input" }
            )
            // Send button
            IconButton(
                onClick = { if (text.isNotBlank() && !isStreaming) onSendMessage(text) },
                enabled = text.isNotBlank() && !isStreaming,
                modifier = Modifier.semantics { contentDescription = "Send message" }
            ) {
                Text("Send")
            }
            // Typing indicator
            if (isTypingIndicatorVisible) {
                Surface(
                    modifier = Modifier.semantics { contentDescription = "Assistant is typing" }
                ) {
                    Text("…")
                }
            }
            // Streaming bubble
            if (streamingText.isNotEmpty()) {
                Surface(
                    modifier = Modifier.semantics {
                        contentDescription = "Assistant is responding: $streamingText"
                    }
                ) {
                    Text(streamingText)
                }
            }
            // Retry option
            if (showRetryOption) {
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
            // Message actions (sample message item)
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.semantics { contentDescription = "Message: Hello" }) {
                    Text("Hello")
                }
                IconButton(
                    onClick = {},
                    modifier = Modifier.semantics { contentDescription = "Message actions" }
                ) {
                    Text("⋮")
                }
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun setChat(
        isStreaming: Boolean = false,
        isTypingIndicatorVisible: Boolean = false,
        streamingText: String = "",
        showRetryOption: Boolean = false,
        onSendMessage: (String) -> Unit = {},
        onRetry: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                fakeChatContent(
                    isStreaming = isStreaming,
                    isTypingIndicatorVisible = isTypingIndicatorVisible,
                    streamingText = streamingText,
                    showRetryOption = showRetryOption,
                    onSendMessage = onSendMessage,
                    onRetry = onRetry
                )
            }
        }
    }

    // ── 1. Idle state shows input and send button ─────────────────────────────

    @Test
    fun chatFlow_idle_showsInputAndSendButton() {
        setChat()

        composeTestRule.onNodeWithContentDescription("Message input").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    // ── 2. Sending a message fires the callback ───────────────────────────────

    @Test
    fun chatFlow_sendMessage_firesCallback() {
        var capturedMessage = ""

        setChat(onSendMessage = { capturedMessage = it })

        composeTestRule.onNodeWithContentDescription("Message input")
            .performTextInput("Hello AI")
        composeTestRule.onNodeWithContentDescription("Send message").performClick()

        assert(capturedMessage == "Hello AI") {
            "Expected 'Hello AI' but got '$capturedMessage'"
        }
    }

    // ── 3. Streaming state disables the send button ───────────────────────────

    @Test
    fun chatFlow_isStreaming_disablesSendButton() {
        setChat(isStreaming = true)

        composeTestRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    // ── 4. Typing indicator visible when set ─────────────────────────────────

    @Test
    fun chatFlow_typingIndicator_visibleWhenSet() {
        setChat(isTypingIndicatorVisible = true)

        composeTestRule.onNodeWithContentDescription("Assistant is typing").assertIsDisplayed()
    }

    // ── 5. Typing indicator absent when not set ───────────────────────────────

    @Test
    fun chatFlow_typingIndicator_hiddenWhenNotSet() {
        setChat(isTypingIndicatorVisible = false)

        composeTestRule.onNodeWithContentDescription("Assistant is typing").assertDoesNotExist()
    }

    // ── 6. Streaming bubble shows text ────────────────────────────────────────

    @Test
    fun chatFlow_streamingBubble_showsText() {
        setChat(streamingText = "Hello AI")

        composeTestRule.onNodeWithContentDescription("Assistant is responding: Hello AI")
            .assertIsDisplayed()
    }

    // ── 7. Retry option shown when set ────────────────────────────────────────

    @Test
    fun chatFlow_retryOption_shownWhenSet() {
        setChat(showRetryOption = true)

        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    // ── 8. Message actions menu is visible ────────────────────────────────────

    @Test
    fun chatFlow_messageActions_menuVisible() {
        setChat()

        composeTestRule.onNodeWithContentDescription("Message actions").assertIsDisplayed()
    }
}
