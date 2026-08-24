/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : SplashScreen.kt
 * Purpose    : Compose UI screen for the Splash feature
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
 * File       : SplashScreen.kt
 * Purpose    : Compose UI screen for the Splash feature
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
 * SplashScreen.kt
 *
 * Purpose: Compose splash screen displaying app branding while the app determines
 *          the initial navigation destination.
 * Architecture: feature-auth â€” Compose UI layer.
 * Dependencies: core-ui (AppTheme, MaterialTheme.spacing)
 *
 * Design decisions:
 * - The system splash screen (duration control) is already installed in MainActivity via
 *   [androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen]. This
 *   composable provides a brief visual bridge (500 ms) so there is no jarring white flash
 *   between the system splash and the first real screen.
 * - A 500 ms delay is used for visual polish only â€” actual auth state determination happens
 *   asynchronously in [AuthViewModel.checkInitialState].
 * - contentDescription satisfies TalkBack requirement (Requirement 28.3).
 *
 * Requirements: 1.6, 28.3
 */
package com.aiassistant.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay

/**
 * Full-screen splash composable shown during cold-start initialisation.
 *
 * Displays app name and logo centred on [MaterialTheme.colorScheme.background].
 * After a 500 ms visual polish delay, calls [onInitComplete] with the result of
 * the initial auth check.
 *
 * @param isAuthenticated Whether a valid JWT was found in secure storage.
 * @param onInitComplete  Callback invoked when the initial delay is complete;
 *                        receives the authentication status so the nav graph can
 *                        route to Home or Login.
 */
@Composable
fun SplashScreen(isAuthenticated: Boolean = false, onInitComplete: (isAuthenticated: Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "AI Assistant splash screen" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null, // described by the Column's parent semantics
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = "Your intelligent companion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // 500 ms visual polish delay â€” the system splash screen controlled by
    // installSplashScreen() in MainActivity governs actual cold-start duration.
    LaunchedEffect(Unit) {
        delay(500L)
        onInitComplete(isAuthenticated)
    }
}
