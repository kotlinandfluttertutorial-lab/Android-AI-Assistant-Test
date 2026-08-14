/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : FederationConfigRepository.kt
 * Purpose    : Implements FederationRepository — fetches the JSON federation config
 *              from Firebase Remote Config and exposes it as a hot StateFlow.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Repository (implements domain interface)
 *
 * Key Concepts:
 *   - Firebase Remote Config delivers the config JSON within 60 s (Req 35.8)
 *   - Uses MutableStateFlow so observers receive updates without app restart
 *   - updateLatency mutates the in-memory copy and re-emits (Req 35.5)
 *
 * Dependencies:
 *   - domain (FederationRepository, FederationConfig, BackendEndpoint)
 *   - Firebase Remote Config
 *   - kotlinx.serialization
 * ============================================================
 */
/**
 * FederationConfigRepository.kt — core-network module
 *
 * Purpose: Concrete implementation of [com.aiassistant.domain.repository.FederationRepository]
 *          that fetches federation configuration from Firebase Remote Config and exposes
 *          it via a [kotlinx.coroutines.flow.StateFlow].
 *
 * Fetch strategy:
 * - Minimum fetch interval is set to 60 seconds in production so Remote Config updates
 *   are reflected within the 60-second window required by Requirement 35.8.
 * - In debug builds the interval is 0 seconds for faster iteration.
 *
 * Architecture: core-network — depends on Firebase Remote Config SDK; MUST NOT import
 *               any domain use-case or feature module.
 * Dependencies: Firebase Remote Config, kotlinx.serialization, domain interfaces
 *
 * Requirements: 35.1, 35.5, 35.8
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.model.FederationConfig
import com.aiassistant.domain.repository.FederationRepository
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

// ─── Firebase Remote Config key ──────────────────────────────────────────────
private const val REMOTE_CONFIG_KEY_FEDERATION = "federation_config"

// ─── Fetch intervals ─────────────────────────────────────────────────────────
/** 60 seconds — satisfies the ≤60-second update SLA for production (Req 35.8). */
private const val FETCH_INTERVAL_RELEASE = 60L

/** 0 seconds — immediate fetch in debug builds. */
private const val FETCH_INTERVAL_DEBUG = 0L

// ─── Serializable DTOs (Remote Config JSON schema) ───────────────────────────

@Serializable
private data class BackendEndpointDto(
    val name: String,
    val baseUrl: String,
    val regionTag: String,
    val allowedRoles: List<String> = emptyList()
)

@Serializable
private data class FederationConfigDto(val endpoints: List<BackendEndpointDto> = emptyList())

/**
 * Repository that sources the [FederationConfig] from Firebase Remote Config and keeps
 * the in-memory state up to date after health-check latency measurements.
 */
@Singleton
class FederationConfigRepository @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
    private val json: Json,
    private val isDebugBuild: Boolean
) : FederationRepository {

    private val _configFlow = MutableStateFlow(FederationConfig())

    override val configFlow: Flow<FederationConfig> = _configFlow.asStateFlow()

    init {
        // Configure the minimum fetch interval so Remote Config updates are picked up
        // within the contractual 60-second window (Requirement 35.8).
        val fetchInterval = if (isDebugBuild) FETCH_INTERVAL_DEBUG else FETCH_INTERVAL_RELEASE
        val settings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(fetchInterval)
            .build()
        remoteConfig.setConfigSettingsAsync(settings)

        // Apply a default empty federation config so that no NullPointerExceptions
        // can occur before the first successful fetch.
        remoteConfig.setDefaultsAsync(mapOf(REMOTE_CONFIG_KEY_FEDERATION to "{}"))
    }

    override suspend fun getConfig(): FederationConfig = _configFlow.value

    /**
     * Fetches and activates the latest Remote Config, then parses the
     * [REMOTE_CONFIG_KEY_FEDERATION] key into a [FederationConfig] and emits it.
     *
     * Called by the application at startup and by [FederationHealthCheckWorker].
     */
    suspend fun fetchAndApply() {
        try {
            remoteConfig.fetchAndActivate().await()
            val raw = remoteConfig.getString(REMOTE_CONFIG_KEY_FEDERATION)
            val config = parseFederationConfig(raw)
            _configFlow.value = config
            Timber.d("FederationConfig applied: ${config.endpoints.size} endpoints")
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch federation config from Remote Config")
            // Retain the previous config so existing endpoints remain usable.
        }
    }

    override suspend fun updateLatency(endpointName: String, latencyMs: Long) {
        _configFlow.update { currentConfig ->
            val updatedEndpoints = currentConfig.endpoints.map { endpoint ->
                if (endpoint.name == endpointName) {
                    endpoint.copy(latencyMs = latencyMs)
                } else {
                    endpoint
                }
            }
            currentConfig.copy(endpoints = updatedEndpoints)
        }
        Timber.d("Updated latency for '$endpointName': ${latencyMs}ms")
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun parseFederationConfig(raw: String): FederationConfig {
        if (raw.isBlank() || raw == "{}") return FederationConfig()
        return try {
            val dto = json.decodeFromString<FederationConfigDto>(raw)
            FederationConfig(
                endpoints = dto.endpoints.map { endpointDto ->
                    BackendEndpoint(
                        name = endpointDto.name,
                        baseUrl = endpointDto.baseUrl,
                        regionTag = endpointDto.regionTag,
                        allowedRoles = endpointDto.allowedRoles,
                        latencyMs = Long.MAX_VALUE // Initial value; overwritten by health check
                    )
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse federation config JSON; falling back to empty config")
            FederationConfig()
        }
    }
}
