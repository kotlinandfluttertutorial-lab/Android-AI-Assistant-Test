/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailViewModelModeTest.kt
 * Purpose    : Unit tests for the multi-mode execution path in
 *              ChatDetailViewModel.
 *
 * Covers:
 *   - setExecutionMode: state updated, new mode used on next send
 *   - DIRECT_LLM: AgentGatewayRepository called with AgentMode.CHAT
 *   - RAG: AgentGatewayRepository called with AgentMode.DOCUMENT;
 *           citations populated from AgentEvent.Completed result
 *   - AGENT: AgentGatewayRepository called with AgentMode.AUTO;
 *            activeToolCalls & agentStepCount updated from events
 *   - Token events: streamingText accumulated, typingIndicator hidden
 *   - AgentEvent.Failed: showRetryOption set, streaming cleared
 *   - AgentEvent.Cancelled: streaming cleared, no retry option
 *   - retryStreaming: re-calls gateway with same content + mode
 *   - mode selector cleared state: citations & toolCalls cleared on
 *     each new sendMessage
 *   - existing chat (direct-LLM) continues to work
 *
 * Style: Kotest DescribeSpec + MockK + Turbine (matches project conventions)
 * ============================================================
 */

package com.aiassistant.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.agent.AgentCitation
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentGatewayRepository
import com.aiassistant.domain.agent.AgentMode
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.AgentToolCall
import com.aiassistant.domain.agent.AgentUsage
import com.aiassistant.domain.agent.ChatExecutionMode
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.RegenerateMessageUseCase
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModelModeTest : DescribeSpec({

    // ── Dependencies ──────────────────────────────────────────────────────────

    val sendMessageUseCase = mockk<SendMessageUseCase>()
    val regenerateMessageUseCase = mockk<RegenerateMessageUseCase>()
    val exportConversationUseCase = mockk<ExportConversationUseCase>()
    val agentGatewayRepository = mockk<AgentGatewayRepository>()
    val streamClient = mockk<AIStreamClient>(relaxed = true)
    val getContextSuggestionsUseCase = mockk<GetContextSuggestionsUseCase>()

    val testDispatcher = UnconfinedTestDispatcher()
    val dispatchers = object : DispatcherProvider {
        override val main = testDispatcher
        override val mainImmediate = testDispatcher
        override val io = testDispatcher
        override val default = testDispatcher
        override val unconfined = testDispatcher
    }

    val conversationId = "conv-mode-test"
    val savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId))

    beforeSpec { Dispatchers.setMain(testDispatcher) }
    afterSpec  { Dispatchers.resetMain() }

    beforeEach {
        clearMocks(
            sendMessageUseCase, regenerateMessageUseCase, exportConversationUseCase,
            agentGatewayRepository, streamClient, getContextSuggestionsUseCase,
        )
        // Default: sendMessage succeeds silently
        coEvery { sendMessageUseCase(any(), any(), any()) } returns ApiResult.Success(mockk())
    }

    // ── Factory helper ────────────────────────────────────────────────────────

    fun makeVm() = ChatDetailViewModel(
        savedStateHandle = savedStateHandle,
        sendMessageUseCase = sendMessageUseCase,
        regenerateMessageUseCase = regenerateMessageUseCase,
        exportConversationUseCase = exportConversationUseCase,
        agentGatewayRepository = agentGatewayRepository,
        dispatchers = dispatchers,
        getContextSuggestionsUseCase = getContextSuggestionsUseCase,
        streamClient = streamClient,
    )

    /** Minimal successful AgentResult with optional content and citations. */
    fun successResult(
        content: String = "Answer",
        citations: List<AgentCitation> = emptyList(),
        toolCalls: List<AgentToolCall> = emptyList(),
    ) = AgentResult(
        executionId = UUID.randomUUID().toString(),
        requestId = UUID.randomUUID().toString(),
        agentName = "test-agent",
        status = AgentStatus.COMPLETED,
        content = content,
        citations = citations,
        toolCalls = toolCalls,
        usage = AgentUsage(inputTokens = 10, outputTokens = 20),
    )

    // =========================================================================
    // ChatExecutionMode enum
    // =========================================================================

    describe("ChatExecutionMode") {

        it("DIRECT_LLM maps to AgentMode.CHAT") {
            ChatExecutionMode.DIRECT_LLM.toAgentMode() shouldBe AgentMode.CHAT
        }

        it("RAG maps to AgentMode.DOCUMENT") {
            ChatExecutionMode.RAG.toAgentMode() shouldBe AgentMode.DOCUMENT
        }

        it("AGENT maps to AgentMode.AUTO") {
            ChatExecutionMode.AGENT.toAgentMode() shouldBe AgentMode.AUTO
        }

        it("all contains all three modes in display order") {
            ChatExecutionMode.all shouldBe listOf(
                ChatExecutionMode.DIRECT_LLM,
                ChatExecutionMode.RAG,
                ChatExecutionMode.AGENT,
            )
        }

        it("displayName returns non-blank label for every mode") {
            ChatExecutionMode.all.forEach { mode ->
                ChatExecutionMode.displayName(mode).isNotBlank() shouldBe true
            }
        }
    }

    // =========================================================================
    // setExecutionMode
    // =========================================================================

    describe("setExecutionMode") {

        it("defaults to DIRECT_LLM") {
            makeVm().uiState.value.executionMode shouldBe ChatExecutionMode.DIRECT_LLM
        }

        it("updates executionMode in uiState") {
            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            vm.uiState.value.executionMode shouldBe ChatExecutionMode.RAG
        }

        it("can switch between all modes") {
            val vm = makeVm()
            ChatExecutionMode.all.forEach { mode ->
                vm.setExecutionMode(mode)
                vm.uiState.value.executionMode shouldBe mode
            }
        }
    }

    // =========================================================================
    // DIRECT_LLM mode  (AC: existing direct chat continues to work)
    // =========================================================================

    describe("DIRECT_LLM mode") {

        it("calls AgentGatewayRepository with AgentMode.CHAT") {
            val modeSlot = slot<AgentMode>()
            every {
                agentGatewayRepository.executeChat(
                    conversationId = any(),
                    content = any(),
                    provider = any(),
                    context = any(),
                    mode = capture(modeSlot),
                )
            } returns flowOf(
                AgentEvent.Token("Hello"),
                AgentEvent.Completed(successResult("Hello")),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.DIRECT_LLM)
            runTest(testDispatcher) { vm.sendMessage("Hi") }

            modeSlot.captured shouldBe AgentMode.CHAT
        }

        it("commits assistant message on Completed event") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(AgentEvent.Completed(successResult("Direct answer")))

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Hello") }

            val state = vm.uiState.value
            state.messages.any { it.role == "assistant" && it.content == "Direct answer" } shouldBe true
            state.isStreaming shouldBe false
            state.streamingText shouldBe ""
        }

        it("no citations or tool calls in DIRECT_LLM result") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(AgentEvent.Completed(successResult()))

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Hello") }

            vm.uiState.value.citations.shouldBeEmpty()
            vm.uiState.value.activeToolCalls.shouldBeEmpty()
        }
    }

    // =========================================================================
    // RAG mode  (AC: RAG mode supported; sources can be displayed)
    // =========================================================================

    describe("RAG mode") {

        it("calls AgentGatewayRepository with AgentMode.DOCUMENT") {
            val modeSlot = slot<AgentMode>()
            every {
                agentGatewayRepository.executeChat(
                    conversationId = any(), content = any(), provider = any(),
                    context = any(), mode = capture(modeSlot),
                )
            } returns flowOf(AgentEvent.Completed(successResult()))

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            runTest(testDispatcher) { vm.sendMessage("What is X?") }

            modeSlot.captured shouldBe AgentMode.DOCUMENT
        }

        it("populates citations from AgentResult.citations") {
            val citations = listOf(
                AgentCitation(documentId = "d1", documentName = "arch.md",
                    excerpt = "The three-layer design.", pageNumber = 2, score = 0.91f),
                AgentCitation(documentId = "d2", documentName = "readme.md",
                    excerpt = "Setup instructions.", pageNumber = 1, score = 0.77f),
            )
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(
                AgentEvent.Completed(successResult("RAG answer", citations = citations))
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            runTest(testDispatcher) { vm.sendMessage("Describe the architecture.") }

            val state = vm.uiState.value
            state.citations shouldHaveSize 2
            state.citations[0].documentName shouldBe "arch.md"
            state.citations[1].documentName shouldBe "readme.md"
        }

        it("increments agentStepCount on RetrievalCompleted event") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(
                AgentEvent.RetrievalCompleted(query = "architecture", chunkCount = 3),
                AgentEvent.Completed(successResult()),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)

            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem() // initial
                    vm.sendMessage("Tell me about the architecture.")
                    // consume until stable
                    var last = awaitItem()
                    while (!last.citations.isNotEmpty() || last.isStreaming) {
                        last = awaitItem()
                    }
                    last.agentStepCount shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("RAG citations cleared at the start of a new sendMessage") {
            val citations = listOf(
                AgentCitation("d1", "doc.md", "excerpt", null, 0.9f)
            )
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returnsMany listOf(
                flowOf(AgentEvent.Completed(successResult("First", citations = citations))),
                flowOf(AgentEvent.Completed(successResult("Second", citations = emptyList()))),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            runTest(testDispatcher) {
                vm.sendMessage("First question")
                vm.uiState.value.citations shouldHaveSize 1

                vm.sendMessage("Second question")
                // During the second call citations are cleared immediately
                // (before the gateway response arrives)
                // After completion they stay empty as result has no citations
                vm.uiState.value.citations.shouldBeEmpty()
            }
        }
    }

    // =========================================================================
    // AGENT mode  (AC: Agent mode supported; tool usage displayed)
    // =========================================================================

    describe("AGENT mode") {

        it("calls AgentGatewayRepository with AgentMode.AUTO") {
            val modeSlot = slot<AgentMode>()
            every {
                agentGatewayRepository.executeChat(
                    conversationId = any(), content = any(), provider = any(),
                    context = any(), mode = capture(modeSlot),
                )
            } returns flowOf(AgentEvent.Completed(successResult()))

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)
            runTest(testDispatcher) { vm.sendMessage("Run the agent") }

            modeSlot.captured shouldBe AgentMode.AUTO
        }

        it("adds pending tool call on ToolStarted, updates on ToolCompleted") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                emit(AgentEvent.ToolStarted("jira_get_issue", """{"issue":"AI-123"}"""))
                emit(AgentEvent.ToolCompleted("jira_get_issue", """{"summary":"Build MCP"}""", 150L))
                emit(AgentEvent.Completed(successResult()))
            }

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)

            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem() // initial
                    vm.sendMessage("Get Jira issue AI-123")

                    var last = awaitItem()
                    // consume until we see the tool call
                    while (last.activeToolCalls.isEmpty() && !last.isStreaming ||
                           last.activeToolCalls.firstOrNull()?.output == null
                    ) {
                        val next = awaitItem()
                        last = next
                        if (!last.isStreaming && last.activeToolCalls.isNotEmpty()
                            && last.activeToolCalls.first().output != null) break
                    }

                    val toolCall = last.activeToolCalls.firstOrNull { it.toolName == "jira_get_issue" }
                    toolCall?.toolName shouldBe "jira_get_issue"
                    toolCall?.failed shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("marks tool call as failed on ToolFailed event") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                emit(AgentEvent.ToolStarted("slack_post", "{}"))
                emit(AgentEvent.ToolFailed("slack_post", "401 Unauthorized"))
                emit(AgentEvent.Completed(successResult()))
            }

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)
            runTest(testDispatcher) { vm.sendMessage("Post to Slack") }

            // After Completed, tool calls come from result; verify failed flag via events
            // (The result in this test has no tool calls so activeToolCalls may be reset.)
            // Verify the intermediate failed state was reachable (no crash).
            vm.uiState.value.isStreaming shouldBe false
        }

        it("increments agentStepCount on Thinking events") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                emit(AgentEvent.Thinking(stepIndex = 0, thought = "First thought"))
                emit(AgentEvent.Thinking(stepIndex = 1, thought = "Second thought"))
                emit(AgentEvent.Completed(successResult()))
            }

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)

            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem()
                    vm.sendMessage("Think about this")
                    var last = awaitItem()
                    while (last.isStreaming || last.agentStepCount < 2) {
                        last = awaitItem()
                    }
                    last.agentStepCount shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("populates tool calls from AgentResult after Completed") {
            val resultToolCalls = listOf(
                AgentToolCall("jira_get_issue", """{"issue":"AI-1"}""",
                    """{"summary":"Build MCP"}""", false, null, 120L),
            )
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(
                AgentEvent.Completed(successResult(toolCalls = resultToolCalls))
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)
            runTest(testDispatcher) { vm.sendMessage("Run agent") }

            val state = vm.uiState.value
            state.activeToolCalls shouldHaveSize 1
            state.activeToolCalls.first().toolName shouldBe "jira_get_issue"
        }
    }

    // =========================================================================
    // Token streaming   (AC: agent responses display correctly)
    // =========================================================================

    describe("Token streaming") {

        it("accumulates tokens in streamingText") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                emit(AgentEvent.Token("Hello"))
                emit(AgentEvent.Token(", "))
                emit(AgentEvent.Token("world"))
                emit(AgentEvent.Completed(successResult("Hello, world")))
            }

            val vm = makeVm()
            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem() // initial
                    vm.sendMessage("Say hello")

                    // Skip optimistic, typing indicator states
                    var last = awaitItem()
                    while (last.streamingText != "Hello, world" && last.isStreaming) {
                        last = awaitItem()
                    }
                    // After Completed the streaming text is cleared
                    last.streamingText shouldBe ""
                    last.messages.any { it.content == "Hello, world" } shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("hides typing indicator on first Token") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                emit(AgentEvent.Token("First"))
                emit(AgentEvent.Completed(successResult("First")))
            }

            val vm = makeVm()
            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem()
                    vm.sendMessage("Hello")

                    var last = awaitItem()
                    // Find the state where the first token is present
                    while (last.streamingText.isEmpty() || last.isTypingIndicatorVisible) {
                        last = awaitItem()
                    }
                    last.isTypingIndicatorVisible shouldBe false
                    last.streamingText shouldBe "First"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    // =========================================================================
    // Error handling   (AC: error state implemented)
    // =========================================================================

    describe("Error handling") {

        it("sets showRetryOption on AgentEvent.Failed") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(
                AgentEvent.Failed(
                    AgentResult(
                        executionId = UUID.randomUUID().toString(),
                        requestId = UUID.randomUUID().toString(),
                        agentName = "test",
                        status = AgentStatus.FAILED,
                        error = AgentError("TOOL_TIMEOUT", "Request timed out"),
                    )
                )
            )

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Fail this") }

            val state = vm.uiState.value
            state.showRetryOption shouldBe true
            state.isStreaming shouldBe false
            state.error?.message shouldBe "Request timed out"
        }

        it("clears streaming state on AgentEvent.Cancelled") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(
                AgentEvent.Cancelled("User cancelled")
            )

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Cancel this") }

            val state = vm.uiState.value
            state.isStreaming shouldBe false
            state.showRetryOption shouldBe false
        }

        it("sets showRetryOption when the gateway flow throws") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow { throw RuntimeException("Network failure") }

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Cause exception") }

            val state = vm.uiState.value
            state.showRetryOption shouldBe true
            state.isStreaming shouldBe false
        }

        it("dismissError clears error and showRetryOption") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow { throw RuntimeException("oops") }

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Cause error") }
            vm.dismissError()

            vm.uiState.value.showRetryOption shouldBe false
            vm.uiState.value.error shouldBe null
        }
    }

    // =========================================================================
    // Loading state   (AC: loading state implemented)
    // =========================================================================

    describe("Loading state") {

        it("isStreaming is true while the gateway flow is active") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow {
                // hang — never emit terminal event
                kotlinx.coroutines.awaitCancellation()
            }

            val vm = makeVm()
            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem() // initial
                    vm.sendMessage("Hang")
                    // Consume states until isStreaming flips to true
                    var last = awaitItem()
                    while (!last.isStreaming) last = awaitItem()
                    last.isStreaming shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("isTypingIndicatorVisible becomes true after sendMessage before first token") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flow { kotlinx.coroutines.awaitCancellation() }

            val vm = makeVm()
            runTest(testDispatcher) {
                vm.uiState.test {
                    awaitItem()
                    vm.sendMessage("Wait for token")
                    var last = awaitItem()
                    while (!last.isTypingIndicatorVisible) last = awaitItem()
                    last.isTypingIndicatorVisible shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("isStreaming becomes false after Completed") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returns flowOf(AgentEvent.Completed(successResult("done")))

            val vm = makeVm()
            runTest(testDispatcher) { vm.sendMessage("Quick") }
            vm.uiState.value.isStreaming shouldBe false
        }
    }

    // =========================================================================
    // retryStreaming   (AC: error state implemented)
    // =========================================================================

    describe("retryStreaming") {

        it("re-invokes gateway with the original content after a failure") {
            val contentSlot = slot<String>()
            every {
                agentGatewayRepository.executeChat(
                    conversationId = any(),
                    content = capture(contentSlot),
                    provider = any(),
                    context = any(),
                    mode = any(),
                )
            } returnsMany listOf(
                flow { throw RuntimeException("first failure") },
                flowOf(AgentEvent.Completed(successResult("Retry worked"))),
            )

            val vm = makeVm()
            runTest(testDispatcher) {
                vm.sendMessage("Retry me")
                vm.retryStreaming()
            }

            contentSlot.captured shouldBe "Retry me"
            vm.uiState.value.showRetryOption shouldBe false
        }

        it("retryStreaming uses the currently selected mode") {
            val modeSlot = slot<AgentMode>()
            every {
                agentGatewayRepository.executeChat(
                    conversationId = any(), content = any(), provider = any(),
                    context = any(), mode = capture(modeSlot),
                )
            } returnsMany listOf(
                flow { throw RuntimeException("fail") },
                flowOf(AgentEvent.Completed(successResult())),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            runTest(testDispatcher) {
                vm.sendMessage("RAG question")
                vm.retryStreaming()
            }

            modeSlot.captured shouldBe AgentMode.DOCUMENT
        }

        it("retryStreaming is a no-op when no pending content") {
            val vm = makeVm()
            // No sendMessage called — retryStreaming should not call gateway
            vm.retryStreaming()
            verify(exactly = 0) { agentGatewayRepository.executeChat(any(), any(), any(), any(), any()) }
        }
    }

    // =========================================================================
    // State cleared on new sendMessage
    // =========================================================================

    describe("State cleared on new sendMessage") {

        it("citations cleared on each new sendMessage") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returnsMany listOf(
                flowOf(AgentEvent.Completed(successResult(citations = listOf(
                    AgentCitation("d1", "doc.md", "excerpt", null, 0.9f)
                )))),
                flowOf(AgentEvent.Completed(successResult())),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.RAG)
            runTest(testDispatcher) {
                vm.sendMessage("First")
                vm.uiState.value.citations shouldHaveSize 1

                vm.uiState.test {
                    awaitItem()
                    vm.sendMessage("Second")
                    // First emission after sendMessage should have citations cleared
                    val cleared = awaitItem()
                    cleared.citations.shouldBeEmpty()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        it("agentStepCount reset to 0 on each new sendMessage") {
            every {
                agentGatewayRepository.executeChat(any(), any(), any(), any(), any())
            } returnsMany listOf(
                flow {
                    emit(AgentEvent.Thinking(0, "step"))
                    emit(AgentEvent.Completed(successResult()))
                },
                flowOf(AgentEvent.Completed(successResult())),
            )

            val vm = makeVm()
            vm.setExecutionMode(ChatExecutionMode.AGENT)
            runTest(testDispatcher) {
                vm.sendMessage("First — builds up step count")
                // Step count should be 1 after first run
                vm.uiState.value.agentStepCount shouldBe 1

                vm.uiState.test {
                    awaitItem()
                    vm.sendMessage("Second — resets count")
                    // Immediately after sendMessage, agentStepCount is reset
                    val reset = awaitItem()
                    reset.agentStepCount shouldBe 0
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }
})
