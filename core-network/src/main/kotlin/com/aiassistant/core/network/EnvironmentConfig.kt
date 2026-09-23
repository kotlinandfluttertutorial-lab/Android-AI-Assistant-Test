/**
 * EnvironmentConfig.kt — core-network module
 *
 * Purpose: Single source of truth for environment-specific URLs and flags.
 *
 * Architecture: core-network — interface definition.
 *   The concrete implementation [BuildConfigEnvironmentConfig] reads values from the
 *   flavor-specific [BuildConfig] fields set in `core-network/build.gradle.kts`.
 *   The Hilt binding lives in `app/di/EnvironmentModule.kt` so that the *app* module's
 *   BuildConfig is authoritative — the same pattern already used for `isDebugBuild`.
 *
 * Supported environments:
 *   local      → Docker Compose stack on developer machine (http://10.0.2.2:8080/)
 *   stage      → GCP Stage Cloud Run
 *   production → GCP Production Cloud Run
 *
 * Consuming code (Retrofit, WebSocket, observability) MUST inject this interface rather
 * than accessing BuildConfig directly, so environment decisions stay inside the
 * infrastructure boundary and never leak into repositories, ViewModels, or UI.
 *
 * Example injection:
 * ```kotlin
 * class NetworkModule {
 *     @Provides fun provideRetrofit(env: EnvironmentConfig, ...): Retrofit =
 *         Retrofit.Builder().baseUrl(env.apiBaseUrl) ...
 * }
 * ```
 */
package com.aiassistant.core.network

/**
 * Immutable configuration contract for the current deployment environment.
 *
 * Implementations must be stateless — all properties are backed by compile-time
 * BuildConfig constants so they are safe to read from any thread.
 */
interface EnvironmentConfig {

    /**
     * Base URL for all REST API calls.
     *
     * Must end with a trailing slash so Retrofit resolves relative paths correctly.
     *
     * Local:      `http://10.0.2.2:8080/`  (Android Emulator → Docker Nginx)
     * Stage:      `https://api-stage.aiassistant.example.com/`
     * Production: `https://ai-assistant-backend-106071012091.asia-south1.run.app/`
     */
    val apiBaseUrl: String

    /**
     * Base URL for WebSocket connections.
     *
     * Local uses plain `ws://` (no TLS — Docker stack on LAN).
     * Stage/Production use `wss://` (TLS-secured WebSocket).
     * The connect path `/ws/chat/{conversationId}` is appended at call-site.
     *
     * Local:      `ws://10.0.2.2:8080`
     * Stage:      `wss://ws-stage.aiassistant.example.com`
     * Production: `wss://ws.aiassistant.example.com`
     */
    val websocketUrl: String

    /**
     * Human-readable environment name used for logging, observability tags, and the
     * environment indicator UI badge.
     *
     * Values: `"local"` | `"stage"` | `"production"`
     */
    val environmentName: String

    /**
     * `true` only in production builds.
     *
     * Callers should depend on [apiBaseUrl] / [websocketUrl] / [environmentName] rather
     * than branching on this flag. The flag is provided for cases where production-only
     * behaviour must be enabled (e.g. stricter certificate pinning policy).
     */
    val isProduction: Boolean

    /**
     * `true` in local development builds that connect to the Docker Compose stack.
     *
     * When `true` the app connects to `http://10.0.2.2:8080/` (Android Emulator)
     * or the developer machine LAN IP via Nginx.
     * Certificate pinning is always bypassed for local builds.
     */
    val isLocal: Boolean

    /**
     * `true` in stage builds that connect to the GCP Stage environment.
     *
     * Convenience property — equivalent to `!isProduction && !isLocal`.
     */
    val isStage: Boolean
}
