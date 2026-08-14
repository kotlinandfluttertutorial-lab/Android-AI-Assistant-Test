/**
 * CodeRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [CodeRepositoryImpl], which is a stub that always returns
 *          HTTP 501 (backend not yet wired). Verifies the stub behavior for all
 *          code action types.
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 12.1, 12.2, 12.3, 12.4, 12.6
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.SupportedLanguage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class CodeRepositoryImplTest :
    DescribeSpec({

        val repository = CodeRepositoryImpl()

        describe("analyzeCode()") {

            it("returns ServerError with 501 status for EXPLAIN action") {
                runTest {
                    val request = CodeAnalysisRequest(
                        code = "fun hello() = println(\"Hello\")",
                        language = SupportedLanguage.KOTLIN,
                        action = CodeAction.EXPLAIN
                    )
                    val result = repository.analyzeCode(request)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("returns ServerError with 501 status for FIX_BUG action") {
                runTest {
                    val request = CodeAnalysisRequest(
                        code = "val x = null.toString()",
                        language = SupportedLanguage.KOTLIN,
                        action = CodeAction.FIX_BUG
                    )
                    val result = repository.analyzeCode(request)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("returns ServerError with 501 status for GENERATE_TESTS action") {
                runTest {
                    val request = CodeAnalysisRequest(
                        code = "fun add(a: Int, b: Int) = a + b",
                        language = SupportedLanguage.KOTLIN,
                        action = CodeAction.GENERATE_TESTS
                    )
                    val result = repository.analyzeCode(request)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ServerError>()
                    (error as DomainError.ServerError).httpStatusCode shouldBe 501
                }
            }

            it("returns error for all supported languages") {
                runTest {
                    SupportedLanguage.entries.forEach { language ->
                        val request = CodeAnalysisRequest(
                            code = "x",
                            language = language,
                            action = CodeAction.EXPLAIN
                        )
                        val result = repository.analyzeCode(request)
                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }

            it("returns error for all analysis actions") {
                runTest {
                    CodeAction.entries.forEach { action ->
                        val request = CodeAnalysisRequest(
                            code = "print('test')",
                            language = SupportedLanguage.PYTHON,
                            action = action
                        )
                        val result = repository.analyzeCode(request)
                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }

            it("error message describes backend connection status") {
                runTest {
                    val request = CodeAnalysisRequest(
                        code = "SELECT 1",
                        language = SupportedLanguage.SQL,
                        action = CodeAction.EXPLAIN
                    )
                    val result = repository.analyzeCode(request)

                    val error = (result as ApiResult.Error).error as DomainError.ServerError
                    error.message shouldBe "Code analysis backend not yet connected"
                }
            }
        }
    })
