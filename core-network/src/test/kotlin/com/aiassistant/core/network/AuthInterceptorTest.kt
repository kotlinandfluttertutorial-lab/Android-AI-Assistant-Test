/**
 * AuthInterceptorTest.kt — core-network test
 *
 * Unit tests for [AuthInterceptor] using MockWebServer.
 *
 * Verifies:
 * - When a JWT is stored, every request carries `Authorization: Bearer <token>`.
 * - When no JWT is stored, requests are forwarded without an Authorization header.
 *
 * Requirements: 9.5, 1.3
 */
package com.aiassistant.core.network

import com.aiassistant.core.security.SecureStorage
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var secureStorage: SecureStorage

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        secureStorage = mockk()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `attaches Bearer JWT header when token is stored`() {
        // Arrange
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test"
        every { secureStorage.getJwt() } returns jwt

        val interceptor = AuthInterceptor(secureStorage)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act
        val response = client.newCall(
            Request.Builder().url(mockWebServer.url("/api/resource")).build()
        ).execute()

        // Assert: request was served
        assertEquals(200, response.code)
        response.body?.close()

        // Verify the Authorization header was sent
        val recordedRequest = mockWebServer.takeRequest()
        val authHeader = recordedRequest.getHeader("Authorization")
        assertNotNull("Authorization header must be present", authHeader)
        assertEquals("Bearer $jwt", authHeader)
    }

    @Test
    fun `does not attach Authorization header when no token is stored`() {
        // Arrange
        every { secureStorage.getJwt() } returns null

        val interceptor = AuthInterceptor(secureStorage)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act
        client.newCall(
            Request.Builder().url(mockWebServer.url("/auth/login")).build()
        ).execute().body?.close()

        // Assert: no Authorization header sent for unauthenticated requests
        val recordedRequest = mockWebServer.takeRequest()
        assertNull(
            "Authorization header must not be present when no JWT is stored",
            recordedRequest.getHeader("Authorization")
        )
    }

    @Test
    fun `does not attach Authorization header when token is empty string`() {
        // Arrange: empty string is treated the same as null (isNullOrBlank)
        every { secureStorage.getJwt() } returns ""

        val interceptor = AuthInterceptor(secureStorage)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act
        client.newCall(
            Request.Builder().url(mockWebServer.url("/auth/register")).build()
        ).execute().body?.close()

        // Assert
        val recordedRequest = mockWebServer.takeRequest()
        assertNull(
            "Authorization header must not be present when JWT is blank",
            recordedRequest.getHeader("Authorization")
        )
    }

    @Test
    fun `does not attach Authorization header when token is blank whitespace`() {
        // Arrange: whitespace-only token is treated as blank
        every { secureStorage.getJwt() } returns "   "

        val interceptor = AuthInterceptor(secureStorage)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        // Act
        client.newCall(
            Request.Builder().url(mockWebServer.url("/auth/register")).build()
        ).execute().body?.close()

        // Assert
        val recordedRequest = mockWebServer.takeRequest()
        assertNull(
            "Authorization header must not be present when JWT is blank",
            recordedRequest.getHeader("Authorization")
        )
    }

    @Test
    fun `Bearer prefix is correct format`() {
        // Verify that the header uses exactly "Bearer " prefix per RFC 6750
        every { secureStorage.getJwt() } returns "my.jwt.token"

        val interceptor = AuthInterceptor(secureStorage)
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        client.newCall(
            Request.Builder().url(mockWebServer.url("/api/chat")).build()
        ).execute().body?.close()

        val authHeader = mockWebServer.takeRequest().getHeader("Authorization")
        assertNotNull(authHeader)
        assert(authHeader!!.startsWith("Bearer ")) {
            "Authorization header must start with 'Bearer '"
        }
    }
}
