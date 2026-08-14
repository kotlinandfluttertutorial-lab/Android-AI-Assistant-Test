/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : OnboardingScreen.kt
 * Purpose    : Compose UI screen for the Onboarding feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : OnboardingScreen.kt
 * Purpose    : Compose UI screen for the Onboarding feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : Jetpack Compose Screen
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
 * OnboardingScreen.kt
 *
 * Purpose: Multi-page onboarding flow covering app overview, privacy policy / terms of
 *          service, and explicit consent collection including optional analytics and
 *          notification permission request.
 * Architecture: feature-auth â€” Compose UI layer.
 * Dependencies: core-ui (MaterialTheme.spacing), Compose Foundation HorizontalPager
 *
 * Design decisions:
 * - Three-page horizontal pager (Foundation [HorizontalPager]) avoids Accompanist
 *   dependency which is deprecated for pager support.
 * - Notification permission is requested on Android 13+ (API 33) via
 *   [rememberLauncherForActivityResult] + [RequestPermission]. The button is hidden on
 *   older API levels where POST_NOTIFICATIONS doesn't exist.
 * - The "Continue" / "Finish" button on the consent page is disabled until the required
 *   privacy policy + ToS consent toggle is checked, enforcing Requirement 16.3.
 * - All interactive elements have contentDescriptions for TalkBack (Requirement 28.3).
 *
 * Requirements: 16.3, 17.1, 28.3
 */
package com.aiassistant.feature.auth

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3
private const val PAGE_WELCOME = 0
private const val PAGE_PRIVACY = 1
private const val PAGE_CONSENT = 2

/**
 * Onboarding flow presented to first-time users.
 *
 * Three pages:
 * 1. **Welcome** â€” app overview and feature highlights.
 * 2. **Privacy & Terms** â€” scrollable privacy policy and terms of service.
 * 3. **Consent** â€” required ToS toggle, optional analytics toggle, notification permission.
 *
 * @param onConsentGiven Invoked when the user checks the required consent and taps "Continue".
 * @param onDecline      Invoked if the user declines (exits the flow without consenting).
 */
@Composable
fun OnboardingScreen(onConsentGiven: () -> Unit, onDecline: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    // Consent state managed locally â€” only emitted to the ViewModel on "Continue"
    var requiredConsentChecked by remember { mutableStateOf(false) }
    var analyticsConsentChecked by remember { mutableStateOf(false) }
    var notificationPermissionGranted by remember { mutableStateOf(false) }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted -> notificationPermissionGranted = granted }
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // â”€â”€ Pager â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            when (page) {
                PAGE_WELCOME -> WelcomePage()
                PAGE_PRIVACY -> PrivacyPolicyPage()
                PAGE_CONSENT -> ConsentPage(
                    requiredConsentChecked = requiredConsentChecked,
                    analyticsConsentChecked = analyticsConsentChecked,
                    notificationPermissionGranted = notificationPermissionGranted,
                    onRequiredConsentChange = { requiredConsentChecked = it },
                    onAnalyticsConsentChange = { analyticsConsentChecked = it },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            notificationPermissionGranted = true
                        }
                    }
                )
            }
        }

        // â”€â”€ Page indicator dots â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.sm),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(PAGE_COUNT) { index ->
                val isSelected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .semantics {
                            contentDescription = "Page ${index + 1} of $PAGE_COUNT indicator"
                        }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ) {}
                }
            }
        }

        // â”€â”€ Navigation buttons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back / Decline button
            if (pagerState.currentPage == PAGE_WELCOME) {
                TextButton(
                    onClick = onDecline,
                    modifier = Modifier.semantics { contentDescription = "Decline and exit onboarding" }
                ) {
                    Text("Skip")
                }
            } else {
                TextButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Go to previous page" }
                ) {
                    Text("Back")
                }
            }

            // Next / Continue button
            if (pagerState.currentPage < PAGE_COUNT - 1) {
                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Go to next onboarding page" }
                ) {
                    Text("Next")
                }
            } else {
                Button(
                    onClick = onConsentGiven,
                    enabled = requiredConsentChecked,
                    modifier = Modifier.semantics { contentDescription = "Continue to the app" }
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

// â”€â”€â”€ Page composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        Text(
            text = "Welcome to AI Assistant",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Text(
            text = "Your intelligent companion for productivity, creativity, and more.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

        FeatureHighlight(
            icon = Icons.Filled.AutoAwesome,
            title = "AI-Powered Conversations",
            description = "Chat with leading AI models including GPT-4o, Gemini, and Claude."
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        FeatureHighlight(
            icon = Icons.Filled.Security,
            title = "Privacy First",
            description = "Biometric protection and encrypted local storage keep your data safe."
        )
    }
}

@Composable
private fun PrivacyPolicyPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Policy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            Text(
                text = "Privacy & Terms",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Privacy Policy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = """
AI Assistant is committed to protecting your privacy. We collect only the minimum data required to provide our services.

Data We Collect:
â€¢ Account information (email address)
â€¢ Conversation history (stored encrypted on your device)
â€¢ Usage analytics (optional, requires your consent)

Data Storage:
â€¢ All credentials and tokens are stored using Android EncryptedSharedPreferences
â€¢ Biometric data is never transmitted â€” authentication is performed entirely on-device
â€¢ Conversations are stored locally and only synced to our servers when you are connected

Your Rights:
â€¢ You can delete your account and all associated data at any time
â€¢ You can opt out of optional analytics at any time in Settings
â€¢ You can request a copy of your data by contacting support
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

            Text(
                text = "Terms of Service",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = """
By using AI Assistant, you agree to these terms:

Acceptable Use:
â€¢ You must not use the app for illegal activities or to harm others
â€¢ You are responsible for the content of your conversations
â€¢ AI responses are provided as-is and should not substitute professional advice

Service:
â€¢ We reserve the right to modify or discontinue the service with reasonable notice
â€¢ We are not liable for any damages arising from the use of AI-generated content

These terms are governed by applicable law. By continuing, you acknowledge that you have read and understood both the Privacy Policy and Terms of Service.
                """.trimIndent(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

@Composable
private fun ConsentPage(
    requiredConsentChecked: Boolean,
    analyticsConsentChecked: Boolean,
    notificationPermissionGranted: Boolean,
    onRequiredConsentChange: (Boolean) -> Unit,
    onAnalyticsConsentChange: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Your Consent",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

        Text(
            text = "Please review and accept the following before continuing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

        // Required consent toggle
        ConsentToggleRow(
            title = "Privacy Policy & Terms of Service",
            description = "I agree to the Privacy Policy and Terms of Service (required to use the app).",
            checked = requiredConsentChecked,
            onCheckedChange = onRequiredConsentChange,
            contentDescription = "Toggle agreement to Privacy Policy and Terms of Service",
            isRequired = true
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        // Optional analytics toggle
        ConsentToggleRow(
            title = "Analytics Data Collection",
            description = "Allow optional analytics data collection to help improve the app. You can change this in Settings at any time.",
            checked = analyticsConsentChecked,
            onCheckedChange = onAnalyticsConsentChange,
            contentDescription = "Toggle optional analytics data collection",
            isRequired = false
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

        // Notification permission request
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = if (notificationPermissionGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Text(
                    text = "Notifications",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            Text(
                text = "Allow AI Assistant to send notifications for reminders and updates. You can change this in device Settings at any time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            if (notificationPermissionGranted) {
                Text(
                    text = "âœ“ Notifications enabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = onRequestNotificationPermission,
                        modifier = Modifier.semantics {
                            contentDescription = "Allow notification permission"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                        Text("Allow Notifications")
                    }
                } else {
                    // On API < 33, POST_NOTIFICATIONS doesn't exist â€” notifications are always allowed
                    Text(
                        text = "Notifications will be enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsentToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    isRequired: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (isRequired) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "*",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { this.contentDescription = contentDescription }
        )
    }
}

@Composable
private fun FeatureHighlight(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
