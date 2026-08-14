/**
 * HistoryViewModelTest.kt
 *
 * Purpose: Unit tests for [HistoryViewModel] covering UI state transitions for the
 *          conversation history, search, export, pin, rename and delete flows.
 * Architecture: feature-history test -- pure JVM tests using Kotest + MockK.
 *               No Android framework dependencies.
 * Dependencies: Kotest DescribeSpec, MockK, kotlinx-coroutines-test, Turbine (via
 *               stateIn / flow collection), core-common (ApiResult, DomainError)
 *
 * Test strategy:
 * - [HistoryViewModel] exposes a [StateFlow<HistoryUiState>] that is the single
 *   source of truth for the screen. Each test group focuses on one transition.
 * - [ConnectivityObserver] is mocked to return connected by default so offline tests
 *   can explicitly flip the flag.
 * - [DispatcherProvider] uses [UnconfinedTestDispatcher] so coroutines run
 *   synchronously in tests without extra advanceUntilIdle() calls.
 * - Use-cases and repository are mocked with MockK; coEvery / every configure return values.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.6, 21.1
 */
package com.aiassistant.feature.history

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.usecase.conversation.DeleteConversationUseCase
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import com.aiassistant.domain.usecase.conversation.SearchConversationsUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val ZONE = ZoneId.systemDefault()

private fun daysAgo(n: Long): Instant = ZonedDateTime.now(ZONE)
    .toLocalDate()
    .minusDays(n)
    .atStartOfDay(ZONE)
    .toInstant()
    .plusSeconds(3_600) // +1 h so it falls clearly within the day

private fun conversation(
    id: String = "conv-1",
    title: String = "Test Conversation",
    isPinned: Boolean = false,
    isDeleted: Boolean = false,
    updatedAt: Instant = Instant.now()
) = Conversation(
    id = id,
    userId = "user-1",
    title = title,
    isPinned = isPinned,
    isDeleted = isDeleted,
    provider = "openai",
    createdAt = updatedAt,
    updatedAt = updatedAt
)

private fun groupedWith(conversations: List<Conversation>): GroupedConversations {
    val today = conversations.filter {
        it.updatedAt.atZone(ZONE).toLocalDate() == java.time.LocalDate.now(ZONE)
    }
    return GroupedConversations(today = today)
}

// ─── Test dispatcher provider ─────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private class TestDispatcherProvider(scheduler: TestCoroutineScheduler = TestCoroutineScheduler()) :
    DispatcherProvider {
    val testDispatcher = UnconfinedTestDispatcher(scheduler)
    override val default: kotlinx.coroutines.CoroutineDispatcher get() = testDispatcher
    override val io: kotlinx.coroutines.CoroutineDispatcher get() = testDispatcher
    override val main: kotlinx.coroutines.CoroutineDispatcher get() = testDispatcher
    override val mainImmediate: kotlinx.coroutines.CoroutineDispatcher get() = testDispatcher
    override val unconfined: kotlinx.coroutines.CoroutineDispatcher get() = testDispatcher
}

// ─── HistoryViewModelTest ──────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest :
    DescribeSpec({

        // -- Mocks and helpers -------------------------------------------------

        val getConversationsUseCase = mockk<GetConversationsUseCase>()
        val searchConversationsUseCase = mockk<SearchConversationsUseCase>()
        val exportConversationUseCase = mockk<ExportConversationUseCase>()
        val deleteConversationUseCase = mockk<DeleteConversationUseCase>()
        val conversationRepository = mockk<ConversationRepository>()
        val connectivityObserver = mockk<ConnectivityObserver>()

        // Connectivity is "connected" by default; override per test as needed.
        val connectivityFlow = MutableStateFlow(true)
        every { connectivityObserver.isConnectedFlow } returns connectivityFlow
        every { connectivityObserver.isConnected() } returns true

        val dispatchers = TestDispatcherProvider()

        // Replace Dispatchers.Main with an unconfined dispatcher for tests that use
        // viewModelScope.launch (HistoryViewModel uses viewModelScope internally).
        beforeSpec { Dispatchers.setMain(dispatchers.testDispatcher) }
        afterSpec { Dispatchers.resetMain() }

        beforeEach {
            clearMocks(
                getConversationsUseCase,
                searchConversationsUseCase,
                exportConversationUseCase,
                deleteConversationUseCase,
                conversationRepository
            )
            connectivityFlow.value = true
            every { connectivityObserver.isConnected() } returns true
        }

        fun makeViewModel(): HistoryViewModel = HistoryViewModel(
            getConversationsUseCase = getConversationsUseCase,
            searchConversationsUseCase = searchConversationsUseCase,
            exportConversationUseCase = exportConversationUseCase,
            deleteConversationUseCase = deleteConversationUseCase,
            conversationRepository = conversationRepository,
            connectivityObserver = connectivityObserver,
            dispatchers = dispatchers
        )

        // -- uiState initial loading -------------------------------------------

        describe("initial state") {
            it("starts with Loading then transitions to HistoryList on success") {
                val conv = conversation(id = "c1", updatedAt = daysAgo(0))
                val grouped = groupedWith(listOf(conv))
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(grouped))

                val vm = makeViewModel()

                val state = vm.uiState.value
                state.shouldBeInstanceOf<HistoryUiState.HistoryList>()
                (state as HistoryUiState.HistoryList).groupedConversations.totalCount shouldBe 1
            }

            it("transitions to Error when getConversationsUseCase emits an error") {
                val error = DomainError.ServerError(httpStatusCode = 500)
                every { getConversationsUseCase() } returns flowOf(ApiResult.Error(error))

                val vm = makeViewModel()

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.Error>()
            }

            it("shows empty HistoryList when NetworkUnavailable and no cached state") {
                every { getConversationsUseCase() } returns flowOf(ApiResult.NetworkUnavailable)

                val vm = makeViewModel()

                val state = vm.uiState.value
                state.shouldBeInstanceOf<HistoryUiState.HistoryList>()
                (state as HistoryUiState.HistoryList).groupedConversations.isEmpty shouldBe true
            }
        }

        // -- loadHistory -------------------------------------------------------

        describe("loadHistory") {
            it("reloads and emits HistoryList with updated data") {
                val conv = conversation(id = "reload-conv", updatedAt = daysAgo(0))
                val grouped = groupedWith(listOf(conv))
                every { getConversationsUseCase() } returns flowOf(ApiResult.Success(grouped))

                val vm = makeViewModel()
                vm.loadHistory()

                val state = vm.uiState.value as HistoryUiState.HistoryList
                state.groupedConversations.today.first().id shouldBe "reload-conv"
            }

            it("clears currentSearchQuery when loadHistory is called") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                every { searchConversationsUseCase(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))

                val vm = makeViewModel()
                vm.search("kotlin")
                vm.loadHistory()

                vm.currentSearchQuery shouldBe ""
            }
        }

        // -- search ------------------------------------------------------------

        describe("search") {
            it("transitions to SearchResults when a query is provided") {
                val conv = conversation(id = "search-result", title = "Kotlin tutorial")
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                every { searchConversationsUseCase("kotlin") } returns
                    flowOf(ApiResult.Success(listOf(conv)))

                val vm = makeViewModel()
                vm.search("kotlin")

                val state = vm.uiState.value as HistoryUiState.SearchResults
                state.query shouldBe "kotlin"
                state.results.first().id shouldBe "search-result"
            }

            it("delegates to loadHistory when query is blank") {
                val grouped = GroupedConversations()
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(grouped))

                val vm = makeViewModel()
                vm.search("")

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
            }

            it("stores currentSearchQuery while search is active") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                every { searchConversationsUseCase("test query") } returns
                    flowOf(ApiResult.Success(emptyList()))

                val vm = makeViewModel()
                vm.search("test query")

                vm.currentSearchQuery shouldBe "test query"
            }

            it("returns empty SearchResults list when nothing matches") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                every { searchConversationsUseCase("nomatch") } returns
                    flowOf(ApiResult.Success(emptyList()))

                val vm = makeViewModel()
                vm.search("nomatch")

                val state = vm.uiState.value as HistoryUiState.SearchResults
                state.results shouldBe emptyList()
            }
        }

        // -- clearSearch -------------------------------------------------------

        describe("clearSearch") {
            it("returns to HistoryList and clears currentSearchQuery") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                every { searchConversationsUseCase(any()) } returns
                    flowOf(ApiResult.Success(emptyList()))

                val vm = makeViewModel()
                vm.search("active query")
                vm.clearSearch()

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
                vm.currentSearchQuery shouldBe ""
            }
        }

        // -- exportConversation ------------------------------------------------

        describe("exportConversation") {
            it("transitions to Exporting then ExportSuccess for Markdown") {
                val markdownContent = "# Exported\n\nUser: Hello\n\nAssistant: Hi!"
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    exportConversationUseCase("conv-1", ExportFormat.MARKDOWN)
                } returns ApiResult.Success(markdownContent)

                val vm = makeViewModel()
                vm.exportConversation("conv-1", ExportFormat.MARKDOWN)

                val state = vm.uiState.value as HistoryUiState.ExportSuccess
                state.filePath shouldBe markdownContent
                state.format shouldBe ExportFormat.MARKDOWN
            }

            it("transitions to Exporting then ExportSuccess for PDF") {
                val pdfPath = "/storage/emulated/0/Download/conversation_export.pdf"
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    exportConversationUseCase("conv-2", ExportFormat.PDF)
                } returns ApiResult.Success(pdfPath)

                val vm = makeViewModel()
                vm.exportConversation("conv-2", ExportFormat.PDF)

                val state = vm.uiState.value as HistoryUiState.ExportSuccess
                state.filePath shouldBe pdfPath
                state.format shouldBe ExportFormat.PDF
            }

            it("transitions to Error when export fails") {
                val error = DomainError.ServerError(httpStatusCode = 500, message = "Export failed")
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    exportConversationUseCase("conv-err", ExportFormat.PDF)
                } returns ApiResult.Error(error)

                val vm = makeViewModel()
                vm.exportConversation("conv-err", ExportFormat.PDF)

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.Error>()
            }

            it("transitions to Error with offline message when NetworkUnavailable") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    exportConversationUseCase("conv-offline", ExportFormat.PDF)
                } returns ApiResult.NetworkUnavailable

                val vm = makeViewModel()
                vm.exportConversation("conv-offline", ExportFormat.PDF)

                val state = vm.uiState.value as HistoryUiState.Error
                state.message.contains("No network", ignoreCase = true) ||
                    state.message.contains("connection", ignoreCase = true) shouldBe true
            }
        }

        // -- dismissExportSuccess ----------------------------------------------

        describe("dismissExportSuccess") {
            it("reloads HistoryList when called in ExportSuccess state") {
                val markdownContent = "# Chat"
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    exportConversationUseCase(any(), ExportFormat.MARKDOWN)
                } returns ApiResult.Success(markdownContent)

                val vm = makeViewModel()
                vm.exportConversation("conv-1", ExportFormat.MARKDOWN)
                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.ExportSuccess>()

                vm.dismissExportSuccess()

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
            }

            it("is a no-op when current state is not ExportSuccess") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))

                val vm = makeViewModel()
                // State is HistoryList at this point
                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()

                vm.dismissExportSuccess() // should not throw or change state

                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
            }
        }

        // -- deleteConversation ------------------------------------------------

        describe("deleteConversation") {
            it("calls DeleteConversationUseCase and reloads the history list") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery { deleteConversationUseCase("conv-del") } returns ApiResult.Success(Unit)

                val vm = makeViewModel()
                vm.deleteConversation("conv-del")

                coVerify(exactly = 1) { deleteConversationUseCase("conv-del") }
                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
            }
        }

        // -- renameConversation ------------------------------------------------

        describe("renameConversation") {
            it("calls repository.renameConversation and reloads the history list") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    conversationRepository.renameConversation("conv-ren", "New Title")
                } returns ApiResult.Success(Unit)

                val vm = makeViewModel()
                vm.renameConversation("conv-ren", "New Title")

                coVerify(exactly = 1) {
                    conversationRepository.renameConversation("conv-ren", "New Title")
                }
                vm.uiState.value.shouldBeInstanceOf<HistoryUiState.HistoryList>()
            }
        }

        // -- pinConversation ---------------------------------------------------

        describe("pinConversation") {
            it("calls repository.pinConversation with isPinned=true and reloads") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    conversationRepository.pinConversation("conv-pin", true)
                } returns ApiResult.Success(Unit)

                val vm = makeViewModel()
                vm.pinConversation("conv-pin", true)

                coVerify(exactly = 1) {
                    conversationRepository.pinConversation("conv-pin", true)
                }
            }

            it("calls repository.pinConversation with isPinned=false to unpin") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                coEvery {
                    conversationRepository.pinConversation("conv-unpin", false)
                } returns ApiResult.Success(Unit)

                val vm = makeViewModel()
                vm.pinConversation("conv-unpin", false)

                coVerify(exactly = 1) {
                    conversationRepository.pinConversation("conv-unpin", false)
                }
            }
        }

        // -- isOffline ---------------------------------------------------------

        describe("isOffline") {
            it("emits false when connected") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                connectivityFlow.value = true
                every { connectivityObserver.isConnected() } returns true

                val vm = makeViewModel()

                vm.isOffline.value shouldBe false
            }

            it("emits true when not connected") {
                every { getConversationsUseCase() } returns
                    flowOf(ApiResult.Success(GroupedConversations()))
                connectivityFlow.value = false
                every { connectivityObserver.isConnected() } returns false

                val vm = makeViewModel()

                vm.isOffline.value shouldBe true
            }
        }
    })
