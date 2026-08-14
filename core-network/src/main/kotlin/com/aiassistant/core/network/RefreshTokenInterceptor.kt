/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : RefreshTokenInterceptor.kt
 * Purpose    : RefreshTokenInterceptor — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : OkHttp Interceptor
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
 * File       : RefreshTokenInterceptor.kt
 * Purpose    : RefreshTokenInterceptor — core-network module component
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : OkHttp Interceptor
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
 * RefreshTokenInterceptor.kt â€” core-network module
 *
 * Purpose: OkHttp [Authenticator] that silently refreshes the JWT when the server returns
 *          HTTP 401. On success the original request is retried with the new token; on
 *          failure credentials are cleared and a forced-logout event is emitted via
 *          [LogoutEventBus] so the UI can navigate to the Login screen.
 *
 * Architecture: core-network â€” registered as the [okhttp3.OkHttpClient] authenticator in
 *               [NetworkModule]. Must NOT import any Android navigation or ViewModel types.
 * Dependencies: OkHttp, kotlinx.coroutines (Mutex), core-security (SecureStorage),
 *               [AuthRefreshApi], [LogoutEventBus]
 *
 * Design decisions:
 * - Implements [okhttp3.Authenticator] rather than [Interceptor] so OkHttp invokes it
 *   only on 401 responses, not on every request.
 * - A [kotlinx.coroutines.sync.Mutex] prevents concurrent refresh storms: only the first
 *   coroutine attempts the refresh while others wait, then re-check the stored JWT to
 *   avoid redundant calls.
 * - `runBlocking` bridges the suspend call to the blocking OkHttp thread. This is the
 *   established pattern for OkHttp Authenticators in coroutine-based Android projects.
 * - Returns `null` (OkHttp convention for "give up") on any refresh failure so that the
 *   original 401 response propagates to the caller rather than looping indefinitely.
 * - Does NOT navigate directly â€” that would require an Android context and fragment
 *   back-stack, creating an illegal dependency direction. Navigation is triggered by
 *   observers of [LogoutEventBus.logoutEvents].
 *
 * Requirements: 1.3 â€” JWT refresh without re-entering credentials;
 *               on refresh failure navigate to Login.
 */
package com.aiassistant.core.network

import com.aiassistant.core.security.SecureStorage
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] that handles HTTP 401 by silently refreshing the stored JWT.
 *
 * ### Happy path
 * 1. OkHttp receives a 401 response and delegates to this authenticator.
 * 2. The [Mutex] is acquired so only one thread attempts the refresh.
 * 3. `POST /auth/refresh` is called with the stored refresh token.
 * 4. New tokens are persisted via [SecureStorage].
 * 5. The original request is retried with the new JWT in the `Authorization` header.
 *
 * ### Failure path
 * 1. The refresh call returns a non-2xx status or throws.
 * 2. All credentials are cleared via [SecureStorage.clearAll].
 * 3. A forced-logout signal is emitted on [LogoutEventBus].
 * 4. `null` is returned â€” OkHttp propagates the original 401 to the caller.
 *
 * ### Concurrent request handling
 * While one thread holds the [Mutex] and is performing the refresh, any other thread that
 * also received a 401 waits. When they are finally unblocked they check whether the stored
 * JWT has changed; if it has they retry with the already-refreshed token instead of making
 * another refresh call.
 */
@Singleton
class RefreshTokenInterceptor @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRefreshApi: Lazy<AuthRefreshApi>,
    private val logoutEventBus: LogoutEventBus
) : Authenticator {

    /**
     * Guards the refresh call so only one thread attempts the network request at a time.
     * All other waiting threads re-use the result stored in [SecureStorage].
     */
    private val refreshMutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only attempt to refresh on a 401 response.
        if (response.code != HTTP_UNAUTHORIZED) return null

        return runBlocking {
            refreshMutex.withLock {
                // Another thread may have already refreshed while we waited for the lock.
                // Check whether the JWT stored now differs from the one that caused the 401.
                val staleToken = response.request.header(HEADER_AUTHORIZATION)
                    ?.removePrefix(PREFIX_BEARER)
                    ?.trim()
                val currentToken = secureStorage.getJwt()

                if (currentToken != null && currentToken != staleToken) {
                    // A fresh token is already available â€” just retry with it.
                    return@withLock response.request.newBuilder()
                        .header(HEADER_AUTHORIZATION, "$PREFIX_BEARER $currentToken")
                        .build()
                }

                // Attempt the refresh.
                val refreshToken = secureStorage.getRefreshToken()
                if (refreshToken.isNullOrBlank()) {
                    // Nothing to refresh with â€” force logout.
                    forceLogout()
                    return@withLock null
                }

                try {
                    val refreshResponse = authRefreshApi.get().refresh(RefreshRequest(refreshToken))

                    // Persist the new tokens.
                    secureStorage.saveJwt(refreshResponse.accessToken)
                    secureStorage.saveRefreshToken(refreshResponse.refreshToken)

                    // Retry the original request with the new JWT.
                    response.request.newBuilder()
                        .header(HEADER_AUTHORIZATION, "$PREFIX_BEARER ${refreshResponse.accessToken}")
                        .build()
                } catch (e: Exception) {
                    // Refresh failed for any reason â€” clear credentials and signal logout.
                    forceLogout()
                    null
                }
            }
        }
    }

    /**
     * Clears all locally stored credentials and emits a forced-logout event so that the
     * UI layer can navigate the user to the Login screen.
     */
    private fun forceLogout() {
        secureStorage.clearAll()
        logoutEventBus.tryEmit()
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HEADER_AUTHORIZATION = "Authorization"
        const val PREFIX_BEARER = "Bearer"
    }
}
