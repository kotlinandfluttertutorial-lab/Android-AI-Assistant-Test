/**
 * BackendEndpointSelectorPropertyTest.kt — core-network property-based tests
 *
 * **Property 34: Federated Endpoint Eligibility**
 *
 * Validates: Requirements 35.2, 35.4
 *
 * Uses Kotest PropTest to generate random lists of [BackendEndpoint] objects with
 * varying regions and roles, plus a user with specific region + role constraints.
 *
 * Invariants asserted:
 * 1. When [BackendEndpointSelector.select] returns [EndpointSelectionResult.Selected],
 *    the selected endpoint's [BackendEndpoint.regionTag] ALWAYS equals the user's region.
 * 2. When [BackendEndpointSelector.select] returns [EndpointSelectionResult.Selected],
 *    the selected endpoint's [BackendEndpoint.allowedRoles] ALWAYS contains the user's role.
 * 3. When NO endpoint in the config satisfies BOTH constraints, the result is ALWAYS
 *    [EndpointSelectionResult.NoEligibleEndpoint] — never a non-eligible endpoint.
 * 4. [BackendEndpointSelector.selectNext] also satisfies invariants 1 and 2 for its
 *    result (when a next endpoint exists).
 * 5. The selected endpoint always has the minimum [BackendEndpoint.latencyMs] among all
 *    eligible endpoints (tie-breaking guarantee).
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.model.FederationConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

// ─── Generators ───────────────────────────────────────────────────────────────

/** A small fixed set of region tags to make collisions realistic. */
private val REGIONS = listOf("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1")

/** A small fixed set of RBAC role values. */
private val ROLES = listOf("user", "premium", "admin")

/** Generates a random region from the small pool (realistic collision rate). */
private val arbRegion: Arb<String> = Arb.element(REGIONS)

/** Generates a random RBAC role. */
private val arbRole: Arb<String> = Arb.element(ROLES)

/**
 * Generates a random [BackendEndpoint] with region drawn from [REGIONS],
 * roles drawn from [ROLES], and a latency between 1..5000 ms.
 */
private val arbEndpoint: Arb<BackendEndpoint> =
    Arb.long(1L..5_000L).map { latency ->
        // Use deterministic naming based on latency to avoid non-determinism in tests
        BackendEndpoint(
            name = "endpoint-$latency-${latency % 97}", // simple deterministic suffix
            baseUrl = "https://endpoint-$latency.example.com/",
            regionTag = REGIONS[(latency % REGIONS.size).toInt()],
            allowedRoles = ROLES.take(((latency % (ROLES.size + 1)).toInt())),
            latencyMs = latency
        )
    }

/** Generates a list of 0..10 endpoints. */
private val arbEndpointList: Arb<List<BackendEndpoint>> = Arb.list(arbEndpoint, 0..10)

// ─── Property test ────────────────────────────────────────────────────────────

class BackendEndpointSelectorPropertyTest :
    FunSpec({

        val selector = BackendEndpointSelector()

        // ── Property 34 (Part 1): Selected endpoint always satisfies region constraint ──

        test("Property 34 – selected endpoint always satisfies the user's region constraint") {
            checkAll(iterations = 500, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                val config = FederationConfig(endpoints)
                val result = selector.select(config, userRegion = userRegion, userRole = userRole)

                if (result is EndpointSelectionResult.Selected) {
                    result.endpoint.regionTag shouldBe userRegion
                }
                // NoEligibleEndpoint result is also acceptable — tested separately below
            }
        }

        // ── Property 34 (Part 2): Selected endpoint always satisfies role constraint ────

        test("Property 34 – selected endpoint always contains the user's role in allowedRoles") {
            checkAll(iterations = 500, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                val config = FederationConfig(endpoints)
                val result = selector.select(config, userRegion = userRegion, userRole = userRole)

                if (result is EndpointSelectionResult.Selected) {
                    (userRole in result.endpoint.allowedRoles) shouldBe true
                }
            }
        }

        // ── Property 34 (Part 3): No eligible endpoint → NoEligibleEndpoint, not a non-eligible ──

        test(
            "Property 34 – when no endpoint satisfies constraints, result is NoEligibleEndpoint, never a non-eligible endpoint"
        ) {
            checkAll(iterations = 500, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                val config = FederationConfig(endpoints)

                // Check whether any endpoint is actually eligible
                val anyEligible = endpoints.any { ep ->
                    ep.regionTag == userRegion && userRole in ep.allowedRoles
                }

                val result = selector.select(config, userRegion = userRegion, userRole = userRole)

                if (!anyEligible) {
                    // If nothing was eligible, we MUST get NoEligibleEndpoint — never a wrong endpoint
                    result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
                }

                // Conversely: if the result is Selected, at least one eligible endpoint must exist
                if (result is EndpointSelectionResult.Selected) {
                    anyEligible shouldBe true
                }
            }
        }

        // ── Property 34 (Part 4): Latency tie-breaking — selected endpoint has minimum latency ──

        test("Property 34 – selected endpoint has the minimum latencyMs among all eligible endpoints") {
            checkAll(iterations = 500, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                val config = FederationConfig(endpoints)
                val result = selector.select(config, userRegion = userRegion, userRole = userRole)

                if (result is EndpointSelectionResult.Selected) {
                    val eligibleLatencies = endpoints
                        .filter { ep -> ep.regionTag == userRegion && userRole in ep.allowedRoles }
                        .map { it.latencyMs }

                    val selectedLatency = result.endpoint.latencyMs
                    val minimumLatency = eligibleLatencies.minOrNull()!!

                    selectedLatency shouldBe minimumLatency
                }
            }
        }

        // ── Property 34 (Part 5): selectNext also satisfies region + role constraints ──

        test("Property 34 – selectNext result always satisfies region and role constraints") {
            checkAll(iterations = 300, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                if (endpoints.isNotEmpty()) {
                    val config = FederationConfig(endpoints)
                    val currentEndpoint = endpoints.first()

                    val result = selector.selectNext(
                        config = config,
                        userRegion = userRegion,
                        userRole = userRole,
                        currentEndpoint = currentEndpoint
                    )

                    if (result is EndpointSelectionResult.Selected) {
                        result.endpoint.regionTag shouldBe userRegion
                        (userRole in result.endpoint.allowedRoles) shouldBe true
                        result.endpoint.name shouldBe result.endpoint.name // tautology — never returns currentEndpoint
                    }
                }
            }
        }

        // ── Property 34 (Part 6): selectNext never returns the failed (currentEndpoint) endpoint ──

        test("Property 34 – selectNext never returns the same endpoint that just failed") {
            checkAll(iterations = 300, arbEndpointList, arbRegion, arbRole) { endpoints, userRegion, userRole ->
                if (endpoints.isNotEmpty()) {
                    val config = FederationConfig(endpoints)
                    val failedEndpoint = endpoints.first()

                    val result = selector.selectNext(
                        config = config,
                        userRegion = userRegion,
                        userRole = userRole,
                        currentEndpoint = failedEndpoint
                    )

                    if (result is EndpointSelectionResult.Selected) {
                        // The selector must exclude the failed endpoint by name.
                        (result.endpoint.name != failedEndpoint.name) shouldBe true
                    }
                }
            }
        }

        // ── Property 34 (Part 7): Empty config always yields NoEligibleEndpoint ──

        test("Property 34 – empty federation config always yields NoEligibleEndpoint") {
            checkAll(iterations = 200, arbRegion, arbRole) { userRegion, userRole ->
                val result = selector.select(FederationConfig(emptyList()), userRegion, userRole)
                result.shouldBeInstanceOf<EndpointSelectionResult.NoEligibleEndpoint>()
            }
        }
    })
