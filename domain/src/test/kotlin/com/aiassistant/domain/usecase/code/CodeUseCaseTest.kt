/**
 * CodeUseCaseTest.kt — domain module
 *
 * Purpose: Unit tests for [AnalyzeCodeUseCase], covering happy path and error propagation.
 * Architecture: domain module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking CodeRepository
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 12.1, 12.2, 12.3, 12.4, 12.6
 */
package com.aiassistant.domain.usecase.code

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import com.aiassistant.domain.repository.CodeRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class CodeUseCaseTest :
    DescribeSpec({

        val codeRepository: CodeRepository = mockk()
        lateinit var analyzeCodeUseCase: AnalyzeCodeUseCase

        beforeEach {
            clearAllMocks()
            analyzeCodeUseCase = AnalyzeCodeUseCase(codeRepository)
        }

        describe("AnalyzeCodeUseCase") {

            describe("happy path") {

                it("returns Success with CodeAnalysisResult from repository for EXPLAIN action") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "fun add(a: Int, b: Int) = a + b",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.EXPLAIN
                        )
                        val expected = CodeAnalysisResult(
                            languageId = "kotlin",
                            originalCode = request.code,
                            action = CodeAction.EXPLAIN,
                            content = "This function adds two integers and returns the result."
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.Success(expected)

                        val result = analyzeCodeUseCase(request)

                        result shouldBe ApiResult.Success(expected)
                        coVerify(exactly = 1) { codeRepository.analyzeCode(request) }
                    }
                }

                it("returns Success with corrected code for FIX_BUG action") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "fun divide(a: Int, b: Int) = a / b",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.FIX_BUG
                        )
                        val expected = CodeAnalysisResult(
                            languageId = "kotlin",
                            originalCode = request.code,
                            action = CodeAction.FIX_BUG,
                            content = "fun divide(a: Int, b: Int): Int { require(b != 0); return a / b }"
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.Success(expected)

                        val result = analyzeCodeUseCase(request)

                        result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                        (result as ApiResult.Success).data.action shouldBe CodeAction.FIX_BUG
                    }
                }

                it("returns Success with test suite for GENERATE_TESTS action") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "fun multiply(a: Int, b: Int) = a * b",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.GENERATE_TESTS
                        )
                        val expected = CodeAnalysisResult(
                            languageId = "kotlin",
                            originalCode = request.code,
                            action = CodeAction.GENERATE_TESTS,
                            content = "@Test fun `multiply returns product`() { assertEquals(6, multiply(2, 3)) }"
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.Success(expected)

                        val result = analyzeCodeUseCase(request)

                        result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                        (result as ApiResult.Success).data.languageId shouldBe "kotlin"
                    }
                }

                it("delegates directly to repository without transformation") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "print('hello')",
                            language = SupportedLanguage.PYTHON,
                            action = CodeAction.EXPLAIN
                        )
                        val expected = ApiResult.Success(
                            CodeAnalysisResult(
                                languageId = "python",
                                originalCode = request.code,
                                action = CodeAction.EXPLAIN,
                                content = "Prints hello to stdout."
                            )
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns expected

                        val result = analyzeCodeUseCase(request)

                        result shouldBe expected
                        coVerify(exactly = 1) { codeRepository.analyzeCode(request) }
                    }
                }
            }

            describe("error path") {

                it("propagates ServerError from repository") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "fun hello() = println(\"Hello\")",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.EXPLAIN
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.Error(
                            DomainError.ServerError(
                                message = "Code analysis backend not yet connected",
                                httpStatusCode = 501
                            )
                        )

                        val result = analyzeCodeUseCase(request)

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        val error = (result as ApiResult.Error).error
                        error.shouldBeInstanceOf<DomainError.ServerError>()
                        (error as DomainError.ServerError).httpStatusCode shouldBe 501
                    }
                }

                it("propagates ValidationError for empty code") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.EXPLAIN
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.Error(
                            DomainError.ValidationError("Empty code block")
                        )

                        val result = analyzeCodeUseCase(request)

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                    }
                }

                it("propagates NetworkUnavailable from repository") {
                    runTest {
                        val request = CodeAnalysisRequest(
                            code = "val x = 1",
                            language = SupportedLanguage.KOTLIN,
                            action = CodeAction.EXPLAIN
                        )
                        coEvery { codeRepository.analyzeCode(request) } returns ApiResult.NetworkUnavailable

                        val result = analyzeCodeUseCase(request)

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }
            }
        }
    })
