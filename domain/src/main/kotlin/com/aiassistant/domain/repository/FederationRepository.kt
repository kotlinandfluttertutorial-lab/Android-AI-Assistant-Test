/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : FederationRepository.kt
 * Purpose    : Repository interface for federation configuration management.
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Implemented in core-network; consumed via the DI graph
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * FederationRepository.kt
 *
 * Purpose: Domain repository interface for obtaining and updating the [FederationConfig].
 *          Abstracts over the Firebase Remote Config fetch mechanism so domain use cases
 *          remain framework-agnostic.
 *
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: kotlinx.coroutines (Flow)
 *
 * Requirements: 35.1, 35.5, 35.8
 */

package com.aiassistant.domain.repository

import com.aiassistant.domain.model.FederationConfig
import kotlinx.coroutines.flow.Flow

/**
 * Repository that provides access to the federation configuration and supports
 * updating individual endpoint latency measurements from health checks.
 */
interface FederationRepository {

    /**
     * Hot [Flow] that emits the most-recent [FederationConfig] whenever it changes
     * (e.g. after a Firebase Remote Config fetch or after a health-check run).
     *
     * Collectors receive the current config immediately on subscription.
     */
    val configFlow: Flow<FederationConfig>

    /**
     * Returns a snapshot of the current [FederationConfig].
     * Suitable for use in non-coroutine contexts such as WorkManager workers.
     */
    suspend fun getConfig(): FederationConfig

    /**
     * Persists an updated [latencyMs] value for the endpoint identified by [endpointName].
     * Called by [FederationHealthCheckWorker] after each ping (Requirement 35.5).
     *
     * @param endpointName Name of the endpoint whose latency measurement changed.
     * @param latencyMs    The new measured round-trip latency in milliseconds.
     */
    suspend fun updateLatency(endpointName: String, latencyMs: Long)
}
