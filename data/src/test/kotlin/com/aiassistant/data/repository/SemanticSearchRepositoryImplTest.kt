/**
 * SemanticSearchRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [SemanticSearchRepositoryImpl], covering:
 *   - Online + success: connectivity true, remote returns results → ApiResult.Success with domain objects
 *   - Offline: connectivity false → ApiResult.NetworkUnavailable (no remote call)
 *   - Remote error: remote returns ApiResult.Error → error propagated
 *
 * Architecture: data module — pure JVM unit tests.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking SemanticSearchRemoteDataSource, ConnectivityObserver
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 36.1, 36.3
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.search.SemanticSearchRemoteDataSource
import com.aiassistant.data.remote.search.SemanticSearchResponseDto
import com.aiassistant.data.remote.search.SemanticSearchResultDto
import com.aiassistant.domain.model.SemanticSearchResult
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

// ─── Fixtures ────────────────────────────────────────────────────────────────

private fun fakeResultDto(sourceType: String = "note", sourceName: String = "Test Note", relevanceScore: Float = 0.9f) =
    SemanticSearchResultDto(
        sourceType = sourceType,
        sourceName = sourceName,
        excerpt = "A short excerpt from the note content.",
        relevanceScore = relevanceScore,
        deepLink = "aiassistant://notes/note-1"
    )

private fun fakeResponseDto(results: List<SemanticSearchResultDto>) =
    SemanticSearchResponseDto(results = results, total = results.size)

class SemanticSearchRepositoryImplTest :
    DescribeSpec({

        val remoteSource: SemanticSearchRemoteDataSource = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var repository: SemanticSearchRepositoryImpl

        beforeEach {
            clearAllMocks()
            // Default: provide a flow to satisfy any isConnectedFlow usages
            io.mockk.every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = SemanticSearchRepositoryImpl(
                remoteSource = remoteSource,
                connectivityObserver = connectivityObserver,
                dispatchers = dispatchers
            )
        }

        describe("search()") {

            describe("online — success path") {

                it("returns Success with mapped domain objects when online") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        val dtos = listOf(
                            fakeResultDto(sourceType = "note", sourceName = "Note A", relevanceScore = 0.9f),
                            fakeResultDto(sourceType = "conversation", sourceName = "Chat B", relevanceScore = 0.7f)
                        )
                        coEvery { remoteSource.search("kotlin coroutines") } returns
                            ApiResult.Success(fakeResponseDto(dtos))

                        val result = repository.search("kotlin coroutines")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        val data = (result as ApiResult.Success).data
                        data shouldHaveSize 2
                        data[0].sourceName shouldBe "Note A"
                        data[0].sourceType shouldBe SemanticSearchResult.SourceType.NOTE
                        data[1].sourceName shouldBe "Chat B"
                        data[1].sourceType shouldBe SemanticSearchResult.SourceType.CONVERSATION
                    }
                }

                it("correctly maps all source types from DTO to domain enum") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        val dtos = listOf(
                            fakeResultDto(sourceType = "conversation", sourceName = "Conv"),
                            fakeResultDto(sourceType = "note", sourceName = "Note"),
                            fakeResultDto(sourceType = "document", sourceName = "Doc"),
                            fakeResultDto(sourceType = "memory", sourceName = "Mem")
                        )
                        coEvery { remoteSource.search("any") } returns
                            ApiResult.Success(fakeResponseDto(dtos))

                        val result = repository.search("any")

                        val data = (result as ApiResult.Success).data
                        data[0].sourceType shouldBe SemanticSearchResult.SourceType.CONVERSATION
                        data[1].sourceType shouldBe SemanticSearchResult.SourceType.NOTE
                        data[2].sourceType shouldBe SemanticSearchResult.SourceType.DOCUMENT
                        data[3].sourceType shouldBe SemanticSearchResult.SourceType.MEMORY
                    }
                }

                it("maps unknown source type to CONVERSATION fallback") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        val dtos = listOf(fakeResultDto(sourceType = "unknown_type", sourceName = "X"))
                        coEvery { remoteSource.search("query") } returns
                            ApiResult.Success(fakeResponseDto(dtos))

                        val result = repository.search("query")

                        val data = (result as ApiResult.Success).data
                        data[0].sourceType shouldBe SemanticSearchResult.SourceType.CONVERSATION
                    }
                }

                it("returns empty Success when remote returns no results") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.search("obscure query") } returns
                            ApiResult.Success(fakeResponseDto(emptyList()))

                        val result = repository.search("obscure query")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        (result as ApiResult.Success).data.shouldBeEmpty()
                    }
                }
            }

            describe("offline path") {

                it("returns NetworkUnavailable when device is offline") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns false

                        val result = repository.search("offline query")

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }

                it("does NOT call remote when device is offline") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns false

                        repository.search("offline query")

                        coVerify(exactly = 0) { remoteSource.search(any()) }
                    }
                }
            }

            describe("remote error path") {

                it("propagates ApiResult.Error from remote source") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.search("error query") } returns ApiResult.Error(
                            DomainError.ServerError(
                                message = "Search service unavailable",
                                httpStatusCode = 503
                            )
                        )

                        val result = repository.search("error query")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        val error = (result as ApiResult.Error).error
                        error.shouldBeInstanceOf<DomainError.ServerError>()
                    }
                }

                it("propagates NetworkUnavailable from remote source") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.search("network query") } returns ApiResult.NetworkUnavailable

                        val result = repository.search("network query")

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }

                it("propagates Unauthorized error from remote source") {
                    runTest {
                        io.mockk.every { connectivityObserver.isConnected() } returns true
                        coEvery { remoteSource.search("auth query") } returns ApiResult.Error(
                            DomainError.Unauthorized()
                        )

                        val result = repository.search("auth query")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                    }
                }
            }
        }
    })
