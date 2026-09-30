/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AgentAuthGuard.kt
 * Purpose    : Validates that every AgentRequest executes under an
 *              authenticated user context — never with a guest/anonymous ID.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Guard / validator (pure function)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - Called by AgentGateway before dispatching any AgentRequest
 *   - Enforces: userId must not be blank or equal to ANONYMOUS_USER_ID
 *   - Does NOT perform token verification (that is SecureStorage's job)
 *   - AgentAuthResult is a sealed class so callers must handle both branches
 *
 * Phase 10 requirement:
 *   "Ensure every agent request executes under authenticated user context.
 *    Never trust client-supplied user IDs."
 * ============================================================
 */

package com.aiassistant.domain.agent

/**
 * The sentinel value used when no authenticated userId is available.
 *
 * Any [AgentRequest] carrying this ID is rejected before reaching the
 * orchestrator, preventing unauthenticated tool calls and data access.
 */
const val ANONYMOUS_USER_ID: String = "anonymous"

/**
 * Result of an [AgentAuthGuard] validation.
 */
sealed class AgentAuthResult {
    /** The request carries a valid authenticated userId. */
    data class Authenticated(val userId: String) : AgentAuthResult()

    /**
     * The request is unauthenticated and must be rejected.
     *
     * @param reason Human-readable reason for logging; never shown to the user.
     */
    data class Unauthenticated(val reason: String) : AgentAuthResult()
}

/**
 * Validates that an [AgentRequest] was submitted by an authenticated user.
 *
 * ## Rules
 * 1. [AgentRequest.userId] must not be blank.
 * 2. [AgentRequest.userId] must not equal [ANONYMOUS_USER_ID].
 * 3. [AgentRequest.userId] must not equal any other known guest sentinel.
 *
 * ## Usage
 * ```kotlin
 * when (val auth = AgentAuthGuard.validate(request)) {
 *     is AgentAuthResult.Authenticated -> proceed(auth.userId)
 *     is AgentAuthResult.Unauthenticated -> emit(AgentEvent.Failed(...))
 * }
 * ```
 */
object AgentAuthGuard {

    /** Known guest/unauthenticated sentinel values that must be rejected. */
    private val GUEST_SENTINELS: Set<String> = setOf(
        ANONYMOUS_USER_ID,
        "guest",
        "unknown",
        "placeholder",
        "__unauthenticated__",   // AgentGateway's internal sentinel for missing context
    )

    /**
     * Validate that [request] carries an authenticated userId.
     *
     * @param request The [AgentRequest] to validate.
     * @return [AgentAuthResult.Authenticated] if valid, or
     *         [AgentAuthResult.Unauthenticated] with the rejection reason.
     */
    fun validate(request: AgentRequest): AgentAuthResult {
        val userId = request.userId.trim()

        if (userId.isBlank()) {
            return AgentAuthResult.Unauthenticated(
                "AgentRequest.userId is blank — request rejected."
            )
        }

        if (userId.lowercase() in GUEST_SENTINELS) {
            return AgentAuthResult.Unauthenticated(
                "AgentRequest.userId='$userId' is a guest sentinel — " +
                    "agent execution requires an authenticated user."
            )
        }

        return AgentAuthResult.Authenticated(userId)
    }

    /**
     * Returns `true` when [userId] is a known guest sentinel or blank.
     *
     * Convenience function for callers that don't need the full [AgentAuthResult].
     */
    fun isAuthenticated(userId: String): Boolean =
        userId.isNotBlank() && userId.lowercase() !in GUEST_SENTINELS
}
