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
import kotlinx.coroutines.test.StandardTestDispatcher
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
                every { streamClient.connect(any(), any()) } returns flowOf(
                    StreamEvent.Token("Hi"),
                    StreamEvent.Done(TokenUsage(1, 1))
                )
                coEvery { streamClient.sendMessage(any()) } returns Unit

                viewModel.sendMessage(content)

                viewModel.uiState.test {
                    // Initial state after sendMessage
                    val stateAfterSend = awaitItem()
                    stateAfterSend.messages.size shouldBe 1
                    stateAfterSend.messages.first().content shouldBe content

                    // Typing indicator
                    awaitItem().isTypingIndicatorVisible shouldBe true

                    // First token
                    val stateWithToken = awaitItem()
                    stateWithToken.streamingText shouldBe "Hi"
                    stateWithToken.isTypingIndicatorVisible shouldBe false

                    // Done
                    val finalState = awaitItem()
                    finalState.messages.size shouldBe 2
                    finalState.messages.last().content shouldBe "Hi"
                    finalState.streamingText shouldBe ""
                    finalState.isStreaming shouldBe false
                }
            }
        }
    })
