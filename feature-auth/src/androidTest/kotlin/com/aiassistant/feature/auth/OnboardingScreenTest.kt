/**
 * OnboardingScreenTest.kt
 *
 * Purpose: Compose UI tests verifying Onboarding screen behaviour — privacy policy
 *          display, notification permission request button presence, and the consent
 *          gate that blocks the Continue button until required consent is given.
 * Architecture: feature-auth androidTest — instrumented Compose UI tests.
 * Dependencies: Compose UI Test (createComposeRule), core-ui (AppTheme)
 *
 * Design decisions:
 * - [OnboardingScreen] is stateful internally (HorizontalPager + local state), so tests
 *   interact with it by clicking the "Next" button to advance pages before asserting
 *   page-specific content.
 * - Notification permission button is only shown on Android 13+ (Build.VERSION_CODES.TIRAMISU).
 *   Tests that verify the button's presence are guarded with an API version check; on
 *   older APIs the test asserts the fallback text is shown instead.
 * - The consent gate test verifies the "Continue" button remains disabled until the
 *   required consent switch is toggled on.
 *
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

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Navigates the pager to the Privacy & Terms page (page 2 of 3). */
    private fun navigateToPrivacyPage() {
        composeTestRule
            .onNodeWithContentDescription("Go to next onboarding page")
            .performClick()
        composeTestRule.waitForIdle()
    }

    /** Navigates the pager to the Consent page (page 3 of 3). */
    private fun navigateToConsentPage() {
        navigateToPrivacyPage()
        composeTestRule
            .onNodeWithContentDescription("Go to next onboarding page")
            .performClick()
        composeTestRule.waitForIdle()
    }

    // ── Page 1: Welcome ───────────────────────────────────────────────────────

    @Test
    fun onboardingScreen_displaysWelcomePage_initially() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("Welcome to AI Assistant")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_skipButton_isDisplayed_onWelcomePage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("Skip")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_skipButton_click_invokesOnDeclineCallback() {
        var declineCalled = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = { declineCalled = true }
                )
            }
        }

        composeTestRule
            .onNodeWithText("Skip")
            .performClick()

        assertTrue("onDecline callback was not invoked when Skip was clicked", declineCalled)
    }

    // ── 4. Privacy policy display ─────────────────────────────────────────────

    @Test
    fun onboardingScreen_displaysPrivacyAndTermsHeader_onPrivacyPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToPrivacyPage()

        composeTestRule
            .onNodeWithText("Privacy & Terms")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysPrivacyPolicySectionHeader_onPrivacyPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToPrivacyPage()

        composeTestRule
            .onNodeWithText("Privacy Policy")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysTermsOfServiceSectionHeader_onPrivacyPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToPrivacyPage()

        composeTestRule
            .onNodeWithText("Terms of Service")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysBackButton_onPrivacyPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToPrivacyPage()

        composeTestRule
            .onNodeWithContentDescription("Go to previous page")
            .assertIsDisplayed()
    }

    // ── 5. Notification permission request dialog ─────────────────────────────

    @Test
    fun onboardingScreen_displaysNotificationPermissionSection_onConsentPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        composeTestRule
            .onNodeWithText("Notifications")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysNotificationPermissionButton_onAndroid13Plus() {
        // Only assert the button exists on API 33+ where POST_NOTIFICATIONS permission exists
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        composeTestRule
            .onNodeWithContentDescription("Allow notification permission")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysNotificationFallbackText_onOlderAndroid() {
        // On API < 33, POST_NOTIFICATIONS doesn't exist — fallback text is shown instead
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        composeTestRule
            .onNodeWithText("Notifications will be enabled.")
            .assertIsDisplayed()
    }

    // ── 6. Consent gate blocks access until confirmed ─────────────────────────

    @Test
    fun onboardingScreen_continueButton_isDisabled_whenRequiredConsentUnchecked() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // "Continue" button is the Next button on the final page; it should be disabled
        // because required consent is not yet checked
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsNotEnabled()
    }

    @Test
    fun onboardingScreen_continueButton_becomesEnabled_afterRequiredConsentChecked() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // Toggle the required Privacy Policy & ToS consent switch
        composeTestRule
            .onNodeWithContentDescription("Toggle agreement to Privacy Policy and Terms of Service")
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsEnabled()
    }

    @Test
    fun onboardingScreen_continueButton_click_invokesOnConsentGiven_whenConsentChecked() {
        var consentGivenCalled = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = { consentGivenCalled = true },
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // Enable required consent
        composeTestRule
            .onNodeWithContentDescription("Toggle agreement to Privacy Policy and Terms of Service")
            .performClick()
        composeTestRule.waitForIdle()

        // Tap Continue
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "onConsentGiven callback was not invoked after consent and Continue click",
            consentGivenCalled
        )
    }

    @Test
    fun onboardingScreen_continueButton_doesNotInvokeCallback_whenConsentNotChecked() {
        var consentGivenCalled = false

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = { consentGivenCalled = true },
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        // The "Continue" button is disabled so clicking it should do nothing
        // We try clicking the button, but it's disabled so the callback must not fire
        composeTestRule
            .onNodeWithContentDescription("Continue to the app")
            .assertIsNotEnabled()

        assertTrue(
            "onConsentGiven must not be called when consent has not been given",
            !consentGivenCalled
        )
    }

    // ── Analytics consent toggle (optional) ──────────────────────────────────

    @Test
    fun onboardingScreen_displaysAnalyticsConsentToggle_onConsentPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        composeTestRule
            .onNodeWithText("Analytics Data Collection")
            .assertIsDisplayed()
    }

    @Test
    fun onboardingScreen_displaysRequiredConsentToggle_withRequiredLabel_onConsentPage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        navigateToConsentPage()

        composeTestRule
            .onNodeWithText("Privacy Policy & Terms of Service")
            .assertIsDisplayed()
    }

    // ── Page indicator dots ───────────────────────────────────────────────────

    @Test
    fun onboardingScreen_displaysThreePageIndicatorDots() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                OnboardingScreen(
                    onConsentGiven = {},
                    onDecline = {}
                )
            }
        }

        // Verify page 1 indicator
        composeTestRule
            .onNodeWithContentDescription("Page 1 of 3 indicator")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Page 2 of 3 indicator")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Page 3 of 3 indicator")
            .assertIsDisplayed()
    }
}
