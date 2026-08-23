package com.aiassistant.feature.code

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.usecase.code.AnalyzeCodeUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CodeViewModelTest :
    DescribeSpec({

        val analyzeCodeUseCase = mockk<AnalyzeCodeUseCase>()
        val testDispatcher = UnconfinedTestDispatcher()
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
            clearMocks(analyzeCodeUseCase)
        }

        describe("CodeViewModel") {

            it("initializes with Idle state") {
                val viewModel = CodeViewModel(analyzeCodeUseCase, dispatchers)
                viewModel.uiState.value shouldBe CodeUiState.Idle
            }

            it("transitions to Editing when code is updated") {
                val viewModel = CodeViewModel(analyzeCodeUseCase, dispatchers)
                viewModel.updateCode("println()", SupportedLanguage.KOTLIN)

                viewModel.uiState.value shouldBe CodeUiState.Editing(
                    code = "println()",
                    language = SupportedLanguage.KOTLIN,
                    selectedAction = CodeAction.EXPLAIN
                )
            }

            it("transitions to Analyzing and then AnalysisResult on successful submission") {
                val viewModel = CodeViewModel(analyzeCodeUseCase, dispatchers)
                val code = "val x = 1"
                val lang = SupportedLanguage.KOTLIN
                val action = CodeAction.EXPLAIN

                viewModel.updateCode(code, lang)

                val resultData = CodeAnalysisResult(
                    languageId = "kotlin",
                    originalCode = code,
                    action = action,
                    content = "Explanation"
                )
                coEvery { analyzeCodeUseCase(any()) } returns ApiResult.Success(resultData)

                viewModel.uiState.test {
                    awaitItem() // Skip current Editing state

                    viewModel.submitForAnalysis()

                    awaitItem() shouldBe CodeUiState.Analyzing(code, lang, action)

                    val finalState = awaitItem()
                    finalState shouldBe CodeUiState.AnalysisResult(
                        request = mockk {
                            every { this@mockk.code } returns code
                            every { this@mockk.language } returns lang
                            every { this@mockk.action } returns action
                        },
                        result = resultData
                    )
                }
            }
        }
    })
