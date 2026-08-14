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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverBannerStateProviderTest :
    DescribeSpec({

        fun buildProvider(): Pair<FailoverBannerStateProvider, FailoverEventBus> {
            val bus = FailoverEventBus()
            val provider = FailoverBannerStateProvider(bus)
            return Pair(provider, bus)
        }

        describe("FailoverBannerStateProvider — initial state") {

            it("starts with banner hidden") {
                val (provider, _) = buildProvider()
                val state = provider.bannerState.value
                state.isVisible shouldBe false
                state.activeBackendName shouldBe ""
                state.failoverReason shouldBe ""
            }
        }

        describe("FailoverBannerStateProvider — SwitchedToEndpoint") {

            it("makes banner visible with endpoint name and reason on SwitchedToEndpoint") {
                runTest {
                    val (provider, bus) = buildProvider()

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
                }
            }

            it("updates activeBackendName when failover switches to a different endpoint") {
                runTest {
                    val (provider, bus) = buildProvider()

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
                }
            }
        }

        describe("FailoverBannerStateProvider — PrimaryEndpointRecovered") {

            it("hides the banner when PrimaryEndpointRecovered is published") {
                runTest {
                    val (provider, bus) = buildProvider()

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
                }
            }
        }

        describe("FailoverBannerStateProvider — AllEndpointsExhausted") {

            it("hides the banner when AllEndpointsExhausted is published") {
                runTest {
                    val (provider, bus) = buildProvider()

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
                }
            }
        }

        describe("FailoverBannerStateProvider — StateFlow semantics") {

            it("late subscribers receive the current banner state immediately") {
                runTest {
                    val (provider, bus) = buildProvider()

                    // Publish an event before any subscriber exists
                    bus.publish(
                        FailoverEvent.SwitchedToEndpoint(
                            activeEndpointName = "eu-failover",
                            failoverReason = "Connection timed out"
                        )
                    )

                    // Allow the internal collection coroutine to process the event
                    advanceUntilIdle()

                    // A late subscriber should see the current state (StateFlow replay = 1)
                    val currentState = provider.bannerState.value
                    currentState.isVisible shouldBe true
                    currentState.activeBackendName shouldBe "eu-failover"
                    currentState.failoverReason shouldBe "Connection timed out"
                }
            }
        }
    })
