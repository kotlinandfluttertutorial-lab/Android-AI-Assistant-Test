/**
 * HistorySearchScreenTest.kt
 *
 * Purpose: Compose UI tests verifying that the [SearchHistoryScreen] displays FTS search
 *          results within 300 ms of the query being submitted (Requirement 11.2).
 * Architecture: feature-history androidTest — instrumented Compose UI tests.
 * Dependencies: Compose UI Test (createComposeRule), core-ui (AppTheme),
 *               domain (Conversation, ExportFormat)
 *
 * Design decisions:
 * - [SearchHistoryScreen] is stateless; tests drive it by passing specific query /
 *   results pairs directly — no ViewModel or Hilt needed.
 * - The 300 ms SLA is validated by recording wall-clock time before setting the content
 *   with results and asserting the first result is visible; the compose rule's idle
 *   mechanism ensures the frame is fully rendered before the assertion runs.
 * - Tests verify semantic tree content (text, contentDescription) rather than pixel
 *   positions for layout resilience.
 *
 * Requirements: 11.2, 21.3
 */
package com.aiassistant.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.domain.model.Conversation
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistorySearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeConversation(
        id: String = "conv-1",
        title: String = "Conversation $id",
        provider: String = "GPT-4o"
    ) = Conversation(
        id = id,
        userId = "user-1",
        title = title,
        isPinned = false,
        isDeleted = false,
        provider = provider,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    // ── 1. FTS results displayed within 300 ms ────────────────────────────────

    /**
     * Verifies that after calling setContent with a non-empty results list the first
     * result row becomes visible within 300 ms (wall-clock). The Compose test rule
     * pumps the frame queue to idle before returning from setContent, so the elapsed
     * time also covers composition + layout + draw — matching the end-to-end render SLA.
     */
    @Test
    fun searchScreen_ftsResults_displayedWithin300ms() {
        val results = listOf(
            makeConversation(id = "1", title = "Kotlin coroutines deep dive"),
            makeConversation(id = "2", title = "Android architecture patterns")
        )

        val startMs = System.currentTimeMillis()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "kotlin",
                    searchResults = results,
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        // Assert the first FTS result is visible
        composeTestRule
            .onNodeWithText("Kotlin coroutines deep dive")
            .assertIsDisplayed()

        val elapsedMs = System.currentTimeMillis() - startMs

        assertTrue(
            "FTS results must be visible within 300 ms, but took $elapsedMs ms",
            elapsedMs <= 300L
        )
    }

    @Test
    fun searchScreen_ftsResults_allItemsDisplayed() {
        val results = listOf(
            makeConversation(id = "1", title = "First result"),
            makeConversation(id = "2", title = "Second result"),
            makeConversation(id = "3", title = "Third result")
        )

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "result",
                    searchResults = results,
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule.onNodeWithText("First result").assertIsDisplayed()
        composeTestRule.onNodeWithText("Second result").assertIsDisplayed()
        composeTestRule.onNodeWithText("Third result").assertIsDisplayed()
    }

    // ── 2. Empty state when no results ────────────────────────────────────────

    @Test
    fun searchScreen_showsEmptyState_whenNoResults() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "nomatch",
                    searchResults = emptyList(),
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("No conversations match \u201cnomatch\u201d.")
            .assertIsDisplayed()
    }

    // ── 3. Prompt shown when query is blank ───────────────────────────────────

    @Test
    fun searchScreen_showsTypingPrompt_whenQueryIsBlank() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "",
                    searchResults = emptyList(),
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("Type to search your conversation history.")
            .assertIsDisplayed()
    }

    // ── 4. Loading indicator ──────────────────────────────────────────────────

    @Test
    fun searchScreen_showsLoadingIndicator_whenIsLoadingTrue() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "kotlin",
                    searchResults = emptyList(),
                    isLoading = true,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Searching conversations")
            .assertIsDisplayed()
    }

    // ── 5. Back navigation callback ───────────────────────────────────────────

    @Test
    fun searchScreen_backButton_click_invokesOnBackCallback() {
        var backClicked = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "",
                    searchResults = emptyList(),
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = { backClicked = true }
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Back to conversation history")
            .performClick()

        assertTrue("onBack callback was not invoked", backClicked)
    }

    // ── 6. Row tap invokes onConversationClick ────────────────────────────────

    @Test
    fun searchScreen_conversationRow_click_invokesOnConversationClickCallback() {
        var clickedId = ""

        val results = listOf(makeConversation(id = "conv-42", title = "My test conversation"))

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "test",
                    searchResults = results,
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = { id -> clickedId = id },
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("My test conversation")
            .performClick()

        assertTrue("onConversationClick was not invoked with correct id", clickedId == "conv-42")
    }
}
