/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : AuthTokens.kt
 * Purpose    : AuthTokens — domain module component
 *
 * Architecture Layer : Domain
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
 * AuthTokens.kt
 *
 * Purpose: Domain model representing the authentication tokens issued after a successful
 *          login or token refresh.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 1.1, 1.2, 1.3
 */

package com.aiassistant.domain.model

/**
 * Holds the JWT and refresh token pair issued by the Auth_Service after a successful
 * login or token refresh operation.
 *
 * @param jwt             The signed JSON Web Token used to authenticate API requests.
 *                        Expires after 15 minutes (per Requirement 1.2).
 * @param refreshToken    The opaque refresh token used to obtain a new JWT without
 *                        re-entering credentials (per Requirement 1.3).
 * @param jwtExpiresAt    Epoch milliseconds when [jwt] expires.
 * @param refreshExpiresAt Epoch milliseconds when [refreshToken] expires (30 days per
 *                         Requirement 1.2).
 */
data class AuthTokens(val jwt: String, val refreshToken: String, val jwtExpiresAt: Long, val refreshExpiresAt: Long)
