/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : AuthViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Auth feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : MVVM ViewModel
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
 * Module     : feature-auth
 * File       : AuthViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Auth feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : MVVM ViewModel
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
 * AuthViewModel.kt
 *
 * Purpose: Manages all authentication UI state and orchestrates use case calls for the
 *          auth flow (login, register, biometric, onboarding, consent).
 * Architecture: feature-auth â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (LoginUseCase, RegisterUseCase), core-security (BiometricAuthManager,
 *               SecureStorage), core-common (DispatcherProvider, ApiResult, DomainError)
 *
 * Requirements: 1.1, 1.6, 1.7, 16.3, 17.1, 28.3
 */
package com.aiassistant.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.domain.usecase.auth.LoginUseCase
import com.aiassistant.domain.usecase.auth.LoginWithGoogleUseCase
import com.aiassistant.domain.usecase.auth.RegisterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the authentication flow.
 *
 * Exposes a [StateFlow] of [AuthUiState] that composables observe. All navigation
 * decisions are driven by state changes observed via [kotlinx.coroutines.flow.collectAsStateWithLifecycle]
 * inside [LaunchedEffect] blocks â€” the ViewModel itself never calls navigation APIs.
 *
 * All blocking work (network calls, storage reads/writes) is dispatched on
 * [DispatcherProvider.io].
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val loginUseCase: LoginUseCase,
    private val registerUseCase: RegisterUseCase,
    private val loginWithGoogleUseCase: LoginWithGoogleUseCase,
    private val secureStorage: SecureStorage,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)

    /** Observable authentication UI state. Never exposes the mutable backing field. */
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Authenticates the user via Google OAuth2.
     *
     * The [idToken] is obtained from the Google Sign-In result and exchanged with the
     * backend, which maps the Google account to a local user record on first sign-in
     * (Requirement 1.6). No biometric data or passwords are involved.
     */
    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = withContext(dispatchers.io) {
                loginWithGoogleUseCase(idToken)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    withContext(dispatchers.io) {
                        secureStorage.saveJwt(result.data.jwt)
                        secureStorage.saveRefreshToken(result.data.refreshToken)
                    }
                    AuthUiState.Authenticated
                }
                is ApiResult.Error -> result.error.toAuthError()
                is ApiResult.NetworkUnavailable -> AuthUiState.Error(
                    message = "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> AuthUiState.Loading
            }
        }
    }

    /**
     * Attempts to log in with the supplied [email] and [password].
     *
     * On success, tokens are persisted to [SecureStorage] and state transitions to
     * [AuthUiState.Authenticated]. On failure, state transitions to [AuthUiState.Error]
     * with field-level details when a [DomainError.ValidationError] is returned.
     */
    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = withContext(dispatchers.io) {
                loginUseCase(email, password)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    withContext(dispatchers.io) {
                        secureStorage.saveJwt(result.data.jwt)
                        secureStorage.saveRefreshToken(result.data.refreshToken)
                    }
                    AuthUiState.Authenticated
                }
                is ApiResult.Error -> result.error.toAuthError()
                is ApiResult.NetworkUnavailable -> AuthUiState.Error(
                    message = "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> AuthUiState.Loading
            }
        }
    }

    /**
     * Attempts to register a new account with [email] and [password].
     *
     * [RegisterUseCase] performs local email + password validation before hitting the
     * network. Field-level errors from [DomainError.ValidationError] are forwarded
     * to [AuthUiState.Error.fieldErrors] for inline display.
     */
    fun register(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val result = withContext(dispatchers.io) {
                registerUseCase(email, password)
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> {
                    withContext(dispatchers.io) {
                        secureStorage.saveJwt(result.data.jwt)
                        secureStorage.saveRefreshToken(result.data.refreshToken)
                    }
                    AuthUiState.Authenticated
                }
                is ApiResult.Error -> result.error.toAuthError()
                is ApiResult.NetworkUnavailable -> AuthUiState.Error(
                    message = "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> AuthUiState.Loading
            }
        }
    }

    /**
     * Signals that the biometric prompt should be shown.
     *
     * The composable layer observes [AuthUiState.BiometricPromptRequired] and launches
     * the system biometric prompt via [BiometricAuthManager].
     */
    fun triggerBiometric() {
        _uiState.value = AuthUiState.BiometricPromptRequired
    }

    /**
     * Called by the composable when biometric authentication succeeds.
     *
     * No biometric data is ever passed here â€” the [BiometricAuthManager] only reports
     * a success/failure outcome (Requirement 1.7).
     */
    fun onBiometricSuccess() {
        _uiState.value = AuthUiState.Authenticated
    }

    /**
     * Called by the composable when biometric authentication fails or is cancelled.
     *
     * @param errorCode Platform-specific error code from [BiometricAuthManager].
     * @param message   Human-readable error message from the biometric framework.
     */
    fun onBiometricError(errorCode: Int, message: String) {
        _uiState.value = AuthUiState.Error(
            message = message,
            fieldErrors = emptyMap()
        )
    }

    /**
     * Determines the correct starting screen for the current session.
     *
     * Decision logic:
     * 1. If onboarding has not been completed â†’ [AuthUiState.OnboardingRequired]
     * 2. If a JWT is already stored â†’ [AuthUiState.Authenticated] (silent re-auth)
     * 3. Otherwise â†’ [AuthUiState.Idle] (show Login)
     */
    fun checkInitialState() {
        viewModelScope.launch {
            val (hasCompletedOnboarding, hasJwt) = withContext(dispatchers.io) {
                val onboarded = secureStorage.isOnboardingComplete()
                val jwt = secureStorage.getJwt() != null
                onboarded to jwt
            }
            _uiState.value = when {
                !hasCompletedOnboarding -> AuthUiState.OnboardingRequired
                hasJwt -> AuthUiState.Authenticated
                else -> AuthUiState.Idle
            }
        }
    }

    /**
     * Marks the onboarding flow as completed and transitions to [AuthUiState.Idle]
     * so the Login screen is shown next.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            withContext(dispatchers.io) {
                secureStorage.saveOnboardingComplete()
            }
            _uiState.value = AuthUiState.Idle
        }
    }

    /**
     * Records that the user has accepted the required consent terms.
     *
     * Transitions to [AuthUiState.Idle] to continue the login/register flow.
     */
    fun acceptConsent() {
        _uiState.value = AuthUiState.Idle
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Maps a [DomainError] to an [AuthUiState.Error] preserving field-level details
     * when available.
     */
    private fun DomainError.toAuthError(): AuthUiState.Error = when (this) {
        is DomainError.ValidationError -> AuthUiState.Error(
            message = message,
            fieldErrors = fields
        )
        is DomainError.Unauthorized -> AuthUiState.Error(
            message = "Invalid email or password. Please try again."
        )
        is DomainError.NetworkError -> AuthUiState.Error(
            message = "A network error occurred. Please try again."
        )
        is DomainError.NetworkUnavailable -> AuthUiState.Error(
            message = "No network connection. Please check your connection and try again."
        )
        else -> AuthUiState.Error(
            message = message
        )
    }
}
