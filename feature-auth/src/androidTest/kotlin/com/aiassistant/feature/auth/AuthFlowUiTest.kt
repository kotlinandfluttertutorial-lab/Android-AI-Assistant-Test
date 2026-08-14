/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : AuthFlowUiTest.kt
 * Purpose    : Compose UI tests for the authentication flow screens
 *
 * Architecture Layer : Feature (feature-auth) — androidTest
 * Pattern Used       : Compose UI Test (ComposeTestRule)
 *
 * Key Concepts:
 *   - Stateless composables are driven directly by passing state as parameters
 *   - createComposeRule() avoids Hilt setup complexity in instrumented tests
 *   - AppTheme wrapper ensures Material3 tokens are available
 *
 * Dependencies:
 *   - androidx.compose.ui.test.junit4
 *   - androidx.test.ext.junit4
 * ============================================================
 */

/**
 * AuthFlowUiTest.kt
 *
 * Purpose: Instrumented Compose UI tests covering the core auth flow scenarios:
 *   - Email/password field-level error display (LoginScreen)
 *   - General error banner display (LoginScreen)
 *   - Biometric button visibility and invocation (LoginScreen)
 *   - Authenticated state / navigation callback (LoginScreen)
 *   - Privacy Policy and Terms of Service text on page 1 (OnboardingScreen)
 *   - Notification permission button visibility on consent page (OnboardingScreen)
 *   - Consent gate blocking "Continue" until required switch is checked (OnboardingScreen)
 *   - onConsentGiven callback fires after consent switch + Continue tap (OnboardingScreen)
 *
 * Architecture: feature-auth — androidTest instrumented tests.
 * Requirements: 21.3
 */
package com.aiassistant.feature.auth

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the authentication flow.
 *
 * Each test uses [createComposeRule] and passes state directly to the stateless
 * composables under test. [AppTheme] is applied with [dynamicColor = false] for
 * deterministic Material3 token resolution.
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── LoginScreen — field-level errors ────────────────────────────────────

    /**
     * When [AuthUiState.Error] carries a field error for "email", the supporting text
     * is rendered directly below the email OutlinedTextField.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun emailFieldError_isDisplayedBelowEmailField() {
        val errorMessage = "Must be a valid email address"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf("email" to errorMessage)
                    ),
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithText(errorMessage)
            .assertIsDisplayed()
    }

    /**
     * When [AuthUiState.Error] carries a field error for "password", the supporting
     * text is rendered directly below the password OutlinedTextField.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun passwordFieldError_isDisplayedBelowPasswordField() {
        val errorMessage = "Password must be at least 12 characters"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf("password" to errorMessage)
                    ),
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithText(errorMessage)
            .assertIsDisplayed()
    }

    /**
     * When [AuthUiState.Error] has no field errors, the general [ErrorBanner] with the
     * top-level message is displayed above the form.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun generalErrorBanner_isDisplayedWhenNoFieldErrors() {
        val errorMessage = "Invalid email or password"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = errorMessage,
                        fieldErrors = emptyMap()
                    ),
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithText(errorMessage)
            .assertIsDisplayed()
    }

    // ─── LoginScreen — biometric button ──────────────────────────────────────

    /**
     * When [isBiometricAvailable] is true, tapping the biometric button invokes
     * [onBiometricLogin].
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun biometricButton_whenAvailable_invokesOnBiometricLogin() {
        var biometricCallbackInvoked = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = { biometricCallbackInvoked = true },
                    isBiometricAvailable = true
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .assertIsDisplayed()
            .performClick()

        assertTrue(
            "onBiometricLogin callback should have been invoked",
            biometricCallbackInvoked
        )
    }

    /**
     * When [isBiometricAvailable] is false, the biometric button must not appear.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun biometricButton_whenUnavailable_isNotDisplayed() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .assertDoesNotExist()
    }

    // ─── LoginScreen — Authenticated state ───────────────────────────────────

    /**
     * When [uiState] is [AuthUiState.Authenticated], the screen does not show a loading
     * spinner — the authentication has completed and navigation should occur. We verify
     * the sign-in button is still present (the composable is still rendered) and no
     * loading overlay is shown, confirming the state is correctly reflected.
     *
     * Navigation itself is exercised at the NavGraph level; here we assert that the
     * screen renders cleanly in the Authenticated state without errors.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun loginScreen_withAuthenticatedState_doesNotShowLoadingOverlay() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Authenticated,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        // Loading overlay must not be present when state is Authenticated
        composeTestRule
            .onNodeWithContentDescription("Loading, please wait")
            .assertDoesNotExist()

        // Sign-in button is still rendered (composable is still laid out)
        composeTestRule
            .onNodeWithContentDescription("Sign in button")
            .assertIsDisplayed()
    }

    // ─── OnboardingScreen — Privacy Policy page ───────────────────────────────

    /**
     * On page 1 of OnboardingScreen (Privacy & Terms), both "Privacy Policy" and
     * "Terms of Service" texts must be visible.
     *
     * Navigation is performed by tapping the "Next" button from page 0.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun onboardingScreen_page1_displaysPrivacyPolicyAndTermsOfService() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        // Navigate from page 0 → page 1 via the "Next" button
        composeTestRule
            .onNodeWithContentDescription("Go to next onboarding page")
            .performClick()

        composeTestRule.waitForIdle()

        // Both texts are rendered on the Privacy page
        composeTestRule
            .onNodeWithText("Privacy Policy")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Terms of Service")
            .assertIsDisplayed()
    }

    // ─── OnboardingScreen — consent page ─────────────────────────────────────

    /**
     * Helper: navigates to the consent page (page 2) by tapping "Next" twice.
     */
    private fun navigateToConsentPage() {
        // Page 0 → Page 1
        composeTestRule
            .onNodeWithContentDescription("Go to next onboarding page")
            .performClick()
        composeTestRule.waitForIdle()

        // Page 1 → Page 2
        composeTestRule
            .onNodeWithContentDescription("Go to next onboarding page")
            .performClick()
        composeTestRule.waitForIdle()
    }

    /**
     * On the consent page (page 2), the "Allow notification permission" button is visible
     * on Android 13+ (API 33+) where POST_NOTIFICATIONS exists. On earlier API levels
     * the button is intentionally hidden by the screen itself, so we skip the assertion.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun onboardingConsentPage_notificationPermissionButton_isVisibleOnApi33Plus() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            composeTestRule
                .onNodeWithContentDescription("Allow notification permission")
                .assertIsDisplayed()
        } else {
            // On API < 33 the button is not rendered — verify it simply does not exist
            composeTestRule
                .onNodeWithContentDescription("Allow notification permission")
                .assertDoesNotExist()
        }
    }

    /**
     * The "Continue" button on the consent page is DISABLED when the required consent
     * switch is unchecked, and ENABLED once it is toggled on.
     *
     * This enforces the consent gate: users cannot proceed without accepting the
     * Privacy Policy & Terms of Service.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun onboardingConsentPage_continueButton_isDisabledUntilRequiredConsentChecked() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // Initially disabled — required consent switch is off
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsNotEnabled()

        // Toggle the required consent switch on
        composeTestRule
            .onNodeWithContentDescription("Toggle agreement to Privacy Policy and Terms of Service")
            .performClick()

        composeTestRule.waitForIdle()

        // Now the "Continue" button should be enabled
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsEnabled()
    }

    /**
     * After navigating to the consent page, toggling the required consent switch, and
     * tapping "Continue", [onConsentGiven] must be invoked.
     *
     * Validates: Requirements 21.3
     */
    @Test
    fun onboardingConsentPage_tapContinueAfterConsent_invokesOnConsentGiven() {
        var consentGivenCallbackInvoked = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = { consentGivenCallbackInvoked = true },
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // Enable the required consent toggle
        composeTestRule
            .onNodeWithContentDescription("Toggle agreement to Privacy Policy and Terms of Service")
            .performClick()

        composeTestRule.waitForIdle()

        // Tap the now-enabled "Continue" button
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsEnabled()
            .performClick()

        assertTrue(
            "onConsentGiven callback should have been invoked after tapping Continue",
            consentGivenCallbackInvoked
        )
    }
}
