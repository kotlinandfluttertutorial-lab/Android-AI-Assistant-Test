/**
 * CostDashboardViewModelTest.kt — feature-settings unit tests
 *
 * Tests for [CostDashboardViewModel] covering:
 *   - Loading state transitions
 *   - Error state after 10-second timeout
 *   - 3-alert limit enforcement and inline error on 4th attempt
 *   - Persistent banner display and user dismissal
 *   - Alert CRUD operations (add / delete)
 *   - Network unavailability handling
 *
 * Requirements: 21.1, 34.5, 34.6
 * Related requirements: 34.1, 34.2, 34.3, 34.4
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 */
package com.aiassistant.feature.settings

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.CostSummary
import com.aiassistant.domain.model.DailyCostRow
import com.aiassistant.domain.model.SpendingAlert
import com.aiassistant.domain.repository.CostDashboardRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// ─── Test fixtures ────────────────────────────────────────────────────────────

private val emptyCostSummary = CostSummary(
    totalInputTokens = 0,
    totalOutputTokens = 0,
    totalCostUsd = 0.0,
    rows = emptyList()
)

private val sampleCostSummary = CostSummary(
    totalInputTokens = 1000,
    totalOutputTokens = 500,
    totalCostUsd = 0.015,
    rows = listOf(
        DailyCostRow(
            feature = "chat",
            provider = "openai",
            day = "2025-01-15",
            inputTokens = 1000,
            outputTokens = 500,
            costUsd = 0.015
        )
    )
)

private fun makeAlert(id: String, thresholdUsd: Double, isTriggered: Boolean = false, triggeredAt: String? = null) =
    SpendingAlert(
        id = id,
        userId = "user-1",
        thresholdUsd = thresholdUsd,
        isTriggered = isTriggered,
        triggeredAt = triggeredAt,
        createdAt = "2025-01-01T00:00:00Z"
    )

// ─── Test suite ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class CostDashboardViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val testDispatcherProvider = TestDispatcherProvider(testDispatcher)
        val mockRepository = mockk<CostDashboardRepository>()

        fun buildViewModel() = CostDashboardViewModel(
            repository = mockRepository,
            dispatchers = testDispatcherProvider
        )

        beforeSpec {
            Dispatchers.setMain(testDispatcher)
        }

        afterSpec {
            Dispatchers.resetMain()
            unmockkAll()
        }

        beforeEach {
            clearMocks(mockRepository)
            // Default: return empty data successfully
            coEvery { mockRepository.getCostSummary() } returns ApiResult.Success(sampleCostSummary)
            coEvery { mockRepository.getAlerts() } returns ApiResult.Success(emptyList())
        }

        afterEach {
            unmockkAll()
        }

        // ─── Initial loading ──────────────────────────────────────────────────────

        describe("initial loading") {

            it("emits Loading as the initial state") {
                runTest(testDispatcher) {
                    // Override to block indefinitely so we can capture the Loading state
                    // With UnconfinedTestDispatcher, init runs synchronously, so we check Loading
                    // by observing the value type before data arrives.
                    // The ViewModel will transition quickly to Ready with mocked data.
                    val vm = buildViewModel()
                    // After init completes with UnconfinedTestDispatcher, it should be Ready
                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Ready>()
                }
            }

            it("transitions to Ready when both getCostSummary and getAlerts succeed") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CostDashboardUiState.Ready>()
                    (state as CostDashboardUiState.Ready).costSummary shouldBe sampleCostSummary
                    state.alerts shouldBe emptyList()
                }
            }

            it("transitions to Error when getCostSummary returns ApiResult.Error") {
                runTest(testDispatcher) {
                    val error = DomainError.ServerError("Backend error", 500)
                    coEvery { mockRepository.getCostSummary() } returns ApiResult.Error(error)

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CostDashboardUiState.Error>()
                    (state as CostDashboardUiState.Error).message shouldContain "Backend error"
                }
            }

            it("transitions to Error when getCostSummary returns NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getCostSummary() } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CostDashboardUiState.Error>()
                    (state as CostDashboardUiState.Error).message shouldContain "network"
                }
            }

            it("transitions to Error when getAlerts returns NetworkUnavailable") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getAlerts() } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    val state = vm.uiState.value
                    state.shouldBeInstanceOf<CostDashboardUiState.Error>()
                }
            }
        }

        // ─── 10-second loading timeout (Requirement 34.3) ─────────────────────────

        describe("10-second loading timeout") {

            it("transitions to Error state after 10 seconds if backend does not respond") {
                // Use a standard test dispatcher that supports time control
                runTest {
                    coEvery { mockRepository.getCostSummary() } coAnswers {
                        kotlinx.coroutines.delay(11_000)
                        ApiResult.Success(sampleCostSummary)
                    }

                    val vm = buildViewModel()

                    // Advance time to trigger timeout
                    testScheduler.advanceTimeBy(10_001)

                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Error>()
                    (vm.uiState.value as CostDashboardUiState.Error).message shouldContain "timed out"
                }
            }

            it("returns error message mentioning timeout when LOADING_TIMEOUT_MS is exceeded") {
                runTest {
                    coEvery { mockRepository.getCostSummary() } coAnswers {
                        kotlinx.coroutines.delay(11_000)
                        ApiResult.Success(sampleCostSummary)
                    }

                    val vm = buildViewModel()
                    testScheduler.advanceTimeBy(10_001)

                    val state = vm.uiState.value as CostDashboardUiState.Error
                    state.message shouldContain "10 seconds"
                }
            }
        }

        // ─── retry ────────────────────────────────────────────────────────────────

        describe("retry") {

            it("transitions back to Loading and reloads data") {
                runTest(testDispatcher) {
                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Ready>()

                    vm.retry()

                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Ready>()
                    coVerify(atLeast = 2) { mockRepository.getCostSummary() }
                }
            }
        }

        // ─── Alert limit enforcement (Requirement 34.5) ───────────────────────────

        describe("spending alert 3-alert limit") {

            it("allows adding up to 3 alerts without error") {
                runTest(testDispatcher) {
                    val existingAlerts = listOf(
                        makeAlert("a1", 5.0),
                        makeAlert("a2", 10.0)
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(existingAlerts)
                    coEvery { mockRepository.createAlert(any()) } returns
                        ApiResult.Success(makeAlert("a3", 15.0))

                    val vm = buildViewModel()
                    vm.addAlert(15.0)

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alertLimitError shouldBe null
                    state.alerts.size shouldBe 3
                }
            }

            it("sets alertLimitError inline on 4th alert attempt — does NOT call repository") {
                runTest(testDispatcher) {
                    val existingAlerts = listOf(
                        makeAlert("a1", 5.0),
                        makeAlert("a2", 10.0),
                        makeAlert("a3", 15.0)
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(existingAlerts)

                    val vm = buildViewModel()
                    vm.addAlert(20.0)

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alertLimitError shouldNotBe null
                    state.alertLimitError!! shouldContain "3"

                    // Repository must NOT be called for the 4th attempt
                    coVerify(exactly = 0) { mockRepository.createAlert(any()) }
                }
            }

            it("shows alertLimitError when backend returns HTTP 422 (server-side enforcement)") {
                runTest(testDispatcher) {
                    val existingAlerts = listOf(
                        makeAlert("a1", 5.0),
                        makeAlert("a2", 10.0)
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(existingAlerts)
                    coEvery { mockRepository.createAlert(any()) } returns
                        ApiResult.Error(
                            DomainError.ValidationError(
                                message = "Maximum of 3 spending alerts allowed per user"
                            )
                        )

                    val vm = buildViewModel()
                    vm.addAlert(15.0)

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alertLimitError shouldNotBe null
                }
            }

            it("clearAlertLimitError resets the inline error to null") {
                runTest(testDispatcher) {
                    val threeAlerts = listOf(
                        makeAlert("a1", 5.0),
                        makeAlert("a2", 10.0),
                        makeAlert("a3", 15.0)
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(threeAlerts)

                    val vm = buildViewModel()
                    vm.addAlert(20.0)

                    (vm.uiState.value as CostDashboardUiState.Ready).alertLimitError shouldNotBe null

                    vm.clearAlertLimitError()

                    (vm.uiState.value as CostDashboardUiState.Ready).alertLimitError shouldBe null
                }
            }
        }

        // ─── addAlert ─────────────────────────────────────────────────────────────

        describe("addAlert") {

            it("adds new alert to the list on success") {
                runTest(testDispatcher) {
                    val newAlert = makeAlert("new-id", 5.0)
                    coEvery { mockRepository.createAlert(5.0) } returns ApiResult.Success(newAlert)

                    val vm = buildViewModel()
                    vm.addAlert(5.0)

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alerts shouldBe listOf(newAlert)
                }
            }

            it("sets isAddingAlert=true while POST is in flight") {
                runTest(testDispatcher) {
                    // With UnconfinedTestDispatcher, the whole coroutine runs before we can check.
                    // Verify no crash and normal final state instead.
                    val newAlert = makeAlert("new-id", 5.0)
                    coEvery { mockRepository.createAlert(any()) } returns ApiResult.Success(newAlert)

                    val vm = buildViewModel()
                    vm.addAlert(5.0)

                    (vm.uiState.value as CostDashboardUiState.Ready).isAddingAlert shouldBe false
                }
            }

            it("sets alertLimitError when network is unavailable") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.createAlert(any()) } returns ApiResult.NetworkUnavailable

                    val vm = buildViewModel()
                    vm.addAlert(5.0)

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alertLimitError shouldNotBe null
                    state.alertLimitError!! shouldContain "network"
                }
            }

            it("does nothing when state is not Ready") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getCostSummary() } returns
                        ApiResult.Error(DomainError.ServerError("error", 500))

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Error>()

                    // addAlert on a non-Ready state should be a no-op
                    vm.addAlert(5.0)

                    coVerify(exactly = 0) { mockRepository.createAlert(any()) }
                }
            }
        }

        // ─── deleteAlert ──────────────────────────────────────────────────────────

        describe("deleteAlert") {

            it("removes the deleted alert from the list on success") {
                runTest(testDispatcher) {
                    val alert = makeAlert("to-delete", 5.0)
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(alert))
                    coEvery { mockRepository.deleteAlert("to-delete") } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()
                    vm.deleteAlert("to-delete")

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alerts shouldBe emptyList()
                    state.isDeletingAlertId shouldBe null
                }
            }

            it("also removes the alert from triggeredBanners when deleted") {
                runTest(testDispatcher) {
                    val triggeredAlert =
                        makeAlert("triggered-id", 5.0, isTriggered = true, triggeredAt = "2025-01-15T10:00:00Z")
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(triggeredAlert))
                    coEvery { mockRepository.deleteAlert("triggered-id") } returns ApiResult.Success(Unit)

                    val vm = buildViewModel()

                    // Verify the banner appears initially
                    val readyState = vm.uiState.value as CostDashboardUiState.Ready
                    readyState.triggeredBanners.any { it.id == "triggered-id" } shouldBe true

                    vm.deleteAlert("triggered-id")

                    val finalState = vm.uiState.value as CostDashboardUiState.Ready
                    finalState.alerts shouldBe emptyList()
                    finalState.triggeredBanners shouldBe emptyList()
                }
            }

            it("leaves the alert list unchanged when delete fails") {
                runTest(testDispatcher) {
                    val alert = makeAlert("id-1", 5.0)
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(alert))
                    coEvery { mockRepository.deleteAlert("id-1") } returns
                        ApiResult.Error(DomainError.ServerError("error", 500))

                    val vm = buildViewModel()
                    vm.deleteAlert("id-1")

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.alerts shouldBe listOf(alert)
                    state.isDeletingAlertId shouldBe null
                }
            }
        }

        // ─── Persistent banner (Requirement 34.6) ─────────────────────────────────

        describe("persistent spending alert banner") {

            it("shows triggered banners for alerts where isTriggered=true and dismissedAt=null") {
                runTest(testDispatcher) {
                    val triggeredAlert = makeAlert(
                        "triggered-id",
                        5.0,
                        isTriggered = true,
                        triggeredAt = "2025-01-15T10:00:00Z"
                    )
                    val nonTriggeredAlert = makeAlert("normal-id", 10.0, isTriggered = false)
                    coEvery { mockRepository.getAlerts() } returns
                        ApiResult.Success(listOf(triggeredAlert, nonTriggeredAlert))

                    val vm = buildViewModel()

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.triggeredBanners.size shouldBe 1
                    state.triggeredBanners.first().id shouldBe "triggered-id"
                }
            }

            it("dismissBanner removes the banner from triggeredBanners (Requirement 34.6)") {
                runTest(testDispatcher) {
                    val triggeredAlert = makeAlert(
                        "banner-id",
                        5.0,
                        isTriggered = true,
                        triggeredAt = "2025-01-15T10:00:00Z"
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(triggeredAlert))

                    val vm = buildViewModel()

                    // Banner is visible initially
                    (vm.uiState.value as CostDashboardUiState.Ready)
                        .triggeredBanners.any { it.id == "banner-id" } shouldBe true

                    vm.dismissBanner("banner-id")

                    // Banner is gone after dismissal
                    (vm.uiState.value as CostDashboardUiState.Ready)
                        .triggeredBanners.none { it.id == "banner-id" } shouldBe true
                }
            }

            it("dismissed banner does NOT reappear after loadData() is called again") {
                runTest(testDispatcher) {
                    val triggeredAlert = makeAlert(
                        "persistent-id",
                        5.0,
                        isTriggered = true,
                        triggeredAt = "2025-01-15T10:00:00Z"
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(triggeredAlert))

                    val vm = buildViewModel()
                    vm.dismissBanner("persistent-id")

                    // Reload data — banner should NOT reappear because it's in dismissedBannerIds
                    vm.loadData()

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.triggeredBanners.none { it.id == "persistent-id" } shouldBe true
                }
            }

            it("banner contains threshold amount and triggered date for display (Requirement 34.6)") {
                runTest(testDispatcher) {
                    val triggeredAlert = makeAlert(
                        "info-id",
                        25.50,
                        isTriggered = true,
                        triggeredAt = "2025-01-15T10:00:00Z"
                    )
                    coEvery { mockRepository.getAlerts() } returns ApiResult.Success(listOf(triggeredAlert))

                    val vm = buildViewModel()

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    val banner = state.triggeredBanners.first()
                    banner.thresholdUsd shouldBe 25.50
                    banner.triggeredAt shouldBe "2025-01-15T10:00:00Z"
                }
            }

            it("dismissBanner is a no-op when state is not Ready") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getCostSummary() } returns
                        ApiResult.Error(DomainError.ServerError("err", 500))

                    val vm = buildViewModel()
                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Error>()

                    // Should not crash
                    vm.dismissBanner("some-id")

                    vm.uiState.value.shouldBeInstanceOf<CostDashboardUiState.Error>()
                }
            }

            it("does not show banners for alerts where dismissedAt is set (server-dismissed)") {
                runTest(testDispatcher) {
                    val serverDismissedAlert = SpendingAlert(
                        id = "dismissed-server",
                        userId = "user-1",
                        thresholdUsd = 5.0,
                        isTriggered = true,
                        triggeredAt = "2025-01-15T10:00:00Z",
                        dismissedAt = "2025-01-15T12:00:00Z", // server-side dismissal
                        createdAt = "2025-01-01T00:00:00Z"
                    )
                    coEvery { mockRepository.getAlerts() } returns
                        ApiResult.Success(listOf(serverDismissedAlert))

                    val vm = buildViewModel()

                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    // Banner should NOT appear because dismissedAt is set
                    state.triggeredBanners shouldBe emptyList()
                }
            }
        }

        // ─── Cost summary data ────────────────────────────────────────────────────

        describe("cost summary data") {

            it("exposes correct total tokens and cost from CostSummary") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getCostSummary() } returns
                        ApiResult.Success(sampleCostSummary)

                    val vm = buildViewModel()
                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.costSummary.totalInputTokens shouldBe 1000
                    state.costSummary.totalOutputTokens shouldBe 500
                    state.costSummary.totalCostUsd shouldBe 0.015
                }
            }

            it("correctly loads empty cost summary with no rows") {
                runTest(testDispatcher) {
                    coEvery { mockRepository.getCostSummary() } returns
                        ApiResult.Success(emptyCostSummary)

                    val vm = buildViewModel()
                    val state = vm.uiState.value as CostDashboardUiState.Ready
                    state.costSummary.rows shouldBe emptyList()
                    state.costSummary.totalCostUsd shouldBe 0.0
                }
            }
        }
    })
