/**
 * MemoryRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [MemoryRepositoryImpl], covering:
 *   - getMemories() — emits Loading then remote result; offline guard
 *   - updateMemory() — online success, offline guard, remote error
 *   - deleteMemory() — online success, offline guard, remote error
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking MemoryRemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test — runTest
 * - Turbine              — Flow collection assertions
 *
 * Requirements covered: 7.3, 7.4
 */
package com.aiassistant.data.repository

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.memory.MemoryDto
import com.aiassistant.data.remote.memory.MemoryRemoteDataSource
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

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeMemoryDto(
    id: String = "mem-1",
    userId: String = "user-1",
    content: String = "User prefers dark mode",
    memoryType: String = "preference",
    createdAt: Long = 1_000_000L
) = MemoryDto(id = id, userId = userId, content = content, memoryType = memoryType, createdAt = createdAt)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class MemoryRepositoryImplTest :
    DescribeSpec({

        val remoteSource: MemoryRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: MemoryRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = MemoryRepositoryImpl(
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                dispatchers = dispatchers
            )
        }

        // ─── getMemories() ────────────────────────────────────────────────────────

        describe("getMemories()") {
            it("emits Loading then Success with mapped domain models when online") {
                runTest {
                    val dtos = listOf(fakeMemoryDto(id = "m1"), fakeMemoryDto(id = "m2"))
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.getMemories() } returns ApiResult.Success(dtos)

                    repository.getMemories().test {
                        awaitItem() shouldBe ApiResult.Loading
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val memories = (result as ApiResult.Success).data
                        memories.size shouldBe 2
                        memories[0].id shouldBe "m1"
                        memories[1].id shouldBe "m2"
                        awaitComplete()
                    }
                }
            }

            it("emits Loading then NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    repository.getMemories().test {
                        awaitItem() shouldBe ApiResult.Loading
                        awaitItem() shouldBe ApiResult.NetworkUnavailable
                        awaitComplete()
                    }
                }
            }

            it("emits Loading then ApiResult.Error when remote returns error") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.getMemories() } returns
                        ApiResult.Error(DomainError.ServerError("Internal error", 500))

                    repository.getMemories().test {
                        awaitItem() shouldBe ApiResult.Loading
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Error>()
                        awaitComplete()
                    }
                }
            }

            it("does NOT call remote when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    repository.getMemories().test {
                        awaitItem() // Loading
                        awaitItem() // NetworkUnavailable
                        awaitComplete()
                    }

                    coVerify(exactly = 0) { remoteSource.getMemories() }
                }
            }
        }

        // ─── updateMemory() ───────────────────────────────────────────────────────

        describe("updateMemory()") {
            it("returns Success with updated domain Memory when online") {
                runTest {
                    val updatedDto = fakeMemoryDto(id = "m1", content = "Updated content")
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.updateMemory("m1", "Updated content") } returns
                        ApiResult.Success(updatedDto)

                    val result = repository.updateMemory("m1", "Updated content")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.id shouldBe "m1"
                    (result).data.content shouldBe "Updated content"
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.updateMemory("m1", "Some content")

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteSource.updateMemory(any(), any()) }
                }
            }

            it("propagates remote error") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.updateMemory(any(), any()) } returns
                        ApiResult.Error(DomainError.Unauthorized())

                    val result = repository.updateMemory("m1", "text")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }
        }

        // ─── deleteMemory() ───────────────────────────────────────────────────────

        describe("deleteMemory()") {
            it("returns Success(Unit) when deletion succeeds online") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.deleteMemory("m1") } returns ApiResult.Success(Unit)

                    val result = repository.deleteMemory("m1")

                    result shouldBe ApiResult.Success(Unit)
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.deleteMemory("m1")

                    result shouldBe ApiResult.NetworkUnavailable
                    coVerify(exactly = 0) { remoteSource.deleteMemory(any()) }
                }
            }

            it("propagates remote error (Requirement 7.4 — embedding removal may fail)") {
                runTest {
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { remoteSource.deleteMemory("m-bad") } returns
                        ApiResult.Error(DomainError.ServerError("Service unavailable", 503))

                    val result = repository.deleteMemory("m-bad")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }
    })
