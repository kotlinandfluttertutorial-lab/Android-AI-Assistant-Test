/**
 * CostDashboardRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [CostDashboardRepositoryImpl], covering:
 *   - getCostSummary() happy path: API DTO maps to CostSummary domain object
 *   - getCostSummary() network error: IOException → ApiResult.NetworkUnavailable
 *   - getAlerts() happy path: returns list of SpendingAlert domain objects
 *   - createAlert() happy path: returns SpendingAlert domain object
 *   - deleteAlert() happy path: returns ApiResult.Success(Unit)
 *   - deleteAlert() HTTP 401: returns ApiResult.Error with DomainError.Unauthorized
 *
 * Architecture: data module — pure JVM unit tests.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking CostDashboardApiService
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 34.1, 34.2, 34.4, 34.7
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.remote.usage.CostDashboardApiService
import com.aiassistant.data.remote.usage.CostSummaryDto
import com.aiassistant.data.remote.usage.CreateAlertRequest
import com.aiassistant.data.remote.usage.DailyCostRowDto
import com.aiassistant.data.remote.usage.SpendingAlertDeleteDto
import com.aiassistant.data.remote.usage.SpendingAlertDto
import com.aiassistant.data.remote.usage.SpendingAlertListDto
import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.SpendingAlert
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

// ─── Fixtures ────────────────────────────────────────────────────────────────

private fun fakeCostSummaryDto() = CostSummaryDto(
    totalInputTokens = 100_000,
    totalOutputTokens = 50_000,
    totalCostUsd = 2.50,
    rows = listOf(
        DailyCostRowDto(
            feature = "chat",
            provider = "openai",
            day = "2025-01-01",
            inputTokens = 50_000,
            outputTokens = 25_000,
            costUsd = 1.25
        )
    ),
    windowDays = 90
)

private fun fakeSpendingAlertDto(id: String = "alert-1", thresholdUsd: Double = 10.0, isTriggered: Boolean = false) =
    SpendingAlertDto(
        id = id,
        userId = "user-123",
        thresholdUsd = thresholdUsd,
        isTriggered = isTriggered,
        triggeredAt = null,
        dismissedAt = null,
        createdAt = "2025-01-01T00:00:00Z"
    )

private fun makeHttpException(code: Int): HttpException {
    val response = Response.error<Any>(
        code,
        "".toResponseBody("application/json".toMediaType())
    )
    return HttpException(response)
}

class CostDashboardRepositoryImplTest :
    DescribeSpec({

        val apiService: CostDashboardApiService = mockk()
        val dispatchers = TestDispatcherProvider()
        lateinit var repository: CostDashboardRepositoryImpl

        beforeEach {
            clearAllMocks()
            repository = CostDashboardRepositoryImpl(
                apiService = apiService,
                dispatchers = dispatchers
            )
        }

        // ─── getCostSummary() ─────────────────────────────────────────────────────

        describe("getCostSummary()") {

            it("maps DTO to CostSummary domain object on success") {
                runTest {
                    val dto = fakeCostSummaryDto()
                    coEvery { apiService.getCostSummary(any()) } returns dto

                    val result = repository.getCostSummary()

                    result.shouldBeInstanceOf<ApiResult.Success<CostSummary>>()
                    val summary = (result as ApiResult.Success).data
                    summary.totalInputTokens shouldBe 100_000
                    summary.totalOutputTokens shouldBe 50_000
                    summary.totalCostUsd shouldBe 2.50
                    summary.windowDays shouldBe 90
                    summary.rows.size shouldBe 1
                    summary.rows[0].feature shouldBe "chat"
                    summary.rows[0].provider shouldBe "openai"
                }
            }

            it("returns NetworkUnavailable when IOException is thrown") {
                runTest {
                    coEvery { apiService.getCostSummary(any()) } throws IOException("No connection")

                    val result = repository.getCostSummary()

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("returns Unauthorized error for HTTP 401") {
                runTest {
                    coEvery { apiService.getCostSummary(any()) } throws makeHttpException(401)

                    val result = repository.getCostSummary()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("returns ServerError for HTTP 500") {
                runTest {
                    coEvery { apiService.getCostSummary(any()) } throws makeHttpException(500)

                    val result = repository.getCostSummary()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }
            }
        }

        // ─── getAlerts() ──────────────────────────────────────────────────────────

        describe("getAlerts()") {

            it("returns list of SpendingAlert domain objects on success") {
                runTest {
                    val alertDtos = listOf(
                        fakeSpendingAlertDto(id = "alert-1", thresholdUsd = 10.0),
                        fakeSpendingAlertDto(id = "alert-2", thresholdUsd = 25.0)
                    )
                    coEvery { apiService.getAlerts() } returns SpendingAlertListDto(alerts = alertDtos)

                    val result = repository.getAlerts()

                    result.shouldBeInstanceOf<ApiResult.Success<List<SpendingAlert>>>()
                    val alerts = (result as ApiResult.Success).data
                    alerts.size shouldBe 2
                    alerts[0].id shouldBe "alert-1"
                    alerts[0].thresholdUsd shouldBe 10.0
                    alerts[1].id shouldBe "alert-2"
                    alerts[1].thresholdUsd shouldBe 25.0
                }
            }

            it("returns empty list when no alerts exist") {
                runTest {
                    coEvery { apiService.getAlerts() } returns SpendingAlertListDto(alerts = emptyList())

                    val result = repository.getAlerts()

                    result.shouldBeInstanceOf<ApiResult.Success<List<SpendingAlert>>>()
                    (result as ApiResult.Success).data.size shouldBe 0
                }
            }

            it("returns NetworkUnavailable when IOException is thrown") {
                runTest {
                    coEvery { apiService.getAlerts() } throws IOException("Timeout")

                    val result = repository.getAlerts()

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }

        // ─── createAlert() ────────────────────────────────────────────────────────

        describe("createAlert()") {

            it("returns SpendingAlert domain object on success") {
                runTest {
                    val threshold = 15.0
                    val dto = fakeSpendingAlertDto(id = "new-alert", thresholdUsd = threshold)
                    coEvery { apiService.createAlert(CreateAlertRequest(thresholdUsd = threshold)) } returns dto

                    val result = repository.createAlert(thresholdUsd = threshold)

                    result.shouldBeInstanceOf<ApiResult.Success<SpendingAlert>>()
                    val alert = (result as ApiResult.Success).data
                    alert.id shouldBe "new-alert"
                    alert.thresholdUsd shouldBe threshold
                    alert.isTriggered shouldBe false
                }
            }

            it("returns NetworkUnavailable when IOException is thrown") {
                runTest {
                    coEvery { apiService.createAlert(any()) } throws IOException("Connection refused")

                    val result = repository.createAlert(thresholdUsd = 50.0)

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("returns ValidationError for HTTP 422") {
                runTest {
                    coEvery { apiService.createAlert(any()) } throws makeHttpException(422)

                    val result = repository.createAlert(thresholdUsd = -1.0)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        // ─── deleteAlert() ────────────────────────────────────────────────────────

        describe("deleteAlert()") {

            it("returns ApiResult.Success(Unit) on successful deletion") {
                runTest {
                    val alertId = "alert-to-delete"
                    coEvery { apiService.deleteAlert(alertId) } returns SpendingAlertDeleteDto(
                        deleted = true,
                        alertId = alertId
                    )

                    val result = repository.deleteAlert(alertId)

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { apiService.deleteAlert(alertId) }
                }
            }

            it("returns Error with Unauthorized for HTTP 401") {
                runTest {
                    coEvery { apiService.deleteAlert(any()) } throws makeHttpException(401)

                    val result = repository.deleteAlert("alert-xyz")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }
            }

            it("returns Error with Forbidden for HTTP 403") {
                runTest {
                    coEvery { apiService.deleteAlert(any()) } throws makeHttpException(403)

                    val result = repository.deleteAlert("alert-xyz")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
            }

            it("returns NetworkUnavailable when IOException is thrown") {
                runTest {
                    coEvery { apiService.deleteAlert(any()) } throws IOException("No network")

                    val result = repository.deleteAlert("alert-abc")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }
    })
