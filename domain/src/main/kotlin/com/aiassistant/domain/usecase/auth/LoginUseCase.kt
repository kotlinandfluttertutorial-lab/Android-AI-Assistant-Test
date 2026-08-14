/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : LoginUseCase.kt
 * Purpose    : Encapsulates the 'Login' business operation
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : LoginUseCase.kt
 * Purpose    : Encapsulates the 'Login' business operation
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
 * LoginUseCase.kt
 *
 * Purpose: Authenticates a user with email and password by delegating to AuthRepository.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (AuthRepository), domain model (AuthTokens)
 *
 * Requirements: 1.2
 *
 * Design decisions:
 * - No local validation is performed here; the backend enforces credential correctness.
 * - Single public `invoke` operator keeps the use case surface area minimal and consistent
 *   with the project-wide use case pattern.
 */

package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * Use case for authenticating a user with their email and password.
 *
 * WHEN a User submits valid credentials, THE Auth_Service SHALL issue a signed JWT with a
 * 15-minute expiry and a refresh token with a 30-day expiry (Requirement 1.2).
 *
 * @param authRepository Repository providing the login network call.
 */
class LoginUseCase @Inject constructor(private val authRepository: AuthRepository) {

    /**
     * Executes the login operation.
     *
     * @param email    The user's email address.
     * @param password The user's password.
     * @return [ApiResult.Success] with [AuthTokens] when the backend accepts the credentials,
     *         [ApiResult.Error] on invalid credentials or server failure,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(email: String, password: String): ApiResult<AuthTokens> =
        authRepository.login(email, password)
}
