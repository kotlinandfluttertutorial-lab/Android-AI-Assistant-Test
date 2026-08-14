/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AuthRepository.kt
 * Purpose    : Domain contract defining data access operations for Auth entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * Module     : domain
 * File       : AuthRepository.kt
 * Purpose    : Domain contract defining data access operations for Auth entities
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * AuthRepository.kt
 *
 * Purpose: Domain-layer repository interface for all authentication operations.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain model (AuthTokens)
 *
 * Requirements: 1.1, 1.2, 1.3
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AuthTokens

/**
 * Contract for authentication operations between the domain and data layers.
 *
 * All functions are suspending to support coroutine-based callers. The data module
 * provides the concrete implementation wired through dependency injection.
 */
interface AuthRepository {

    /**
     * Authenticates a user with email and password credentials.
     *
     * On success returns [ApiResult.Success] carrying a fresh [AuthTokens] pair.
     * Callers should store the tokens via SecureStorage after a successful login.
     *
     * @param email    The user's email address.
     * @param password The user's plaintext password (transmitted over TLS; never stored).
     * @return [ApiResult] wrapping [AuthTokens] on success, or an error variant.
     */
    suspend fun login(email: String, password: String): ApiResult<AuthTokens>

    /**
     * Registers a new user account with the provided credentials.
     *
     * The caller (i.e. [com.aiassistant.domain.usecase.auth.RegisterUseCase]) is
     * responsible for pre-validating [email] and [password] before this method is
     * invoked.
     *
     * @param email    A valid email address for the new account.
     * @param password A plaintext password of at least 12 characters.
     * @return [ApiResult] wrapping [AuthTokens] on success, or an error variant.
     */
    suspend fun register(email: String, password: String): ApiResult<AuthTokens>

    /**
     * Exchanges a valid refresh token for a new JWT without requiring the user to
     * re-enter credentials (Requirement 1.3).
     *
     * The Auth_Service invalidates the supplied [refreshToken] and issues a replacement
     * (token rotation, Requirement 1.4).
     *
     * @param refreshToken The current, unexpired refresh token.
     * @return [ApiResult] wrapping fresh [AuthTokens] on success, or an error variant.
     */
    suspend fun refreshToken(refreshToken: String): ApiResult<AuthTokens>

    /**
     * Authenticates a user via Google OAuth2 by exchanging a Google ID token with the
     * backend, which maps the Google account to a local user record on first sign-in
     * (Requirement 1.6).
     *
     * @param idToken The Google ID token obtained from the Google Sign-In flow.
     * @return [ApiResult] wrapping [AuthTokens] on success, or an error variant.
     */
    suspend fun loginWithGoogle(idToken: String): ApiResult<AuthTokens>

    /**
     * Invalidates all active refresh tokens for the current user's session (Requirement 1.10).
     *
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    suspend fun logout(): ApiResult<Unit>

    /**
     * Changes the authenticated user's password.
     *
     * The caller must supply the current password for re-authentication before the backend
     * accepts the new password.
     *
     * @param currentPassword The user's existing plaintext password.
     * @param newPassword      The new plaintext password (minimum 12 characters; validated by
     *                         the domain layer before calling this method).
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit>

    /**
     * Links a Google account to the currently authenticated user by exchanging a Google
     * ID token with the backend (Requirement 1.6).
     *
     * If the user already has a Google account linked this call is a no-op and returns
     * [ApiResult.Success].
     *
     * @param idToken The Google ID token obtained from the Google Sign-In flow.
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    suspend fun linkGoogleAccount(idToken: String): ApiResult<Unit>

    /**
     * Removes the Google OAuth2 link from the currently authenticated user's account.
     *
     * The user must have a password-based credential in addition to the Google link before
     * unlinking is permitted (enforced by the backend).
     *
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    suspend fun unlinkGoogleAccount(): ApiResult<Unit>

    /**
     * Returns whether the currently authenticated user has a Google account linked.
     *
     * @return [ApiResult.Success] with `true` if a Google link exists.
     */
    suspend fun isGoogleAccountLinked(): ApiResult<Boolean>
}
