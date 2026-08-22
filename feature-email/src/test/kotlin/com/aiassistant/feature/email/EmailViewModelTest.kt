package com.aiassistant.feature.email

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.resume.CorrectGrammarUseCase
import com.aiassistant.domain.usecase.resume.GenerateEmailUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class EmailViewModelTest :
    DescribeSpec({

        val generateEmailUseCase = mockk<GenerateEmailUseCase>()
        val correctGrammarUseCase = mockk<CorrectGrammarUseCase>()
        val testDispatcher = StandardTestDispatcher()
        val dispatchers = object : DispatcherProvider {
            override val main = testDispatcher
            override val mainImmediate = testDispatcher
            override val io = testDispatcher
            override val default = testDispatcher
            override val unconfined = testDispatcher
        }

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(generateEmailUseCase, correctGrammarUseCase)
        }

        describe("EmailViewModel") {

            it("initializes with Idle state") {
                val viewModel = EmailViewModel(generateEmailUseCase, correctGrammarUseCase, dispatchers)
                viewModel.uiState.value shouldBe EmailUiState.Idle
            }

            it("emits EmailGenerated on successful generation") {
                val viewModel = EmailViewModel(generateEmailUseCase, correctGrammarUseCase, dispatchers)
                val context = "Context"
                val intent = "Intent"
                val generatedText = "Subject: Hello\n\nBody"

                coEvery { generateEmailUseCase(context, intent) } returns ApiResult.Success(generatedText)

                viewModel.uiState.test {
                    awaitItem() shouldBe EmailUiState.Idle

                    viewModel.generateEmail(context, intent)

                    awaitItem() shouldBe EmailUiState.Loading("Generating emailâ€¦")
                    awaitItem() shouldBe EmailUiState.EmailGenerated(generatedText)
                }
            }

            it("emits GrammarCorrected on successful grammar correction") {
                val viewModel = EmailViewModel(generateEmailUseCase, correctGrammarUseCase, dispatchers)
                val draft = "I is here"
                val corrected = "I am here"

                coEvery { correctGrammarUseCase(draft) } returns ApiResult.Success(corrected)

                viewModel.uiState.test {
                    awaitItem() shouldBe EmailUiState.Idle

                    viewModel.correctGrammar(draft)

                    awaitItem() shouldBe EmailUiState.Loading("Correcting grammarâ€¦")
                    val finalState = awaitItem() as EmailUiState.GrammarCorrected
                    finalState.corrected shouldBe corrected
                    finalState.original shouldBe draft
                }
            }
        }
    })
