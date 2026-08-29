/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag (test)
 * File       : OnDeviceRagViewModelTest.kt
 * Purpose    : Unit tests for OnDeviceRagViewModel.
 *              Validates the spec's primary success and error StateFlow
 *              emissions using Turbine:
 *                1. "Running on device" indicator in UiState on ON_DEVICE routing.
 *                2. ChunkCitation list populated in UiState on Done event.
 *                3. Error state + retry option on Error event.
 *                4. NoRelevantContent state.
 *
 * Architecture Layer : Feature test — verifies ViewModel state transitions.
 *
 * Requirements: 21.1, 31.4
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import app.cash.turbine.test
import com.aiassistant.core.common.CapabilityBit
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DefaultDispatcherProvider
import com.aiassistant.domain.model.ChunkCitation
import com.aiassistant.domain.model.OnDeviceInferencePath
import com.aiassistant.domain.model.OnDeviceQueryEvent
import com.aiassistant.domain.model.OnDeviceRoutingDecision
import com.aiassistant.domain.usecase.ondevicerag.OnDeviceQueryUseCase
import com.aiassistant.domain.usecase.ondevicerag.RouteQueryUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceRagViewModelTest : DescribeSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeSpec {
        Dispatchers.setMain(testDispatcher)
    }

    afterSpec {
        Dispatchers.resetMain()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    fun onDeviceDecision() = OnDeviceRoutingDecision(
        path = OnDeviceInferencePath.ON_DEVICE,
        capabilityBitmask = CapabilityBit.FULLY_CAPABLE,
        reason = "All signals active",
    )

    fun cloudDecision() = OnDeviceRoutingDecision(
        path = OnDeviceInferencePath.CLOUD,
        capabilityBitmask = 0,
        reason = "No signals",
    )

    fun fakeCitation() = ChunkCitation(
        documentId = "doc1",
        documentName = "report.txt",
        chunkIndex = 0,
        pageNumber = null,
        excerpt = "Relevant excerpt",
        cosineSimilarity = 0.85f,
    )

    fun buildViewModel(
        routeUseCase: RouteQueryUseCase,
        queryUseCase: OnDeviceQueryUseCase,
    ): OnDeviceRagViewModel {
        val dispatchers = object : com.aiassistant.core.common.DispatcherProvider {
            override val main = testDispatcher
            override val mainImmediate = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
            override val unconfined = testDispatcher
        }
        return object : OnDeviceRagViewModel(routeUseCase, queryUseCase, dispatchers) {
            override fun buildCapabilityBitmask() = CapabilityBit.FULLY_CAPABLE
        }
    }

    // ── ON_DEVICE path — "Running on device" indicator ───────────────────────

    describe("submitQuery() — ON_DEVICE routing") {

        it("transitions to Searching with ON_DEVICE path after routing") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Success(onDeviceDecision())
                coEvery { queryUseCase(any(), any(), any()) } returns flowOf(
                    OnDeviceQueryEvent.Searching,
                    OnDeviceQueryEvent.Done(
                        tokensGenerated = 2,
                        generationTimeMs = 100,
                        citations = listOf(fakeCitation()),
                    ),
                )

                val vm = buildViewModel(routeUseCase, queryUseCase)
                vm.uiState.test {
                    awaitItem() // Idle

                    vm.submitQuery("What is X?")
                    testDispatcher.scheduler.advanceUntilIdle()

                    val states = cancelAndConsumeRemainingEvents()
                        .filterIsInstance<app.cash.turbine.Event.Item<OnDeviceRagChatUiState>>()
                        .map { it.value }

                    // Should contain a Done state with ON_DEVICE path
                    val doneState = states.filterIsInstance<OnDeviceRagChatUiState.Done>()
                        .firstOrNull()
                    doneState?.activePath shouldBe OnDeviceInferencePath.ON_DEVICE
                }
            }
        }

        it("Done state contains populated ChunkCitation list") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Success(onDeviceDecision())

                val citation = fakeCitation()
                coEvery { queryUseCase(any(), any(), any()) } returns flowOf(
                    OnDeviceQueryEvent.Token("The "),
                    OnDeviceQueryEvent.Done(
                        tokensGenerated = 1,
                        generationTimeMs = 50,
                        citations = listOf(citation),
                    ),
                )

                val vm = buildViewModel(routeUseCase, queryUseCase)

                vm.submitQuery("test")
                testDispatcher.scheduler.advanceUntilIdle()

                val state = vm.uiState.value
                state.shouldBeInstanceOf<OnDeviceRagChatUiState.Done>()
                (state as OnDeviceRagChatUiState.Done).citations.size shouldBe 1
                state.citations[0].documentId shouldBe "doc1"
            }
        }
    }

    // ── NoRelevantContent ────────────────────────────────────────────────────

    describe("submitQuery() — NoRelevantContent event") {

        it("transitions to NoRelevantContent state") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Success(onDeviceDecision())
                coEvery { queryUseCase(any(), any(), any()) } returns flowOf(
                    OnDeviceQueryEvent.Searching,
                    OnDeviceQueryEvent.NoRelevantContent,
                )

                val vm = buildViewModel(routeUseCase, queryUseCase)

                vm.submitQuery("obscure query")
                testDispatcher.scheduler.advanceUntilIdle()

                vm.uiState.value shouldBe OnDeviceRagChatUiState.NoRelevantContent
            }
        }
    }

    // ── Error state + retry ──────────────────────────────────────────────────

    describe("submitQuery() — Error event from query pipeline") {

        it("transitions to Error state with canRetry = true") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Success(onDeviceDecision())
                coEvery { queryUseCase(any(), any(), any()) } returns flowOf(
                    OnDeviceQueryEvent.Error("RAM exceeded", "generation"),
                )

                val vm = buildViewModel(routeUseCase, queryUseCase)

                vm.submitQuery("complex query")
                testDispatcher.scheduler.advanceUntilIdle()

                val state = vm.uiState.value
                state.shouldBeInstanceOf<OnDeviceRagChatUiState.Error>()
                (state as OnDeviceRagChatUiState.Error).canRetry shouldBe true
                state.stage shouldBe "generation"
            }
        }

        it("routing failure emits Error(stage=router)") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Error(
                        com.aiassistant.core.common.DomainError.ServerError("Routing DB error", 500)
                    )

                val vm = buildViewModel(routeUseCase, queryUseCase)

                vm.submitQuery("query")
                testDispatcher.scheduler.advanceUntilIdle()

                val state = vm.uiState.value
                state.shouldBeInstanceOf<OnDeviceRagChatUiState.Error>()
                (state as OnDeviceRagChatUiState.Error).stage shouldBe "router"
            }
        }
    }

    // ── reset() ──────────────────────────────────────────────────────────────

    describe("reset()") {

        it("returns to Idle state") {
            runTest {
                val routeUseCase = mockk<RouteQueryUseCase>()
                val queryUseCase = mockk<OnDeviceQueryUseCase>()

                coEvery { routeUseCase(any(), any(), any()) } returns
                    ApiResult.Success(onDeviceDecision())
                coEvery { queryUseCase(any(), any(), any()) } returns flowOf(
                    OnDeviceQueryEvent.NoRelevantContent
                )

                val vm = buildViewModel(routeUseCase, queryUseCase)
                vm.submitQuery("q")
                testDispatcher.scheduler.advanceUntilIdle()

                vm.reset()
                vm.uiState.value shouldBe OnDeviceRagChatUiState.Idle
            }
        }
    }
})
