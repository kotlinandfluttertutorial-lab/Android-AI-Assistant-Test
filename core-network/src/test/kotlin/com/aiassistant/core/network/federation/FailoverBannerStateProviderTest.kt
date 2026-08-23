/**
 * FailoverBannerStateProviderTest.kt — core-network unit tests
 *
 * Unit tests for [FailoverBannerStateProvider] covering:
 * - Initial state is hidden (isVisible = false)
 * - SwitchedToEndpoint event makes banner visible with correct name/reason
 * - PrimaryEndpointRecovered event hides the banner (auto-dismiss)
 * - AllEndpointsExhausted event hides the banner (error handled elsewhere)
 * - StateFlow reflects the latest event when multiple events fire in sequence
 *
 * Requirements: 21.1, 35.6
 */

package com.aiassistant.core.network.federation

import app.cash.turbine.test
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverBannerStateProviderTest :
    DescribeSpec({
        // A single UnconfinedTestDispatcher is shared across all tests so that
        // the virtual-time scheduler is consistent. The bus and provider are
        // created INSIDE each runTest block so that providerScope.launch { collect }
        // fires eagerly within the active scheduler context before bus.publish() is called.
        val testDispatcher = UnconfinedTestDispatcher()

        afterSpec { unmockkAll() }

        describe("FailoverBannerStateProvider — initial state") {

            it("starts with banner hidden") {
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )
                    val state = provider.bannerState.value
                    state.isVisible shouldBe false
                    state.activeBackendName shouldBe ""
                    state.failoverReason shouldBe ""
                    provider.cancelScope()
                }
            }
        }

        describe("FailoverBannerStateProvider — SwitchedToEndpoint") {

            it("makes banner visible with endpoint name and reason on SwitchedToEndpoint") {
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )
                    provider.bannerState.test {
                        // Initial emission
                        val initial = awaitItem()
                        initial.isVisible shouldBe false

                        bus.publish(
                            FailoverEvent.SwitchedToEndpoint(
                                activeEndpointName = "us-secondary",
                                failoverReason = "HTTP 503"
                            )
                        )

                        val updated = awaitItem()
                        updated.isVisible shouldBe true
                        updated.activeBackendName shouldBe "us-secondary"
                        updated.failoverReason shouldBe "HTTP 503"

                        cancelAndIgnoreRemainingEvents()
                    }
                    provider.cancelScope()
                }
            }

            it("updates activeBackendName when failover switches to a different endpoint") {
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )
                    provider.bannerState.test {
                        awaitItem() // initial hidden state

                        bus.publish(
                            FailoverEvent.SwitchedToEndpoint(
                                activeEndpointName = "us-secondary",
                                failoverReason = "Connection error"
                            )
                        )
                        val firstSwitch = awaitItem()
                        firstSwitch.activeBackendName shouldBe "us-secondary"

                        bus.publish(
                            FailoverEvent.SwitchedToEndpoint(
                                activeEndpointName = "us-tertiary",
                                failoverReason = "HTTP 502"
                            )
                        )
                        val secondSwitch = awaitItem()
                        secondSwitch.activeBackendName shouldBe "us-tertiary"
                        secondSwitch.failoverReason shouldBe "HTTP 502"
                        secondSwitch.isVisible shouldBe true

                        cancelAndIgnoreRemainingEvents()
                    }
                    provider.cancelScope()
                }
            }
        }

        describe("FailoverBannerStateProvider — PrimaryEndpointRecovered") {

            it("hides the banner when PrimaryEndpointRecovered is published") {
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )
                    provider.bannerState.test {
                        awaitItem() // initial hidden state

                        // First show the banner
                        bus.publish(
                            FailoverEvent.SwitchedToEndpoint(
                                activeEndpointName = "us-secondary",
                                failoverReason = "HTTP 503"
                            )
                        )
                        val visible = awaitItem()
                        visible.isVisible shouldBe true

                        // Primary recovers → banner auto-dismisses (Requirement 35.6)
                        bus.publish(FailoverEvent.PrimaryEndpointRecovered(primaryEndpointName = "us-primary"))

                        val dismissed = awaitItem()
                        dismissed.isVisible shouldBe false
                        dismissed.activeBackendName shouldBe ""
                        dismissed.failoverReason shouldBe ""

                        cancelAndIgnoreRemainingEvents()
                    }
                    provider.cancelScope()
                }
            }
        }

        describe("FailoverBannerStateProvider — AllEndpointsExhausted") {

            it("hides the banner when AllEndpointsExhausted is published") {
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )
                    provider.bannerState.test {
                        awaitItem() // initial hidden state

                        // Show banner first
                        bus.publish(
                            FailoverEvent.SwitchedToEndpoint(
                                activeEndpointName = "us-secondary",
                                failoverReason = "HTTP 503"
                            )
                        )
                        val visible = awaitItem()
                        visible.isVisible shouldBe true

                        // All endpoints exhausted → banner dismissed, error shown elsewhere
                        bus.publish(FailoverEvent.AllEndpointsExhausted("All eligible endpoints exhausted."))

                        val dismissed = awaitItem()
                        dismissed.isVisible shouldBe false

                        cancelAndIgnoreRemainingEvents()
                    }
                    provider.cancelScope()
                }
            }
        }

        describe("FailoverBannerStateProvider — StateFlow semantics") {

            it("late subscribers receive the current banner state immediately") {
                // With UnconfinedTestDispatcher the provider's internal collect coroutine
                // starts eagerly, so tryEmit will always find an active collector.
                runTest(testDispatcher) {
                    val bus = FailoverEventBus()
                    val provider = FailoverBannerStateProvider(
                        bus, CoroutineScope(SupervisorJob() + testDispatcher)
                    )

                    // Publish the event — the internal coroutine is already collecting.
                    bus.publish(
                        FailoverEvent.SwitchedToEndpoint(
                            activeEndpointName = "eu-failover",
                            failoverReason = "Connection timed out"
                        )
                    )

                    // A late subscriber sees the current StateFlow value immediately (replay = 1).
                    val currentState = provider.bannerState.value
                    currentState.isVisible shouldBe true
                    currentState.activeBackendName shouldBe "eu-failover"
                    currentState.failoverReason shouldBe "Connection timed out"

                    provider.cancelScope()
                }
            }
        }
    })
