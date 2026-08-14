/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AuthApiService.kt
 * Purpose    : AuthApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * Module     : data
 * File       : AuthApiService.kt
 * Purpose    : AuthApiService — data module component
 *
 * Architecture Layer : Data
 * Pattern Used       : Retrofit API Service Interface
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
 * AuthApiService.kt — data module
 *
 * Purpose: Retrofit service interface for all `/auth/...` REST endpoints consumed by the
 *          data layer. This is distinct from [com.aiassistant.core.network.AuthRefreshApi],
 *          which is internal to core-network's [com.aiassistant.core.network.RefreshTokenInterceptor].
 *
 * Architecture: data module — remote data source layer. Consumed exclusively by
 *               [com.aiassistant.data.repository.AuthRepositoryImpl].
 * Dependencies: Retrofit, kotlinx.serialization
 *
 * Design decisions:
 * - `@Serializable` data classes with `@SerialName` for snake_case ↔ camelCase mapping,
 *   consistent with the kotlinx.serialization converter configured in NetworkModule.
 * - `AuthResponse` captures full token pair including expiry timestamps so the domain
 *   layer can track token lifetime without calling the server again.
 * - Logout returns `Unit` — HTTP 204 No Content is acceptable; Retrofit with a `Unit`
 *   return type treats any 2xx response (including 204) as success.
 * - All functions are `suspend` for clean coroutine integration.
 *
 * Requirements: 1.1 (registration), 1.2 (JWT + refresh token issuance), 1.10 (logout
 *               invalidates all refresh tokens).
 */
package com.aiassistant.data.remote.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// ─── Request models ───────────────────────────────────────────────────────────

/**
 * Request body for `POST /auth/login`.
 *
 * @param email    The user's registered email address.
 * @param password The user's plaintext password (transmitted over TLS; never stored).
 */
@Serializable
data class LoginRequest(@SerialName("email") val email: String, @SerialName("password") val password: String)

/**
 * Request body for `POST /auth/register`.
 *
 * @param email    A valid email address for the new account.
 * @param password A plaintext password of at least 12 characters (Requirement 1.1).
 */
@Serializable
data class RegisterRequest(@SerialName("email") val email: String, @SerialName("password") val password: String)

/**
 * Request body for `POST /auth/refresh`.
 *
 * @param refreshToken The stored refresh token to exchange for a new JWT (Requirement 1.3).
 */
@Serializable
data class RefreshTokenRequest(@SerialName("refresh_token") val refreshToken: String)

/**
 * Request body for `POST /auth/google`.
 *
 * @param idToken The Google ID token obtained from the Google Sign-In flow (Requirement 1.6).
 */
@Serializable
data class GoogleSignInRequest(@SerialName("id_token") val idToken: String)

/**
 * Request body for `POST /auth/change-password`.
 *
 * @param currentPassword The user's current plaintext password for re-authentication.
 * @param newPassword      The desired new plaintext password (minimum 12 characters).
 */
@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String
)

/**
 * Request body for `POST /auth/link-google`.
 *
 * @param idToken The Google ID token obtained from the Google Sign-In flow.
 */
@Serializable
data class LinkGoogleRequest(@SerialName("id_token") val idToken: String)

// ─── Response models ──────────────────────────────────────────────────────────

/**
 * Response body returned by `/auth/login`, `/auth/register`, and `/auth/refresh`.
 *
 * Maps the server's snake_case JSON fields to idiomatic Kotlin camelCase properties.
 *
 * @param accessToken          The signed JWT used to authenticate subsequent API requests.
 *                             Expires after 15 minutes (Requirement 1.2).
 * @param refreshToken         The opaque refresh token used to obtain a new JWT without
 *                             re-entering credentials (Requirement 1.3).
 * @param accessTokenExpiresAt Epoch milliseconds when [accessToken] expires.
 * @param refreshTokenExpiresAt Epoch milliseconds when [refreshToken] expires (30 days,
 *                              Requirement 1.2).
 */
@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("access_token_expires_at") val accessTokenExpiresAt: Long = 0L,
    @SerialName("refresh_token_expires_at") val refreshTokenExpiresAt: Long = 0L
)

/**
 * Response body returned by `GET /auth/google-linked`.
 *
 * @param linked `true` if the authenticated user has a Google account linked.
 */
@Serializable
data class GoogleLinkedResponse(@SerialName("linked") val linked: Boolean)

// ─── Retrofit service interface ───────────────────────────────────────────────

/**
 * Retrofit service for the full set of `/auth/...` endpoints used by the data module.
 *
 * Instantiated by [com.aiassistant.data.di.AuthDataModule] via `Retrofit.create` and
 * injected into [com.aiassistant.data.repository.AuthRepositoryImpl].
 *
 * Note: This service should NOT be used inside OkHttp interceptors or authenticators
 * (which run on OkHttp's thread pool). Use [com.aiassistant.core.network.AuthRefreshApi]
 * for silent token refresh inside [com.aiassistant.core.network.RefreshTokenInterceptor].
 */
interface AuthApiService {

    /**
     * Authenticates a user and returns a fresh token pair.
     *
     * @param body [LoginRequest] with email and password credentials.
     * @return [AuthResponse] containing the new JWT and refresh token.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    /**
     * Registers a new user account and returns an initial token pair.
     *
     * @param body [RegisterRequest] with the new account's email and password.
     * @return [AuthResponse] containing the issued JWT and refresh token.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    /**
     * Exchanges a valid refresh token for a new JWT and rotated refresh token (Requirement 1.3, 1.4).
     *
     * The server invalidates [body.refreshToken] and issues a fresh replacement pair
     * (token rotation). On 401/403 the caller must clear local storage and redirect the user
     * to the login screen.
     *
     * @param body [RefreshTokenRequest] containing the current refresh token.
     * @return [AuthResponse] containing the new token pair with updated expiry timestamps.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshTokenRequest): AuthResponse

    /**
     * Authenticates a user via Google OAuth2 by exchanging a Google ID token.
     *
     * @param body [GoogleSignInRequest] containing the Google ID token.
     * @return [AuthResponse] containing the issued JWT and refresh token.
     */
    @POST("auth/google")
    suspend fun loginWithGoogle(@Body body: GoogleSignInRequest): AuthResponse

    /**
     * Invalidates all active refresh tokens for the current user's session (Requirement 1.10).
     *
     * The server returns HTTP 204 No Content on success. The Bearer JWT attached by
     * [com.aiassistant.core.network.AuthInterceptor] identifies the session to invalidate —
     * no request body is required.
     *
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/logout")
    suspend fun logout(): Unit

    /**
     * Changes the authenticated user's password.
     *
     * @param body [ChangePasswordRequest] with current and new passwords.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Unit

    /**
     * Links a Google account to the currently authenticated user.
     *
     * @param body [LinkGoogleRequest] containing the Google ID token.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/link-google")
    suspend fun linkGoogleAccount(@Body body: LinkGoogleRequest): Unit

    /**
     * Removes the Google OAuth2 link from the currently authenticated user's account.
     *
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @POST("auth/unlink-google")
    suspend fun unlinkGoogleAccount(): Unit

    /**
     * Returns whether the currently authenticated user has a Google account linked.
     *
     * @return [GoogleLinkedResponse] with a boolean `linked` field.
     * @throws retrofit2.HttpException on non-2xx HTTP responses.
     * @throws java.io.IOException on network failures.
     */
    @GET("auth/google-linked")
    suspend fun isGoogleAccountLinked(): GoogleLinkedResponse
}
