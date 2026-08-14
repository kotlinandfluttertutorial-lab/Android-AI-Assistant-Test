package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * LoginWithGoogleUseCase.kt
 *
 * Purpose: Authenticates a user via Google OAuth2 by exchanging a Google ID token with the
 *          backend. The backend maps the Google account to a local user record on first
 *          sign-in (Requirement 1.6).
 *
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain repository (AuthRepository), domain model (AuthTokens)
 *
 * Requirements: 1.6
 */
class LoginWithGoogleUseCase @Inject constructor(private val authRepository: AuthRepository) {

    /**
     * Executes the Google sign-in operation.
     *
     * @param idToken The Google ID token obtained from the Google Sign-In client.
     * @return [ApiResult.Success] with [AuthTokens] when the backend accepts the token,
     *         [ApiResult.Error] on failure,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(idToken: String): ApiResult<AuthTokens> = authRepository.loginWithGoogle(idToken)
}
