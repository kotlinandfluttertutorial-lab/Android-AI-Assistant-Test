/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : FederationConfig.kt
 * Purpose    : FederationConfig — ordered list of BackendEndpoint objects that
 *              defines the full federation topology for this client.
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Data Class (pure domain entity)
 *
 * Key Concepts:
 *   - Read from Firebase Remote Config JSON (Req 35.8)
 *   - Applied within 60 seconds of publication without app restart
 *
 * Dependencies:
 *   - None (pure Kotlin)
 * ============================================================
 */
/**
 * FederationConfig.kt
 *
 * Purpose: Domain entity that holds the ordered list of [BackendEndpoint] objects that
 *          constitute the complete backend federation topology.
 *
 * The config is published by an Admin via Firebase Remote Config and must be applied to
 * the running application within 60 seconds of publication without requiring an app restart
 * (Requirement 35.8). The config is parsed from a JSON blob delivered by Remote Config and
 * stored in-memory as this data class.
 *
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 35.1, 35.8
 */

package com.aiassistant.domain.model

/**
 * The complete set of backend endpoints that the AI Assistant may route to.
 *
 * Endpoints are stored in declaration order. [BackendEndpointSelector] uses region + role
 * matching followed by latency ordering to select the active endpoint (Requirement 35.2).
 *
 * @param endpoints Ordered list of configured [BackendEndpoint] objects. An empty list
 *                  means no backend is reachable — all requests will produce a structured
 *                  error (Requirement 35.4).
 */
data class FederationConfig(val endpoints: List<BackendEndpoint> = emptyList())
