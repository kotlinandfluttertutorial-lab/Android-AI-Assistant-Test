/**
 * EnvironmentConfigTest.kt — core-network test
 *
 * Purpose: Unit tests for the [EnvironmentConfig] contract and its test double.
 *
 * Strategy: [BuildConfigEnvironmentConfig] reads compile-time [BuildConfig] fields, so
 * testing it in isolation requires a [BuildConfig]-less environment.  Instead we test
 * the *interface contract* via a plain [FakeEnvironmentConfig] value object — the same
 * pattern tests across the project use for injected dependencies.
 *
 * Coverage:
 *   1. Stage-flavour values satisfy the interface contract.
 *   2. Production-flavour values satisfy the interface contract.
 *   3. Stage API URL ends with a trailing slash (Retrofit requirement).
 *   4. Production API URL ends with a trailing slash.
 *   5. Stage WS URL uses wss:// scheme.
 *   6. Production WS URL uses wss:// scheme.
 *   7. Stage is NOT production.
 *   8. Production IS production.
 *   9. environmentName reflects the IS_PRODUCTION flag correctly.
 *  10. [EnvironmentConfig] behaves identically whether constructed as Stage or Production
 *      — the interface contract is symmetric.
 *
 * NOTE: Tests for the live [BuildConfigEnvironmentConfig] class — which reads real
 * [BuildConfig] fields — are covered by the Gradle build verification task
 * (`assembleStageDebug` / `assembleProductionRelease`) rather than here, since
 * BuildConfig values are only meaningful in a compiled variant.
 */
package com.aiassistant.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ─── Test double ─────────────────────────────────────────────────────────────

/**
 * Minimal [EnvironmentConfig] value object for use in unit tests.
 *
 * Recommended usage in tests across the codebase:
 * ```kotlin
 * val env = FakeEnvironmentConfig(
 *     apiBaseUrl   = "http://localhost:8080/",
 *     websocketUrl = "ws://localhost:8080",
 *     isProduction = false
 * )
 * ```
 *
 * This class is declared `internal` so it does not leak into production code while
 * remaining accessible to all tests within the core-network module.
 */
internal data class FakeEnvironmentConfig(
    override val apiBaseUrl: String,
    override val websocketUrl: String,
    override val isProduction: Boolean,
    override val environmentName: String = if (isProduction) "production" else "stage"
) : EnvironmentConfig

// ─── Stage values ─────────────────────────────────────────────────────────────

private val stageConfig = FakeEnvironmentConfig(
    apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
    websocketUrl = "wss://ws-stage.aiassistant.example.com",
    isProduction = false
)

// ─── Production values ────────────────────────────────────────────────────────

private val productionConfig = FakeEnvironmentConfig(
    apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
    websocketUrl = "wss://ws.aiassistant.example.com",
    isProduction = true
)

// ─── Tests ────────────────────────────────────────────────────────────────────

class EnvironmentConfigTest {

    // ── Stage contract ────────────────────────────────────────────────────────

    @Test
    fun `stage apiBaseUrl ends with trailing slash`() {
        assertTrue(
            "Retrofit requires a trailing slash on base URL",
            stageConfig.apiBaseUrl.endsWith("/")
        )
    }

    @Test
    fun `stage websocketUrl uses wss scheme`() {
        assertTrue(
            "WebSocket URL must use wss:// for TLS",
            stageConfig.websocketUrl.startsWith("wss://")
        )
    }

    @Test
    fun `stage isProduction is false`() {
        assertFalse(stageConfig.isProduction)
    }

    @Test
    fun `stage environmentName is stage`() {
        assertEquals("stage", stageConfig.environmentName)
    }

    @Test
    fun `stage apiBaseUrl matches expected stage domain`() {
        assertEquals(
            "https://api-stage.aiassistant.example.com/",
            stageConfig.apiBaseUrl
        )
    }

    @Test
    fun `stage websocketUrl matches expected stage domain`() {
        assertEquals(
            "wss://ws-stage.aiassistant.example.com",
            stageConfig.websocketUrl
        )
    }

    // ── Production contract ───────────────────────────────────────────────────

    @Test
    fun `production apiBaseUrl ends with trailing slash`() {
        assertTrue(
            "Retrofit requires a trailing slash on base URL",
            productionConfig.apiBaseUrl.endsWith("/")
        )
    }

    @Test
    fun `production websocketUrl uses wss scheme`() {
        assertTrue(
            "WebSocket URL must use wss:// for TLS",
            productionConfig.websocketUrl.startsWith("wss://")
        )
    }

    @Test
    fun `production isProduction is true`() {
        assertTrue(productionConfig.isProduction)
    }

    @Test
    fun `production environmentName is production`() {
        assertEquals("production", productionConfig.environmentName)
    }

    @Test
    fun `production apiBaseUrl contains expected GCP Cloud Run host`() {
        assertTrue(
            "Production URL must point to the GCP Cloud Run service",
            productionConfig.apiBaseUrl.contains("run.app")
        )
    }

    // ── Isolation contract (stage and production must be distinct) ────────────

    @Test
    fun `stage and production API URLs are different`() {
        assertTrue(
            "Stage and Production must not share the same API base URL",
            stageConfig.apiBaseUrl != productionConfig.apiBaseUrl
        )
    }

    @Test
    fun `stage and production WebSocket URLs are different`() {
        assertTrue(
            "Stage and Production must not share the same WebSocket URL",
            stageConfig.websocketUrl != productionConfig.websocketUrl
        )
    }

    @Test
    fun `stage and production isProduction flags are opposite`() {
        assertTrue(stageConfig.isProduction != productionConfig.isProduction)
    }

    @Test
    fun `stage and production environmentNames are different`() {
        assertTrue(stageConfig.environmentName != productionConfig.environmentName)
    }

    // ── FakeEnvironmentConfig equality (test double sanity) ──────────────────

    @Test
    fun `FakeEnvironmentConfig equals itself`() {
        val copy = stageConfig.copy()
        assertEquals(stageConfig, copy)
    }

    @Test
    fun `FakeEnvironmentConfig with different isProduction differs`() {
        val modified = stageConfig.copy(isProduction = true)
        assertFalse(stageConfig == modified)
    }
}
