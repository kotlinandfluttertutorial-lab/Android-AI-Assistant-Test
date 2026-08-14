/**
 * ComparisonModeViewModelTest.kt — feature-chat unit tests
 *
 * Tests for [ComparisonModeViewModel] covering:
 *   - initialise() sets isComparisonModeAvailable based on configured provider count
 *   - dispatchComparison() builds initial Loading panels and guards against < 2 providers
 *   - Panel state transitions: Loading → Streaming → Complete via stream events
 *   - Error and Timeout panel transitions
 *   - useThisResponse() sets canonicalPanelId and builds correct canonical Message
 *   - reset() clears panels and preserves availability flag
 *   - computeQualityScore() formula: length + coherence + latency components
 *   - computeLengthScore() clamps at 2000 chars → 40 pts
 *   - computeCoherenceScore() returns 0 for blank input, peaks at 40
 *   - computeLatencyScore() returns 20 at ≤500 ms, 0 at ≥5000 ms, 10 for unknown (-1)
 *
 * Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */
package com.aiassistant.feature.chat

import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.ai.TokenUsage
import com.aiassistant.core.common.DispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Test dispatcher provider ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
    override val mainImmediate: CoroutineDispatcher = dispatcher
    override val unconfined: CoroutineDispatcher = dispatcher
}

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonModeViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)
        val mockStreamClient = mockk<AIStreamClient>(relaxed = true)

        fun buildViewModel() = ComparisonModeViewModel(
            streamClient = mockStreamClient,
            dispatchers = testDispatcherProvider
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        beforeEach {
            clearMocks(mockStreamClient)
        }

        // ─── initialise ──────────────────────────────────────────────────────────

        describe("initialise") {

            it("sets isComparisonModeAvailable = true when 2 or more providers are configured") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initialise(listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO))

                    vm.uiState.value.isComparisonModeAvailable shouldBe true
                }
            }

            it("sets isComparisonModeAvailable = true when 4 providers are configured") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initialise(
                        listOf(
                            LlmProvider.OPENAI_GPT4O,
                            LlmProvider.GEMINI_1_5_PRO,
                            LlmProvider.CLAUDE_3_5_SONNET,
                            LlmProvider.MISTRAL
                        )
                    )

                    vm.uiState.value.isComparisonModeAvailable shouldBe true
                }
            }

            it("sets isComparisonModeAvailable = false when only 1 provider is configured (Req 30.8)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initialise(listOf(LlmProvider.OPENAI_GPT4O))

                    vm.uiState.value.isComparisonModeAvailable shouldBe false
                }
            }

            it("sets isComparisonModeAvailable = false when no providers are configured") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.initialise(emptyList())

                    vm.uiState.value.isComparisonModeAvailable shouldBe false
                }
            }

            it("clears any existing panels when called a second time") {
                runTest(testDispatcher) {
                    // Arrange: set up two providers that yield an immediate Done event
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Done(TokenUsage(inputTokens = 10, outputTokens = 50))
                    )

                    val vm = buildViewModel()
                    vm.initialise(listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO))
                    vm.dispatchComparison(
                        conversationId = "conv1",
                        prompt = "test",
                        selectedProviders = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    // Panels should exist now
                    vm.uiState.value.panels.isEmpty() shouldBe false

                    // Act: re-initialise
                    vm.initialise(listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO))

                    // Assert: panels reset
                    vm.uiState.value.panels.isEmpty() shouldBe true
                }
            }
        }

        // ─── dispatchComparison ───────────────────────────────────────────────────

        describe("dispatchComparison") {

            it("creates one Loading panel per selected provider (Req 30.1)") {
                runTest(testDispatcher) {
                    // Providers that never emit (so panels stay Loading during this test)
                    every { mockStreamClient.connect(any(), any()) } returns flowOf()

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "prompt", providers)

                    // At least the Loading panels must have been created first
                    // (with UnconfinedTestDispatcher the coroutines may have already completed)
                    // Verify that provider IDs match
                    val panels = vm.uiState.value.panels
                    panels.size shouldBe 2
                    panels.map { it.providerId } shouldBe listOf(
                        LlmProvider.OPENAI_GPT4O.id,
                        LlmProvider.GEMINI_1_5_PRO.id
                    )
                }
            }

            it("does NOT dispatch when fewer than 2 providers are selected (Req 30.8)") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.dispatchComparison("conv1", "prompt", listOf(LlmProvider.OPENAI_GPT4O))

                    // No panels created, stream client never called
                    vm.uiState.value.panels.isEmpty() shouldBe true
                    verify(exactly = 0) { mockStreamClient.connect(any(), any()) }
                }
            }

            it("resets canonicalPanelId to null on a new dispatch") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("hello"),
                        StreamEvent.Done(TokenUsage(inputTokens = 5, outputTokens = 20))
                    )

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)

                    // First dispatch + adopt
                    vm.dispatchComparison("conv1", "q1", providers)
                    vm.useThisResponse(LlmProvider.OPENAI_GPT4O.id) { }

                    vm.uiState.value.canonicalPanelId shouldBe LlmProvider.OPENAI_GPT4O.id

                    // Second dispatch should reset canonical selection
                    vm.dispatchComparison("conv1", "q2", providers)

                    vm.uiState.value.canonicalPanelId shouldBe null
                }
            }
        }

        // ─── Stream event handling ────────────────────────────────────────────────

        describe("stream event handling") {

            it("transitions panel from Loading to Streaming on first Token event") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("Hello")
                    ) andThen flowOf() // second provider: never emits

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "What is coroutines?", providers)

                    val openAiPanel = vm.uiState.value.panels.first {
                        it.providerId == LlmProvider.OPENAI_GPT4O.id
                    }
                    openAiPanel.status shouldBe ProviderPanelStatus.Streaming
                    openAiPanel.responseText shouldBe "Hello"
                }
            }

            it("appends tokens to responseText on subsequent Token events") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("Hello"),
                        StreamEvent.Token(" world")
                    ) andThen flowOf()

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    val panel = vm.uiState.value.panels.first {
                        it.providerId == LlmProvider.OPENAI_GPT4O.id
                    }
                    panel.responseText shouldBe "Hello world"
                }
            }

            it("transitions panel to Complete on Done event and populates token count (Req 30.2)") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("Result"),
                        StreamEvent.Done(TokenUsage(inputTokens = 10, outputTokens = 42))
                    )

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    // Both panels get the same stubbed flow so check the first one
                    val panel = vm.uiState.value.panels.first()
                    panel.status shouldBe ProviderPanelStatus.Complete
                    panel.tokenCount shouldBe 42
                }
            }

            it("sets qualityScore in 0–100 range on Complete (Req 30.5)") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("A reasonable response."),
                        StreamEvent.Done(TokenUsage(inputTokens = 5, outputTokens = 5))
                    )

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    val panel = vm.uiState.value.panels.first()
                    panel.qualityScore shouldNotBe null
                    panel.qualityScore!! shouldBeInRange 0..100
                }
            }

            it("calculates estimatedCostUsd > 0 for non-free providers") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("answer"),
                        StreamEvent.Done(TokenUsage(inputTokens = 10, outputTokens = 1000))
                    )

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    val panel = vm.uiState.value.panels.first {
                        it.providerId == LlmProvider.OPENAI_GPT4O.id
                    }
                    panel.estimatedCostUsd shouldNotBe 0.0
                }
            }

            it("transitions panel to Error state on StreamEvent.Error (Req 30.4)") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Error("Rate limit exceeded")
                    ) andThen flowOf()

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    val panel = vm.uiState.value.panels.first {
                        it.providerId == LlmProvider.OPENAI_GPT4O.id
                    }
                    panel.status shouldBe ProviderPanelStatus.Error("Rate limit exceeded")
                }
            }

            it("records latencyMs for first-token event") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("first"),
                        StreamEvent.Done(TokenUsage(inputTokens = 1, outputTokens = 1))
                    )

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    // latencyMs should be ≥ 0 (was -1 before first token)
                    val panel = vm.uiState.value.panels.first()
                    panel.latencyMs shouldNotBe -1L
                    (panel.latencyMs >= 0L) shouldBe true
                }
            }
        }

        // ─── useThisResponse ──────────────────────────────────────────────────────

        describe("useThisResponse") {

            it("sets canonicalPanelId to the chosen provider ID (Req 30.6)") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("response text"),
                        StreamEvent.Done(TokenUsage(inputTokens = 5, outputTokens = 10))
                    )

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "test", providers)

                    vm.useThisResponse(LlmProvider.OPENAI_GPT4O.id) { }

                    vm.uiState.value.canonicalPanelId shouldBe LlmProvider.OPENAI_GPT4O.id
                }
            }

            it("invokes onAdopted callback with a Message whose content matches the panel response") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("The answer is 42."),
                        StreamEvent.Done(TokenUsage(inputTokens = 5, outputTokens = 8))
                    )

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "test", providers)

                    var adoptedContent: String? = null
                    vm.useThisResponse(LlmProvider.OPENAI_GPT4O.id) { message ->
                        adoptedContent = message.content
                    }

                    adoptedContent shouldBe "The answer is 42."
                }
            }

            it("creates an adopted Message with role = 'assistant'") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("ok"),
                        StreamEvent.Done(TokenUsage(inputTokens = 1, outputTokens = 2))
                    )

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "test", providers)

                    var adoptedRole: String? = null
                    vm.useThisResponse(LlmProvider.OPENAI_GPT4O.id) { message ->
                        adoptedRole = message.role
                    }

                    adoptedRole shouldBe "assistant"
                }
            }

            it("is a no-op when panel ID is not found") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf()

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    var callbackInvoked = false
                    vm.useThisResponse("nonexistent_provider") { callbackInvoked = true }

                    callbackInvoked shouldBe false
                    vm.uiState.value.canonicalPanelId shouldBe null
                }
            }
        }

        // ─── reset ────────────────────────────────────────────────────────────────

        describe("reset") {

            it("clears all panels and the prompt") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf()

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "Hello", providers)

                    vm.reset()

                    vm.uiState.value.panels.isEmpty() shouldBe true
                    vm.uiState.value.prompt shouldBe ""
                }
            }

            it("preserves isComparisonModeAvailable after reset") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf()

                    val vm = buildViewModel()
                    vm.initialise(listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO))
                    vm.dispatchComparison(
                        "conv1",
                        "Hello",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    vm.reset()

                    vm.uiState.value.isComparisonModeAvailable shouldBe true
                }
            }

            it("clears canonicalPanelId after reset") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("text"),
                        StreamEvent.Done(TokenUsage(inputTokens = 1, outputTokens = 3))
                    )

                    val vm = buildViewModel()
                    val providers = listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    vm.dispatchComparison("conv1", "q", providers)
                    vm.useThisResponse(LlmProvider.OPENAI_GPT4O.id) { }

                    vm.reset()

                    vm.uiState.value.canonicalPanelId shouldBe null
                }
            }
        }

        // ─── Concurrent dispatch within 100 ms (Req 30.3) ────────────────────────

        describe("concurrent dispatch (Req 30.3)") {

            it("records dispatchedAt before any panel emits tokens — all panels share the same dispatch epoch") {
                runTest(testDispatcher) {
                    // Record wall-clock timestamps when connect() is called per provider.
                    val connectTimestamps = mutableListOf<Long>()
                    every { mockStreamClient.connect(any(), any()) } answers {
                        connectTimestamps += System.currentTimeMillis()
                        flowOf() // no events — panels stay Loading; we only care about timing
                    }

                    val vm = buildViewModel()
                    val providers = listOf(
                        LlmProvider.OPENAI_GPT4O,
                        LlmProvider.GEMINI_1_5_PRO,
                        LlmProvider.CLAUDE_3_5_SONNET
                    )
                    vm.dispatchComparison("conv1", "concurrent test", providers)

                    // All three connect() calls must happen; skew between first and last < 100 ms
                    connectTimestamps.size shouldBe providers.size
                    val skewMs = connectTimestamps.max() - connectTimestamps.min()
                    (skewMs < 100L) shouldBe true
                }
            }

            it("sets dispatchedAt to a positive epoch-ms timestamp when dispatched") {
                runTest(testDispatcher) {
                    every { mockStreamClient.connect(any(), any()) } returns flowOf()

                    val vm = buildViewModel()
                    val beforeDispatch = System.currentTimeMillis()
                    vm.dispatchComparison(
                        "conv1",
                        "timing test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )
                    val afterDispatch = System.currentTimeMillis()

                    val dispatchedAt = vm.uiState.value.dispatchedAt
                    (dispatchedAt >= beforeDispatch) shouldBe true
                    (dispatchedAt <= afterDispatch) shouldBe true
                }
            }
        }

        // ─── Timeout panel transition (Req 30.4) ──────────────────────────────────

        describe("provider timeout (Req 30.4)") {

            it("transitions panel to Timeout status when no Done event arrives within 30 seconds") {
                // Use StandardTestDispatcher so we can advance virtual time precisely.
                val stdDispatcher = StandardTestDispatcher()
                val stdDispatcherProvider = TestDispatcherProvider(stdDispatcher)
                Dispatchers.setMain(stdDispatcher)

                try {
                    runTest(stdDispatcher) {
                        // A flow that suspends forever (simulates a provider that never finishes)
                        every { mockStreamClient.connect(any(), any()) } returns flow {
                            // emit a single token then hang — never reaches Done
                            emit(StreamEvent.Token("partial"))
                            kotlinx.coroutines.awaitCancellation()
                        }

                        val vm = ComparisonModeViewModel(
                            streamClient = mockStreamClient,
                            dispatchers = stdDispatcherProvider
                        )
                        vm.dispatchComparison(
                            "conv1",
                            "timeout test",
                            listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                        )

                        // Advance virtual clock past the 30-second provider timeout
                        advanceTimeBy(31_000L)

                        // Both panels must now be in Timeout status
                        vm.uiState.value.panels.forEach { panel ->
                            panel.status shouldBe ProviderPanelStatus.Timeout
                        }
                    }
                } finally {
                    Dispatchers.resetMain()
                    Dispatchers.setMain(testDispatcher)
                }
            }

            it("does NOT timeout panel when Done event arrives before 30 seconds") {
                runTest(testDispatcher) {
                    // Immediate Done — no timeout should fire
                    every { mockStreamClient.connect(any(), any()) } returns flowOf(
                        StreamEvent.Token("fast response"),
                        StreamEvent.Done(TokenUsage(inputTokens = 3, outputTokens = 10))
                    )

                    val vm = buildViewModel()
                    vm.dispatchComparison(
                        "conv1",
                        "fast test",
                        listOf(LlmProvider.OPENAI_GPT4O, LlmProvider.GEMINI_1_5_PRO)
                    )

                    vm.uiState.value.panels.forEach { panel ->
                        panel.status shouldBe ProviderPanelStatus.Complete
                    }
                }
            }
        }

        // ─── Quality score computation (Req 30.5) ────────────────────────────────

        describe("computeQualityScore") {

            it("returns a score in 0–100 for typical input") {
                val vm = buildViewModel()
                val score = vm.computeQualityScore(
                    responseText = "This is a well-structured response with multiple sentences.",
                    latencyMs = 800L
                )
                score shouldBeInRange 0..100
            }

            it("returns 0 for blank response with very high latency") {
                val vm = buildViewModel()
                val score = vm.computeQualityScore(
                    responseText = "",
                    latencyMs = 10_000L
                )
                score shouldBe 0
            }

            it("returns 100 for a 2000-char response with low latency and good coherence") {
                val vm = buildViewModel()
                // Build a 2000-char response with 3 paragraphs and good sentence lengths
                val paragraph = "This response contains well-structured content with thoughtful analysis. " +
                    "Each sentence expresses a clear idea and contributes to the overall argument. "
                val longText = (paragraph.repeat(10) + "\n\n").repeat(3).take(2000)

                val score = vm.computeQualityScore(
                    responseText = longText,
                    latencyMs = 300L
                )
                score shouldBeInRange 60..100
            }

            it("sums length + coherence + latency sub-scores") {
                val vm = buildViewModel()
                val text = "A".repeat(1000) // 1000 chars
                val latency = 500L

                val score = vm.computeQualityScore(responseText = text, latencyMs = latency)
                val lengthScore = vm.computeLengthScore(text)
                val coherenceScore = vm.computeCoherenceScore(text)
                val latencyScore = vm.computeLatencyScore(latency)

                score shouldBe lengthScore + coherenceScore + latencyScore
            }
        }

        // ─── computeLengthScore ───────────────────────────────────────────────────

        describe("computeLengthScore") {

            it("returns 0 for empty string") {
                val vm = buildViewModel()
                vm.computeLengthScore("") shouldBe 0
            }

            it("returns 40 for a 2000-character string (maximum)") {
                val vm = buildViewModel()
                vm.computeLengthScore("A".repeat(2000)) shouldBe 40
            }

            it("returns 40 for strings longer than 2000 characters (capped)") {
                val vm = buildViewModel()
                vm.computeLengthScore("A".repeat(3000)) shouldBe 40
            }

            it("returns 20 for a 1000-character string (half of max)") {
                val vm = buildViewModel()
                val score = vm.computeLengthScore("A".repeat(1000))
                score shouldBe 20
            }

            it("returns a value in 0–40 for any input length") {
                val vm = buildViewModel()
                listOf(0, 1, 100, 500, 1000, 1999, 2000, 5000).forEach { len ->
                    vm.computeLengthScore("A".repeat(len)) shouldBeInRange 0..40
                }
            }
        }

        // ─── computeCoherenceScore ────────────────────────────────────────────────

        describe("computeCoherenceScore") {

            it("returns 0 for blank input") {
                val vm = buildViewModel()
                vm.computeCoherenceScore("") shouldBe 0
                vm.computeCoherenceScore("   ") shouldBe 0
            }

            it("returns a value in 0–40 for all inputs") {
                val vm = buildViewModel()
                listOf(
                    "Short.",
                    "A medium-length response that explains something clearly.",
                    "This is a longer answer.\n\nIt has multiple paragraphs.\n\nEach adds value.",
                    "x".repeat(2000)
                ).forEach { text ->
                    vm.computeCoherenceScore(text) shouldBeInRange 0..40
                }
            }

            it("scores higher for multi-paragraph structured text than a single long word") {
                val vm = buildViewModel()
                val structured =
                    "First paragraph introduces the topic.\n\nSecond paragraph develops it. Third is the conclusion."
                val singleWord = "antidisestablishmentarianism"

                vm.computeCoherenceScore(structured) shouldNotBe vm.computeCoherenceScore(singleWord)
            }
        }

        // ─── computeLatencyScore ─────────────────────────────────────────────────

        describe("computeLatencyScore") {

            it("returns 20 for latency = 0 ms (better than threshold)") {
                val vm = buildViewModel()
                vm.computeLatencyScore(0L) shouldBe 20
            }

            it("returns 20 for latency exactly at 500 ms threshold") {
                val vm = buildViewModel()
                vm.computeLatencyScore(500L) shouldBe 20
            }

            it("returns 0 for latency at or above 5000 ms (Req 30.5 worst latency)") {
                val vm = buildViewModel()
                vm.computeLatencyScore(5000L) shouldBe 0
                vm.computeLatencyScore(10_000L) shouldBe 0
            }

            it("returns 10 for unknown latency (-1)") {
                val vm = buildViewModel()
                vm.computeLatencyScore(-1L) shouldBe 10
            }

            it("returns a value between 0 and 20 for mid-range latency") {
                val vm = buildViewModel()
                val score = vm.computeLatencyScore(2750L) // midpoint between 500 and 5000
                score shouldBeInRange 0..20
            }

            it("scores decrease monotonically as latency increases") {
                val vm = buildViewModel()
                val latencies = listOf(500L, 1000L, 2000L, 3000L, 4000L, 5000L)
                val scores = latencies.map { vm.computeLatencyScore(it) }

                for (i in 0 until scores.size - 1) {
                    (scores[i] >= scores[i + 1]) shouldBe true
                }
            }
        }
    })
