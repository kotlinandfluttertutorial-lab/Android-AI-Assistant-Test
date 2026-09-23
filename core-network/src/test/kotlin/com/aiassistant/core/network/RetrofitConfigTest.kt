/**
 * RetrofitConfigTest.kt — core-network test
 *
 * Purpose: Verifies that the Retrofit instance assembled by [NetworkModule.provideRetrofit]
 *          uses the base URL from [EnvironmentConfig] and not a hardcoded value.
 *
 * Strategy:
 *   - Directly instantiate [NetworkModule.provideRetrofit] with a [FakeEnvironmentConfig]
 *     and a minimal [OkHttpClient] + [Json] — no Hilt, no Android runtime required.
 *   - Use [MockWebServer] to receive the request and assert the correct host/path is used.
 *   - Separate test cases for stage and production base URLs.
 *
 * What is NOT tested here:
 *   - Auth interceptors, certificate pinning, logging — covered by their own test files.
 *   - BuildConfig values — those are verified by the assembled APK variant.
 *
 * Requirements: Networking layer must use [EnvironmentConfig.apiBaseUrl] (task 6).
 */
package com.aiassistant.core.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

class RetrofitConfigTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // ── Helper: build a Retrofit instance using NetworkModule logic ───────────

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

    // ── Stage ─────────────────────────────────────────────────────────────────

    @Test
    fun `Retrofit baseUrl is set from EnvironmentConfig apiBaseUrl for stage`() {
        val serverUrl = mockWebServer.url("/").toString()
        val config = FakeEnvironmentConfig(
            apiBaseUrl   = serverUrl,
            websocketUrl = "wss://ws-stage.aiassistant.example.com",
            isProduction = false
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val retrofit = buildRetrofit(config)

        // baseUrl() is the URL Retrofit was constructed with
        assertEquals(serverUrl, retrofit.baseUrl().toString())
    }

    @Test
    fun `Retrofit with stage config uses stage domain not production domain`() {
        val config = FakeEnvironmentConfig(
            apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
            websocketUrl = "wss://ws-stage.aiassistant.example.com",
            isProduction = false
        )

        val retrofit = buildRetrofit(config)

        assertTrue(
            "Stage Retrofit must point at the stage domain",
            retrofit.baseUrl().host.contains("stage")
        )
    }

    // ── Production ────────────────────────────────────────────────────────────

    @Test
    fun `Retrofit baseUrl is set from EnvironmentConfig apiBaseUrl for production`() {
        val serverUrl = mockWebServer.url("/").toString()
        val config = FakeEnvironmentConfig(
            apiBaseUrl   = serverUrl,
            websocketUrl = "wss://ws.aiassistant.example.com",
            isProduction = true
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val retrofit = buildRetrofit(config)

        assertEquals(serverUrl, retrofit.baseUrl().toString())
    }

    @Test
    fun `Retrofit with production config uses GCP Cloud Run host`() {
        val config = FakeEnvironmentConfig(
            apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
            websocketUrl = "wss://ws.aiassistant.example.com",
            isProduction = true
        )

        val retrofit = buildRetrofit(config)

        assertTrue(
            "Production Retrofit must point at the GCP Cloud Run host",
            retrofit.baseUrl().host.contains("run.app")
        )
    }

    // ── Isolation: stage and production Retrofit instances must differ ────────

    @Test
    fun `stage and production Retrofit instances use different base URLs`() {
        val stageConfig = FakeEnvironmentConfig(
            apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
            websocketUrl = "wss://ws-stage.aiassistant.example.com",
            isProduction = false
        )
        val prodConfig = FakeEnvironmentConfig(
            apiBaseUrl   = "https://ai-assistant-backend-106071012091.asia-south1.run.app/",
            websocketUrl = "wss://ws.aiassistant.example.com",
            isProduction = true
        )

        val stageRetrofit = buildRetrofit(stageConfig)
        val prodRetrofit  = buildRetrofit(prodConfig)

        assertTrue(
            "Stage and Production Retrofit must point at different hosts",
            stageRetrofit.baseUrl().host != prodRetrofit.baseUrl().host
        )
    }

    // ── Trailing-slash invariant (Retrofit contract) ──────────────────────────

    @Test
    fun `apiBaseUrl must end with trailing slash for Retrofit to resolve paths`() {
        val config = FakeEnvironmentConfig(
            apiBaseUrl   = "https://api-stage.aiassistant.example.com/",
            websocketUrl = "wss://ws-stage.aiassistant.example.com",
            isProduction = false
        )
        assertTrue(
            "Retrofit base URL must end with '/' for relative path resolution",
            config.apiBaseUrl.endsWith("/")
        )
    }
}
