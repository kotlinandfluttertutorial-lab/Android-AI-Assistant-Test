/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : LoginScreen.kt
 * Purpose    : Compose UI screen for the Login feature
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
 * File       : LoginScreen.kt
 * Purpose    : Compose UI screen for the Login feature
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
 * LoginScreen.kt
 *
 * Purpose: Login screen with email/password inline validation, Google OAuth2 button,
 *          and biometric login option.
 * Architecture: feature-auth â€” Compose UI layer.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing), domain models.
 *
 * Design decisions:
 * - Inline field errors are shown via OutlinedTextField's supportingText parameter so
 *   they appear directly below the relevant field (Material 3 pattern).
 * - A general ErrorBanner is shown above the form only when fieldErrors is empty â€”
 *   this avoids duplicating field-level errors in both the banner and the field.
 * - Google sign-in is stubbed with a TODO comment since play-services-auth is not
 *   included in the feature-auth dependency list (Requirement note in task spec).
 * - Biometric button is only shown when biometric hardware is available, checked via
 *   a parameter rather than calling the manager inside the composable.
 * - Loading overlay uses a full-screen semi-transparent scrim with a centered spinner
 *   to prevent interaction during in-flight requests.
 * - All interactive elements have contentDescriptions (Requirement 28.3).
 *
 * Requirements: 1.1, 1.6, 1.7, 28.3
 */
package com.aiassistant.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing

/**
 * Login screen composable.
 *
 * Renders an email + password form with inline validation, Google sign-in,
 * and optional biometric login. Navigation and state mutations are delegated
 * to callbacks so this composable remains stateless (parameters drive rendering).
 *
 * @param uiState             Current auth UI state from [AuthViewModel].
 * @param onLogin             Invoked with (email, password) when the user taps "Sign In".
 * @param onNavigateToRegister Called when the user taps "Create Account".
 * @param onGoogleSignIn       Called when the user taps "Sign in with Google".
 * @param onBiometricLogin     Called when the user taps the biometric icon button.
 * @param isBiometricAvailable Whether biometric hardware is available on this device.
 */
@Composable
fun LoginScreen(
    uiState: AuthUiState,
    onLogin: (String, String) -> Unit,
    onNavigateToRegister: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onBiometricLogin: () -> Unit,
    isBiometricAvailable: Boolean = false
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val isLoading = uiState is AuthUiState.Loading
    val fieldErrors = (uiState as? AuthUiState.Error)?.fieldErrors ?: emptyMap()
    val generalError = (uiState as? AuthUiState.Error)
        ?.takeIf { it.fieldErrors.isEmpty() }
        ?.message

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // â”€â”€ Header â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Text(
                text = "Welcome back",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            Text(
                text = "Sign in to your account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))

            // â”€â”€ General error banner (non-field errors only) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (generalError != null) {
                ErrorBanner(
                    message = generalError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
            }

            // â”€â”€ Email field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email address") },
                placeholder = { Text("user@example.com") },
                singleLine = true,
                isError = fieldErrors.containsKey("email"),
                supportingText = fieldErrors["email"]?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Email address input field" }
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // â”€â”€ Password field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                isError = fieldErrors.containsKey("password"),
                supportingText = fieldErrors["password"]?.let { { Text(it) } },
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onLogin(email, password)
                    }
                ),
                trailingIcon = {
                    val desc = if (passwordVisible) "Hide password" else "Show password"
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.semantics { contentDescription = desc }
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = null // parent button has description
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Password input field" }
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

            // â”€â”€ Sign In button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Button(
                onClick = { onLogin(email, password) },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Sign in button" }
            ) {
                Text("Sign In")
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // â”€â”€ Google sign-in button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            OutlinedButton(
                onClick = onGoogleSignIn,
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Sign in with Google button" }
            ) {
                // Using a text placeholder per task spec â€” no play-services-auth dependency
                Text("G  Sign in with Google")
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            // â”€â”€ Biometric login button (conditional) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isBiometricAvailable) {
                IconButton(
                    onClick = onBiometricLogin,
                    modifier = Modifier
                        .size(MaterialTheme.spacing.xxl)
                        .semantics { contentDescription = "Login with biometrics" }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Fingerprint,
                        contentDescription = null, // parent button has description
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MaterialTheme.spacing.lg)
                    )
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            }

            // â”€â”€ Navigate to Register â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            TextButton(
                onClick = onNavigateToRegister,
                modifier = Modifier.semantics { contentDescription = "Create a new account" }
            ) {
                Text("Don't have an account? Create Account")
            }
        }

        // â”€â”€ Loading overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (isLoading) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading, please wait"
                        }
                    )
                }
            }
        }
    }
}
