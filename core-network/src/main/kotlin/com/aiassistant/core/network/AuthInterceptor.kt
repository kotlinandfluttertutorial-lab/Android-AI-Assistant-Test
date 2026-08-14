/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : AuthInterceptor.kt
 * Purpose    : AuthInterceptor — core-network module component
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
 * File       : AuthInterceptor.kt
 * Purpose    : AuthInterceptor — core-network module component
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
 * AuthInterceptor.kt â€” core-network module
 *
 * Purpose: OkHttp [Interceptor] that attaches the current JWT as a
 *          `Authorization: Bearer <token>` header on every outgoing request.
 * Architecture: core-network â€” all authenticated requests pass through here
 *               before they reach the server.
 * Dependencies: OkHttp, core-security (SecureStorage)
 *
 * Design decisions:
 * - Reads the token at request time (not at construction time) so a freshly
 *   issued JWT is always used after a token refresh, without rebuilding the
 *   interceptor chain.
 * - Skips adding the header when no token is stored (unauthenticated flows such
 *   as login/register do not need it, and adding a null-derived empty header
 *   would waste bandwidth and could confuse the server).
 * - The interceptor does NOT attempt token refresh itself; that responsibility
 *   belongs exclusively to [RefreshTokenInterceptor] (OkHttp Authenticator).
 *
 * Requirements: 9.5, 1.3
 */
package com.aiassistant.core.network

import com.aiassistant.core.security.SecureStorage
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches a `Bearer` JWT to every outgoing HTTP request.
 *
 * If [SecureStorage] returns `null` (no JWT stored yet, e.g. during login),
 * the request is forwarded as-is without an `Authorization` header.
 *
 * Usage â€” registered in [NetworkModule] as an application-level interceptor:
 * ```kotlin
 * OkHttpClient.Builder()
 *     .addInterceptor(authInterceptor)
 * ```
 */
@Singleton
class AuthInterceptor @Inject constructor(private val secureStorage: SecureStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip adding the Authorization header for auth-related endpoints.
        // These requests are used to obtain or refresh tokens and should not send a JWT.
        val path = originalRequest.url.encodedPath
        if (path.contains("/auth/login") ||
            path.contains("/auth/register") ||
            path.contains("/auth/google") ||
            path.contains("/auth/refresh")
        ) {
            return chain.proceed(originalRequest)
        }

        // Retrieve the current JWT from encrypted storage.
        val jwt = secureStorage.getJwt()

        // If no token is available, forward the request unmodified.
        if (jwt.isNullOrBlank()) {
            return chain.proceed(originalRequest)
        }

        // Attach the Bearer token to the request.
        val authenticatedRequest = originalRequest.newBuilder()
            .header(HEADER_AUTHORIZATION, "$PREFIX_BEARER $jwt")
            .build()

        return chain.proceed(authenticatedRequest)
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val PREFIX_BEARER = "Bearer"
    }
}
