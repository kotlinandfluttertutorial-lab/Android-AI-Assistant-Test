/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app (androidTest)
 * File       : LoginFlowTest.kt
 * Purpose    : Compose UI integration tests for the Login with email/password flow.
 *
 * Architecture Layer : androidTest — UI integration
 * Requirements: 21.3, 1.1, 1.6, 1.7
 * ============================================================
 */
package com.aiassistant.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.feature.auth.AuthUiState
import com.aiassistant.feature.auth.LoginScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the Login screen.
 *
 * Tests the email/password login flow including:
 * - Screen rendering in Idle state
 * - Email and password field interactions
 * - Sign In button tap triggers onLogin callback
 * - Loading state shows spinner and disables controls
 * - Error state shows inline field errors
 * - Error state shows general error banner when no field errors
 * - Biometric button visibility based on availability
 * - Navigate-to-register text button
 *
 * Requirements: 21.3
 */
@RunWith(AndroidJUnit4::class)
class LoginFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helper: set up LoginScreen with given state ─────────────────────────

    private fun setLoginScreen(
        uiState: AuthUiState = AuthUiState.Idle,
        onLogin: (String, String) -> Unit = { _, _ -> },
        onNavigateToRegister: () -> Unit = {},
        onGoogleSignIn: () -> Unit = {},
        onBiometricLogin: () -> Unit = {},
        isBiometricAvailable: Boolean = false
    ) {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = uiState,
                    onLogin = onLogin,
                    onNavigateToRegister = onNavigateToRegister,
                    onGoogleSignIn = onGoogleSignIn,
                    onBiometricLogin = onBiometricLogin,
                    isBiometricAvailable = isBiometricAvailable
                )
            }
        }
    }

    // ── 1. Screen renders in Idle state ─────────────────────────────────────

    @Test
    fun loginScreen_idleState_displaysEmailAndPasswordFields() {
        setLoginScreen()

        composeTestRule.onNodeWithText("Email address").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sign In").assertIsDisplayed()
        composeTestRule.onNodeWithText("G  Sign in with Google").assertIsDisplayed()
        composeTestRule.onNodeWithText("Don't have an account? Create Account").assertIsDisplayed()
    }

    // ── 2. Email input updates correctly ────────────────────────────────────

    @Test
    fun loginScreen_emailFieldInput_acceptsText() {
        setLoginScreen()

        composeTestRule.onNodeWithContentDescription("Email address input field")
            .performTextInput("test@example.com")

        composeTestRule.onNodeWithText("test@example.com").assertIsDisplayed()
    }

    // ── 3. Password input updates correctly ─────────────────────────────────

    @Test
    fun loginScreen_passwordFieldInput_acceptsText() {
        setLoginScreen()

        composeTestRule.onNodeWithContentDescription("Password input field")
            .performTextInput("SecurePass123!")

        // Password is masked — we verify the field is displayed with input accepted
        composeTestRule.onNodeWithContentDescription("Password input field").assertIsDisplayed()
    }

    // ── 4. Sign In button tap triggers onLogin callback ─────────────────────

    @Test
    fun loginScreen_signInButtonTap_invokesOnLogin() {
        val capturedCredentials = mutableListOf<Pair<String, String>>()

        setLoginScreen(
            onLogin = { email, password -> capturedCredentials.add(email to password) }
        )

        composeTestRule.onNodeWithContentDescription("Email address input field")
            .performTextInput("user@example.com")
        composeTestRule.onNodeWithContentDescription("Password input field")
            .performTextInput("securepassword123")
        composeTestRule.onNodeWithContentDescription("Sign in button").performClick()

        assert(capturedCredentials.isNotEmpty()) {
            "Expected onLogin to be called but it was not"
        }
        assert(capturedCredentials.first().first == "user@example.com") {
            "Expected email 'user@example.com' but got '${capturedCredentials.first().first}'"
        }
    }

    // ── 5. Loading state shows spinner and disables Sign In button ──────────

    @Test
    fun loginScreen_loadingState_displaysSpinnerAndDisablesButton() {
        setLoginScreen(uiState = AuthUiState.Loading)

        composeTestRule.onNodeWithContentDescription("Loading, please wait").assertIsDisplayed()
        // Sign In button is disabled while loading
        composeTestRule.onNodeWithContentDescription("Sign in button").assertIsNotEnabled()
    }

    // ── 6. Error state with field errors shows inline validation errors ──────

    @Test
    fun loginScreen_errorStateWithFieldErrors_displaysInlineErrors() {
        setLoginScreen(
            uiState = AuthUiState.Error(
                message = "Validation failed",
                fieldErrors = mapOf(
                    "email" to "Must be a valid email address",
                    "password" to "Password must be at least 12 characters"
                )
            )
        )

        composeTestRule.onNodeWithText("Must be a valid email address").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password must be at least 12 characters").assertIsDisplayed()
    }

    // ── 7. Error state without field errors shows general error banner ───────

    @Test
    fun loginScreen_errorStateNoFieldErrors_displaysGeneralErrorBanner() {
        setLoginScreen(
            uiState = AuthUiState.Error(
                message = "Invalid email or password. Please try again."
            )
        )

        composeTestRule.onNodeWithText("Invalid email or password. Please try again.").assertIsDisplayed()
    }

    // ── 8. Biometric button shown when available ─────────────────────────────

    @Test
    fun loginScreen_biometricAvailable_displaysBiometricButton() {
        setLoginScreen(isBiometricAvailable = true)

        composeTestRule.onNodeWithContentDescription("Login with biometrics").assertIsDisplayed()
    }

    // ── 9. Biometric button hidden when not available ────────────────────────

    @Test
    fun loginScreen_biometricNotAvailable_hidesBiometricButton() {
        setLoginScreen(isBiometricAvailable = false)

        composeTestRule.onNodeWithContentDescription("Login with biometrics").assertDoesNotExist()
    }

    // ── 10. Biometric button tap triggers callback ───────────────────────────

    @Test
    fun loginScreen_biometricButtonTap_invokesOnBiometricLogin() {
        var biometricTriggered = false

        setLoginScreen(
            isBiometricAvailable = true,
            onBiometricLogin = { biometricTriggered = true }
        )

        composeTestRule.onNodeWithContentDescription("Login with biometrics").performClick()
        assert(biometricTriggered) { "Expected biometric login callback to be triggered" }
    }

    // ── 11. Navigate to register tap ─────────────────────────────────────────

    @Test
    fun loginScreen_createAccountTap_invokesOnNavigateToRegister() {
        var navigatedToRegister = false

        setLoginScreen(onNavigateToRegister = { navigatedToRegister = true })

        composeTestRule.onNodeWithContentDescription("Create a new account").performClick()
        assert(navigatedToRegister) { "Expected navigate-to-register callback to be triggered" }
    }

    // ── 12. Google sign-in button tap triggers callback ──────────────────────

    @Test
    fun loginScreen_googleSignInTap_invokesOnGoogleSignIn() {
        var googleSignInTriggered = false

        setLoginScreen(onGoogleSignIn = { googleSignInTriggered = true })

        composeTestRule.onNodeWithContentDescription("Sign in with Google button").performClick()
        assert(googleSignInTriggered) { "Expected Google sign-in callback to be triggered" }
    }

    // ── 13. Sign-In button is enabled when not loading ───────────────────────

    @Test
    fun loginScreen_idleState_signInButtonEnabled() {
        setLoginScreen(uiState = AuthUiState.Idle)

        composeTestRule.onNodeWithContentDescription("Sign in button").assertIsEnabled()
    }
}
