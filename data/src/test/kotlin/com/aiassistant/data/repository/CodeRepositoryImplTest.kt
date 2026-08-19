/**
 * CodeRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [CodeRepositoryImpl], covering:
 *   - Online path: delegates to CodeRemoteDataSource and returns its result.
 *   - Offline path: returns ApiResult.NetworkUnavailable without hitting the network.
 *
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock ConnectivityObserver and CodeRemoteDataSource
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 12.1, 12.2, 12.3, 12.4, 12.6
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.code.CodeRemoteDataSource
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class CodeRepositoryImplTest :
    DescribeSpec({

        val remoteDataSource = mockk<CodeRemoteDataSource>()
        val connectivityObserver = mockk<ConnectivityObserver>()
        val repository = CodeRepositoryImpl(remoteDataSource, connectivityObserver)

        val sampleRequest = CodeAnalysisRequest(
            code = "fun hello() = println(\"Hello\")",
            language = SupportedLanguage.KOTLIN,
            action = CodeAction.EXPLAIN
        )
        val sampleResult = CodeAnalysisResult(
            languageId = "kotlin",
            originalCode = sampleRequest.code,
            action = CodeAction.EXPLAIN,
            content = "This function prints Hello to standard output."
        )

        describe("analyzeCode() — online") {

            it("delegates to remoteDataSource and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns ApiResult.Success(sampleResult)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                    (result as ApiResult.Success).data shouldBe sampleResult
                    coVerify(exactly = 1) { remoteDataSource.analyzeCode(sampleRequest) }
                }
            }

            it("propagates Error from remoteDataSource") {
                runTest {
                    val domainError = DomainError.ServerError(
                        message = "Server error (HTTP 500).",
                        httpStatusCode = 500
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns ApiResult.Error(domainError)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe domainError
                }
            }

            it("delegates for all supported languages") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    SupportedLanguage.entries.forEach { language ->
                        val request = sampleRequest.copy(language = language)
                        val expectedResult = sampleResult.copy(languageId = language.name.lowercase())
                        coEvery { remoteDataSource.analyzeCode(request) } returns ApiResult.Success(expectedResult)

                        val result = repository.analyzeCode(request)
                        result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                    }
                }
            }

            it("delegates for all code actions") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    CodeAction.entries.forEach { action ->
                        val request = sampleRequest.copy(action = action)
                        val expectedResult = sampleResult.copy(action = action)
                        coEvery { remoteDataSource.analyzeCode(request) } returns ApiResult.Success(expectedResult)

                        val result = repository.analyzeCode(request)
                        result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                    }
                }
            }
        }

        describe("analyzeCode() — offline") {

            it("returns NetworkUnavailable without calling remoteDataSource") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.analyzeCode(sampleRequest)

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteDataSource.analyzeCode(any()) }
                }
            }

            it("returns NetworkUnavailable for every action type when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false
                    CodeAction.entries.forEach { action ->
                        val result = repository.analyzeCode(sampleRequest.copy(action = action))
                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }
            }
        }
    })
