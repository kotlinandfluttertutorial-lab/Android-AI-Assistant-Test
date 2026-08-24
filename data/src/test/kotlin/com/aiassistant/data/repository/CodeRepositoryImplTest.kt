/**
 * CodeRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [CodeRepositoryImpl], covering:
 *   - Offline path: returns ApiResult.NetworkUnavailable without calling remoteDataSource.
 *   - Online success path: delegates to CodeRemoteDataSource and returns Success.
 *   - Online error path: propagates errors from CodeRemoteDataSource.
 *   - HTTP 404 response (backend endpoint not yet deployed): propagates as ServerError.
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
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest

class CodeRepositoryImplTest :
    DescribeSpec({

        val remoteDataSource = mockk<CodeRemoteDataSource>()
        val connectivityObserver = mockk<ConnectivityObserver>()
        val repository = CodeRepositoryImpl(remoteDataSource, connectivityObserver)

        afterEach {
            unmockkAll()
        }

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

        // ─── Offline path ─────────────────────────────────────────────────────

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

        // ─── Online success path ──────────────────────────────────────────────

        describe("analyzeCode() — online, success") {

            it("delegates to remoteDataSource and returns Success") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns
                        ApiResult.Success(sampleResult)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                    (result as ApiResult.Success).data shouldBe sampleResult
                    coVerify(exactly = 1) { remoteDataSource.analyzeCode(sampleRequest) }
                }
            }

            it("delegates for all supported languages") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    SupportedLanguage.entries.forEach { language ->
                        val request = sampleRequest.copy(language = language)
                        val expected = sampleResult.copy(languageId = language.name.lowercase())
                        coEvery { remoteDataSource.analyzeCode(request) } returns
                            ApiResult.Success(expected)

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
                        val expected = sampleResult.copy(action = action)
                        coEvery { remoteDataSource.analyzeCode(request) } returns
                            ApiResult.Success(expected)

                        val result = repository.analyzeCode(request)
                        result.shouldBeInstanceOf<ApiResult.Success<CodeAnalysisResult>>()
                    }
                }
            }
        }

        // ─── Online error path ────────────────────────────────────────────────

        describe("analyzeCode() — online, error") {

            it("propagates ServerError from remoteDataSource") {
                runTest {
                    val error = DomainError.ServerError(
                        message = "Server error (HTTP 500).",
                        httpStatusCode = 500
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns
                        ApiResult.Error(error)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            it("propagates HTTP 404 when backend endpoint is not yet deployed") {
                runTest {
                    // The /code/analyze endpoint is planned but not yet on the backend.
                    // Until deployed, the server returns 404 which maps to ServerError.
                    val error = DomainError.ServerError(
                        message = "Server error (HTTP 404).",
                        httpStatusCode = 404
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns
                        ApiResult.Error(error)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val serverError = (result as ApiResult.Error).error
                    serverError.shouldBeInstanceOf<DomainError.ServerError>()
                    (serverError as DomainError.ServerError).httpStatusCode shouldBe 404
                }
            }

            it("propagates NetworkError from remoteDataSource") {
                runTest {
                    val error = DomainError.NetworkError(message = "Connection timeout.")
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(sampleRequest) } returns
                        ApiResult.Error(error)

                    val result = repository.analyzeCode(sampleRequest)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            it("propagates the exact error object without modification") {
                runTest {
                    val error = DomainError.ServerError(
                        message = "Service unavailable (HTTP 503).",
                        httpStatusCode = 503
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteDataSource.analyzeCode(any()) } returns ApiResult.Error(error)

                    val result = repository.analyzeCode(sampleRequest)

                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
