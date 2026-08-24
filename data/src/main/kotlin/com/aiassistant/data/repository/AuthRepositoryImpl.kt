/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : AuthRepositoryImpl.kt
 * Purpose    : Implements AuthRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * File       : AuthRepositoryImpl.kt
 * Purpose    : Implements AuthRepository with Room (local) and Retrofit (remote) data sources
 *
 * Architecture Layer : Data
 * Pattern Used       : Repository Implementation (offline-first)
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
 * AuthRepositoryImpl.kt — data module
 *
 * Purpose: Production implementation of [com.aiassistant.domain.repository.AuthRepository].
 *          Orchestrates the local [com.aiassistant.core.security.SecureStorage] data source
 *          (for JWT / refresh token persistence) and the remote [AuthApiService] data source
 *          (for all `/auth/...` network calls).
 *
 * Architecture: data module — repository layer. Bridges domain contracts (AuthRepository)
 *               with infrastructure concerns (Retrofit, EncryptedSharedPreferences).
 *               Domain layer has zero knowledge of this class; it is wired at runtime via
 *               the [com.aiassistant.data.di.AuthDataModule] Hilt binding.
 * Dependencies: AuthApiService (remote), SecureStorage (local), ConnectivityObserver,
 *               DispatcherProvider, DomainError, ApiResult
 *
 * Error-handling strategy:
 * - [retrofit2.HttpException] is mapped to typed [DomainError] subtypes by HTTP status code:
 *     401 → [DomainError.Unauthorized]
 *     403 → [DomainError.Forbidden]
 *     4xx → [DomainError.ValidationError]
 *     5xx → [DomainError.ServerError]
 * - [java.io.IOException] → [DomainError.NetworkError] (covers DNS, timeout, SSL failures)
 * - No connectivity → [ApiResult.NetworkUnavailable] (early return, no network call made)
 *
 * Requirements: 1.1 (registration), 1.2 (JWT + refresh issuance), 1.3 (silent refresh),
 *               1.10 (logout invalidates all session refresh tokens).
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.auth.AuthApiService
import com.aiassistant.data.remote.auth.AuthResponse
import com.aiassistant.data.remote.auth.GoogleSignInRequest
import com.aiassistant.data.remote.auth.LoginRequest
import com.aiassistant.data.remote.auth.RefreshTokenRequest
import com.aiassistant.data.remote.auth.RegisterRequest
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber

/**
 * Concrete implementation of [AuthRepository] that uses a Retrofit-based remote data source
 * and an EncryptedSharedPreferences-backed local data source.
 *
 * All network operations execute on [DispatcherProvider.io] to keep them off the main thread
 * and to allow test doubles to substitute a controlled dispatcher in unit tests.
 *
 * @param authApiService     Retrofit service for `/auth/...` endpoints.
 * @param secureStorage      Encrypted local store for the JWT and refresh token.
 * @param connectivityObserver Synchronous connectivity snapshot used for early-exit checks.
 * @param dispatchers        Injectable dispatcher provider; never references Dispatchers directly.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApiService: AuthApiService,
    private val secureStorage: SecureStorage,
    private val connectivityObserver: ConnectivityObserver,
    private val dispatchers: DispatcherProvider
) : AuthRepository {

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Authenticates the user with email/password credentials (Requirement 1.1, 1.2).
     *
     * Steps:
     * 1. Guard: return [ApiResult.NetworkUnavailable] when offline.
     * 2. POST `/auth/login`; map [AuthResponse] → [AuthTokens].
     * 3. Persist tokens in [SecureStorage].
     * 4. Return [ApiResult.Success] with the new tokens.
     */
    override suspend fun login(email: String, password: String): ApiResult<AuthTokens> = withContext(dispatchers.io) {
        val connected = connectivityObserver.isConnected()
        Timber.d("AUTH_DEBUG login() connected=$connected url=auth/login email=$email")
        if (!connected) return@withContext ApiResult.NetworkUnavailable

        safeApiCall {
            val response = authApiService.login(LoginRequest(email, password))
            persistTokens(response)
            response.toDomain()
        }
    }

    /**
     * Registers a new account and issues an initial token pair (Requirement 1.1, 1.2).
     *
     * Same flow as [login]: guard → network call → persist → return success.
     */
    override suspend fun register(email: String, password: String): ApiResult<AuthTokens> =
        withContext(dispatchers.io) {
            val connected = connectivityObserver.isConnected()
            Timber.d("AUTH_DEBUG register() connected=$connected url=auth/register email=$email")
            if (!connected) return@withContext ApiResult.NetworkUnavailable

            safeApiCall {
                val response = authApiService.register(RegisterRequest(email, password))
                persistTokens(response)
                response.toDomain()
            }
        }

    /**
     * Exchanges the given [refreshToken] for a fresh token pair (Requirement 1.3).
     *
     * On 401/403 the local token store is cleared immediately so the user is forced
     * back to the login screen — there is no valid session to recover.
     *
     * Note: The `/auth/refresh` endpoint is also handled by OkHttp's
     * [com.aiassistant.core.network.RefreshTokenInterceptor] for silent background refresh.
     * This method is the explicit, domain-typed variant called by use cases that need
     * full [AuthTokens] (including expiry timestamps) returned to the caller.
     */
    override suspend fun refreshToken(refreshToken: String): ApiResult<AuthTokens> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

        try {
            val response = authApiService.refresh(
                RefreshTokenRequest(refreshToken)
            )
            persistTokens(response)
            ApiResult.Success(response.toDomain())
        } catch (e: HttpException) {
            val domainError = e.todomainError()
            if (domainError is DomainError.Unauthorized || domainError is DomainError.Forbidden) {
                // Clear local storage — the session is irrecoverably invalid.
                secureStorage.clearAll()
            }
            ApiResult.Error(domainError)
        } catch (e: IOException) {
            ApiResult.Error(DomainError.NetworkError(message = e.message ?: "Network error.", cause = e))
        }
    }

    /**
     * Authenticates the user via Google OAuth2 by exchanging a Google ID token with the
     * backend (Requirement 1.6). The backend maps the Google account to a local user on
     * first sign-in.
     */
    override suspend fun loginWithGoogle(idToken: String): ApiResult<AuthTokens> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable

        safeApiCall {
            val response = authApiService.loginWithGoogle(GoogleSignInRequest(idToken))
            persistTokens(response)
            response.toDomain()
        }
    }

    /**
     * Invalidates all server-side refresh tokens then clears local storage (Requirement 1.10).
     *
     * Best-effort semantics: even if the network call fails (device is offline, or any
     * non-auth error), local tokens are always cleared so the user is logged out locally.
     *
     * Return values:
     * - Device offline              → clear local storage, return [ApiResult.Success].
     * - Remote call succeeds (2xx)  → clear local storage, return [ApiResult.Success].
     * - Remote call returns 401/403 → clear local storage, return [ApiResult.Success]
     *   (session was already invalid; treat as a successful logout).
     * - Remote call fails with other error → clear local storage, return [ApiResult.Error].
     */
    override suspend fun logout(): ApiResult<Unit> = withContext(dispatchers.io) {
        // Offline: clear locally and report success — no pending session to invalidate.
        if (!connectivityObserver.isConnected()) {
            secureStorage.clearAll()
            return@withContext ApiResult.Success(Unit)
        }

        val remoteResult = safeApiCall { authApiService.logout() }

        // Always clear local tokens regardless of the remote outcome.
        secureStorage.clearAll()

        return@withContext when (remoteResult) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> when (remoteResult.error) {
                // Treat auth errors as a successful logout: the session was already gone.
                is DomainError.Unauthorized,
                is DomainError.Forbidden
                -> ApiResult.Success(Unit)
                // Any other error (server, network) surfaces to the caller.
                else -> remoteResult
            }
            // NetworkUnavailable cannot happen here — we already checked above — but the
            // when expression must be exhaustive.
            is ApiResult.NetworkUnavailable -> ApiResult.Success(Unit)
            is ApiResult.Loading -> ApiResult.Success(Unit)
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────
    /**
     * Persists the JWT and refresh token from [response] into [SecureStorage].
     *
     * Called after every successful login, registration, or token refresh.
     */
    private fun persistTokens(response: AuthResponse) {
        secureStorage.saveJwt(response.accessToken)
        secureStorage.saveRefreshToken(response.refreshToken)
    }

    /**
     * Maps [AuthResponse] to the domain model [AuthTokens].
     */
    private fun AuthResponse.toDomain(): AuthTokens = AuthTokens(
        jwt = accessToken,
        refreshToken = refreshToken,
        jwtExpiresAt = accessTokenExpiresAt,
        refreshExpiresAt = refreshTokenExpiresAt
    )

    /**
     * Wraps a suspending [block] that calls a Retrofit endpoint and maps any exception
     * to a typed [ApiResult.Error].
     *
     * Mapping rules:
     * - [HttpException] with status 401 → [DomainError.Unauthorized]
     * - [HttpException] with status 403 → [DomainError.Forbidden]
     * - [HttpException] with 4xx status → [DomainError.ValidationError]
     * - [HttpException] with 5xx status → [DomainError.ServerError]
     * - [IOException]                   → [DomainError.NetworkError]
     *
     * @param block Suspending lambda that performs the Retrofit call and returns [T].
     * @return [ApiResult.Success] wrapping the result, or [ApiResult.Error] on failure.
     */
    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        val result = block()
        Timber.d("AUTH_DEBUG safeApiCall SUCCESS")
        ApiResult.Success(result)
    } catch (e: HttpException) {
        Timber.e(
            "AUTH_DEBUG safeApiCall HttpException code=${e.code()} body=${e.response()?.errorBody()?.string()}"
        )
        ApiResult.Error(e.todomainError())
    } catch (e: IOException) {
        Timber.e(e, "AUTH_DEBUG safeApiCall IOException message=${e.message} cause=${e.cause}")
        ApiResult.Error(
            DomainError.NetworkError(
                message = e.message ?: "A network I/O error occurred.",
                cause = e
            )
        )
    }

    /**
     * Converts a Retrofit [HttpException] to the appropriate [DomainError] subtype based
     * on the HTTP status code.
     */
    /**
     * Changes the user's password by sending the current and new passwords to the backend
     * (Requirement 1.1 — account management).
     *
     * @param currentPassword The user's current password for verification.
     * @param newPassword     The desired new password (≥ 12 characters per domain validation).
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    override suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> =
        withContext(dispatchers.io) {
            if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
            safeApiCall {
                authApiService.changePassword(
                    com.aiassistant.data.remote.auth.ChangePasswordRequest(
                        currentPassword = currentPassword,
                        newPassword = newPassword
                    )
                )
            }
        }

    /**
     * Links a Google account to the currently authenticated user (Requirement 1.6).
     *
     * @param idToken The Google ID token from the Google Sign-In flow.
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    override suspend fun linkGoogleAccount(idToken: String): ApiResult<Unit> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        safeApiCall {
            authApiService.linkGoogleAccount(
                com.aiassistant.data.remote.auth.LinkGoogleRequest(idToken = idToken)
            )
        }
    }

    /**
     * Removes the Google OAuth2 link from the currently authenticated user's account (Requirement 1.6).
     *
     * @return [ApiResult.Success] with [Unit] on success, or an error variant.
     */
    override suspend fun unlinkGoogleAccount(): ApiResult<Unit> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        safeApiCall { authApiService.unlinkGoogleAccount() }
    }

    /**
     * Returns whether the currently authenticated user has a Google account linked (Requirement 1.6).
     *
     * @return [ApiResult.Success] with `true` if a Google link exists.
     */
    override suspend fun isGoogleAccountLinked(): ApiResult<Boolean> = withContext(dispatchers.io) {
        if (!connectivityObserver.isConnected()) return@withContext ApiResult.NetworkUnavailable
        safeApiCall { authApiService.isGoogleAccountLinked().linked }
    }

    private fun HttpException.todomainError(): DomainError = when (code()) {
        401 -> DomainError.Unauthorized(
            message = "Authentication required. Please log in again.",
            cause = this
        )
        403 -> DomainError.Forbidden(
            message = "You do not have permission to perform this action.",
            cause = this
        )
        in 400..499 -> DomainError.ValidationError(
            message = "The request was invalid (HTTP ${code()}).",
            cause = this
        )
        in 500..599 -> DomainError.ServerError(
            message = "A server error occurred (HTTP ${code()}). Please try again later.",
            httpStatusCode = code(),
            cause = this
        )
        else -> DomainError.NetworkError(
            message = "Unexpected HTTP response: ${code()}.",
            cause = this
        )
    }
}
