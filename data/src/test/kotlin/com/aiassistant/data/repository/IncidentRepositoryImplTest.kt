/**
 * IncidentRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [IncidentRepositoryImpl].
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.data.remote.devops.IncidentDto
import com.aiassistant.data.remote.devops.IncidentListResponse
import com.aiassistant.data.remote.devops.IncidentRemoteDataSource
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.model.IncidentSeverity
import com.aiassistant.domain.model.IncidentStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest

class IncidentRepositoryImplTest : DescribeSpec({

    val remote: IncidentRemoteDataSource = mockk()
    val connectivityObserver: ConnectivityObserver = mockk()
    val dispatchers = TestDispatcherProvider()

    lateinit var repository: IncidentRepositoryImpl

    beforeEach {
        clearAllMocks()
        repository = IncidentRepositoryImpl(
            remote = remote,
            connectivityObserver = connectivityObserver,
            dispatchers = dispatchers
        )
    }

    afterEach {
        unmockkAll()
    }

    describe("getIncidents()") {
        it("returns Success and maps DTOs to domain model when online and remote succeeds") {
            runTest {
                // Given
                val status = "OPEN"
                val severity = "CRITICAL"
                val limit = 10
                val incidentDto = IncidentDto(
                    id = "inc-1",
                    title = "Database Down",
                    severity = "CRITICAL",
                    status = "OPEN",
                    detectionMethod = "health_check",
                    triggeredBy = "system",
                    metricValue = 0.0,
                    thresholdValue = 1.0,
                    aiSummary = "DB is unreachable",
                    aiConfidence = 0.99,
                    aiRecommendedFix = "Check connection",
                    rcaSummary = "Unknown",
                    rcaConfidence = 0.1,
                    eventCount = 5,
                    detectedAt = "2023-10-01T10:00:00Z",
                    resolvedAt = null
                )
                val response = IncidentListResponse(
                    incidents = listOf(incidentDto),
                    total = 1,
                    openCount = 1
                )
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.listIncidents(status, severity, limit) } returns ApiResult.Success(response)

                // When
                val result = repository.getIncidents(status, severity, limit)

                // Then
                result.shouldBeInstanceOf<ApiResult.Success<List<Incident>>>()
                val incidents = (result as ApiResult.Success).data
                incidents.size shouldBe 1
                val firstIncident = incidents[0]
                firstIncident.id shouldBe "inc-1"
                firstIncident.title shouldBe "Database Down"
                firstIncident.severity shouldBe IncidentSeverity.CRITICAL
                firstIncident.status shouldBe IncidentStatus.OPEN
                firstIncident.detectionMethod shouldBe "health_check"
                firstIncident.triggeredBy shouldBe "system"
                firstIncident.metricValue shouldBe 0.0
                firstIncident.thresholdValue shouldBe 1.0
                firstIncident.aiSummary shouldBe "DB is unreachable"
                firstIncident.aiConfidence shouldBe 0.99
                firstIncident.aiRecommendedFix shouldBe "Check connection"
                firstIncident.rcaSummary shouldBe "Unknown"
                firstIncident.rcaConfidence shouldBe 0.1
                firstIncident.eventCount shouldBe 5
                firstIncident.detectedAt shouldBe "2023-10-01T10:00:00Z"
                firstIncident.resolvedAt shouldBe null
            }
        }

        it("returns NetworkUnavailable when connectivity is lost") {
            runTest {
                every { connectivityObserver.isConnected() } returns false

                val result = repository.getIncidents(null, null, 20)

                result shouldBe ApiResult.NetworkUnavailable
            }
        }

        it("propagates Error from remote") {
            runTest {
                val error = DomainError.ServerError("API Error", 502)
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.listIncidents(any(), any(), any()) } returns ApiResult.Error(error)

                val result = repository.getIncidents(null, null, 20)

                result shouldBe ApiResult.Error(error)
            }
        }
    }

    describe("getOpenCount()") {
        it("returns Success with openCount from listIncidents response") {
            runTest {
                // Given
                val response = IncidentListResponse(
                    incidents = emptyList(),
                    total = 10,
                    openCount = 5
                )
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.listIncidents(status = "OPEN", limit = 1) } returns ApiResult.Success(response)

                // When
                val result = repository.getOpenCount()

                // Then
                result shouldBe ApiResult.Success(5)
            }
        }

        it("returns NetworkUnavailable when connectivity is lost") {
            runTest {
                every { connectivityObserver.isConnected() } returns false

                val result = repository.getOpenCount()

                result shouldBe ApiResult.NetworkUnavailable
            }
        }

        it("propagates Error from remote") {
            runTest {
                val error = DomainError.NetworkError("Failed to fetch")
                every { connectivityObserver.isConnected() } returns true
                coEvery { remote.listIncidents(any(), any(), any()) } returns ApiResult.Error(error)

                val result = repository.getOpenCount()

                result shouldBe ApiResult.Error(error)
            }
        }
    }
})
