/**
 * SemanticSearchUseCaseTest.kt — domain module
 *
 * Purpose: Unit tests for [SemanticSearchUseCase], covering:
 *   - Happy path: results returned and filtered to relevance ≥ 0.5
 *   - Filtering: results with score < 0.5 are excluded
 *   - Empty list returned when no results meet threshold
 *   - Error propagation from repository
 *
 * Architecture: domain module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking SemanticSearchRepository
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 36.1, 36.3, 36.8
 */
package com.aiassistant.domain.usecase.search

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.SemanticSearchResult
import com.aiassistant.domain.repository.SemanticSearchRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest

// ─── Fixtures ────────────────────────────────────────────────────────────────

private fun fakeResult(
    sourceName: String = "Test Note",
    relevanceScore: Float = 0.9f,
    sourceType: SemanticSearchResult.SourceType = SemanticSearchResult.SourceType.NOTE
) = SemanticSearchResult(
    sourceType = sourceType,
    sourceName = sourceName,
    excerpt = "A short excerpt from the matching content.",
    relevanceScore = relevanceScore,
    deepLinkUri = "aiassistant://notes/test-id"
)

class SemanticSearchUseCaseTest :
    DescribeSpec({

        val repository: SemanticSearchRepository = mockk()
        lateinit var semanticSearchUseCase: SemanticSearchUseCase

        beforeEach {
            clearAllMocks()
            semanticSearchUseCase = SemanticSearchUseCase(repository)
        }

        afterEach {
            unmockkAll()
        }

        describe("SemanticSearchUseCase") {

            describe("happy path") {

                it("returns Success with results from repository when all have score >= 0.5") {
                    runTest {
                        val results = listOf(
                            fakeResult(sourceName = "Note A", relevanceScore = 0.9f),
                            fakeResult(sourceName = "Note B", relevanceScore = 0.7f),
                            fakeResult(sourceName = "Note C", relevanceScore = 0.5f)
                        )
                        coEvery { repository.search("test query") } returns ApiResult.Success(results)

                        val result = semanticSearchUseCase("test query")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        (result as ApiResult.Success).data shouldHaveSize 3
                        coVerify(exactly = 1) { repository.search("test query") }
                    }
                }

                it("filters out results with relevance score < 0.5 (Requirement 36.3)") {
                    runTest {
                        val results = listOf(
                            fakeResult(sourceName = "High Score", relevanceScore = 0.8f),
                            fakeResult(sourceName = "Below Threshold", relevanceScore = 0.4f),
                            fakeResult(sourceName = "Exactly At Threshold", relevanceScore = 0.5f),
                            fakeResult(sourceName = "Very Low", relevanceScore = 0.1f)
                        )
                        coEvery { repository.search("query") } returns ApiResult.Success(results)

                        val result = semanticSearchUseCase("query")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        val data = (result as ApiResult.Success).data
                        data shouldHaveSize 2
                        data.map { it.sourceName } shouldBe listOf("High Score", "Exactly At Threshold")
                    }
                }

                it("returns empty list when no results meet the 0.5 threshold") {
                    runTest {
                        val results = listOf(
                            fakeResult(sourceName = "Too Low A", relevanceScore = 0.2f),
                            fakeResult(sourceName = "Too Low B", relevanceScore = 0.3f)
                        )
                        coEvery { repository.search("niche query") } returns ApiResult.Success(results)

                        val result = semanticSearchUseCase("niche query")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        (result as ApiResult.Success).data.shouldBeEmpty()
                    }
                }

                it("returns empty list when repository returns empty results") {
                    runTest {
                        coEvery { repository.search("unknown") } returns ApiResult.Success(emptyList())

                        val result = semanticSearchUseCase("unknown")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        (result as ApiResult.Success).data.shouldBeEmpty()
                    }
                }

                it("preserves results across multiple source types") {
                    runTest {
                        val results = listOf(
                            fakeResult("Note Result", 0.9f, SemanticSearchResult.SourceType.NOTE),
                            fakeResult("Conversation Result", 0.8f, SemanticSearchResult.SourceType.CONVERSATION),
                            fakeResult("Document Result", 0.7f, SemanticSearchResult.SourceType.DOCUMENT),
                            fakeResult("Memory Result", 0.6f, SemanticSearchResult.SourceType.MEMORY)
                        )
                        coEvery { repository.search("cross-type") } returns ApiResult.Success(results)

                        val result = semanticSearchUseCase("cross-type")

                        result.shouldBeInstanceOf<ApiResult.Success<List<SemanticSearchResult>>>()
                        (result as ApiResult.Success).data shouldHaveSize 4
                    }
                }
            }

            describe("error path") {

                it("propagates NetworkUnavailable from repository") {
                    runTest {
                        coEvery { repository.search("offline query") } returns ApiResult.NetworkUnavailable

                        val result = semanticSearchUseCase("offline query")

                        result shouldBe ApiResult.NetworkUnavailable
                    }
                }

                it("propagates ServerError from repository") {
                    runTest {
                        coEvery { repository.search("failing query") } returns ApiResult.Error(
                            DomainError.ServerError(
                                message = "Search service unavailable",
                                httpStatusCode = 503
                            )
                        )

                        val result = semanticSearchUseCase("failing query")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        val error = (result as ApiResult.Error).error
                        error.shouldBeInstanceOf<DomainError.ServerError>()
                    }
                }

                it("propagates Unauthorized error from repository") {
                    runTest {
                        coEvery { repository.search("auth query") } returns ApiResult.Error(
                            DomainError.Unauthorized()
                        )

                        val result = semanticSearchUseCase("auth query")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                    }
                }
            }
        }
    })
