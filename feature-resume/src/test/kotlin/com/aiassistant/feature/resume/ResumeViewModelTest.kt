package com.aiassistant.feature.resume

import android.content.Context
import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.resume.GenerateCoverLetterUseCase
import com.aiassistant.domain.usecase.resume.GenerateResumeUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ResumeViewModelTest :
    DescribeSpec({

        val generateResumeUseCase = mockk<GenerateResumeUseCase>()
        val generateCoverLetterUseCase = mockk<GenerateCoverLetterUseCase>()
        val applicationContext = mockk<Context>()
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
        }

        beforeEach {
            clearMocks(generateResumeUseCase, generateCoverLetterUseCase)
        }

        describe("ResumeViewModel") {

            it("initializes with Idle state") {
                val viewModel = ResumeViewModel(
                    generateResumeUseCase,
                    generateCoverLetterUseCase,
                    dispatchers,
                    applicationContext
                )
                viewModel.uiState.value shouldBe ResumeUiState.Idle
            }

            it("emits ResumeGenerated on successful resume generation") {
                val viewModel = ResumeViewModel(
                    generateResumeUseCase,
                    generateCoverLetterUseCase,
                    dispatchers,
                    applicationContext
                )
                val history = "History"
                val job = "Job"
                val result = "# Resume"

                coEvery { generateResumeUseCase(history, job) } returns ApiResult.Success(result)

                viewModel.uiState.test {
                    awaitItem() shouldBe ResumeUiState.Idle

                    viewModel.generateResume(history, job)

                    awaitItem() shouldBe ResumeUiState.Loading("Generating ATS-optimized resumeâ€¦")
                    awaitItem() shouldBe ResumeUiState.ResumeGenerated(result)
                }
            }

            it("emits CoverLetterGenerated on successful cover letter generation") {
                val viewModel = ResumeViewModel(
                    generateResumeUseCase,
                    generateCoverLetterUseCase,
                    dispatchers,
                    applicationContext
                )
                val history = "History"
                val job = "Job"
                val result = "Dear Hiring Manager..."

                coEvery { generateCoverLetterUseCase(history, job) } returns ApiResult.Success(result)

                viewModel.uiState.test {
                    awaitItem() shouldBe ResumeUiState.Idle

                    viewModel.generateCoverLetter(history, job)

                    awaitItem() shouldBe ResumeUiState.Loading("Generating cover letterâ€¦")
                    awaitItem() shouldBe ResumeUiState.CoverLetterGenerated(result)
                }
            }
        }
    })
