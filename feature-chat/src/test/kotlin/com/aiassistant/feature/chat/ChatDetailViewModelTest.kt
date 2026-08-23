package com.aiassistant.feature.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.aiassistant.core.ai.AIStreamClient
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.ai.TokenUsage
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.conversation.ExportConversationUseCase
import com.aiassistant.domain.usecase.conversation.RegenerateMessageUseCase
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModelTest :
    DescribeSpec({

        val sendMessageUseCase = mockk<SendMessageUseCase>()
        val regenerateMessageUseCase = mockk<RegenerateMessageUseCase>()
        val exportConversationUseCase = mockk<ExportConversationUseCase>()
        val streamClient = mockk<AIStreamClient>()
        val getContextSuggestionsUseCase = mockk<GetContextSuggestionsUseCase>()

        val testDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val mainImmediate = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
            override val unconfined = testDispatcher
        }

        val conversationId = "conv-123"
        val savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversationId))

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
        }

        beforeEach {
            clearMocks(
                sendMessageUseCase,
                regenerateMessageUseCase,
                exportConversationUseCase,
                streamClient,
                getContextSuggestionsUseCase
            )
        }

        describe("ChatDetailViewModel") {

            it("initializes with correct conversationId") {
                val viewModel = ChatDetailViewModel(
                    savedStateHandle,
                    sendMessageUseCase,
                    regenerateMessageUseCase,
                    exportConversationUseCase,
                    streamClient,
                    dispatchers,
                    getContextSuggestionsUseCase
                )

                viewModel.conversationId shouldBe conversationId
                viewModel.uiState.value.conversationId shouldBe conversationId
            }

            it("updates provider when setProvider is called") {
                val viewModel = ChatDetailViewModel(
                    savedStateHandle,
                    sendMessageUseCase,
                    regenerateMessageUseCase,
                    exportConversationUseCase,
                    streamClient,
                    dispatchers,
                    getContextSuggestionsUseCase
                )

                viewModel.setProvider("gemini")
                viewModel.uiState.value.provider shouldBe "gemini"
            }

            it("streams response when sendMessage is called") {
                val viewModel = ChatDetailViewModel(
                    savedStateHandle,
                    sendMessageUseCase,
                    regenerateMessageUseCase,
                    exportConversationUseCase,
                    streamClient,
                    dispatchers,
                    getContextSuggestionsUseCase
                )

                val content = "Hello"
                coEvery { sendMessageUseCase(any(), any(), any()) } returns ApiResult.Success(mockk())
                every { streamClient.connect(any(), any()) } returns kotlinx.coroutines.flow.flow {
                    emit(StreamEvent.Token("Hi"))
                    kotlinx.coroutines.delay(10) // Force suspension to avoid state conflation
                    emit(StreamEvent.Done(TokenUsage(1, 1)))
                }
                coEvery { streamClient.sendMessage(any()) } returns Unit

                viewModel.uiState.test {
                    // 1. Initial state (from flow start)
                    awaitItem().messages shouldBe emptyList()

                    viewModel.sendMessage(content)
                    testDispatcher.scheduler.runCurrent()

                    // 2. Optimistic update (user message added)
                    val stateOptimistic = awaitItem()
                    stateOptimistic.messages.size shouldBe 1
                    stateOptimistic.messages.first().content shouldBe content

                    // 3. Typing indicator (from sendMessage launch)
                    val stateTyping = awaitItem()
                    stateTyping.isTypingIndicatorVisible shouldBe true

                    // 4. Streaming start (from startStreaming launch)
                    val stateStreamingStart = awaitItem()
                    stateStreamingStart.isStreaming shouldBe true
                    stateStreamingStart.streamingText shouldBe ""

                    // 5. First token (from flow collection)
                    val stateWithToken = awaitItem()
                    stateWithToken.streamingText shouldBe "Hi"
                    stateWithToken.isTypingIndicatorVisible shouldBe false

                    // 6. Done (after delay)
                    testDispatcher.scheduler.advanceTimeBy(20)
                    testDispatcher.scheduler.runCurrent()
                    val finalState = awaitItem()
                    finalState.messages.size shouldBe 2
                    finalState.messages.last().content shouldBe "Hi"
                    finalState.streamingText shouldBe ""
                    finalState.isStreaming shouldBe false
                }
            }
        }
    })
