/**
 * MemoryUseCaseTest.kt — domain module unit tests
 *
 * Tests for memory use cases:
 *   - [GetMemoriesUseCase]  — pure Flow delegation; no validation
 *   - [DeleteMemoryUseCase] — pure delegation; no validation
 *
 * Requirements: 21.1
 * Related requirements: 7.3, 7.4
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for MemoryRepository mocking
 */

package com.aiassistant.domain.usecase.memory

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.MemoryType
import com.aiassistant.domain.repository.MemoryRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val SAMPLE_MEMORY = Memory(
    id = "mem-001",
    userId = "user-456",
    content = "Prefers concise responses",
    memoryType = MemoryType.PREFERENCE,
    createdAt = 1_000_000L
)

private val SAMPLE_MEMORIES = listOf(
    SAMPLE_MEMORY,
    Memory(
        id = "mem-002",
        userId = "user-456",
        content = "Works at Acme Corp",
        memoryType = MemoryType.FACT,
        createdAt = 2_000_000L
    )
)

// ─── GetMemoriesUseCase ────────────────────────────────────────────────────────

class GetMemoriesUseCaseTest :
    DescribeSpec({

        val memoryRepository = mockk<MemoryRepository>()
        val getMemoriesUseCase = GetMemoriesUseCase(memoryRepository)

        beforeEach {
            clearMocks(memoryRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("GetMemoriesUseCase") {

            describe("successful retrieval") {

                it("returns Flow emitting Success with memory list") {
                    every { memoryRepository.getMemories() } returns
                        flowOf(ApiResult.Success(SAMPLE_MEMORIES))

                    val result = getMemoriesUseCase().first()

                    result.shouldBeInstanceOf<ApiResult.Success<List<Memory>>>()
                    (result as ApiResult.Success<List<Memory>>).data shouldBe SAMPLE_MEMORIES
                }

                it("delegates to repository exactly once") {
                    every { memoryRepository.getMemories() } returns
                        flowOf(ApiResult.Success(SAMPLE_MEMORIES))

                    getMemoriesUseCase().first()

                    verify(exactly = 1) { memoryRepository.getMemories() }
                }

                it("returns Flow emitting Success with empty list when no memories exist") {
                    every { memoryRepository.getMemories() } returns
                        flowOf(ApiResult.Success(emptyList()))

                    val result = getMemoriesUseCase().first()

                    result.shouldBeInstanceOf<ApiResult.Success<List<Memory>>>()
                    (result as ApiResult.Success<List<Memory>>).data shouldBe emptyList()
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository Flow") {
                    every { memoryRepository.getMemories() } returns
                        flowOf(ApiResult.NetworkUnavailable)

                    val result = getMemoriesUseCase().first()

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository Flow") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    every { memoryRepository.getMemories() } returns
                        flowOf(ApiResult.Error(error))

                    val result = getMemoriesUseCase().first()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeleteMemoryUseCase ──────────────────────────────────────────────────────

class DeleteMemoryUseCaseTest :
    DescribeSpec({

        val memoryRepository = mockk<MemoryRepository>()
        val deleteMemoryUseCase = DeleteMemoryUseCase(memoryRepository)

        beforeEach {
            clearMocks(memoryRepository)
        }

        afterEach {
            unmockkAll()
        }

        describe("DeleteMemoryUseCase") {

            describe("successful deletion") {

                it("returns Success with Unit when repository succeeds") {
                    coEvery { memoryRepository.deleteMemory("mem-001") } returns ApiResult.Success(Unit)

                    val result = deleteMemoryUseCase("mem-001")

                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }

                it("delegates to repository exactly once with the given memoryId") {
                    coEvery { memoryRepository.deleteMemory("mem-001") } returns ApiResult.Success(Unit)

                    deleteMemoryUseCase("mem-001")

                    coVerify(exactly = 1) { memoryRepository.deleteMemory("mem-001") }
                }
            }

            describe("error propagation") {

                it("propagates NetworkUnavailable from repository") {
                    coEvery { memoryRepository.deleteMemory(any()) } returns ApiResult.NetworkUnavailable

                    val result = deleteMemoryUseCase("mem-001")

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { memoryRepository.deleteMemory(any()) } returns ApiResult.Error(error)

                    val result = deleteMemoryUseCase("mem-001")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
