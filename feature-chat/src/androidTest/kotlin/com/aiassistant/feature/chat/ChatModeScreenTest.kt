/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatModeScreenTest.kt
 * Purpose    : Compose UI tests for multi-mode chat components.
 *
 * Covers (all acceptance criteria):
 *   - ExecutionModeSelectorRow: all three chips shown, selected chip
 *     reports correct content-description, tapping an unselected chip
 *     invokes onModeSelected, chips disabled during streaming
 *   - CitationsPanel: hidden when empty, header shows correct count,
 *     tapping header expands/collapses, CitationCard shows doc name /
 *     page / excerpt / score, accessibility content description
 *   - ToolCallProgressRow: pending / completed / failed states all
 *     render correct icons and content descriptions
 *   - ToolCallsPanel: hidden when empty, shows tool count + step count,
 *     expandable, each row accessible
 *   - AgentStepIndicator: hidden when 0, visible with correct label
 *   - ChatDetailScreenContent: mode selector present, disabled while
 *     streaming; citations panel absent when empty, shown when non-empty;
 *     tool panel absent when empty, shown when non-empty; existing
 *     send/typing/message nodes still present (no regressions)
 *   - ChatDetailScreenContent: existing chat (DIRECT_LLM) still works
 *
 * Architecture Layer : feature-chat — androidTest (Compose UI)
 * Pattern Used       : Stateless Composable Testing (no ViewModel / Hilt)
 * ============================================================
 */

package com.aiassistant.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.domain.agent.AgentCitation
import com.aiassistant.domain.agent.AgentToolCall
import com.aiassistant.domain.agent.ChatExecutionMode
import com.aiassistant.domain.model.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun setModeSelector(
        selected: ChatExecutionMode = ChatExecutionMode.DIRECT_LLM,
        enabled: Boolean = true,
        onModeSelected: (ChatExecutionMode) -> Unit = {},
    ) {
        rule.setContent {
            AppTheme(dynamicColor = false) {
                ExecutionModeSelectorRow(
                    selectedMode = selected,
                    onModeSelected = onModeSelected,
                    enabled = enabled,
                )
            }
        }
    }

    private fun setCitationsPanel(citations: List<AgentCitation>) {
        rule.setContent {
            AppTheme(dynamicColor = false) {
                CitationsPanel(citations = citations)
            }
        }
    }

    private fun setToolCallsPanel(
        toolCalls: List<AgentToolCall>,
        stepCount: Int = 0,
    ) {
        rule.setContent {
            AppTheme(dynamicColor = false) {
                ToolCallsPanel(toolCalls = toolCalls, stepCount = stepCount)
            }
        }
    }

    private fun setScreen(
        uiState: ChatDetailUiState,
        onModeSelected: (ChatExecutionMode) -> Unit = {},
    ) {
        rule.setContent {
            AppTheme(dynamicColor = false) {
                ChatDetailScreenContent(
                    uiState = uiState,
                    onSendMessage = {},
                    onRetryStreaming = {},
                    onRegenerateMessage = {},
                    onDismissError = {},
                    onExportConversation = {},
                    onNavigateUp = {},
                    onModeSelected = onModeSelected,
                )
            }
        }
    }

    // =========================================================================
    // ExecutionModeSelectorRow
    // =========================================================================

    @Test
    fun modeSelectorRow_allThreeChips_areDisplayed() {
        setModeSelector()
        rule.onNodeWithText("Direct").assertIsDisplayed()
        rule.onNodeWithText("RAG").assertIsDisplayed()
        rule.onNodeWithText("Agent").assertIsDisplayed()
    }

    @Test
    fun modeSelectorRow_selectedChip_isMarkedSelected() {
        setModeSelector(selected = ChatExecutionMode.RAG)
        rule.onNodeWithText("RAG").assertIsSelected()
    }

    @Test
    fun modeSelectorRow_directLlm_selectedByDefault() {
        setModeSelector(selected = ChatExecutionMode.DIRECT_LLM)
        rule.onNodeWithText("Direct").assertIsSelected()
    }

    @Test
    fun modeSelectorRow_tappingUnselectedChip_invokesCallback() {
        var captured: ChatExecutionMode? = null
        setModeSelector(
            selected = ChatExecutionMode.DIRECT_LLM,
            onModeSelected = { captured = it },
        )
        rule.onNodeWithText("RAG").performClick()
        assertEquals(ChatExecutionMode.RAG, captured)
    }

    @Test
    fun modeSelectorRow_tappingAgentChip_invokesCallbackWithAgent() {
        var captured: ChatExecutionMode? = null
        setModeSelector(
            selected = ChatExecutionMode.DIRECT_LLM,
            onModeSelected = { captured = it },
        )
        rule.onNodeWithText("Agent").performClick()
        assertEquals(ChatExecutionMode.AGENT, captured)
    }

    @Test
    fun modeSelectorRow_allChipsDisabled_whenEnabledFalse() {
        setModeSelector(enabled = false)
        // With enabled=false, FilterChip renders disabled state;
        // none should be interactive (assertIsNotEnabled checks enabled=false
        // in the semantics of the root chip node).
        rule.onNodeWithText("Direct").assertIsNotEnabled()
        rule.onNodeWithText("RAG").assertIsNotEnabled()
        rule.onNodeWithText("Agent").assertIsNotEnabled()
    }

    @Test
    fun modeSelectorRow_hasAccessibilityContainerDescription() {
        setModeSelector()
        rule.onNodeWithContentDescription("Execution mode selector").assertIsDisplayed()
    }

    @Test
    fun modeSelectorRow_eachChip_hasContentDescription() {
        setModeSelector(selected = ChatExecutionMode.DIRECT_LLM)
        // The selected chip has "(selected)" appended
        rule.onNodeWithContentDescription(
            "Direct: Send directly to the language model (selected)",
            substring = true,
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(
            "RAG: Search documents, then answer",
            substring = true,
        ).assertIsDisplayed()
    }

    // =========================================================================
    // CitationsPanel
    // =========================================================================

    @Test
    fun citationsPanel_hiddenWhenEmpty() {
        setCitationsPanel(emptyList())
        // Header node must not exist when citations list is empty
        rule.onNodeWithContentDescription(
            "source", substring = true, useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun citationsPanel_showsCorrectSourceCount_singular() {
        val citations = listOf(
            AgentCitation("d1", "arch.md", "Some excerpt", 2, 0.91f),
        )
        setCitationsPanel(citations)
        rule.onNodeWithText("1 source").assertIsDisplayed()
    }

    @Test
    fun citationsPanel_showsCorrectSourceCount_plural() {
        val citations = listOf(
            AgentCitation("d1", "arch.md", "Excerpt 1", 1, 0.9f),
            AgentCitation("d2", "readme.md", "Excerpt 2", 3, 0.75f),
        )
        setCitationsPanel(citations)
        rule.onNodeWithText("2 sources").assertIsDisplayed()
    }

    @Test
    fun citationsPanel_expandsOnTap_showsCitationContent() {
        val citations = listOf(
            AgentCitation("d1", "architecture.md", "Three-layer design", 2, 0.92f),
        )
        setCitationsPanel(citations)

        // Tap the header to expand
        rule.onNodeWithContentDescription(
            "Expand 1 source", substring = false,
        ).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("architecture.md").assertIsDisplayed()
        rule.onNodeWithText("Three-layer design").assertIsDisplayed()
    }

    @Test
    fun citationCard_pageNumber_isDisplayed() {
        val citations = listOf(
            AgentCitation("d1", "doc.md", "Some text", pageNumber = 5, score = 0.8f),
        )
        setCitationsPanel(citations)
        rule.onNodeWithContentDescription(
            "Expand 1 source", substring = false,
        ).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("p.5").assertIsDisplayed()
    }

    @Test
    fun citationCard_hasAccessibilityDescription() {
        val citations = listOf(
            AgentCitation("d1", "guide.md", "Important context", pageNumber = 3, score = 0.85f),
        )
        rule.setContent {
            AppTheme(dynamicColor = false) {
                CitationCard(citation = citations[0], index = 1)
            }
        }
        rule.onNodeWithContentDescription(
            "Source 1: guide.md, page 3",
            substring = true,
        ).assertIsDisplayed()
    }

    // =========================================================================
    // ToolCallProgressRow
    // =========================================================================

    @Test
    fun toolCallProgressRow_pending_showsCorrectDescription() {
        val pending = AgentToolCall("jira_get_issue", "{}", null, false, null, 0L)
        rule.setContent {
            AppTheme(dynamicColor = false) { ToolCallProgressRow(toolCall = pending) }
        }
        rule.onNodeWithContentDescription(
            "Tool jira_get_issue: in progress",
            substring = false,
        ).assertIsDisplayed()
    }

    @Test
    fun toolCallProgressRow_completed_showsCorrectDescription() {
        val done = AgentToolCall("jira_get_issue", "{}", """{"key":"AI-1"}""", false, null, 120L)
        rule.setContent {
            AppTheme(dynamicColor = false) { ToolCallProgressRow(toolCall = done) }
        }
        rule.onNodeWithContentDescription(
            "Tool jira_get_issue: completed",
            substring = false,
        ).assertIsDisplayed()
    }

    @Test
    fun toolCallProgressRow_failed_showsErrorDescription() {
        val failed = AgentToolCall("slack_post", "{}", null, true, "401 Unauthorized", 0L)
        rule.setContent {
            AppTheme(dynamicColor = false) { ToolCallProgressRow(toolCall = failed) }
        }
        rule.onNodeWithContentDescription(
            "Tool slack_post: failed",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun toolCallProgressRow_showsDuration_whenNonZero() {
        val done = AgentToolCall("search", "{}", "{}", false, null, 350L)
        rule.setContent {
            AppTheme(dynamicColor = false) { ToolCallProgressRow(toolCall = done) }
        }
        rule.onNodeWithText("350ms").assertIsDisplayed()
    }

    @Test
    fun toolCallProgressRow_hideDuration_whenZero() {
        val pending = AgentToolCall("search", "{}", null, false, null, 0L)
        rule.setContent {
            AppTheme(dynamicColor = false) { ToolCallProgressRow(toolCall = pending) }
        }
        rule.onNodeWithText("0ms").assertDoesNotExist()
    }

    // =========================================================================
    // ToolCallsPanel
    // =========================================================================

    @Test
    fun toolCallsPanel_hiddenWhenEmpty_andZeroSteps() {
        setToolCallsPanel(emptyList(), stepCount = 0)
        rule.onNodeWithContentDescription("tool", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun toolCallsPanel_showsToolCount() {
        val toolCalls = listOf(
            AgentToolCall("jira_get_issue", "{}", null, false, null, 0L),
            AgentToolCall("slack_post", "{}", null, false, null, 0L),
        )
        setToolCallsPanel(toolCalls)
        rule.onNodeWithText("2 tools").assertIsDisplayed()
    }

    @Test
    fun toolCallsPanel_showsStepCount() {
        setToolCallsPanel(emptyList(), stepCount = 3)
        rule.onNodeWithText("3 steps").assertIsDisplayed()
    }

    @Test
    fun toolCallsPanel_startsExpanded_showsToolRows() {
        val toolCalls = listOf(
            AgentToolCall("jira_get_issue", "{}", null, false, null, 0L),
        )
        setToolCallsPanel(toolCalls)
        // Starts expanded — tool name should be visible
        rule.onNodeWithText("jira_get_issue").assertIsDisplayed()
    }

    @Test
    fun toolCallsPanel_collapses_onHeaderTap() {
        val toolCalls = listOf(
            AgentToolCall("jira_get_issue", "{}", null, false, null, 0L),
        )
        setToolCallsPanel(toolCalls)

        // Tool name visible (expanded)
        rule.onNodeWithText("jira_get_issue").assertIsDisplayed()

        // Tap header to collapse
        rule.onNodeWithContentDescription("1 tool(s)", substring = true).performClick()
        rule.waitForIdle()

        // Tool name no longer visible
        rule.onNodeWithText("jira_get_issue").assertDoesNotExist()
    }

    // =========================================================================
    // AgentStepIndicator
    // =========================================================================

    @Test
    fun agentStepIndicator_hiddenWhenZero() {
        rule.setContent {
            AppTheme(dynamicColor = false) { AgentStepIndicator(stepCount = 0) }
        }
        rule.onNodeWithContentDescription("Agent reasoning", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun agentStepIndicator_visibleWithCorrectLabel() {
        rule.setContent {
            AppTheme(dynamicColor = false) { AgentStepIndicator(stepCount = 4) }
        }
        rule.onNodeWithContentDescription("Agent reasoning: step 4").assertIsDisplayed()
        rule.onNodeWithText("Step 4").assertIsDisplayed()
    }

    // =========================================================================
    // ChatDetailScreenContent — integration with new components
    // =========================================================================

    @Test
    fun screen_modeSelectorRow_isAlwaysPresent() {
        setScreen(uiState = ChatDetailUiState(isLoading = false))
        rule.onNodeWithContentDescription("Execution mode selector").assertIsDisplayed()
    }

    @Test
    fun screen_modeSelectorRow_disabledDuringStreaming() {
        setScreen(uiState = ChatDetailUiState(isStreaming = true, isLoading = false))
        rule.onNodeWithText("Direct").assertIsNotEnabled()
        rule.onNodeWithText("RAG").assertIsNotEnabled()
        rule.onNodeWithText("Agent").assertIsNotEnabled()
    }

    @Test
    fun screen_modeSelectorRow_enabledWhenNotStreaming() {
        setScreen(uiState = ChatDetailUiState(isStreaming = false, isLoading = false))
        rule.onNodeWithText("Direct").assertIsEnabled()
    }

    @Test
    fun screen_citationsPanel_absentWhenNoCitations() {
        setScreen(uiState = ChatDetailUiState(citations = emptyList(), isLoading = false))
        rule.onNodeWithText("1 source").assertDoesNotExist()
        rule.onNodeWithText("2 sources").assertDoesNotExist()
    }

    @Test
    fun screen_citationsPanel_presentWhenCitationsExist() {
        val citations = listOf(
            AgentCitation("d1", "arch.md", "Excerpt", 1, 0.9f),
        )
        setScreen(uiState = ChatDetailUiState(citations = citations, isLoading = false))
        rule.onNodeWithText("1 source").assertIsDisplayed()
    }

    @Test
    fun screen_toolCallsPanel_absentWhenNoToolCalls() {
        setScreen(
            uiState = ChatDetailUiState(
                activeToolCalls = emptyList(),
                agentStepCount = 0,
                isLoading = false,
            )
        )
        rule.onNodeWithText("1 tool").assertDoesNotExist()
        rule.onNodeWithText("tools").assertDoesNotExist()
    }

    @Test
    fun screen_toolCallsPanel_presentWhenToolCallsExist() {
        val toolCalls = listOf(
            AgentToolCall("jira_get_issue", "{}", null, false, null, 0L),
        )
        setScreen(
            uiState = ChatDetailUiState(
                activeToolCalls = toolCalls,
                agentStepCount = 1,
                isLoading = false,
            )
        )
        rule.onNodeWithText("1 tool").assertIsDisplayed()
    }

    @Test
    fun screen_modeSelector_tapped_invokesCallback() {
        var selected: ChatExecutionMode? = null
        setScreen(
            uiState = ChatDetailUiState(
                executionMode = ChatExecutionMode.DIRECT_LLM,
                isStreaming = false,
                isLoading = false,
            ),
            onModeSelected = { selected = it },
        )
        rule.onNodeWithText("RAG").performClick()
        assertEquals(ChatExecutionMode.RAG, selected)
    }

    // ── Regression: existing chat elements still present ─────────────────────

    @Test
    fun screen_existingElements_messageInputPresent() {
        setScreen(uiState = ChatDetailUiState(isLoading = false))
        rule.onNodeWithContentDescription("Message input").assertIsDisplayed()
    }

    @Test
    fun screen_existingElements_sendButtonPresent() {
        setScreen(uiState = ChatDetailUiState(isLoading = false))
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun screen_existingElements_backButtonPresent() {
        setScreen(uiState = ChatDetailUiState(isLoading = false))
        rule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
    }

    @Test
    fun screen_existingElements_typingIndicator_stillWorksWithNewUiState() {
        setScreen(
            uiState = ChatDetailUiState(
                isTypingIndicatorVisible = true,
                executionMode = ChatExecutionMode.AGENT,
                isLoading = false,
            )
        )
        rule.onNodeWithContentDescription("Assistant is typing").assertIsDisplayed()
    }

    @Test
    fun screen_agentStepIndicator_visibleDuringAgentStreaming() {
        setScreen(
            uiState = ChatDetailUiState(
                isStreaming = true,
                agentStepCount = 2,
                executionMode = ChatExecutionMode.AGENT,
                isLoading = false,
            )
        )
        rule.onNodeWithContentDescription("Agent reasoning: step 2").assertIsDisplayed()
    }

    @Test
    fun screen_agentStepIndicator_hiddenWhenZeroSteps() {
        setScreen(
            uiState = ChatDetailUiState(
                isStreaming = true,
                agentStepCount = 0,
                isLoading = false,
            )
        )
        rule.onNodeWithContentDescription("Agent reasoning", substring = true)
            .assertDoesNotExist()
    }

    // ── Mode state reflected after state update ───────────────────────────────

    @Test
    fun screen_modeSelectorRow_reflectsStateChange() {
        var uiState by mutableStateOf(
            ChatDetailUiState(executionMode = ChatExecutionMode.DIRECT_LLM, isLoading = false)
        )
        rule.setContent {
            AppTheme(dynamicColor = false) {
                ChatDetailScreenContent(
                    uiState = uiState,
                    onSendMessage = {},
                    onRetryStreaming = {},
                    onRegenerateMessage = {},
                    onDismissError = {},
                    onExportConversation = {},
                    onNavigateUp = {},
                )
            }
        }

        rule.onNodeWithText("Direct").assertIsSelected()

        // Simulate ViewModel updating the state
        uiState = uiState.copy(executionMode = ChatExecutionMode.AGENT)
        rule.waitForIdle()

        rule.onNodeWithText("Agent").assertIsSelected()
    }
}
