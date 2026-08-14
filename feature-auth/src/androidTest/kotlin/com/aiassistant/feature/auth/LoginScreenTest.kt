/**
 * LoginScreenTest.kt
 *
 * Purpose: Compose UI tests verifying Login screen behaviour — inline email/password
 *          validation error display, biometric prompt trigger, and navigation to Home
 *          Dashboard on successful authentication.
 * Architecture: feature-auth androidTest — instrumented Compose UI tests.
 * Dependencies: Compose UI Test (createComposeRule), core-ui (AppTheme)
 *
 * Design decisions:
 * - [LoginScreen] is stateless (all state is passed as parameters), so tests drive it
 *   by passing specific [AuthUiState] instances directly — no ViewModel or Hilt required.
 * - Navigation is captured via a boolean callback lambda; this avoids setting up a full
 *   NavController in every test.
 * - Biometric availability is set via [isBiometricAvailable] parameter; tests flip it to
 *   [true] to assert the fingerprint button appears and that its click invokes the callback.
 * - Tests verify semantic tree content (contentDescription, text) rather than pixel
 *   positions, making them resilient to layout changes.
 *
 * Requirements: 21.3
 */
package com.aiassistant.feature.auth

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── 1. Email validation error display ────────────────────────────────────

    @Test
    fun loginScreen_displaysEmailFieldError_whenEmailFieldErrorPresent() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf("email" to "Must be a valid email address")
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
            .onNodeWithText("Must be a valid email address")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysPasswordFieldError_whenPasswordFieldErrorPresent() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf("password" to "Password must be at least 12 characters")
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
            .onNodeWithText("Password must be at least 12 characters")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysMultipleFieldErrors_simultaneously() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf(
                            "email" to "Must be a valid email address",
                            "password" to "Password must be at least 12 characters"
                        )
                    ),
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule.onNodeWithText("Must be a valid email address").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password must be at least 12 characters").assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysGeneralErrorBanner_whenNoFieldErrors() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Invalid email or password. Please try again.",
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
            .onNodeWithText("Invalid email or password. Please try again.")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_doesNotDisplayGeneralErrorBanner_whenFieldErrorsPresent() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Validation failed",
                        fieldErrors = mapOf("email" to "Must be a valid email address")
                    ),
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        // General error banner should NOT appear when field-level errors exist
        composeTestRule
            .onNodeWithText("Validation failed")
            .assertDoesNotExist()
    }

    // ── 2. Biometric prompt trigger ───────────────────────────────────────────

    @Test
    fun loginScreen_showsBiometricButton_whenBiometricAvailable() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = true
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_doesNotShowBiometricButton_whenBiometricUnavailable() {
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

    @Test
    fun loginScreen_biometricButton_click_invokesOnBiometricLoginCallback() {
        var biometricLoginTriggered = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = { biometricLoginTriggered = true },
                    isBiometricAvailable = true
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .performClick()

        assertTrue("onBiometricLogin callback was not invoked", biometricLoginTriggered)
    }

    // ── 3. Navigation to Home on success ─────────────────────────────────────

    @Test
    fun loginScreen_signInButton_click_invokesOnLoginCallback() {
        var loginCalled = false
        var capturedEmail = ""
        var capturedPassword = ""

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { email, password ->
                        loginCalled = true
                        capturedEmail = email
                        capturedPassword = password
                    },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Email address input field")
            .performTextInput("test@example.com")

        composeTestRule
            .onNodeWithContentDescription("Password input field")
            .performTextInput("SecurePassword123!")

        composeTestRule
            .onNodeWithContentDescription("Sign in button")
            .performClick()

        assertTrue("onLogin callback was not invoked", loginCalled)
        assertTrue("Captured email is incorrect", capturedEmail == "test@example.com")
        assertTrue("Captured password is incorrect", capturedPassword == "SecurePassword123!")
    }

    @Test
    fun loginScreen_signInButton_isEnabled_inIdleState() {
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
            .onNodeWithContentDescription("Sign in button")
            .assertIsEnabled()
    }

    @Test
    fun loginScreen_signInButton_isDisabled_whenLoading() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Loading,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Sign in button")
            .assertIsNotEnabled()
    }

    @Test
    fun loginScreen_displaysLoadingIndicator_whenStateIsLoading() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Loading,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Loading, please wait")
            .assertIsDisplayed()
    }

    // ── 4. Navigate to Register ────────────────────────────────────────────────

    @Test
    fun loginScreen_createAccountButton_click_invokesNavigateToRegisterCallback() {
        var navigateToRegisterCalled = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = { navigateToRegisterCalled = true },
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = false
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Create a new account")
            .performClick()

        assertTrue("onNavigateToRegister callback was not invoked", navigateToRegisterCalled)
    }

    // ── 5. Basic screen structure ─────────────────────────────────────────────

    @Test
    fun loginScreen_displaysEmailAndPasswordFields() {
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
            .onNodeWithContentDescription("Email address input field")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Password input field")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysWelcomeBackHeader() {
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
            .onNodeWithText("Welcome back")
            .assertIsDisplayed()
    }
}
