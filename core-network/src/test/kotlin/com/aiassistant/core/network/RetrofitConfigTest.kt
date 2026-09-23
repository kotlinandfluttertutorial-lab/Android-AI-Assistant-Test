/**
 * RetrofitConfigTest.kt — core-network test
 *
 * Purpose: Verifies that the Retrofit instance uses the base URL from
 *          [EnvironmentConfig] for all three environments (local, stage, production).
 *
 * Strategy:
 *   - Build Retrofit directly with a [FakeEnvironmentConfig] and a minimal
 *     [OkHttpClient] — no Hilt, no Android runtime required.
 *   - Use [MockWebServer] where a real URL is needed.
 *   - Separate test cases for local, stage, and production.
 *
 * Requirements: Networking layer must use [EnvironmentConfig.apiBaseUrl] (task 6).
 *
 * NOTE: This file uses JUnit4 (@Test / @Before / @After) which is NOT run by
 * core-network's useJUnitPlatform() / Kotest engine.  It is kept for reference
 * and run manually via the JUnit4 runner if needed. The Kotest-based
 * EnvironmentConfigTest covers the equivalent contract assertions.
 */
package com.aiassistant.core.network

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

// ─── Helper ───────────────────────────────────────────────────────────────────

private fun buildRetrofit(environmentConfig: EnvironmentConfig): Retrofit {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val client = OkHttpClient.Builder().build()
    val contentType = "application/json".toMediaType()
    return Retrofit.Builder()
        .baseUrl(environmentConfig.apiBaseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
}

// ─── Tests ────────────────────────────────────────────────────────────────────

class RetrofitConfigTest : DescribeSpec({

    // ── Local ─────────────────────────────────────────────────────────────────

    describe("Retrofit — local environment") {

        it("baseUrl matches local dev server domain") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api.handsonandroid.com/",
                websocketUrl = "wss://api.handsonandroid.com",
                isProduction = false,
                isLocal      = true
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().host shouldContain "handsonandroid.com"
        }

        it("baseUrl uses https scheme") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api.handsonandroid.com/",
                websocketUrl = "wss://api.handsonandroid.com",
                isProduction = false,
                isLocal      = true
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().scheme shouldBe "https"
        }

        it("baseUrl ends with trailing slash") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api.handsonandroid.com/",
                websocketUrl = "wss://api.handsonandroid.com",
                isProduction = false,
                isLocal      = true
            )
            config.apiBaseUrl shouldEndWith "/"
        }

        it("baseUrl resolves against MockWebServer (functional)") {
            val server = MockWebServer()
            server.start()
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = server.url("/").toString(),
                websocketUrl = "wss://api.handsonandroid.com",
                isProduction = false,
                isLocal      = true
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().toString() shouldBe server.url("/").toString()
            server.shutdown()
        }
    }

    // ── Stage ─────────────────────────────────────────────────────────────────

    describe("Retrofit — stage environment") {

        it("baseUrl matches stage domain") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
                websocketUrl = "wss://ws-stage.aiassistant.example.com",
                isProduction = false,
                isLocal      = false
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().host shouldContain "stage"
        }

        it("baseUrl uses https scheme") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
                websocketUrl = "wss://ws-stage.aiassistant.example.com",
                isProduction = false
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().scheme shouldBe "https"
        }

        it("baseUrl ends with trailing slash") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
                websocketUrl = "wss://ws-stage.aiassistant.example.com",
                isProduction = false
            )
            config.apiBaseUrl shouldEndWith "/"
        }
    }

    // ── Production ────────────────────────────────────────────────────────────

    describe("Retrofit — production environment") {

        it("baseUrl matches GCP Cloud Run host") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
                websocketUrl = "wss://ws.aiassistant.example.com",
                isProduction = true
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().host shouldContain "run.app"
        }

        it("baseUrl uses https scheme") {
            val config = FakeEnvironmentConfig(
                apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
                websocketUrl = "wss://ws.aiassistant.example.com",
                isProduction = true
            )
            val retrofit = buildRetrofit(config)
            retrofit.baseUrl().scheme shouldBe "https"
        }
    }

    // ── Three-way isolation ───────────────────────────────────────────────────

    describe("Retrofit — three-environment isolation") {

        it("local, stage, and production Retrofit instances use different hosts") {
            val local = buildRetrofit(FakeEnvironmentConfig(
                apiBaseUrl = "https://api.handsonandroid.com/",
                websocketUrl = "wss://api.handsonandroid.com",
                isProduction = false, isLocal = true
            ))
            val stage = buildRetrofit(FakeEnvironmentConfig(
                apiBaseUrl = "https://api-stage.aiassistant.example.com/",
                websocketUrl = "wss://ws-stage.aiassistant.example.com", isProduction = false
            ))
            val prod = buildRetrofit(FakeEnvironmentConfig(
                apiBaseUrl = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
                websocketUrl = "wss://ws.aiassistant.example.com", isProduction = true
            ))
            val hosts = setOf(local.baseUrl().host, stage.baseUrl().host, prod.baseUrl().host)
            hosts.size shouldBe 3
        }
    }
})
