/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : User.kt
 * Purpose    : User — domain module component
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

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : User.kt
 * Purpose    : User — domain module component
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
 * User.kt
 *
 * Purpose: Domain entity representing an authenticated user of the AI Assistant.
 * Architecture: domain module â€” pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: None
 *
 * Requirements: 19.2
 */

package com.aiassistant.domain.model

/**
 * The role level of a user account, controlling feature access.
 *
 * Maps to the "role" string field in the backend and Room entity.
 */
enum class UserRole(val value: String) {
    /** Standard user account. */
    USER("user"),

    /** Premium subscription user with additional features. */
    PREMIUM("premium"),

    /** Administrator with full system access. */
    ADMIN("admin");

    companion object {
        fun fromValue(value: String): UserRole = entries.firstOrNull { it.value == value } ?: USER
    }
}

/**
 * The application UI theme mode preference for a user.
 */
enum class ThemeMode(val value: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromValue(value: String): ThemeMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * Represents an authenticated user of the AI Assistant.
 *
 * This is a pure domain entity with no Android or Room dependencies. The data
 * layer maps between [User] and `UserEntity` (Room) or JSON (API).
 *
 * @param id              Unique identifier for the user.
 * @param email           The user's email address.
 * @param displayName     The user's display name shown in the UI.
 * @param avatarUrl       Optional URL to the user's profile picture.
 * @param role            The user's access role; defaults to [UserRole.USER].
 * @param activeProvider  The currently selected LLM provider identifier.
 * @param themeMode       The user's preferred UI theme; defaults to [ThemeMode.SYSTEM].
 * @param createdAt       Epoch milliseconds when the account was created.
 * @param updatedAt       Epoch milliseconds of the last profile update.
 */
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String?,
    val role: UserRole = UserRole.USER,
    val activeProvider: String,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val createdAt: Long,
    val updatedAt: Long
)
