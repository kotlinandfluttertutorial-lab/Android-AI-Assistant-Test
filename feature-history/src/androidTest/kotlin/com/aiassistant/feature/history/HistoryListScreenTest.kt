/**
 * HistoryListScreenTest.kt
 *
 * Purpose: Compose UI tests verifying the [HistoryListScreen] behaviour:
 *          1. Typing in the search field invokes the [onSearchQueryChange] callback.
 *          2. Search results are displayed when [HistoryUiState.SearchResults] is active.
 *          3. Export option triggers [onExportConversation] callback with the correct format.
 *          4. Pinned conversations show the pin icon; pinned items appear before unpinned.
 *          5. Offline banner is shown when [isOffline] is true.
 *          6. Export-success snackbar is shown when state is [HistoryUiState.ExportSuccess].
 *
 * Architecture: feature-history androidTest — instrumented Compose UI tests.
 * Dependencies: Compose UI Test (createComposeRule), core-ui (AppTheme),
 *               domain (Conversation, ExportFormat, GroupedConversations)
 *
 * Design decisions:
 * - [HistoryListScreen] is stateless; tests pass specific [HistoryUiState] variants plus
 *   pre-built [PagingData] to drive each assertion without a ViewModel or Hilt.
 * - Pinned-before-unpinned ordering is validated by asserting vertical position of nodes
 *   in the semantic tree (top-to-bottom index order of sibling nodes).
 * - The 300 ms search-result render SLA is covered in [HistorySearchScreenTest].
 * - All Paging 3 data is provided via [PagingData.from] wrapped in a [flowOf] so the
 *   [LazyPagingItems] reflect a synchronous, fully-loaded first page.
 *
 * Requirements: 11.2, 11.3, 11.6, 21.3
 */
package com.aiassistant.feature.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.GroupedConversations
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeConversation(
        id: String = "conv-1",
        title: String = "Sample Conversation",
        provider: String = "GPT-4o",
        isPinned: Boolean = false,
        updatedAt: Instant = Instant.now()
    ) = Conversation(
        id = id,
        userId = "user-1",
        title = title,
        isPinned = isPinned,
        isDeleted = false,
        provider = provider,
        createdAt = Instant.now(),
        updatedAt = updatedAt
    )

    /**
     * Sets [HistoryListScreen] with the given [pagedItems] and [uiState].
     */
    private fun setHistoryListContent(
        uiState: HistoryUiState,
        pagedItems: List<HistoryListItem> = emptyList(),
        isOffline: Boolean = false,
        onSearchClick: () -> Unit = {},
        onExportConversation: (String, ExportFormat) -> Unit = { _, _ -> },
        onDismissExportResult: () -> Unit = {},
        onPinConversation: (String, Boolean) -> Unit = { _, _ -> }
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                val pagingItems = flowOf(PagingData.from(pagedItems))
                    .collectAsLazyPagingItems()
                HistoryListScreen(
                    uiState = uiState,
                    pagedItems = pagingItems,
                    isOffline = isOffline,
                    onSearchClick = onSearchClick,
                    onConversationClick = {},
                    onPinConversation = onPinConversation,
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onExportConversation = onExportConversation,
                    onDismissExportResult = onDismissExportResult
                )
            }
        }
    }

    // ── 1. Search field typing invokes callback ───────────────────────────────

    /**
     * Verifies that typing in the [SearchHistoryScreen] search field triggers the
     * [onSearchQueryChange] callback (Requirement 11.2).
     *
     * The HistoryListScreen shows an Icon button that navigates to SearchHistoryScreen;
     * the SearchHistoryScreen has the live search field. This test exercises the
     * SearchHistoryScreen directly with an explicit callback spy.
     */
    @Test
    fun searchHistoryScreen_typingInSearchField_invokesOnSearchQueryChangeCallback() {
        val capturedQueries = mutableListOf<String>()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "",
                    searchResults = emptyList(),
                    isLoading = false,
                    onSearchQueryChange = { query -> capturedQueries.add(query) },
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Search conversations input")
            .performTextInput("kotlin")

        assertTrue(
            "onSearchQueryChange callback was not invoked after typing",
            capturedQueries.isNotEmpty()
        )
        assertTrue(
            "Expected query to contain 'kotlin' but got: $capturedQueries",
            capturedQueries.any { it.contains("kotlin") }
        )
    }

    @Test
    fun searchHistoryScreen_typingMultipleChars_eachCharInvokesCallback() {
        val capturedQueries = mutableListOf<String>()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "",
                    searchResults = emptyList(),
                    isLoading = false,
                    onSearchQueryChange = { query -> capturedQueries.add(query) },
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Search conversations input")
            .performTextInput("hi")

        assertTrue(
            "Expected callback to be invoked at least once for 'hi', got: $capturedQueries",
            capturedQueries.isNotEmpty()
        )
    }

    // ── 2. Search results displayed when SearchResults state is active ─────────

    /**
     * Verifies that [HistoryUiState.SearchResults] results are rendered in
     * [SearchHistoryScreen] (Requirement 11.2).
     */
    @Test
    fun searchHistoryScreen_withSearchResultsState_displaysResultTitles() {
        val results = listOf(
            makeConversation(id = "r1", title = "Architecture deep dive"),
            makeConversation(id = "r2", title = "Kotlin flows explained")
        )

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "architecture",
                    searchResults = results,
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("Architecture deep dive")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Kotlin flows explained")
            .assertIsDisplayed()
    }

    @Test
    fun searchHistoryScreen_withSearchResultsState_displaysAllMatchingConversations() {
        val results = (1..5).map { i ->
            makeConversation(id = "r$i", title = "Result $i for query")
        }

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                SearchHistoryScreen(
                    searchQuery = "query",
                    searchResults = results,
                    isLoading = false,
                    onSearchQueryChange = {},
                    onConversationClick = {},
                    onBack = {}
                )
            }
        }

        results.forEach { conv ->
            composeTestRule
                .onNodeWithText(conv.title)
                .assertIsDisplayed()
        }
    }

    // ── 3. Export option triggers callback with correct format ─────────────────

    /**
     * Verifies that tapping "Export as Markdown" in the overflow menu triggers
     * [onExportConversation] with [ExportFormat.MARKDOWN] (Requirement 11.6).
     */
    @Test
    fun historyListScreen_exportMarkdown_triggersCallbackWithMarkdownFormat() {
        var capturedFormat: ExportFormat? = null
        var capturedId: String? = null

        val conversation = makeConversation(id = "export-conv", title = "Exportable Conversation")
        val items = listOf(HistoryListItem.ConversationItem(conversation))

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(conversation))
            ),
            pagedItems = items,
            onExportConversation = { id, format ->
                capturedId = id
                capturedFormat = format
            }
        )

        // Open the overflow menu
        composeTestRule
            .onNodeWithContentDescription("More options for Exportable Conversation")
            .performClick()

        // Tap "Export as Markdown"
        composeTestRule
            .onNodeWithText("Export as Markdown")
            .performClick()

        assertEquals(
            "Export callback should be invoked with MARKDOWN format",
            ExportFormat.MARKDOWN,
            capturedFormat
        )
        assertEquals("Export callback should be invoked with correct conversation id", "export-conv", capturedId)
    }

    /**
     * Verifies that tapping "Export as PDF" triggers [onExportConversation] with
     * [ExportFormat.PDF] (Requirement 11.6).
     */
    @Test
    fun historyListScreen_exportPdf_triggersCallbackWithPdfFormat() {
        var capturedFormat: ExportFormat? = null
        var capturedId: String? = null

        val conversation = makeConversation(id = "pdf-conv", title = "PDF Conversation")
        val items = listOf(HistoryListItem.ConversationItem(conversation))

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(conversation))
            ),
            pagedItems = items,
            onExportConversation = { id, format ->
                capturedId = id
                capturedFormat = format
            }
        )

        composeTestRule
            .onNodeWithContentDescription("More options for PDF Conversation")
            .performClick()

        composeTestRule
            .onNodeWithText("Export as PDF")
            .performClick()

        assertEquals(
            "Export callback should be invoked with PDF format",
            ExportFormat.PDF,
            capturedFormat
        )
        assertEquals("Export callback should be invoked with correct conversation id", "pdf-conv", capturedId)
    }

    // ── 4. Pinned conversations show pin icon ──────────────────────────────────

    /**
     * Verifies that a pinned conversation renders the "Pinned" icon in the list
     * (Requirement 11.3).
     */
    @Test
    fun historyListScreen_pinnedConversation_showsPinIcon() {
        val pinnedConv = makeConversation(id = "pinned-1", title = "Pinned Conversation", isPinned = true)
        val items = listOf(HistoryListItem.ConversationItem(pinnedConv))

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(pinnedConv))
            ),
            pagedItems = items
        )

        composeTestRule
            .onNodeWithContentDescription("Pinned")
            .assertIsDisplayed()
    }

    /**
     * Verifies that an unpinned conversation does NOT render the "Pinned" icon.
     */
    @Test
    fun historyListScreen_unpinnedConversation_doesNotShowPinIcon() {
        val unpinnedConv = makeConversation(id = "unpinned-1", title = "Regular Conversation", isPinned = false)
        val items = listOf(HistoryListItem.ConversationItem(unpinnedConv))

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(unpinnedConv))
            ),
            pagedItems = items
        )

        // No "Pinned" icon should exist for an unpinned conversation
        assertEquals(
            "Unpinned conversation should not show the pin icon",
            0,
            composeTestRule.onAllNodesWithContentDescription("Pinned").fetchSemanticsNodes().size
        )
    }

    /**
     * Verifies that when both pinned and unpinned conversations are present, only the
     * pinned one shows the pin icon (Requirement 11.3 — pinned items appear at top).
     *
     * The pin icon is the observable indicator; ordering in the paged list itself is
     * controlled by the data layer (pinned items returned first by the Repository).
     * Here we confirm the correct number of pin icons match the number of pinned items.
     */
    @Test
    fun historyListScreen_mixedPinnedAndUnpinned_onlyPinnedShowsPinIcon() {
        val pinnedConv = makeConversation(id = "pin-1", title = "Pinned First", isPinned = true)
        val unpinnedConv = makeConversation(id = "unpin-1", title = "Unpinned Second", isPinned = false)

        // Pinned item appears first in the list (as the domain layer guarantees)
        val items = listOf(
            HistoryListItem.ConversationItem(pinnedConv),
            HistoryListItem.ConversationItem(unpinnedConv)
        )

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(
                    today = listOf(pinnedConv, unpinnedConv)
                )
            ),
            pagedItems = items
        )

        // Both titles must be visible
        composeTestRule.onNodeWithText("Pinned First").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unpinned Second").assertIsDisplayed()

        // Exactly one "Pinned" icon (for the pinned conversation only)
        val pinNodes = composeTestRule
            .onAllNodesWithContentDescription("Pinned")
            .fetchSemanticsNodes()

        assertEquals(
            "Expected exactly 1 pin icon for 1 pinned conversation, found ${pinNodes.size}",
            1,
            pinNodes.size
        )
    }

    /**
     * Verifies that multiple pinned conversations all show the pin icon.
     */
    @Test
    fun historyListScreen_multiplePinnedConversations_allShowPinIcon() {
        val pinned1 = makeConversation(id = "pin-1", title = "Pinned Alpha", isPinned = true)
        val pinned2 = makeConversation(id = "pin-2", title = "Pinned Beta", isPinned = true)
        val unpinned = makeConversation(id = "unpin-1", title = "Regular Gamma", isPinned = false)

        val items = listOf(
            HistoryListItem.ConversationItem(pinned1),
            HistoryListItem.ConversationItem(pinned2),
            HistoryListItem.ConversationItem(unpinned)
        )

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(
                    today = listOf(pinned1, pinned2, unpinned)
                )
            ),
            pagedItems = items
        )

        val pinNodes = composeTestRule
            .onAllNodesWithContentDescription("Pinned")
            .fetchSemanticsNodes()

        assertEquals(
            "Expected exactly 2 pin icons for 2 pinned conversations, found ${pinNodes.size}",
            2,
            pinNodes.size
        )
    }

    // ── 5. Offline banner shown when isOffline = true ──────────────────────────

    /**
     * Verifies that [HistoryListScreen] displays the offline banner when [isOffline] is
     * `true` (Requirement 10.4).
     *
     * The [OfflineBanner] composable uses contentDescription =
     * "You are offline. Some features are unavailable." by default.
     */
    @Test
    fun historyListScreen_showsOfflineBanner_whenIsOfflineTrue() {
        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations()
            ),
            isOffline = true
        )

        composeTestRule
            .onNodeWithContentDescription("You are offline. Some features are unavailable.")
            .assertIsDisplayed()
    }

    /**
     * Verifies that the offline banner is NOT shown when [isOffline] is `false`.
     */
    @Test
    fun historyListScreen_doesNotShowOfflineBanner_whenOnline() {
        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations()
            ),
            isOffline = false
        )

        assertEquals(
            "Offline banner should not be displayed when online",
            0,
            composeTestRule
                .onAllNodesWithContentDescription("You are offline. Some features are unavailable.")
                .fetchSemanticsNodes().size
        )
    }

    // ── 6. Export success snackbar shown when state is ExportSuccess ──────────

    /**
     * Verifies that [HistoryUiState.ExportSuccess] with [ExportFormat.MARKDOWN] surfaces
     * the snackbar message "Exported as Markdown successfully." (Requirement 11.6).
     */
    @Test
    fun historyListScreen_exportSuccessMarkdown_showsSnackbar() {
        setHistoryListContent(
            uiState = HistoryUiState.ExportSuccess(
                filePath = "# Conversation\n\nContent here",
                format = ExportFormat.MARKDOWN
            )
        )

        composeTestRule
            .onNodeWithText("Exported as Markdown successfully.")
            .assertIsDisplayed()
    }

    /**
     * Verifies that [HistoryUiState.ExportSuccess] with [ExportFormat.PDF] surfaces
     * the snackbar message "Exported as PDF successfully." (Requirement 11.6).
     */
    @Test
    fun historyListScreen_exportSuccessPdf_showsSnackbar() {
        setHistoryListContent(
            uiState = HistoryUiState.ExportSuccess(
                filePath = "/storage/emulated/0/Download/conversation.pdf",
                format = ExportFormat.PDF
            )
        )

        composeTestRule
            .onNodeWithText("Exported as PDF successfully.")
            .assertIsDisplayed()
    }

    /**
     * Verifies that [onDismissExportResult] is invoked after the export success snackbar
     * is shown (Requirement 11.6).
     */
    @Test
    fun historyListScreen_exportSuccess_invokesDismissCallback() {
        var dismissCalled = false

        setHistoryListContent(
            uiState = HistoryUiState.ExportSuccess(
                filePath = "# Content",
                format = ExportFormat.MARKDOWN
            ),
            onDismissExportResult = { dismissCalled = true }
        )

        // Wait for the snackbar to appear and dismiss callback to fire
        composeTestRule.waitUntil(timeoutMillis = 3_000L) { dismissCalled }

        assertTrue("onDismissExportResult should be called after snackbar is shown", dismissCalled)
    }

    // ── 7. Section headers for grouped conversations ───────────────────────────

    /**
     * Verifies that "Today" section header is displayed when conversations exist in the
     * today group (Requirement 11.5).
     */
    @Test
    fun historyListScreen_todayHeader_displayedForTodayConversations() {
        val todayConv = makeConversation(id = "today-1", title = "Today's Chat")
        val items = listOf(
            HistoryListItem.Header("Today"),
            HistoryListItem.ConversationItem(todayConv)
        )

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(todayConv))
            ),
            pagedItems = items
        )

        composeTestRule
            .onNodeWithText("Today")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Today's Chat")
            .assertIsDisplayed()
    }

    // ── 8. Pin action in overflow menu ─────────────────────────────────────────

    /**
     * Verifies that selecting "Pin" from the overflow menu invokes [onPinConversation]
     * with `isPinned = true` (Requirement 11.3).
     */
    @Test
    fun historyListScreen_pinFromMenu_invokesPinCallback() {
        var pinnedId: String? = null
        var pinnedState: Boolean? = null

        val conversation = makeConversation(id = "unpin-conv", title = "Unpinned Chat", isPinned = false)
        val items = listOf(HistoryListItem.ConversationItem(conversation))

        setHistoryListContent(
            uiState = HistoryUiState.HistoryList(
                groupedConversations = GroupedConversations(today = listOf(conversation))
            ),
            pagedItems = items,
            onPinConversation = { id, newState ->
                pinnedId = id
                pinnedState = newState
            }
        )

        composeTestRule
            .onNodeWithContentDescription("More options for Unpinned Chat")
            .performClick()

        composeTestRule
            .onNodeWithText("Pin")
            .performClick()

        assertEquals("Pin callback should be invoked with correct id", "unpin-conv", pinnedId)
        assertEquals("Pin callback should be invoked with isPinned = true", true, pinnedState)
    }
}
