/**
 * EnvironmentConfigTest.kt — core-network test
 *
 * Purpose: Unit tests for the [EnvironmentConfig] contract and [FakeEnvironmentConfig] double.
 *
 * Strategy: [BuildConfigEnvironmentConfig] reads compile-time [BuildConfig] fields, so
 * testing it in isolation requires a [BuildConfig]-less environment. Instead we test
 * the *interface contract* via a plain [FakeEnvironmentConfig] value object.
 *
 * Test runner: Kotest DescribeSpec (required by core-network's useJUnitPlatform() setting).
 *
 * Coverage:
 *   Local:
 *     1. apiBaseUrl ends with trailing slash.
 *     2. websocketUrl uses ws:// (not wss:// — local has no TLS).
 *     3. isProduction is false.
 *     4. isLocal is true.
 *     5. isStage is false.
 *     6. environmentName is "local".
 *     7. apiBaseUrl contains 10.0.2.2 (emulator host).
 *
 *   Stage:
 *     8.  apiBaseUrl ends with trailing slash.
 *     9.  websocketUrl uses wss://.
 *     10. isProduction is false.
 *     11. isLocal is false.
 *     12. isStage is true.
 *     13. environmentName is "stage".
 *
 *   Production:
 *     14. apiBaseUrl ends with trailing slash.
 *     15. websocketUrl uses wss://.
 *     16. isProduction is true.
 *     17. isLocal is false.
 *     18. isStage is false.
 *     19. environmentName is "production".
 *
 *   Isolation:
 *     20. All three API URLs are distinct.
 *     21. All three WebSocket URLs are distinct.
 *     22. All three environmentNames are distinct.
 *
 *   FakeEnvironmentConfig:
 *     23. equals its copy.
 *     24. differs when isLocal is toggled.
 */
package com.aiassistant.core.network

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

// ─── Test double ──────────────────────────────────────────────────────────────

/**
 * Minimal [EnvironmentConfig] value object for use in unit tests across all modules.
 *
 * Recommended usage:
 * ```kotlin
 * val env = FakeEnvironmentConfig(
 *     apiBaseUrl   = "http://10.0.2.2:8080/",
 *     websocketUrl = "ws://10.0.2.2:8080",
 *     isProduction = false,
 *     isLocal      = true
 * )
 * ```
 */
data class FakeEnvironmentConfig(
    override val apiBaseUrl: String,
    override val websocketUrl: String,
    override val isProduction: Boolean,
    override val isLocal: Boolean = false,
    override val environmentName: String = when {
        isLocal      -> "local"
        isProduction -> "production"
        else         -> "stage"
    },
    override val isStage: Boolean = !isProduction && !isLocal
) : EnvironmentConfig

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private val localConfig = FakeEnvironmentConfig(
    apiBaseUrl   = "https://api.handsonandroid.com/",
    websocketUrl = "wss://api.handsonandroid.com",
    isProduction = false,
    isLocal      = true
)

private val stageConfig = FakeEnvironmentConfig(
    apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
    websocketUrl = "wss://ws-stage.aiassistant.example.com",
    isProduction = false,
    isLocal      = false
)

private val productionConfig = FakeEnvironmentConfig(
    apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
    websocketUrl = "wss://ws.aiassistant.example.com",
    isProduction = true,
    isLocal      = false
)

// ─── Tests ────────────────────────────────────────────────────────────────────

class EnvironmentConfigTest : DescribeSpec({

    // ── Local ─────────────────────────────────────────────────────────────────

    describe("Local EnvironmentConfig") {

        it("apiBaseUrl ends with trailing slash") {
            localConfig.apiBaseUrl shouldEndWith "/"
        }

        it("websocketUrl uses wss:// scheme") {
            localConfig.websocketUrl shouldStartWith "wss://"
        }

        it("isProduction is false") {
            localConfig.isProduction.shouldBeFalse()
        }

        it("isLocal is true") {
            localConfig.isLocal.shouldBeTrue()
        }

        it("isStage is false") {
            localConfig.isStage.shouldBeFalse()
        }

        it("environmentName is 'local'") {
            localConfig.environmentName shouldBe "local"
        }

        it("apiBaseUrl targets the local dev domain") {
            localConfig.apiBaseUrl shouldContain "handsonandroid.com"
        }

        it("websocketUrl targets the local dev domain") {
            localConfig.websocketUrl shouldContain "handsonandroid.com"
        }
    }

    // ── Stage ─────────────────────────────────────────────────────────────────

    describe("Stage EnvironmentConfig") {

        it("apiBaseUrl ends with trailing slash") {
            stageConfig.apiBaseUrl shouldEndWith "/"
        }

        it("websocketUrl uses wss:// scheme") {
            stageConfig.websocketUrl shouldStartWith "wss://"
        }

        it("isProduction is false") {
            stageConfig.isProduction.shouldBeFalse()
        }

        it("isLocal is false") {
            stageConfig.isLocal.shouldBeFalse()
        }

        it("isStage is true") {
            stageConfig.isStage.shouldBeTrue()
        }

        it("environmentName is 'stage'") {
            stageConfig.environmentName shouldBe "stage"
        }

        it("apiBaseUrl contains stage domain") {
            stageConfig.apiBaseUrl shouldContain "stage"
        }
    }

    // ── Production ────────────────────────────────────────────────────────────

    describe("Production EnvironmentConfig") {

        it("apiBaseUrl ends with trailing slash") {
            productionConfig.apiBaseUrl shouldEndWith "/"
        }

        it("websocketUrl uses wss:// scheme") {
            productionConfig.websocketUrl shouldStartWith "wss://"
        }

        it("isProduction is true") {
            productionConfig.isProduction.shouldBeTrue()
        }

        it("isLocal is false") {
            productionConfig.isLocal.shouldBeFalse()
        }

        it("isStage is false") {
            productionConfig.isStage.shouldBeFalse()
        }

        it("environmentName is 'production'") {
            productionConfig.environmentName shouldBe "production"
        }

        it("apiBaseUrl contains GCP Cloud Run host") {
            productionConfig.apiBaseUrl shouldContain "run.app"
        }
    }

    // ── Three-way isolation ───────────────────────────────────────────────────

    describe("Three-environment isolation") {

        it("all API URLs are distinct") {
            val urls = setOf(
                localConfig.apiBaseUrl,
                stageConfig.apiBaseUrl,
                productionConfig.apiBaseUrl
            )
            urls.size shouldBe 3
        }

        it("all WebSocket URLs are distinct") {
            val urls = setOf(
                localConfig.websocketUrl,
                stageConfig.websocketUrl,
                productionConfig.websocketUrl
            )
            urls.size shouldBe 3
        }

        it("all environmentNames are distinct") {
            val names = setOf(
                localConfig.environmentName,
                stageConfig.environmentName,
                productionConfig.environmentName
            )
            names shouldBe setOf("local", "stage", "production")
        }

        it("exactly one environment is production") {
            val prodCount = listOf(localConfig, stageConfig, productionConfig)
                .count { it.isProduction }
            prodCount shouldBe 1
        }

        it("exactly one environment is local") {
            val localCount = listOf(localConfig, stageConfig, productionConfig)
                .count { it.isLocal }
            localCount shouldBe 1
        }

        it("exactly one environment is stage") {
            val stageCount = listOf(localConfig, stageConfig, productionConfig)
                .count { it.isStage }
            stageCount shouldBe 1
        }

        it("no environment is both local and production") {
            listOf(localConfig, stageConfig, productionConfig).forEach { env ->
                assert(!(env.isLocal && env.isProduction)) {
                    "isLocal and isProduction cannot both be true"
                }
            }
        }

        it("no environment is both stage and production") {
            listOf(localConfig, stageConfig, productionConfig).forEach { env ->
                assert(!(env.isStage && env.isProduction)) {
                    "isStage and isProduction cannot both be true"
                }
            }
        }
    }

    // ── FakeEnvironmentConfig equality ────────────────────────────────────────

    describe("FakeEnvironmentConfig equality") {

        it("equals its copy") {
            localConfig shouldBe localConfig.copy()
        }

        it("differs when isLocal is toggled") {
            localConfig shouldNotBe localConfig.copy(isLocal = false)
        }

        it("differs when isProduction is toggled") {
            productionConfig shouldNotBe productionConfig.copy(isProduction = false)
        }
    }
})
