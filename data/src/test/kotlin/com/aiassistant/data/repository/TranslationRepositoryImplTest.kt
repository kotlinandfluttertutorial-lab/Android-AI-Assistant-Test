/**
 * TranslationRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [TranslationRepositoryImpl], covering:
 *   - translateText() — online routes to remote; offline returns NetworkUnavailable
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking TranslationRemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 10.5, 19.1
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.translator.TranslationRemoteDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class TranslationRepositoryImplTest :
    DescribeSpec({

        val remoteDataSource: TranslationRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()

        lateinit var repository: TranslationRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = TranslationRepositoryImpl(
                remoteDataSource = remoteDataSource,
                connectivityObserver = connectivityObserver
            )
        }

        afterEach {
            unmockkAll()
        }

        describe("translateText()") {
            describe("online") {
                it("delegates to remote data source and returns its result") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteDataSource.translateText("Hello", "en", "fr")
                        } returns ApiResult.Success("Bonjour")

                        val result = repository.translateText("Hello", "en", "fr")

                        result shouldBe ApiResult.Success("Bonjour")
                    }
                }

                it("passes all parameters correctly to remote data source") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteDataSource.translateText("Hola", "es", "ja")
                        } returns ApiResult.Success("こんにちは")

                        repository.translateText("Hola", "es", "ja")

                        coVerify(exactly = 1) {
                            remoteDataSource.translateText("Hola", "es", "ja")
                        }
                    }
                }

                it("propagates remote error") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery {
                            remoteDataSource.translateText(any(), any(), any())
                        } returns ApiResult.Error(DomainError.ServerError("Provider error", 503))

                        val result = repository.translateText("text", "en", "de")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                    }
                }
            }

            describe("offline") {
                it("returns NetworkUnavailable without calling remote") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.translateText("text", "en", "fr")

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { remoteDataSource.translateText(any(), any(), any()) }
                    }
                }

                it("returns NetworkUnavailable for any language pair when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.translateText("some text", "zh-Hans", "ar")

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }
            }
        }
    })
