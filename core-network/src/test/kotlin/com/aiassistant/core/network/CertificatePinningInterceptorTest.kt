/**
 * CertificatePinningInterceptorTest.kt — core-network test
 *
 * Unit tests for [CertificatePinningInterceptor] using MockWebServer.
 *
 * Strategy:
 * - When bypass = false and the connection uses plain HTTP (no TLS handshake),
 *   the interceptor throws IOException because no handshake is available. This
 *   verifies the certificate pinning rejection path: any connection that cannot
 *   present a matching pinned certificate is rejected.
 * - When bypass = true, the request passes through to the server regardless of
 *   whether a TLS certificate is present.
 *
 * Requirements: 9.5 — certificate pinning for all Backend API connections.
 */
package com.aiassistant.core.network

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CertificatePinningInterceptorTest {

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

    // ─── Bypass mode (debug / testing) ────────────────────────────────────────

    @Test
    fun `bypass mode passes request through without pin validation`() {
        // Arrange: interceptor with bypass=true and a non-empty pin set
        val interceptor = CertificatePinningInterceptor(
            pinnedSha256Hashes = setOf("some-fake-pin"),
            bypass = true
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        // Act
        val response = client.newCall(
            Request.Builder().url(mockWebServer.url("/api/test")).build()
        ).execute()

        // Assert: request went through — pin check was skipped
        assertEquals(200, response.code)
        response.body?.close()
    }

    @Test
    fun `bypass mode with empty pin set still passes request through`() {
        // Arrange: bypassed interceptor with empty pin set
        val interceptor = CertificatePinningInterceptor(
            pinnedSha256Hashes = emptySet(),
            bypass = true
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act
        val response = client.newCall(
            Request.Builder().url(mockWebServer.url("/health")).build()
        ).execute()

        // Assert
        assertEquals(200, response.code)
        response.body?.close()
    }

    // ─── Pin enforcement mode (non-bypass) ────────────────────────────────────

    @Test
    fun `pinning enforced mode rejects plain HTTP connection with IOException`() {
        // Arrange: interceptor with real pin set and bypass=false
        // Plain HTTP (MockWebServer) has no TLS handshake, so the interceptor
        // will throw IOException — this simulates a pinning rejection.
        val interceptor = CertificatePinningInterceptor(
            pinnedSha256Hashes = setOf("abc123/validPinHash="),
            bypass = false
        )
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act & Assert: IOException is thrown because no TLS handshake exists
        try {
            client.newCall(
                Request.Builder().url(mockWebServer.url("/api/data")).build()
            ).execute()
            fail("Expected IOException to be thrown for missing TLS handshake")
        } catch (e: IOException) {
            // Expected: interceptor rejected because no pinned certificate could be verified
            assertNotNull(e.message)
            assertTrue(
                "Exception message should reference TLS handshake or certificate pinning",
                e.message!!.contains("TLS") ||
                    e.message!!.contains("handshake") ||
                    e.message!!.contains("pinning") ||
                    e.message!!.contains("HTTPS")
            )
        }
    }

    @Test
    fun `default no-arg constructor uses bypass true`() {
        // The @Inject constructor sets bypass=true so Hilt-injected builds
        // can work without pinned certificates during test/debug builds.
        val interceptor = CertificatePinningInterceptor()
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val response = client.newCall(
            Request.Builder().url(mockWebServer.url("/test")).build()
        ).execute()

        // Verify default constructor bypasses pin checks
        assertEquals(200, response.code)
        response.body?.close()
    }
}
