/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-common
 * File       : DomainError.kt
 * Purpose    : DomainError — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
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
 * Module     : core-common
 * File       : DomainError.kt
 * Purpose    : DomainError — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
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
 * DomainError.kt
 *
 * Purpose: Exhaustive sealed hierarchy of all domain-level error conditions.
 * Architecture: core-common â€” shared infrastructure, no Android/framework dependencies.
 * Dependencies: None (pure Kotlin)
 *
 * Design decisions:
 * - Sealed class allows exhaustive `when` expressions everywhere errors are handled.
 * - Each subclass carries only the data relevant to its failure mode.
 * - `cause: Throwable?` is optional so callers are never forced to construct fake exceptions.
 * - String `message` overrides provide human-readable defaults without depending on Android
 *   string resources â€” localisation happens in the UI layer.
 */

package com.aiassistant.core.common

/**
 * Sealed hierarchy representing every distinct error that can propagate across domain
 * and data module boundaries.
 *
 * All [ApiResult.Error] values carry a [DomainError] payload, ensuring that error
 * handling is always exhaustive and typed.
 */
sealed class DomainError(override val message: String, override val cause: Throwable? = null) :
    Exception(message, cause) {

    // â”€â”€â”€ Network â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * A network call failed due to an I/O or protocol error (e.g. connection refused,
     * DNS failure, timeout, SSL handshake failure).
     *
     * @param message Human-readable description of the failure.
     * @param cause   Original exception from the HTTP client or socket layer.
     */
    data class NetworkError(
        override val message: String = "A network error occurred.",
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    /**
     * The device has no active network interface. No network call was attempted.
     *
     * Distinct from [NetworkError] so the UI can show an offline banner rather than
     * a generic error message.
     */
    data class NetworkUnavailable(
        override val message: String = "No network connection available.",
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    // â”€â”€â”€ HTTP / Auth â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * The server returned HTTP 401 â€” the request lacked valid authentication credentials.
     *
     * After receiving this error the auth module should clear stored tokens and redirect
     * the user to the login screen.
     */
    data class Unauthorized(
        override val message: String = "Authentication required. Please log in again.",
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    /**
     * The server returned HTTP 403 â€” the authenticated user lacks the required permission.
     *
     * The session is valid; the user simply does not have the RBAC role needed for the
     * requested resource.
     */
    data class Forbidden(
        override val message: String = "You do not have permission to perform this action.",
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    /**
     * Client-supplied input failed validation (HTTP 400 / 422 or local pre-validation).
     *
     * @param fields Optional map of field name â†’ validation message for inline form errors.
     */
    data class ValidationError(
        override val message: String = "The provided input is invalid.",
        val fields: Map<String, String> = emptyMap(),
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    /**
     * The server returned an unexpected 5xx status or an unstructured error body.
     *
     * @param httpStatusCode HTTP status code when available (null for non-HTTP failures).
     */
    data class ServerError(
        override val message: String = "A server error occurred. Please try again later.",
        val httpStatusCode: Int? = null,
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    // â”€â”€â”€ Streaming â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * A WebSocket streaming session was interrupted before the [StreamEvent.Done] frame
     * was received.
     *
     * The last successfully received token position is optionally included so the UI can
     * display a "Resume from token N" option (Requirement 2.8).
     *
     * @param lastTokenIndex Zero-based index of the last token successfully received,
     *                       or null if no tokens were received before interruption.
     */
    data class StreamingInterrupted(
        override val message: String = "The streaming response was interrupted.",
        val lastTokenIndex: Int? = null,
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    // â”€â”€â”€ Security / Biometrics â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Biometric authentication failed or was cancelled by the user.
     *
     * No biometric data is attached â€” by design the biometric result only produces a
     * success/failure outcome.
     *
     * @param errorCode Platform-specific biometric error code for diagnostics.
     */
    data class BiometricFailed(
        override val message: String = "Biometric authentication failed.",
        val errorCode: Int? = null,
        override val cause: Throwable? = null
    ) : DomainError(message, cause)

    // â”€â”€â”€ Offline Queue â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * The offline message queue has reached its capacity limit and cannot accept any
     * further queued items until the device reconnects and the queue is flushed.
     *
     * @param queueLimit The maximum number of items the queue can hold.
     */
    data class OfflineQueueFull(
        override val message: String = "The offline queue is full. Connect to the internet to send queued messages.",
        val queueLimit: Int? = null,
        override val cause: Throwable? = null
    ) : DomainError(message, cause)
}
