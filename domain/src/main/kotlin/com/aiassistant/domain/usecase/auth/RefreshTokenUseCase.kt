/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RefreshTokenUseCase.kt
 * Purpose    : Encapsulates the 'RefreshToken' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * RefreshTokenUseCase.kt
 *
 * Purpose: Exchanges a valid refresh token for a new JWT without requiring the user to
 *          re-enter credentials.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (AuthRepository), domain model (AuthTokens)
 *
 * Requirements: 1.3
 *
 * Design decisions:
 * - No local validation is needed: an empty or malformed refresh token will be rejected by
 *   the Auth_Service with an appropriate error response which maps to ApiResult.Error.
 * - Token rotation (Requirement 1.4) is handled server-side; the use case simply surfaces
 *   the new AuthTokens to the caller so SecureStorage can be updated.
 */

package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * Use case for refreshing an expired JWT using a valid refresh token.
 *
 * WHEN a JWT expires, THE Auth_Service SHALL accept a valid refresh token and issue a new
 * JWT without requiring the User to re-enter credentials (Requirement 1.3).
 *
 * @param authRepository Repository providing the token refresh network call.
 */
class RefreshTokenUseCase @Inject constructor(private val authRepository: AuthRepository) {

    /**
     * Executes the token refresh operation.
     *
     * @param refreshToken The current, unexpired refresh token obtained from a previous
     *                     [LoginUseCase] or [RefreshTokenUseCase] invocation.
     * @return [ApiResult.Success] with a fresh [AuthTokens] pair (new JWT + rotated refresh
     *         token) on success, [ApiResult.Error] when the refresh token is invalid or
     *         expired, [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(refreshToken: String): ApiResult<AuthTokens> = authRepository.refreshToken(refreshToken)
}
