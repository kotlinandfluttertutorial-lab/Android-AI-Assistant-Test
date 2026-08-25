/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : FederationHealthCheckWorker.kt
 * Purpose    : WorkManager CoroutineWorker that pings every configured backend
 *              endpoint's /health URL every 30 seconds and updates latency rankings.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : WorkManager CoroutineWorker + Hilt injection
 *
 * Key Concepts:
 *   - Periodic execution every 30 seconds (Req 35.5)
 *   - Updates latencyMs in FederationConfigRepository without app restart
 *   - Also triggers a Remote Config fetch to pick up Admin-published config changes
 *
 * Dependencies:
 *   - WorkManager (hilt-work), OkHttp, domain (FederationRepository)
 * ============================================================
 */
/**
 * FederationHealthCheckWorker.kt — core-network module
 *
 * Purpose: WorkManager [CoroutineWorker] that runs every 30 seconds to:
 * 1. Trigger a Firebase Remote Config fetch so new [FederationConfig] changes published
 *    by an Admin are applied within the 60-second window (Requirement 35.8).
 * 2. Ping each configured endpoint's `/health` URL and measure round-trip latency.
 * 3. Persist the measured latency via [FederationRepository.updateLatency] so that the
 *    [BackendEndpointSelector] always uses fresh latency data (Requirement 35.5).
 *
 * Architecture: core-network — injected via @HiltWorker. MUST NOT import feature or domain
 *               use-case classes.
 * Dependencies: WorkManager, OkHttp (plain GET for health ping), domain interfaces
 *
 * WorkManager scheduling:
 * ```
 * PeriodicWorkRequestBuilder<FederationHealthCheckWorker>(30, TimeUnit.SECONDS)
 *     .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
 *     .build()
 * ```
 * The work request is enqueued by [FederationModule] at app startup using KEEP policy
 * so only one instance runs at a time.
 *
 * Requirements: 35.5, 35.8
 */

package com.aiassistant.core.network.federation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Maximum time in milliseconds we wait for a single health-ping response. */
private const val HEALTH_PING_TIMEOUT_MS = 5_000L

/** The path appended to each endpoint's [com.aiassistant.domain.model.BackendEndpoint.baseUrl]. */
private const val HEALTH_PATH = "health"

/**
 * Latency value assigned to an endpoint whose health ping timed-out or errored.
 * This ensures unreachable endpoints sort to the bottom of the candidate list.
 */
private const val UNREACHABLE_LATENCY = Long.MAX_VALUE

/**
 * WorkManager worker that periodically measures every backend endpoint's health latency
 * and refreshes the Firebase Remote Config.
 */
@HiltWorker
class FederationHealthCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val federationConfigRepository: FederationConfigRepository,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("FederationHealthCheckWorker: starting health check run")

        // 1. Pull latest config from Firebase Remote Config (≤60 s SLA, Req 35.8).
        federationConfigRepository.fetchAndApply()

        // 2. Ping each configured endpoint.
        val config = federationConfigRepository.getConfig()
        if (config.endpoints.isEmpty()) {
            Timber.d("FederationHealthCheckWorker: no endpoints configured — skipping pings")
            return Result.success()
        }

        for (endpoint in config.endpoints) {
            val latency = measureLatency(endpoint.baseUrl)
            federationConfigRepository.updateLatency(endpoint.name, latency)
            Timber.d(
                "FederationHealthCheckWorker: endpoint='${endpoint.name}' latency=${
                    if (latency == UNREACHABLE_LATENCY) "UNREACHABLE" else "${latency}ms"
                }"
            )
        }

        Timber.d("FederationHealthCheckWorker: health check complete")
        return Result.success()
    }

    /**
     * Sends a GET request to `<baseUrl>/health` and returns the round-trip time in ms.
     * Returns [UNREACHABLE_LATENCY] on error or timeout.
     */
    private suspend fun measureLatency(baseUrl: String): Long = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/$HEALTH_PATH"
        val request = Request.Builder().url(url).get().build()

        withTimeoutOrNull(HEALTH_PING_TIMEOUT_MS) {
            val start = System.currentTimeMillis()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        System.currentTimeMillis() - start
                    } else {
                        Timber.w("Health check for $url returned HTTP ${response.code}")
                        UNREACHABLE_LATENCY
                    }
                }
            } catch (e: IOException) {
                Timber.w(e, "Health check for $url failed with IO error")
                UNREACHABLE_LATENCY
            }
        } ?: run {
            Timber.w("Health check for $url timed out after ${HEALTH_PING_TIMEOUT_MS}ms")
            UNREACHABLE_LATENCY
        }
    }
}
