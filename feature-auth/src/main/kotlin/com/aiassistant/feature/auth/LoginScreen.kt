/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : LoginScreen.kt
 * Purpose    : Redesigned Login screen (Task 50.2) with MeshGradientBackground,
 *              SurfaceFillTextField inputs, gradient Sign-In button with
 *              Crossfade loading state, animated ErrorBanner, and a pulsing
 *              brand logo.
 *
 * Architecture Layer : Feature (feature-auth) — Compose UI layer.
 *                      Delegates all state and side-effects to AuthViewModel
 *                      via callbacks; this composable is stateless.
 *
 * Dependencies       : core-ui (MeshGradientBackground, SurfaceFillTextField,
 *                      AppColors, AppType, pressScale, ErrorBanner, spacing),
 *                      domain models (AuthUiState).
 *
 * Design Decision    : The gradient button is built with a Box + Brush.linearGradient
 *                      overlay on top of a ButtonDefaults shape rather than a custom
 *                      Canvas draw, so it inherits the standard M3 ripple, disabled-
 *                      state alpha, and touch target sizing automatically.
 *                      Crossfade inside the button avoids a layout jump when the label
 *                      switches to a spinner — both states are the same height.
 *                      MeshGradientBackground fills behind the card so the gradient is
 *                      always visible regardless of system dark/light mode.
 *
 * Requirements       : 1.1, 1.6, 1.7, 24.1, 24.3
 * ============================================================
 */
package com.aiassistant.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.SurfaceFillTextField
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import com.aiassistant.core.ui.motion.MeshGradientBackground
import com.aiassistant.core.ui.spacing
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

// ── Gradient button dimensions ────────────────────────────────────────────────
private val BUTTON_HEIGHT = 52.dp
private val LOGO_SIZE = 64.dp
private val LOGO_PULSE_MIN = 0.94f

/**
 * Redesigned Login screen.
 *
 * Places a [MeshGradientBackground] behind a centered [ElevatedCard] that holds
 * the form.  All inputs use [SurfaceFillTextField].  The Sign-In button uses a
 * gradient fill via [Brush.linearGradient].
 *
 * @param uiState              Current auth UI state from [AuthViewModel].
 * @param onLogin              Invoked with (email, password) when the user taps "Sign In".
 * @param onNavigateToRegister Called when the user taps "Create Account".
 * @param onGoogleSignIn       Called with the Google ID token on sign-in success.
 * @param onBiometricLogin     Called when the user taps the biometric icon button.
 * @param isBiometricAvailable Whether biometric hardware is available on this device.
 * @param googleWebClientId    Web Client ID from Firebase/Google Cloud Console.
 */
@Composable
fun LoginScreen(
    uiState: AuthUiState,
    onLogin: (String, String) -> Unit,
    onNavigateToRegister: () -> Unit,
    onGoogleSignIn: (String) -> Unit,
    onBiometricLogin: () -> Unit,
    isBiometricAvailable: Boolean = false,
    googleWebClientId: String = ""
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

    val isDark = isSystemInDarkTheme()

    // ── Gradient colours ──────────────────────────────────────────────────────
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight

    Box(modifier = Modifier.fillMaxSize()) {
        // ── 1. Animated mesh gradient background ──────────────────────────────
        MeshGradientBackground(modifier = Modifier.fillMaxSize())

        // ── Form card ─────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.screenEdge),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 8. Pulsing brand logo ─────────────────────────────────────────
            PulsingLogo(isDark = isDark)

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isDark) {
                        AppColors.surfaceTonal1Dark.copy(alpha = 0.92f)
                    } else {
                        AppColors.surfaceTonal1Light.copy(alpha = 0.95f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Welcome back",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                    Text(
                        text = "Sign in to your account",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

                    // ── 7. AnimatedVisibility slide-down for ErrorBanner ──────
                    AnimatedVisibility(
                        visible = generalError != null,
                        enter = expandVertically() + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically() + fadeOut(animationSpec = tween(150))
                    ) {
                        Column {
                            ErrorBanner(
                                message = generalError ?: "",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
                        }
                    }

                    // ── 3. SurfaceFillTextField — Email ───────────────────────
                    SurfaceFillTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = "Email address",
                        placeholder = "user@example.com",
                        isError = fieldErrors.containsKey("email"),
                        supportingText = fieldErrors["email"],
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        contentDescriptionText = "Email address input field",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    // ── 3. SurfaceFillTextField — Password ────────────────────
                    SurfaceFillTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        isError = fieldErrors.containsKey("password"),
                        supportingText = fieldErrors["password"],
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
                                    contentDescription = null
                                )
                            }
                        },
                        contentDescriptionText = "Password input field",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

                    // ── 4 + 5. Gradient button with Crossfade loading state ───
                    GradientSignInButton(
                        isLoading = isLoading,
                        onClick = { onLogin(email, password) },
                        gradientStart = gradientStart,
                        gradientEnd = gradientEnd,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    // ── 6. Google sign-in with outlined token-based styling ────
                    GoogleSignInButton(
                        googleWebClientId = googleWebClientId,
                        enabled = !isLoading,
                        onTokenReceived = onGoogleSignIn
                    )

                    if (isBiometricAvailable) {
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
                        IconButton(
                            onClick = onBiometricLogin,
                            modifier = Modifier
                                .size(MaterialTheme.spacing.xxl)
                                .semantics { contentDescription = "Login with biometrics" }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(MaterialTheme.spacing.lg)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    TextButton(
                        onClick = onNavigateToRegister,
                        modifier = Modifier.semantics { contentDescription = "Create a new account" }
                    ) {
                        Text("Don't have an account? Create Account")
                    }
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

// ── 8. Pulsing brand logo ─────────────────────────────────────────────────────

/**
 * Brand logo that pulses between [LOGO_PULSE_MIN] and 1.0 scale on an infinite
 * repeatable animation.  Respects [LocalReducedMotionEnabled] — static at 1.0
 * when reduced motion is active.
 */
@Composable
private fun PulsingLogo(isDark: Boolean) {
    val reducedMotion = LocalReducedMotionEnabled.current

    val scale: Float = if (reducedMotion) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "logoSpin")
        val animated by infiniteTransition.animateFloat(
            initialValue = LOGO_PULSE_MIN,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "logoPulse"
        )
        animated
    }

    val glowColor = if (isDark) AppColors.accentGlowDark else AppColors.accentGlowLight
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight

    Box(
        modifier = Modifier
            .size(LOGO_SIZE)
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(listOf(gradientStart, gradientEnd))
            )
            .semantics { contentDescription = "AI Assistant brand logo" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

// ── 4 + 5. Gradient fill Sign-In button with Crossfade ───────────────────────

/**
 * Full-width Sign-In button with a [Brush.linearGradient] fill and a [Crossfade]
 * that swaps between the label text and a [CircularProgressIndicator] while loading.
 *
 * Built as a [Box] over a transparent [androidx.compose.material3.Button] so the
 * M3 ripple, touch target, and disabled-state alpha all work out-of-the-box.
 */
@Composable
private fun GradientSignInButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    gradientStart: androidx.compose.ui.graphics.Color,
    gradientEnd: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .clip(ButtonDefaults.shape)
            .background(
                brush = Brush.linearGradient(listOf(gradientStart, gradientEnd)),
                alpha = if (isLoading) 0.6f else 1f
            ),
        contentAlignment = Alignment.Center
    ) {
        // Transparent button on top to provide ripple + click + accessibility
        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = !isLoading,
            modifier = Modifier.matchParentSize(),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            // ── 5. Crossfade between label and spinner ───────────────────────
            Crossfade(
                targetState = isLoading,
                animationSpec = tween(durationMillis = 200),
                label = "signInButtonContent"
            ) { loading ->
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Signing in, please wait" },
                        color = androidx.compose.ui.graphics.Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Sign In",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

// ── 6. Google Sign-In button ─────────────────────────────────────────────────

/**
 * Outlined Google Sign-In button using the new design tokens.
 *
 * Uses [OutlinedButton] with an explicit outline colour from
 * [MaterialTheme.colorScheme.outline] and a surfaceTonal1 container tint so it
 * reads clearly against the gradient background.
 */
@Composable
fun GoogleSignInButton(googleWebClientId: String, enabled: Boolean, onTokenReceived: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSigningIn by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light

    OutlinedButton(
        onClick = {
            if (googleWebClientId.isEmpty()) return@OutlinedButton
            isSigningIn = true
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(context)
                    val googleIdOption = GetSignInWithGoogleOption
                        .Builder(googleWebClientId)
                        .build()
                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(googleIdOption)
                        .build()
                    val result = credentialManager.getCredential(request = request, context = context)
                    val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                    onTokenReceived(googleCredential.idToken)
                } catch (_: GetCredentialCancellationException) {
                    // User dismissed — no error to show
                } catch (_: GetCredentialException) {
                    // Credential Manager error — caller handles retries
                } finally {
                    isSigningIn = false
                }
            }
        },
        enabled = enabled && !isSigningIn && googleWebClientId.isNotEmpty(),
        modifier = Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT)
            .semantics { contentDescription = "Sign in with Google button" },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Crossfade(
                targetState = isSigningIn,
                animationSpec = tween(200),
                label = "googleButtonContent"
            ) { signing ->
                if (signing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = "Sign in with Google")
                }
            }
        }
    }
}
