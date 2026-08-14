/**
 * RemoteConfigManager.kt — app module
 *
 * Purpose: Single point of access for Firebase Remote Config values published by the
 *          Admin_Dashboard. Fetches and caches configurable runtime parameters so they
 *          can be applied on the next app launch without an app update (Req 15.8).
 *
 *          Minimum fetch interval:
 *            - Production : 3600 seconds (1 hour) — balances freshness with quota limits.
 *            - Debug      : 0 seconds — allows instant iteration during development.
 *
 *          All value accessors return a sensible hard-coded default so the app remains
 *          functional when Remote Config is unreachable (offline, quota exceeded, etc.).
 *
 * Architecture: app module — analytics/config layer. Injected as a singleton via Hilt.
 *               Feature modules MUST NOT depend on this class directly; route calls
 *               through a domain use case or ViewModel if runtime config must affect
 *               business logic.
 *
 * Requirements: 15.8, 18.7
 */
package com.aiassistant.analytics

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Configurable runtime values published by the Admin_Dashboard via Firebase Remote Config.
 *
 * Each property maps 1-to-1 to a Remote Config key. Change the Admin_Dashboard to update
 * these values; clients apply them on the next app launch (or sooner, on an explicit
 * [RemoteConfigManager.fetchAndActivate] call).
 *
 * @param maxContextTokens       Maximum LLM context window token budget.
 * @param defaultProvider        Default LLM provider identifier.
 * @param ragChunkSize           RAG chunking token size for document ingestion.
 * @param enableMemory           Whether the Memory Service is enabled.
 * @param rateLimitPerMinute     Per-user request rate cap enforced by the backend.
 * @param maintenanceMessage     Non-empty when Admin has published a maintenance notice.
 * @param minAppVersion          Minimum required app version string (semantic, e.g. "1.2.0").
 */
data class RemoteConfigValues(
    val maxContextTokens: Long = DEFAULT_MAX_CONTEXT_TOKENS,
    val defaultProvider: String = DEFAULT_PROVIDER,
    val ragChunkSize: Long = DEFAULT_RAG_CHUNK_SIZE,
    val enableMemory: Boolean = DEFAULT_ENABLE_MEMORY,
    val rateLimitPerMinute: Long = DEFAULT_RATE_LIMIT,
    val maintenanceMessage: String = "",
    val minAppVersion: String = ""
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT_TOKENS = 32_000L
        const val DEFAULT_PROVIDER = "openai_gpt4o"
        const val DEFAULT_RAG_CHUNK_SIZE = 512L
        const val DEFAULT_ENABLE_MEMORY = true
        const val DEFAULT_RATE_LIMIT = 60L
    }
}

/**
 * Wraps [FirebaseRemoteConfig] to fetch, activate, and expose typed config values.
 *
 * Call [fetchAndActivate] once per app launch (or when re-activating the app from
 * background) to pull the latest Admin-published values. After activation, use the
 * property accessors to read typed values without additional async calls.
 *
 * @param remoteConfig Pre-configured [FirebaseRemoteConfig] instance injected by Hilt.
 *                     The minimum fetch interval is set by [com.aiassistant.di.FirebaseModule.provideFirebaseRemoteConfig].
 */
@Singleton
class RemoteConfigManager @Inject constructor(private val remoteConfig: FirebaseRemoteConfig) {

    /**
     * Fetches the latest Remote Config values from the Firebase backend and activates them.
     *
     * This is a suspending call that completes once fetch + activate are both done.
     * Activation makes newly fetched values visible to the in-process [remoteConfig]
     * accessor methods without requiring an app restart.
     *
     * Failure is non-fatal: on any exception the currently cached values (from the last
     * successful fetch, or the built-in defaults) continue to be used. A warning is
     * logged via Timber but the coroutine does not throw.
     *
     * @return `true` if fetch + activate succeeded and new values were applied,
     *         `false` when the call failed or no new values were available.
     */
    suspend fun fetchAndActivate(): Boolean = try {
        val activatedNewValues = remoteConfig.fetchAndActivate().await()
        Timber.d("RemoteConfigManager: fetchAndActivate() → newValues=$activatedNewValues")
        activatedNewValues
    } catch (e: Exception) {
        Timber.w(e, "RemoteConfigManager: fetchAndActivate() failed — using cached/default values.")
        false
    }

    /**
     * Returns a snapshot of all known [RemoteConfigValues] using the currently activated
     * Remote Config parameters.
     *
     * Each field falls back to the hard-coded default in [RemoteConfigValues] when the
     * corresponding key is absent, empty, or has never been fetched.
     */
    fun getValues(): RemoteConfigValues = RemoteConfigValues(
        maxContextTokens = remoteConfig.getLong(KEY_MAX_CONTEXT_TOKENS)
            .takeIf { it > 0 } ?: RemoteConfigValues.DEFAULT_MAX_CONTEXT_TOKENS,
        defaultProvider = remoteConfig.getString(KEY_DEFAULT_PROVIDER)
            .ifBlank { RemoteConfigValues.DEFAULT_PROVIDER },
        ragChunkSize = remoteConfig.getLong(KEY_RAG_CHUNK_SIZE)
            .takeIf { it > 0 } ?: RemoteConfigValues.DEFAULT_RAG_CHUNK_SIZE,
        enableMemory = remoteConfig.getBoolean(KEY_ENABLE_MEMORY),
        rateLimitPerMinute = remoteConfig.getLong(KEY_RATE_LIMIT_PER_MINUTE)
            .takeIf { it > 0 } ?: RemoteConfigValues.DEFAULT_RATE_LIMIT,
        maintenanceMessage = remoteConfig.getString(KEY_MAINTENANCE_MESSAGE),
        minAppVersion = remoteConfig.getString(KEY_MIN_APP_VERSION)
    )

    // ─── Individual typed accessors ──────────────────────────────────────────

    /** Maximum LLM context window token budget as configured by the Admin_Dashboard. */
    val maxContextTokens: Long get() = getValues().maxContextTokens

    /** Default LLM provider identifier. */
    val defaultProvider: String get() = getValues().defaultProvider

    /** RAG chunking token size. */
    val ragChunkSize: Long get() = getValues().ragChunkSize

    /** Whether the Memory Service is enabled platform-wide. */
    val enableMemory: Boolean get() = getValues().enableMemory

    /** Per-user request rate cap per minute. */
    val rateLimitPerMinute: Long get() = getValues().rateLimitPerMinute

    /** Non-empty when the Admin has published a maintenance notice. */
    val maintenanceMessage: String get() = getValues().maintenanceMessage

    /** Minimum required semantic version string (e.g. "1.2.0"). */
    val minAppVersion: String get() = getValues().minAppVersion

    companion object {
        // Firebase Remote Config parameter keys — must match Admin_Dashboard key names.
        const val KEY_MAX_CONTEXT_TOKENS = "feature_max_context_tokens"
        const val KEY_DEFAULT_PROVIDER = "feature_default_provider"
        const val KEY_RAG_CHUNK_SIZE = "feature_rag_chunk_size"
        const val KEY_ENABLE_MEMORY = "feature_enable_memory"
        const val KEY_RATE_LIMIT_PER_MINUTE = "feature_rate_limit_per_minute"
        const val KEY_MAINTENANCE_MESSAGE = "app_maintenance_message"
        const val KEY_MIN_APP_VERSION = "app_min_version"
    }
}
