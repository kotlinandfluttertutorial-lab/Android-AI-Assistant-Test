/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : BackendEndpoint.kt
 * Purpose    : BackendEndpoint and FederationConfig — domain entities for
 *              federated multi-backend support.
 *
 * Architecture Layer : Domain
 * Pattern Used       : Kotlin Data Classes (pure domain entities)
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Federated backend routing with region + RBAC constraints
 *   - Zero Android or third-party framework dependencies
 *
 * Dependencies:
 *   - None (pure Kotlin)
 * ============================================================
 */
/**
 * BackendEndpoint.kt
 *
 * Purpose: Domain entities representing a single backend endpoint and the complete
 *          federation configuration used to route API requests to the correct backend.
 *
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 35.1, 35.2, 35.5, 35.8
 */

package com.aiassistant.domain.model

/**
 * Represents a single backend endpoint within the federated cluster.
 *
 * Each endpoint is characterized by its geographic region and the RBAC roles it is
 * authorized to serve. The [latencyMs] field is updated periodically by the health-check
 * worker and is used to break ties when multiple eligible endpoints exist (Requirement 35.2).
 *
 * @param name         Human-readable identifier displayed in the failover banner (Req 35.6).
 * @param baseUrl      The root URL against which all API paths are resolved. Must end with '/'.
 * @param regionTag    Geographic region identifier (e.g. "us-east-1", "eu-west-1").
 *                     Used to enforce data-residency constraints (Req 35.1, 35.4).
 * @param allowedRoles Ordered list of RBAC role values that this endpoint is authorized
 *                     to process. An empty list means no user may use this endpoint.
 * @param latencyMs    Most recently measured round-trip latency in milliseconds. Defaults to
 *                     [Long.MAX_VALUE] so brand-new endpoints rank last until a health check
 *                     has been completed. Updated by [FederationHealthCheckWorker] (Req 35.5).
 */
data class BackendEndpoint(
    val name: String,
    val baseUrl: String,
    val regionTag: String,
    val allowedRoles: List<String>,
    val latencyMs: Long = Long.MAX_VALUE
)
