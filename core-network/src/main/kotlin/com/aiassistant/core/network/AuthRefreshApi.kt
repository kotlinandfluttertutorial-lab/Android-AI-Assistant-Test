/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : AuthRefreshApi.kt
 * Purpose    : AuthRefreshApi — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : AuthRefreshApi.kt
 * Purpose    : AuthRefreshApi — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * AuthRefreshApi.kt â€” core-network module
 *
 * Purpose: Minimal Retrofit service interface for the token-refresh endpoint.
 *          Used exclusively by [RefreshTokenInterceptor] to obtain a new JWT when
 *          the current one has expired (HTTP 401).
 *
 * Architecture: core-network â€” internal to the network layer; not exposed to feature
 *               or domain modules.
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Design decisions:
 * - Kept minimal on purpose: only the single endpoint needed for silent refresh is
 *   declared here. All other auth endpoints live in the `data` module's auth API.
 * - Uses `suspend` so the call integrates cleanly with the coroutine scope launched
 *   inside [RefreshTokenInterceptor].
 * - `@Serializable` data classes avoid the need for a separate Gson/Moshi adapter.
 *
 * Requirements: 1.3 â€” JWT refresh without re-entering credentials.
 */
package com.aiassistant.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// â”€â”€â”€ Request / Response models â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Request body sent to `POST /auth/refresh`.
 *
 * @param refreshToken The stored refresh token to exchange for a new JWT.
 */
@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

/**
 * Response body returned by `POST /auth/refresh` on success.
 *
 * @param accessToken  The new signed JWT (15-minute expiry per Requirement 1.2).
 * @param refreshToken The rotated refresh token (30-day expiry, Requirement 1.4).
 */
@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String
)

// â”€â”€â”€ Retrofit service interface â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Retrofit service for the token-refresh endpoint.
 *
 * Instantiated by [NetworkModule] and injected into [RefreshTokenInterceptor].
 *
 * NOTE: This Retrofit call is made from inside an OkHttp [okhttp3.Authenticator], which
 * runs on a background thread managed by OkHttp. The `suspend` function is bridged to
 * a blocking call via `runBlocking` inside the authenticator.
 */
interface AuthRefreshApi {

    /**
     * Exchanges a valid refresh token for a new JWT and a rotated refresh token.
     *
     * @param body [RefreshRequest] containing the current refresh token.
     * @return [RefreshResponse] with the new access and refresh tokens.
     * @throws retrofit2.HttpException when the server returns a non-2xx status.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse
}
