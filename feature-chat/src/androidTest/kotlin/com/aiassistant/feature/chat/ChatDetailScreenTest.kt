/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailScreenTest.kt
 * Purpose    : Compose UI tests for ChatDetailScreen — chat send, streaming
 *              display, typing indicator lifecycle, copy action, and retry banner
 *
 * Architecture Layer : Feature (feature-chat) — androidTest
 * Pattern Used       : Stateless Composable Testing (no ViewModel / Hilt)
 *
 * Key Concepts:
 *   - Drive ChatDetailScreenContent with specific ChatDetailUiState values
 *   - Validate semantic tree nodes by contentDescription
 *   - mutableStateOf allows state mutation tests within a single setContent block
 *
 * Dependencies:
 *   - Compose UI Test (createComposeRule)
 *   - core-ui (AppTheme)
 *   - domain (Message, ExportFormat)
 *   - core-common (DomainError)
 *
 * Requirements: 21.3
 * ============================================================
 */
package com.aiassistant.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.Message
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeMessage(
        id: String = "msg-1",
        conversationId: String = "conv-1",
        role: String = "assistant",
        content: String = "Hello from the assistant"
    ) = Message(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        createdAt = Instant.now()
    )

    /** Sets up a stateless composable with all callbacks as no-ops by default. */
    private fun setScreen(
        uiState: ChatDetailUiState,
        onSendMessage: (String) -> Unit = {},
        onRetryStreaming: () -> Unit = {},
        onRegenerateMessage: (String) -> Unit = {},
        onDismissError: () -> Unit = {},
        onExportConversation: (ExportFormat) -> Unit = {},
        onNavigateUp: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ChatDetailScreenContent(
                    uiState = uiState,
                    onSendMessage = onSendMessage,
                    onRetryStreaming = onRetryStreaming,
                    onRegenerateMessage = onRegenerateMessage,
                    onDismissError = onDismissError,
                    onExportConversation = onExportConversation,
                    onNavigateUp = onNavigateUp
                )
            }
        }
    }

    // ── 1. Typing indicator appears after send ────────────────────────────────

    /**
     * Verifies that the typing indicator node is displayed when
     * isTypingIndicatorVisible is true (simulates the moment after send).
     */
    @Test
    fun chatDetail_typingIndicator_isDisplayed_whenIsTypingIndicatorVisibleTrue() {
        setScreen(
            uiState = ChatDetailUiState(
                isTypingIndicatorVisible = true,
                isLoading = false
            )
        )

        composeTestRule
            .onNodeWithContentDescription("Assistant is typing")
            .assertIsDisplayed()
    }

    // ── 2. Typing indicator absent before send ────────────────────────────────

    /**
     * Verifies that the typing indicator is NOT in the tree when
     * isTypingIndicatorVisible is false (initial idle state).
     */
    @Test
    fun chatDetail_typingIndicator_doesNotExist_whenIsTypingIndicatorVisibleFalse() {
        setScreen(
            uiState = ChatDetailUiState(
                isTypingIndicatorVisible = false,
                isLoading = false
            )
        )

        composeTestRule
            .onNodeWithContentDescription("Assistant is typing")
            .assertDoesNotExist()
    }

    // ── 3. Streaming display — tokens render incrementally ────────────────────

    /**
     * Verifies that setting streamingText = "Hello " shows a streaming bubble
     * with the expected contentDescription.
     */
    @Test
    fun chatDetail_streamingBubble_displays_partialToken() {
        setScreen(
            uiState = ChatDetailUiState(
                isStreaming = true,
                streamingText = "Hello ",
                isLoading = false
            )
        )

        composeTestRule
            .onNodeWithContentDescription("Assistant is responding: Hello ")
            .assertIsDisplayed()
    }

    /**
     * Verifies that updating streamingText reflects the new accumulated token text.
     */
    @Test
    fun chatDetail_streamingBubble_reflects_updatedTokenText() {
        setScreen(
            uiState = ChatDetailUiState(
                isStreaming = true,
                streamingText = "Hello World",
                isLoading = false
            )
        )

        composeTestRule
            .onNodeWithContentDescription("Assistant is responding: Hello World")
            .assertIsDisplayed()
    }

    // ── 4. Typing indicator disappears on first token ─────────────────────────

    /**
     * Simulates the transition from typing-indicator-visible to first-token-received:
     * state starts with isTypingIndicatorVisible=true and streamingText="", then
     * transitions to isTypingIndicatorVisible=false and streamingText="Hello".
     * Asserts the indicator disappears and the streaming bubble appears.
     */
    @Test
    fun chatDetail_typingIndicator_disappears_onFirstToken() {
        var testState by mutableStateOf(
            ChatDetailUiState(
                isTypingIndicatorVisible = true,
                streamingText = "",
                isLoading = false
            )
        )

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                ChatDetailScreenContent(
                    uiState = testState,
                    onSendMessage = {},
                    onRetryStreaming = {},
                    onRegenerateMessage = {},
                    onDismissError = {},
                    onExportConversation = {},
                    onNavigateUp = {}
                )
            }
        }

        // Typing indicator should be present initially
        composeTestRule
            .onNodeWithContentDescription("Assistant is typing")
            .assertIsDisplayed()

        // Simulate first token arriving
        testState = testState.copy(
            isTypingIndicatorVisible = false,
            streamingText = "Hello"
        )
        composeTestRule.waitForIdle()

        // Typing indicator must now be gone
        composeTestRule
            .onNodeWithContentDescription("Assistant is typing")
            .assertDoesNotExist()

        // Streaming bubble must be visible with the first token
        composeTestRule
            .onNodeWithContentDescription("Assistant is responding: Hello")
            .assertIsDisplayed()
    }

    // ── 5. Send button invokes onSendMessage callback ─────────────────────────

    /**
     * Types text into the message input field, taps Send, and asserts
     * the onSendMessage callback was invoked.
     */
    @Test
    fun chatDetail_sendButton_click_invokesOnSendMessageCallback() {
        var capturedMessage = ""

        setScreen(
            uiState = ChatDetailUiState(isStreaming = false, isLoading = false),
            onSendMessage = { capturedMessage = it }
        )

        composeTestRule
            .onNodeWithContentDescription("Message input")
            .performTextInput("Hello world")

        composeTestRule
            .onNodeWithContentDescription("Send message")
            .performClick()

        assertTrue(
            "onSendMessage callback was not invoked with the typed text",
            capturedMessage == "Hello world"
        )
    }

    // ── 6. Send button disabled during streaming ──────────────────────────────

    /**
     * Verifies that when isStreaming=true the message input field and
     * send button are both disabled.
     */
    @Test
    fun chatDetail_inputAndSendButton_disabled_duringStreaming() {
        setScreen(
            uiState = ChatDetailUiState(isStreaming = true, isLoading = false)
        )

        composeTestRule
            .onNodeWithContentDescription("Message input")
            .assertIsNotEnabled()

        composeTestRule
            .onNodeWithContentDescription("Send message")
            .assertIsNotEnabled()
    }

    // ── 7. Copy action — opens dropdown and copy item is clickable ────────────

    /**
     * Given an assistant message, taps the 3-dot menu, asserts the copy item
     * appears, then taps it to confirm it doesn't crash (clipboard write is a
     * system side-effect; no assertion on clipboard content is needed here).
     */
    @Test
    fun chatDetail_copyAction_menuItem_isClickable() {
        val messages = listOf(
            makeMessage(
                id = "1",
                conversationId = "c1",
                role = "assistant",
                content = "AI response text"
            )
        )

        setScreen(
            uiState = ChatDetailUiState(messages = messages, isLoading = false)
        )

        // Open the 3-dot action menu
        composeTestRule
            .onNodeWithContentDescription("Message actions")
            .performClick()

        // Copy item should be visible
        composeTestRule
            .onNodeWithContentDescription("Copy message as plain text")
            .assertIsDisplayed()

        // Tapping it writes to clipboard — just assert it doesn't throw
        composeTestRule
            .onNodeWithContentDescription("Copy message as plain text")
            .performClick()
    }

    // ── 8. Retry banner visible on StreamingInterrupted error ─────────────────

    /**
     * Verifies that when showRetryOption=true and error is StreamingInterrupted,
     * a retry banner containing the error message is shown.
     */
    @Test
    fun chatDetail_retryBanner_isDisplayed_onStreamingInterruptedError() {
        setScreen(
            uiState = ChatDetailUiState(
                showRetryOption = true,
                error = DomainError.StreamingInterrupted("Connection lost"),
                isLoading = false
            )
        )

        composeTestRule
            .onNodeWithText("Connection lost")
            .assertIsDisplayed()
    }

    // ── 9. Retry banner — retry button invokes callback ───────────────────────

    /**
     * Verifies that tapping the retry option in the retry banner invokes the
     * onRetryStreaming callback.
     */
    @Test
    fun chatDetail_retryBanner_retryButton_invokesOnRetryStreamingCallback() {
        var retryInvoked = false

        setScreen(
            uiState = ChatDetailUiState(
                showRetryOption = true,
                error = DomainError.StreamingInterrupted("Connection lost"),
                isLoading = false
            ),
            onRetryStreaming = { retryInvoked = true }
        )

        // Tap the "Retry" button inside the banner (contentDescription set in ErrorBanner)
        composeTestRule
            .onNodeWithContentDescription("Retry")
            .performClick()

        assertTrue("onRetryStreaming callback was not invoked", retryInvoked)
    }

    // ── 10. Persisted assistant message displays ──────────────────────────────

    /**
     * Verifies that a persisted assistant message in the messages list is
     * displayed with the expected contentDescription.
     */
    @Test
    fun chatDetail_assistantMessage_isDisplayed_withContentDescription() {
        val messages = listOf(
            makeMessage(
                id = "a1",
                role = "assistant",
                content = "This is the assistant reply"
            )
        )

        setScreen(
            uiState = ChatDetailUiState(messages = messages, isLoading = false)
        )

        composeTestRule
            .onNodeWithContentDescription("Assistant: This is the assistant reply")
            .assertIsDisplayed()
    }

    // ── 11. User message displayed ────────────────────────────────────────────

    /**
     * Verifies that a user message in the messages list is displayed with the
     * correct contentDescription.
     */
    @Test
    fun chatDetail_userMessage_isDisplayed_withContentDescription() {
        val messages = listOf(
            makeMessage(
                id = "u1",
                role = "user",
                content = "Tell me about coroutines"
            )
        )

        setScreen(
            uiState = ChatDetailUiState(messages = messages, isLoading = false)
        )

        composeTestRule
            .onNodeWithContentDescription("You: Tell me about coroutines")
            .assertIsDisplayed()
    }

    // ── 12. Navigate back callback invoked ───────────────────────────────────

    /**
     * Verifies that clicking the back arrow invokes the onNavigateUp callback.
     */
    @Test
    fun chatDetail_navigateBack_button_invokesOnNavigateUpCallback() {
        var navigateUpInvoked = false

        setScreen(
            uiState = ChatDetailUiState(isLoading = false),
            onNavigateUp = { navigateUpInvoked = true }
        )

        composeTestRule
            .onNodeWithContentDescription("Navigate back")
            .performClick()

        assertTrue("onNavigateUp callback was not invoked", navigateUpInvoked)
    }
}
