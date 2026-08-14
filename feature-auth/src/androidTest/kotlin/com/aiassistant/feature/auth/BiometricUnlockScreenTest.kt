/**
 * BiometricUnlockScreenTest.kt
 *
 * Purpose: Compose UI tests verifying that the Login screen correctly handles the
 *          [AuthUiState.BiometricPromptRequired] state — the biometric button is
 *          shown and that tapping it invokes [onBiometricLogin].
 *
 *          This covers the "biometric prompt trigger" scenario from task 11.2:
 *          the Login screen shows the biometric button when hardware is available,
 *          the button is tappable, and the callback reaches the ViewModel's
 *          triggerBiometric() method.
 *
 * Architecture: feature-auth androidTest — instrumented Compose UI tests.
 * Dependencies: Compose UI Test (createComposeRule), core-ui (AppTheme)
 *
 * Design decisions:
 * - Biometric prompt is launched from the composable layer (see [AuthNavigation]) once
 *   [AuthUiState.BiometricPromptRequired] is observed via [LaunchedEffect]. These tests
 *   verify the UI layer correctly reacts to this state and that the trigger callback fires.
 * - Actual biometric hardware is never invoked — tests mock the callback to avoid
 *   requiring physical biometric hardware in the test environment.
 * - [AuthUiState.BiometricPromptRequired] is passed directly to verify the screen renders
 *   without crashing in that state.
 *
 * Requirements: 21.3
 */
package com.aiassistant.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.AppTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BiometricUnlockScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── 2. Biometric prompt trigger ───────────────────────────────────────────

    @Test
    fun loginScreen_biometricButton_isShown_whenBiometricAvailable_inIdleState() {
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
    fun loginScreen_biometricButton_click_triggersBiometricPrompt() {
        var biometricTriggered = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Idle,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = { biometricTriggered = true },
                    isBiometricAvailable = true
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .performClick()

        assertTrue(
            "Biometric prompt trigger callback was not invoked when the biometric button was tapped",
            biometricTriggered
        )
    }

    @Test
    fun loginScreen_rendersWithoutCrash_whenStateIsBiometricPromptRequired() {
        // Verifies the screen remains stable when the ViewModel emits BiometricPromptRequired.
        // The actual prompt launch happens in [AuthNavigation] via LaunchedEffect; here we
        // confirm the screen composable itself doesn't crash in this state.
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.BiometricPromptRequired,
                    onLogin = { _, _ -> },
                    onNavigateToRegister = {},
                    onGoogleSignIn = {},
                    onBiometricLogin = {},
                    isBiometricAvailable = true
                )
            }
        }

        // Screen should still show its main elements without crashing
        composeTestRule
            .onNodeWithContentDescription("Email address input field")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Login with biometrics")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_biometricButton_isNotShown_whenBiometricUnavailable_inIdleState() {
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
    fun loginScreen_biometricButton_remainsAccessible_whenErrorStateActive() {
        // If biometric fails and produces an error, the button remains visible
        // so the user can retry
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                LoginScreen(
                    uiState = AuthUiState.Error(
                        message = "Biometric authentication failed. Try again."
                    ),
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
}
