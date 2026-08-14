/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : AuthUiState.kt
 * Purpose    : AuthUiState — feature-auth module component
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : UI State Data Class
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
 * File       : AuthUiState.kt
 * Purpose    : AuthUiState — feature-auth module component
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : UI State Data Class
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
 * AuthUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the authentication flow.
 * Architecture: feature-auth â€” MVVM presentation layer.
 * Dependencies: None (pure Kotlin)
 *
 * Requirements: 1.1, 1.6, 1.7, 16.3, 17.1, 28.3
 */
package com.aiassistant.feature.auth

/**
 * Represents every possible UI state in the authentication flow.
 *
 * The [AuthViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed class.
 * Composables observe it and render accordingly, while navigation is driven by
 * [LaunchedEffect] blocks keyed on the current state.
 */
sealed class AuthUiState {

    /** Initial state before any operation has started. */
    data object Idle : AuthUiState()

    /** An async operation is in progress (login, register, etc.). */
    data object Loading : AuthUiState()

    /**
     * Authentication succeeded. The composable nav graph should navigate
     * to the Home Dashboard and clear the back stack.
     */
    data object Authenticated : AuthUiState()

    /**
     * An operation failed.
     *
     * @param message     Human-readable top-level error for general error banners.
     * @param fieldErrors Map of field name â†’ error message for inline field-level errors
     *                    (e.g., `"email" to "Must be a valid email address"`).
     */
    data class Error(val message: String, val fieldErrors: Map<String, String> = emptyMap()) : AuthUiState()

    /**
     * The device supports biometric authentication and the biometric prompt
     * should be shown to the user.
     */
    data object BiometricPromptRequired : AuthUiState()

    /**
     * This is the user's first launch â€” they must complete onboarding (privacy policy,
     * terms of service, and consent) before accessing the app.
     */
    data object OnboardingRequired : AuthUiState()

    /**
     * The user has not yet given explicit consent to the required terms. They must
     * consent before optional data collection can be activated.
     */
    data object ConsentRequired : AuthUiState()
}
