/**
 * ResumeUseCaseTest.kt — domain module unit tests
 *
 * Tests for resume/productivity writing use cases:
 *   - [GenerateResumeUseCase] — validates professionalHistory and jobDescription not blank;
 *                               trims both inputs before delegating
 *   - [GenerateEmailUseCase]  — validates context and intent not blank;
 *                               trims both inputs before delegating
 *
 * Requirements: 21.1
 * Related requirements: 14.1, 14.4
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for ResumeRepository mocking
 */

package com.aiassistant.domain.usecase.resume

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.repository.ResumeRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private const val VALID_HISTORY = "5 years as a senior software engineer at Acme Corp"
private const val VALID_JOB_DESCRIPTION = "Looking for a principal engineer to lead architecture"
private const val GENERATED_RESUME = "# Resume\n## John Doe\n..."

private const val VALID_EMAIL_CONTEXT = "I need to request a project deadline extension"
private const val VALID_EMAIL_INTENT = "Ask the manager for a two-week extension"
private const val GENERATED_EMAIL = "Subject: Deadline Extension Request\n\nDear Manager,..."

// ─── GenerateResumeUseCase ─────────────────────────────────────────────────────

class GenerateResumeUseCaseTest :
    DescribeSpec({

        val resumeRepository = mockk<ResumeRepository>()
        val generateResumeUseCase = GenerateResumeUseCase(resumeRepository)

        beforeEach {
            clearMocks(resumeRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("GenerateResumeUseCase") {

            describe("successful generation") {

                it("returns Success with resume Markdown when both inputs are valid") {
                    coEvery {
                        resumeRepository.generateResume(VALID_HISTORY, VALID_JOB_DESCRIPTION)
                    } returns ApiResult.Success(GENERATED_RESUME)

                    val result = generateResumeUseCase(VALID_HISTORY, VALID_JOB_DESCRIPTION)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe GENERATED_RESUME
                }

                it("delegates to repository exactly once with correct arguments") {
                    coEvery {
                        resumeRepository.generateResume(VALID_HISTORY, VALID_JOB_DESCRIPTION)
                    } returns ApiResult.Success(GENERATED_RESUME)

                    generateResumeUseCase(VALID_HISTORY, VALID_JOB_DESCRIPTION)

                    coVerify(exactly = 1) {
                        resumeRepository.generateResume(VALID_HISTORY, VALID_JOB_DESCRIPTION)
                    }
                }
            }

            describe("input trimming") {

                it("trims whitespace from both inputs before passing to repository") {
                    coEvery {
                        resumeRepository.generateResume(VALID_HISTORY, VALID_JOB_DESCRIPTION)
                    } returns ApiResult.Success(GENERATED_RESUME)

                    generateResumeUseCase("  $VALID_HISTORY  ", "  $VALID_JOB_DESCRIPTION  ")

                    coVerify(exactly = 1) {
                        resumeRepository.generateResume(VALID_HISTORY, VALID_JOB_DESCRIPTION)
                    }
                }
            }

            describe("professionalHistory validation") {

                it("returns ValidationError when professionalHistory is blank") {
                    val result = generateResumeUseCase("", VALID_JOB_DESCRIPTION)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when professionalHistory is only whitespace") {
                    val result = generateResumeUseCase("   ", VALID_JOB_DESCRIPTION)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'professionalHistory' in fields map") {
                    val result = generateResumeUseCase("", VALID_JOB_DESCRIPTION)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateResumeUseCase.FIELD_HISTORY
                }

                it("does NOT call repository when professionalHistory is blank") {
                    generateResumeUseCase("", VALID_JOB_DESCRIPTION)

                    coVerify(exactly = 0) { resumeRepository.generateResume(any(), any()) }
                }
            }

            describe("jobDescription validation") {

                it("returns ValidationError when jobDescription is blank") {
                    val result = generateResumeUseCase(VALID_HISTORY, "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'jobDescription' in fields map") {
                    val result = generateResumeUseCase(VALID_HISTORY, "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateResumeUseCase.FIELD_JOB_DESCRIPTION
                }

                it("does NOT call repository when jobDescription is blank") {
                    generateResumeUseCase(VALID_HISTORY, "")

                    coVerify(exactly = 0) { resumeRepository.generateResume(any(), any()) }
                }

                it("professionalHistory error wins when both inputs are blank") {
                    val result = generateResumeUseCase("", "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateResumeUseCase.FIELD_HISTORY
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        resumeRepository.generateResume(any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result = generateResumeUseCase(VALID_HISTORY, VALID_JOB_DESCRIPTION)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        resumeRepository.generateResume(any(), any())
                    } returns ApiResult.Error(error)

                    val result = generateResumeUseCase(VALID_HISTORY, VALID_JOB_DESCRIPTION)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── GenerateEmailUseCase ─────────────────────────────────────────────────────

class GenerateEmailUseCaseTest :
    DescribeSpec({

        val resumeRepository = mockk<ResumeRepository>()
        val generateEmailUseCase = GenerateEmailUseCase(resumeRepository)

        beforeEach {
            clearMocks(resumeRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("GenerateEmailUseCase") {

            describe("successful generation") {

                it("returns Success with email text when both inputs are valid") {
                    coEvery {
                        resumeRepository.generateEmail(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)
                    } returns ApiResult.Success(GENERATED_EMAIL)

                    val result = generateEmailUseCase(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)

                    result.shouldBeInstanceOf<ApiResult.Success<String>>()
                    (result as ApiResult.Success<String>).data shouldBe GENERATED_EMAIL
                }

                it("delegates to repository exactly once with correct arguments") {
                    coEvery {
                        resumeRepository.generateEmail(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)
                    } returns ApiResult.Success(GENERATED_EMAIL)

                    generateEmailUseCase(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)

                    coVerify(exactly = 1) {
                        resumeRepository.generateEmail(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)
                    }
                }
            }

            describe("input trimming") {

                it("trims whitespace from both inputs before passing to repository") {
                    coEvery {
                        resumeRepository.generateEmail(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)
                    } returns ApiResult.Success(GENERATED_EMAIL)

                    generateEmailUseCase("  $VALID_EMAIL_CONTEXT  ", "  $VALID_EMAIL_INTENT  ")

                    coVerify(exactly = 1) {
                        resumeRepository.generateEmail(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)
                    }
                }
            }

            describe("context validation") {

                it("returns ValidationError when context is blank") {
                    val result = generateEmailUseCase("", VALID_EMAIL_INTENT)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'context' in fields map") {
                    val result = generateEmailUseCase("", VALID_EMAIL_INTENT)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateEmailUseCase.FIELD_CONTEXT
                }

                it("does NOT call repository when context is blank") {
                    generateEmailUseCase("", VALID_EMAIL_INTENT)

                    coVerify(exactly = 0) { resumeRepository.generateEmail(any(), any()) }
                }
            }

            describe("intent validation") {

                it("returns ValidationError when intent is blank") {
                    val result = generateEmailUseCase(VALID_EMAIL_CONTEXT, "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("ValidationError contains 'intent' in fields map") {
                    val result = generateEmailUseCase(VALID_EMAIL_CONTEXT, "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateEmailUseCase.FIELD_INTENT
                }

                it("does NOT call repository when intent is blank") {
                    generateEmailUseCase(VALID_EMAIL_CONTEXT, "")

                    coVerify(exactly = 0) { resumeRepository.generateEmail(any(), any()) }
                }

                it("context error wins when both inputs are blank") {
                    val result = generateEmailUseCase("", "")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey GenerateEmailUseCase.FIELD_CONTEXT
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery {
                        resumeRepository.generateEmail(any(), any())
                    } returns ApiResult.NetworkUnavailable

                    val result = generateEmailUseCase(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery {
                        resumeRepository.generateEmail(any(), any())
                    } returns ApiResult.Error(error)

                    val result = generateEmailUseCase(VALID_EMAIL_CONTEXT, VALID_EMAIL_INTENT)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
