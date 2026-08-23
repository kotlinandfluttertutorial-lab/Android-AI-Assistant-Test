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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModelTest :
    DescribeSpec({

        val sendMessageUseCase = mockk<SendMessageUseCase>()
        val regenerateMessageUseCase = mockk<RegenerateMessageUseCase>()
        val exportConversationUseCase = mockk<ExportConversationUseCase>()
        val streamClient = mockk<AIStreamClient>()
        val getContextSuggestionsUseCase = mockk<GetContextSuggestionsUseCase>()

        val testDispatcher = UnconfinedTestDispatcher()
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

                runTest(testDispatcher) {
                    viewModel.uiState.test {
                        // 1. Initial state
                        awaitItem().messages shouldBe emptyList()

                        viewModel.sendMessage(content)

                        // 2. Optimistic update — user message appended immediately
                        val stateOptimistic = awaitItem()
                        stateOptimistic.messages.size shouldBe 1
                        stateOptimistic.messages.first().content shouldBe content

                        // 3. Typing indicator becomes visible
                        val stateTyping = awaitItem()
                        stateTyping.isTypingIndicatorVisible shouldBe true

                        // 4+5. StateFlow may conflate the streaming-start state (isStreaming=true,
                        //       streamingText="") and the first Token state into one emission when
                        //       both updates fire within the same coroutine pump. Consume emissions
                        //       until we see the first token, verifying invariants along the way.
                        var stateWithToken = awaitItem()
                        // If we got the streaming-start state, advance to the token state
                        if (stateWithToken.streamingText.isEmpty() && stateWithToken.isStreaming) {
                            stateWithToken = awaitItem()
                        }
                        stateWithToken.isStreaming shouldBe true
                        stateWithToken.streamingText shouldBe "Hi"
                        stateWithToken.isTypingIndicatorVisible shouldBe false

                        // 6. Advance virtual time past the delay(10) to trigger StreamEvent.Done
                        testScheduler.advanceTimeBy(20)

                        val finalState = awaitItem()
                        finalState.messages.size shouldBe 2
                        finalState.messages.last().content shouldBe "Hi"
                        finalState.streamingText shouldBe ""
                        finalState.isStreaming shouldBe false
                    }
                }
            }
        }
    })
