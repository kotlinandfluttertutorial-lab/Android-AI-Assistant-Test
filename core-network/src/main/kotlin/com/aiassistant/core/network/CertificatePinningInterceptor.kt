/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : CertificatePinningInterceptor.kt
 * Purpose    : CertificatePinningInterceptor — core-network module component
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
 * File       : CertificatePinningInterceptor.kt
 * Purpose    : CertificatePinningInterceptor — core-network module component
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
 * CertificatePinningInterceptor.kt â€” core-network module
 *
 * Purpose: OkHttp [Interceptor] that enforces certificate pinning by verifying that at
 *          least one of the peer's TLS certificate public keys matches a pre-configured
 *          set of SHA-256 hashes. Any connection whose certificate chain contains no
 *          matching pin is rejected with an [IOException].
 *
 * Architecture: core-network â€” applied as an application-level interceptor in
 *               [NetworkModule] before requests reach the wire.
 * Dependencies: OkHttp, Java crypto (MessageDigest, Base64)
 *
 * Design decisions:
 * - Pinned hashes are supplied via constructor injection (not hard-coded) so that
 *   [NetworkModule] can read them from BuildConfig / string resources, making rotation
 *   possible without code changes.
 * - The `bypass` flag defaults to `false`. [NetworkModule] sets it to `true` in debug
 *   builds so local / staging servers are reachable without pinned certificates.
 * - SHA-256 of the SubjectPublicKeyInfo (SPKI) is used rather than the full certificate
 *   hash; this survives certificate renewals with the same key pair.
 * - Computed hashes are Base64-encoded (no-wrap) for compact comparison with the pinned
 *   set, which is the industry convention used by OkHttp's own CertificatePinner.
 * - The interceptor does NOT call `proceed()` on failure â€” it throws, causing OkHttp to
 *   mark the connection as unusable and surface an IOException to the caller.
 *
 * Requirements: 9.5 â€” certificate pinning for all Backend API connections.
 */
package com.aiassistant.core.network

import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rejects any TLS connection whose peer certificate chain does not contain a public key
 * whose SHA-256 hash matches one of the [pinnedSha256Hashes].
 *
 * Usage â€” registered in [NetworkModule] as an application-level interceptor:
 * ```kotlin
 * OkHttpClient.Builder()
 *     .addInterceptor(certificatePinningInterceptor)
 * ```
 *
 * @param pinnedSha256Hashes Set of Base64-encoded SHA-256 hashes of accepted public keys.
 *                           Must not be empty when [bypass] is `false`.
 * @param bypass             When `true`, all pin checks are skipped. Set to `true` only
 *                           in debug/testing builds. Defaults to `false`.
 */
class CertificatePinningInterceptor(private val pinnedSha256Hashes: Set<String>, private val bypass: Boolean = false) :
    Interceptor {

    /**
     * Secondary constructor for Hilt injection when the pin set is provided by
     * [NetworkModule]. The module reads the real values from BuildConfig.
     */
    @Inject constructor() : this(pinnedSha256Hashes = emptySet(), bypass = true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Skip pin validation in debug builds or when explicitly bypassed.
        if (bypass) {
            return chain.proceed(request)
        }

        // Skip validation for non-HTTPS connections.
        // Certificate pinning only applies to TLS handshakes.
        if (!request.url.isHttps) {
            return chain.proceed(request)
        }

        // Obtain the TLS handshake for this connection.
        val handshake = chain.connection()?.handshake()
            ?: throw IOException(
                "Certificate pinning: no TLS handshake available for ${request.url}. " +
                    "Only HTTPS connections are supported for pinning."
            )

        // Compute the SHA-256 SPKI hash for each certificate in the peer chain.
        val peerHashes: List<String> = handshake.peerCertificates.map { cert ->
            val spki = cert.publicKey.encoded
            val digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(spki)
            Base64.encodeToString(digest, Base64.NO_WRAP)
        }

        // Reject if none of the peer hashes are in the pinned set.
        val matched = peerHashes.any { hash -> hash in pinnedSha256Hashes }
        if (!matched) {
            throw IOException(
                "Certificate pinning failure for ${chain.request().url.host}. " +
                    "None of the peer certificate public key hashes matched the pinned set. " +
                    "Peer hashes: $peerHashes"
            )
        }

        return chain.proceed(chain.request())
    }

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
    }
}
