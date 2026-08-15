/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : RegisterUseCase.kt
 * Purpose    : Encapsulates the 'Register' business operation
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
 * RegisterUseCase.kt
 *
 * Purpose: Registers a new user account after validating email format and password length.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DomainError), domain repository (AuthRepository),
 *               domain model (AuthTokens)
 *
 * Requirements: 1.1, 1.2
 *
 * Design decisions:
 * - Email and password validation is performed in the domain layer before any network call.
 *   This ensures the server is never called with structurally invalid inputs, reducing
 *   unnecessary network traffic and keeping validation logic testable in isolation.
 * - A simplified RFC 5322 regex is used: must contain a local part, an '@', and a domain
 *   part with at least one dot. Full RFC 5322 is extremely complex and not necessary for
 *   practical email validation.
 * - Returns ApiResult.Error wrapping DomainError.ValidationError (with field-level detail)
 *   on validation failure so the UI can display inline field errors without further parsing.
 */

package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * Use case for registering a new user account.
 *
 * THE Auth_Service SHALL accept user registration with a valid email address and a password
 * of at least 12 characters (Requirement 1.1).
 *
 * Validates inputs locally before calling [AuthRepository.register]:
 * 1. Email must match a simplified RFC 5322 pattern (local-part @ domain.tld).
 * 2. Password must be at least [MIN_PASSWORD_LENGTH] characters.
 *
 * @param authRepository Repository providing the registration network call.
 */
class RegisterUseCase @Inject constructor(private val authRepository: AuthRepository) {

    /**
     * Executes the registration operation.
     *
     * Validates [email] and [password] first. If either fails, returns
     * [ApiResult.Error] with a [DomainError.ValidationError] containing a field-level
     * map entry describing the problem. The repository is NOT called on invalid input.
     *
     * @param email    The prospective user's email address.
     * @param password The desired password (minimum [MIN_PASSWORD_LENGTH] characters).
     * @return [ApiResult.Success] with [AuthTokens] when the account is created,
     *         [ApiResult.Error] with [DomainError.ValidationError] on invalid input,
     *         [ApiResult.Error] with other [DomainError] subtypes on server/network failures,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity.
     */
    suspend operator fun invoke(email: String, password: String): ApiResult<AuthTokens> {
        // ── 1. Validate email ────────────────────────────────────────────────
        if (!isValidEmail(email)) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Please enter a valid email address.",
                    fields = mapOf(FIELD_EMAIL to "Must be a valid email address (e.g. user@example.com).")
                )
            )
        }

        // ── 2. Validate password length ──────────────────────────────────────
        if (password.length < MIN_PASSWORD_LENGTH) {
            return ApiResult.Error(
                DomainError.ValidationError(
                    message = "Password must be at least $MIN_PASSWORD_LENGTH characters.",
                    fields = mapOf(FIELD_PASSWORD to "Must be at least $MIN_PASSWORD_LENGTH characters.")
                )
            )
        }

        // ── 3. Delegate to repository ────────────────────────────────────────
        return authRepository.register(email, password)
    }

    // ─── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns `true` when [email] conforms to a simplified RFC 5322 pattern:
     * - Non-empty local part
     * - Literal '@' separator
     * - Non-empty domain label(s)
     * - At least one dot in the domain portion
     * - Non-empty top-level domain (at least 2 characters)
     *
     * Examples that pass : `user@example.com`, `first.last@sub.domain.org`
     * Examples that fail  : `@example.com`, `user@`, `user@domain`, `userexample.com`
     */
    private fun isValidEmail(email: String): Boolean = EMAIL_REGEX.matches(email.trim())

    internal companion object {
        /** Minimum number of characters required in a password (Requirement 1.1). */
        const val MIN_PASSWORD_LENGTH = 12

        /** Form field name used in [DomainError.ValidationError.fields] for email errors. */
        const val FIELD_EMAIL = "email"

        /** Form field name used in [DomainError.ValidationError.fields] for password errors. */
        const val FIELD_PASSWORD = "password"

        /**
         * Simplified RFC 5322 email regex.
         *
         * Pattern breakdown:
         * - `[^@\s]+`   â€” one or more non-'@', non-whitespace characters (local part)
         * - `@`         â€” literal '@'
         * - `[^@\s]+`   â€” one or more characters (domain labels, may include dots)
         * - `\.`        â€” requires at least one dot in the domain
         * - `[^@\s]{2,}` â€” TLD of at least 2 characters
         */
        private val EMAIL_REGEX = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]{2,}$""")
    }
}
