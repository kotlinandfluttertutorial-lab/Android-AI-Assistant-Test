package com.aiassistant.feature.chat

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.GroupedConversations
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.usecase.conversation.CreateConversationUseCase
import com.aiassistant.domain.usecase.conversation.DeleteConversationUseCase
import com.aiassistant.domain.usecase.conversation.GetConversationsUseCase
import com.aiassistant.domain.usecase.conversation.SearchConversationsUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest :
    DescribeSpec({

        val getConversationsUseCase = mockk<GetConversationsUseCase>()
        val createConversationUseCase = mockk<CreateConversationUseCase>()
        val deleteConversationUseCase = mockk<DeleteConversationUseCase>()
        val searchConversationsUseCase = mockk<SearchConversationsUseCase>()
        val conversationRepository = mockk<ConversationRepository>()
        val connectivityObserver = mockk<ConnectivityObserver>()

        val testDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val mainImmediate = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
            override val unconfined = testDispatcher
        }

        val isConnectedFlow = MutableStateFlow(true)

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(
                getConversationsUseCase,
                createConversationUseCase,
                deleteConversationUseCase,
                searchConversationsUseCase,
                conversationRepository,
                connectivityObserver
            )
            every { connectivityObserver.isConnectedFlow } returns isConnectedFlow
            every { connectivityObserver.isConnected() } returns isConnectedFlow.value
        }

        describe("ChatViewModel") {

            it("initially emits Loading state") {
                every { getConversationsUseCase() } returns flowOf(ApiResult.Loading)

                val viewModel = ChatViewModel(
                    getConversationsUseCase,
                    createConversationUseCase,
                    deleteConversationUseCase,
                    searchConversationsUseCase,
                    conversationRepository,
                    connectivityObserver,
                    dispatchers
                )

                viewModel.uiState.value shouldBe ChatListUiState.Loading
            }

            it("emits Success when conversations are loaded") {
                val grouped = GroupedConversations(
                    today = listOf(
                        Conversation("1", "u1", "Title", false, false, "openai", Instant.now(), Instant.now())
                    ),
                    yesterday = emptyList(),
                    last7Days = emptyList(),
                    older = emptyList()
                )
                every { getConversationsUseCase() } returns flowOf(ApiResult.Success(grouped))

                val viewModel = ChatViewModel(
                    getConversationsUseCase,
                    createConversationUseCase,
                    deleteConversationUseCase,
                    searchConversationsUseCase,
                    conversationRepository,
                    connectivityObserver,
                    dispatchers
                )

                viewModel.uiState.test {
                    awaitItem() shouldBe ChatListUiState.Success(grouped, isOffline = false)
                }
            }

            it("emits Empty state when no conversations exist") {
                val emptyGrouped = GroupedConversations(emptyList(), emptyList(), emptyList(), emptyList())
                every { getConversationsUseCase() } returns flowOf(ApiResult.Success(emptyGrouped))

                val viewModel = ChatViewModel(
                    getConversationsUseCase,
                    createConversationUseCase,
                    deleteConversationUseCase,
                    searchConversationsUseCase,
                    conversationRepository,
                    connectivityObserver,
                    dispatchers
                )

                viewModel.uiState.test {
                    awaitItem() shouldBe ChatListUiState.Empty(isOffline = false)
                }
            }

            it("updates isOffline when connectivity changes") {
                every { getConversationsUseCase() } returns flowOf(ApiResult.Loading)

                val viewModel = ChatViewModel(
                    getConversationsUseCase,
                    createConversationUseCase,
                    deleteConversationUseCase,
                    searchConversationsUseCase,
                    conversationRepository,
                    connectivityObserver,
                    dispatchers
                )

                viewModel.isOffline.test {
                    awaitItem() shouldBe false
                    isConnectedFlow.value = false
                    awaitItem() shouldBe true
                }
            }
        }
    })
