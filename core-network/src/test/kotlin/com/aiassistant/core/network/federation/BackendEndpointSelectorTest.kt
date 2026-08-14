/**
 * BackendEndpointSelectorTest.kt — core-network unit tests
 *
 * Unit tests for [BackendEndpointSelector] covering:
 * - Correct endpoint selected when region + role both match
 * - Latency-based tie-breaking when multiple endpoints are eligible
 * - [selectNext] skips non-eligible endpoints (different region or role)
 * - [selectNext] excludes the currently-failed endpoint
 * - Structured [EndpointSelectionResult.NoEligibleEndpoint] returned when all
 *   endpoints are exhausted (no silent fallback to non-eligible endpoint)
 *
 * Requirements: 21.1, 35.2, 35.4
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.model.FederationConfig
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

// ─── Test fixtures ────────────────────────────────────────────────────────────

private fun endpoint(name: String, region: String, roles: List<String>, latencyMs: Long = Long.MAX_VALUE) =
    BackendEndpoint(
        name = name,
        baseUrl = "https://$name.example.com/",
        regionTag = region,
        allowedRoles = roles,
        latencyMs = latencyMs
    )

private fun config(vararg endpoints: BackendEndpoint) = FederationConfig(endpoints.toList())

// ─── Test suite ───────────────────────────────────────────────────────────────

class BackendEndpointSelectorTest :
    DescribeSpec({

        val selector = BackendEndpointSelector()

        describe("BackendEndpointSelector.select") {

            describe("region and role matching") {

                it("selects the endpoint whose regionTag and allowedRoles both match") {
                    val ep = endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint shouldBe ep
                }

                it("returns NoEligibleEndpoint when region does not match") {
                    val ep = endpoint("eu-primary", region = "eu-west-1", roles = listOf("user"), latencyMs = 50)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("returns NoEligibleEndpoint when role is not in allowedRoles") {
                    val ep = endpoint("us-admin", region = "us-east-1", roles = listOf("admin"), latencyMs = 50)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("returns NoEligibleEndpoint when allowedRoles is empty") {
                    val ep = endpoint("us-locked", region = "us-east-1", roles = emptyList(), latencyMs = 10)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("returns NoEligibleEndpoint for empty FederationConfig") {
                    val result = selector.select(FederationConfig(), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("matches endpoint with multiple allowed roles when user role is listed") {
                    val ep =
                        endpoint(
                            "multi-role",
                            region = "us-east-1",
                            roles = listOf("user", "premium", "admin"),
                            latencyMs = 80
                        )
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "premium")

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint shouldBe ep
                }

                it("region matching is case-sensitive") {
                    val ep = endpoint("us-case", region = "us-east-1", roles = listOf("user"), latencyMs = 50)
                    val result = selector.select(config(ep), userRegion = "US-EAST-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("role matching is case-sensitive") {
                    val ep = endpoint("us-role-case", region = "us-east-1", roles = listOf("user"), latencyMs = 50)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "User")

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }
            }

            describe("latency-based tie-breaking") {

                it("selects the endpoint with the lowest latencyMs among eligible endpoints") {
                    val slow = endpoint("us-slow", region = "us-east-1", roles = listOf("user"), latencyMs = 500)
                    val fast = endpoint("us-fast", region = "us-east-1", roles = listOf("user"), latencyMs = 50)
                    val medium = endpoint("us-medium", region = "us-east-1", roles = listOf("user"), latencyMs = 200)

                    val result = selector.select(
                        config(slow, fast, medium),
                        userRegion = "us-east-1",
                        userRole = "user"
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint.name shouldBe "us-fast"
                }

                it("selects the sole eligible endpoint even if its latency is MAX_VALUE") {
                    val ep =
                        endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = Long.MAX_VALUE)
                    val result = selector.select(config(ep), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint shouldBe ep
                }

                it("with equal latency, returns one of the tied endpoints (not NoEligibleEndpoint)") {
                    val ep1 = endpoint("ep-a", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val ep2 = endpoint("ep-b", region = "us-east-1", roles = listOf("user"), latencyMs = 100)

                    val result = selector.select(config(ep1, ep2), userRegion = "us-east-1", userRole = "user")

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                }

                it("ignores ineligible endpoints when selecting by latency") {
                    val ineligible = endpoint("eu-fast", region = "eu-west-1", roles = listOf("user"), latencyMs = 5)
                    val eligible = endpoint("us-slow", region = "us-east-1", roles = listOf("user"), latencyMs = 999)

                    val result = selector.select(
                        config(ineligible, eligible),
                        userRegion = "us-east-1",
                        userRole = "user"
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint.name shouldBe "us-slow"
                }
            }
        }

        describe("BackendEndpointSelector.selectNext (failover)") {

            describe("skipping non-eligible endpoints") {

                it("returns the next eligible endpoint after the failed one") {
                    val primary = endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val secondary =
                        endpoint("us-secondary", region = "us-east-1", roles = listOf("user"), latencyMs = 200)

                    val result = selector.selectNext(
                        config = config(primary, secondary),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = primary
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint shouldBe secondary
                }

                it("skips endpoints with a different region during failover") {
                    val primary = endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val wrongRegion =
                        endpoint("eu-secondary", region = "eu-west-1", roles = listOf("user"), latencyMs = 50)
                    val correctNext =
                        endpoint("us-backup", region = "us-east-1", roles = listOf("user"), latencyMs = 300)

                    val result = selector.selectNext(
                        config = config(primary, wrongRegion, correctNext),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = primary
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint.name shouldBe "us-backup"
                }

                it("skips endpoints that do not allow the user's role during failover") {
                    val primary = endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val wrongRole =
                        endpoint("us-admin-only", region = "us-east-1", roles = listOf("admin"), latencyMs = 50)
                    val correctNext =
                        endpoint("us-user-backup", region = "us-east-1", roles = listOf("user"), latencyMs = 300)

                    val result = selector.selectNext(
                        config = config(primary, wrongRole, correctNext),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = primary
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint.name shouldBe "us-user-backup"
                }

                it("excludes the failed endpoint from candidates") {
                    val only = endpoint("us-only", region = "us-east-1", roles = listOf("user"), latencyMs = 100)

                    val result = selector.selectNext(
                        config = config(only),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = only
                    )

                    // The only eligible endpoint was the one that failed — no more remain.
                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }
            }

            describe("all endpoints exhausted") {

                it("returns NoEligibleEndpoint when the only eligible endpoint just failed") {
                    val sole = endpoint("us-sole", region = "us-east-1", roles = listOf("user"), latencyMs = 100)

                    val result = selector.selectNext(
                        config = config(sole),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = sole
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("returns NoEligibleEndpoint when all remaining candidates are ineligible") {
                    val current = endpoint("us-primary", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val wrongRegion = endpoint("eu-1", region = "eu-west-1", roles = listOf("user"), latencyMs = 50)
                    val wrongRole = endpoint("us-admin", region = "us-east-1", roles = listOf("admin"), latencyMs = 60)

                    val result = selector.selectNext(
                        config = config(current, wrongRegion, wrongRole),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = current
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                it("selects the next lowest-latency eligible endpoint among multiple candidates") {
                    val failed = endpoint("us-failed", region = "us-east-1", roles = listOf("user"), latencyMs = 100)
                    val nextSlow = endpoint("us-slow", region = "us-east-1", roles = listOf("user"), latencyMs = 600)
                    val nextFast = endpoint("us-fast", region = "us-east-1", roles = listOf("user"), latencyMs = 200)

                    val result = selector.selectNext(
                        config = config(failed, nextSlow, nextFast),
                        userRegion = "us-east-1",
                        userRole = "user",
                        currentEndpoint = failed
                    )

                    result.shouldBeInstanceOf<EndpointSelectionResult.Selected>()
                    (result as EndpointSelectionResult.Selected).endpoint.name shouldBe "us-fast"
                }
            }
        }
    })
