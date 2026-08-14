/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : BackendEndpointSelector.kt
 * Purpose    : Selects the best eligible BackendEndpoint for a given user's
 *              region and RBAC role, with latency-based tie-breaking.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Strategy / Selector
 *
 * Key Concepts:
 *   - Eligibility = matching regionTag AND containing the user's role in allowedRoles
 *   - Tie-breaking = lowest latencyMs among eligible endpoints
 *   - Returns sealed Result so callers can distinguish "eligible found" vs "all exhausted"
 *
 * Dependencies:
 *   - domain (BackendEndpoint, FederationConfig)
 * ============================================================
 */
/**
 * BackendEndpointSelector.kt — core-network module
 *
 * Purpose: Pure selection logic that picks the best [BackendEndpoint] for a user's
 *          region + role constraints, or returns [EndpointSelectionResult.NoEligibleEndpoint]
 *          when the constraints cannot be satisfied.
 *
 * Architecture: core-network — injected into [FailoverInterceptor] and the
 *               Hilt [FederationModule]. Zero Android UI dependencies.
 * Dependencies: domain (BackendEndpoint, FederationConfig)
 *
 * Algorithm:
 * 1. Filter endpoints whose [BackendEndpoint.regionTag] equals [userRegion].
 * 2. From those, filter endpoints whose [BackendEndpoint.allowedRoles] contains [userRole].
 * 3. From the eligible set, return the endpoint with the minimum [BackendEndpoint.latencyMs].
 * 4. If the eligible set is empty, return [EndpointSelectionResult.NoEligibleEndpoint].
 *
 * Requirements: 35.2, 35.4
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.model.FederationConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of an endpoint selection attempt.
 */
sealed class EndpointSelectionResult {
    /**
     * A suitable endpoint was found.
     * @param endpoint The selected [BackendEndpoint].
     */
    data class Selected(val endpoint: BackendEndpoint) : EndpointSelectionResult()

    /**
     * No endpoint satisfied both the region and role constraints.
     * The caller MUST display a structured error and MUST NOT route to a non-eligible endpoint
     * (Requirement 35.4).
     */
    data object NoEligibleEndpoint : EndpointSelectionResult()
}

/**
 * Selects the optimal [BackendEndpoint] from a [FederationConfig] for a user with
 * a specific data residency region and RBAC role.
 *
 * This class is intentionally stateless; the [FederationConfig] is always passed
 * explicitly so callers control when to re-read the latest config.
 */
@Singleton
class BackendEndpointSelector @Inject constructor() {

    /**
     * Returns the best eligible endpoint for [userRegion] + [userRole] from [config],
     * or [EndpointSelectionResult.NoEligibleEndpoint] if none qualifies.
     *
     * "Best" is defined as:
     * 1. Endpoint's [BackendEndpoint.regionTag] equals [userRegion] (case-sensitive).
     * 2. Endpoint's [BackendEndpoint.allowedRoles] contains [userRole] (exact match).
     * 3. Among all qualifying endpoints, the one with the lowest [BackendEndpoint.latencyMs].
     *
     * @param config     The current federation configuration to select from.
     * @param userRegion The data residency region tag required by the user.
     * @param userRole   The RBAC role of the authenticated user.
     */
    fun select(config: FederationConfig, userRegion: String, userRole: String): EndpointSelectionResult {
        val eligible = config.endpoints
            .filter { it.regionTag == userRegion && userRole in it.allowedRoles }

        if (eligible.isEmpty()) {
            return EndpointSelectionResult.NoEligibleEndpoint
        }

        val best = eligible.minByOrNull { it.latencyMs }
            ?: return EndpointSelectionResult.NoEligibleEndpoint

        return EndpointSelectionResult.Selected(best)
    }

    /**
     * Returns the next eligible endpoint after [currentEndpoint] from [config], skipping
     * non-eligible endpoints. Used by [FailoverInterceptor] during failover.
     *
     * @param config          The current federation configuration.
     * @param userRegion      The data residency region tag required by the user.
     * @param userRole        The RBAC role of the authenticated user.
     * @param currentEndpoint The endpoint that just failed — excluded from the candidate set.
     * @return The next lowest-latency eligible endpoint, or [EndpointSelectionResult.NoEligibleEndpoint]
     *         if no other eligible endpoint is available (Requirement 35.4).
     */
    fun selectNext(
        config: FederationConfig,
        userRegion: String,
        userRole: String,
        currentEndpoint: BackendEndpoint
    ): EndpointSelectionResult {
        val eligible = config.endpoints
            .filter { it.regionTag == userRegion && userRole in it.allowedRoles }
            .filter { it.name != currentEndpoint.name }

        if (eligible.isEmpty()) {
            return EndpointSelectionResult.NoEligibleEndpoint
        }

        val best = eligible.minByOrNull { it.latencyMs }
            ?: return EndpointSelectionResult.NoEligibleEndpoint

        return EndpointSelectionResult.Selected(best)
    }

    /**
     * Returns the next eligible endpoint excluding ALL [exhaustedEndpointNames] from [config].
     * Used by [FailoverInterceptor] to avoid re-trying endpoints that have already failed in
     * the current request cycle.
     *
     * @param config                 The current federation configuration.
     * @param userRegion             The data residency region tag required by the user.
     * @param userRole               The RBAC role of the authenticated user.
     * @param exhaustedEndpointNames Names of ALL endpoints that have already failed in this call.
     * @return The next lowest-latency eligible endpoint, or [EndpointSelectionResult.NoEligibleEndpoint]
     *         if all eligible endpoints are exhausted (Requirement 35.4).
     */
    fun selectNext(
        config: FederationConfig,
        userRegion: String,
        userRole: String,
        exhaustedEndpointNames: Set<String>
    ): EndpointSelectionResult {
        val eligible = config.endpoints
            .filter { it.regionTag == userRegion && userRole in it.allowedRoles }
            .filter { it.name !in exhaustedEndpointNames }

        if (eligible.isEmpty()) {
            return EndpointSelectionResult.NoEligibleEndpoint
        }

        val best = eligible.minByOrNull { it.latencyMs }
            ?: return EndpointSelectionResult.NoEligibleEndpoint

        return EndpointSelectionResult.Selected(best)
    }
}
