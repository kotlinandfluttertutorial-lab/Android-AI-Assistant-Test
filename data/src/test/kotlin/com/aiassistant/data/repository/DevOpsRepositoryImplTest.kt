/**
 * DevOpsRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [DevOpsRepositoryImpl].
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.devops.DevOpsChatResponse
import com.aiassistant.data.remote.devops.DevOpsRemoteDataSource
import com.aiassistant.data.remote.devops.ErrorAnalysisResponse
import com.aiassistant.data.remote.devops.ToolCallDto
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest

class DevOpsRepositoryImplTest : DescribeSpec({

    val remote: DevOpsRemoteDataSource = mockk()
    val connectivityObserver: ConnectivityObserver = mockk()
    val dispatchers = TestDispatcherProvider()

    lateinit var repository: DevOpsRepositoryImpl

    beforeEach {
        clearAllMocks()
        repository = DevOpsRepositoryImpl(
            remote = remote,
            connectivityObserver = connectivityObserver,
            dispatchers = dispatchers
        )
    }

    afterEach {
        unmockkAll()
    }

    describe("chat()") {
        it("returns Success and maps DTO to domain model when online and remote succeeds") {
            runTest {
                // Given
                val question = "What is wrong with the cluster?"
                val provider = "openai"
                val remoteResponse = DevOpsChatResponse(
                    sessionId = "session-123",
                    question = question,
                    answer = "Everything is fine.",
                    citations = listOf("doc1"),
                    toolCalls = listOf(ToolCallDto(toolName = "get_logs")),
                    roundsUsed = 2,
                    llmProvider = "openai"
                )
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.chat(question, provider) } returns ApiResult.Success(remoteResponse)

                // When
                val result = repository.chat(question, provider)

                // Then
                result.shouldBeInstanceOf<ApiResult.Success<*>>()
                val data = (result as ApiResult.Success).data
                data.sessionId shouldBe "session-123"
                data.question shouldBe question
                data.answer shouldBe "Everything is fine."
                data.citations shouldBe listOf("doc1")
                data.toolsUsed shouldBe listOf("get_logs")
                data.roundsUsed shouldBe 2
                data.llmProvider shouldBe "openai"
            }
        }

        it("returns NetworkUnavailable when connectivity is lost") {
            runTest {
                every { connectivityObserver.isConnected() } returns false

                val result = repository.chat("query", null)

                result shouldBe ApiResult.NetworkUnavailable
            }
        }

        it("returns Error when remote call fails") {
            runTest {
                val error = DomainError.ServerError("Remote failure", 500)
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.chat(any(), any()) } returns ApiResult.Error(error)

                val result = repository.chat("query", null)

                result shouldBe ApiResult.Error(error)
            }
        }
    }

    describe("analyseErrors()") {
        it("returns Success and maps DTO to domain model when online and remote succeeds") {
            runTest {
                // Given
                val lookback = 30
                val sessionId = "session-456"
                val remoteResponse = ErrorAnalysisResponse(
                    analysisId = "analysis-1",
                    severity = "high",
                    summary = "CPU Spike",
                    likelyRootCause = "Memory leak",
                    confidence = 0.95,
                    recommendedFix = "Restart pods",
                    evidence = listOf("log-A"),
                    possibleCauses = listOf("cause-1"),
                    relatedDocumentation = listOf("doc-B"),
                    lowConfidenceWarning = null,
                    eventsAnalysed = 150
                )
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.analyseErrors(lookback, sessionId) } returns ApiResult.Success(remoteResponse)

                // When
                val result = repository.analyseErrors(lookback, sessionId)

                // Then
                result.shouldBeInstanceOf<ApiResult.Success<*>>()
                val data = (result as ApiResult.Success).data
                data.analysisId shouldBe "analysis-1"
                data.severity shouldBe "high"
                data.summary shouldBe "CPU Spike"
                data.likelyRootCause shouldBe "Memory leak"
                data.confidence shouldBe 0.95
                data.recommendedFix shouldBe "Restart pods"
                data.evidence shouldBe listOf("log-A")
                data.possibleCauses shouldBe listOf("cause-1")
                data.relatedDocs shouldBe listOf("doc-B")
                data.lowConfidenceWarning shouldBe null
                data.eventsAnalysed shouldBe 150
            }
        }

        it("returns NetworkUnavailable when connectivity is lost") {
            runTest {
                every { connectivityObserver.isConnected() } returns false

                val result = repository.analyseErrors(30, null)

                result shouldBe ApiResult.NetworkUnavailable
            }
        }

        it("propagates Error from remote") {
            runTest {
                val error = DomainError.NetworkError("Timeout")
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.analyseErrors(any(), any()) } returns ApiResult.Error(error)

                val result = repository.analyseErrors(30, null)

                result shouldBe ApiResult.Error(error)
            }
        }
    }
})
