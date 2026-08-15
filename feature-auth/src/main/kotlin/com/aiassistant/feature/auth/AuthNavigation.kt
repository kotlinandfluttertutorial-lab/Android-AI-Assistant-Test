package com.aiassistant.feature.auth

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import com.aiassistant.core.security.BiometricAuthManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * AuthNavigation.kt
 *
 * Purpose: Navigation graph for the authentication flow — Splash, Onboarding, Login, Register.
 * The Login screen includes full Google OAuth2 sign-in via play-services-auth (Requirement 1.6).
 *
 * Requirements: 1.1, 1.6, 1.7, 16.3, 17.1
 */

object AuthRoute {
    const val GRAPH = "auth"
    const val SPLASH = "auth/splash"
    const val ONBOARDING = "auth/onboarding"
    const val LOGIN = "auth/login"
    const val REGISTER = "auth/register"
}

fun NavGraphBuilder.authNavGraph(navController: NavHostController, onAuthSuccess: () -> Unit) {
    navigation(
        startDestination = AuthRoute.SPLASH,
        route = AuthRoute.GRAPH
    ) {
        // -- Splash --
        composable(route = AuthRoute.SPLASH) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(Unit) {
                viewModel.checkInitialState()
            }

            val isAuthenticated = uiState is AuthUiState.Authenticated

            SplashScreen(
                isAuthenticated = isAuthenticated,
                onInitComplete = { authenticated ->
                    when {
                        authenticated -> onAuthSuccess()
                        uiState is AuthUiState.OnboardingRequired -> {
                            navController.navigate(AuthRoute.ONBOARDING) {
                                popUpTo(AuthRoute.SPLASH) { inclusive = true }
                            }
                        }
                        else -> {
                            navController.navigate(AuthRoute.LOGIN) {
                                popUpTo(AuthRoute.SPLASH) { inclusive = true }
                            }
                        }
                    }
                }
            )

            LaunchedEffect(uiState) {
                when (uiState) {
                    is AuthUiState.Authenticated -> onAuthSuccess()
                    is AuthUiState.OnboardingRequired -> {
                        navController.navigate(AuthRoute.ONBOARDING) {
                            popUpTo(AuthRoute.SPLASH) { inclusive = true }
                        }
                    }
                    is AuthUiState.Idle -> {
                        navController.navigate(AuthRoute.LOGIN) {
                            popUpTo(AuthRoute.SPLASH) { inclusive = true }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // -- Onboarding --
        composable(route = AuthRoute.ONBOARDING) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(uiState) {
                when (uiState) {
                    is AuthUiState.Idle -> {
                        navController.navigate(AuthRoute.LOGIN) {
                            popUpTo(AuthRoute.ONBOARDING) { inclusive = true }
                        }
                    }
                    is AuthUiState.Authenticated -> onAuthSuccess()
                    else -> Unit
                }
            }

            OnboardingScreen(
                onConsentGiven = { viewModel.completeOnboarding() },
                onDecline = {
                    navController.navigate(AuthRoute.LOGIN) {
                        popUpTo(AuthRoute.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        // -- Login --
        composable(route = AuthRoute.LOGIN) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()
            val context = LocalContext.current

            val biometricManager: BiometricAuthManager = rememberBiometricAuthManager()
            val isBiometricAvailable = biometricManager.isBiometricAvailable(context)

            // Google Sign-In result launcher.
            // When the user completes (or cancels) the Google account picker, the result
            // lands here. A valid ID token is forwarded to AuthViewModel.loginWithGoogle()
            // which POSTs it to POST /auth/google on the backend (Requirement 1.6).
            val googleSignInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account?.idToken
                    if (idToken != null) {
                        viewModel.loginWithGoogle(idToken)
                    } else {
                        viewModel.onBiometricError(-1, "Google Sign-In failed: no ID token received.")
                    }
                } catch (e: ApiException) {
                    viewModel.onBiometricError(e.statusCode, "Google Sign-In failed: ${e.message}")
                }
            }

            LaunchedEffect(uiState) {
                when (uiState) {
                    is AuthUiState.Authenticated -> onAuthSuccess()
                    is AuthUiState.BiometricPromptRequired -> {
                        val activity = context.findFragmentActivity()
                        if (activity != null) {
                            biometricManager.authenticate(
                                activity = activity,
                                onSuccess = { viewModel.onBiometricSuccess() },
                                onError = { code, msg -> viewModel.onBiometricError(code, msg) }
                            )
                        }
                    }
                    else -> Unit
                }
            }

            LoginScreen(
                uiState = uiState,
                onLogin = { email, password -> viewModel.login(email, password) },
                onNavigateToRegister = {
                    navController.navigate(AuthRoute.REGISTER)
                },
                onGoogleSignIn = {
                    // Build GoogleSignInOptions requesting an ID token.
                    // default_web_client_id is generated by google-services.json via the
                    // google-services plugin and placed in app/build/generated/res/google-services/
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestIdToken("106071012091-d4brm5cng1gaor0al51veafjd0fa239v.apps.googleusercontent.com")
                        .requestEmail()
                        .build()
                    val googleSignInClient = GoogleSignIn.getClient(context, gso)
                    // Force account picker on every sign-in so the user can switch accounts.
                    googleSignInClient.signOut().addOnCompleteListener {
                        googleSignInLauncher.launch(googleSignInClient.signInIntent)
                    }
                },
                onBiometricLogin = { viewModel.triggerBiometric() },
                isBiometricAvailable = isBiometricAvailable
            )
        }

        // -- Register --
        composable(route = AuthRoute.REGISTER) {
            val viewModel: AuthViewModel = hiltViewModel()
            val uiState by viewModel.uiState.collectAsState()

            LaunchedEffect(uiState) {
                if (uiState is AuthUiState.Authenticated) {
                    onAuthSuccess()
                }
            }

            RegisterScreen(
                uiState = uiState,
                onRegister = { email, password -> viewModel.register(email, password) },
                onNavigateToLogin = { navController.popBackStack() }
            )
        }
    }
}

// -- Helpers --

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun rememberBiometricAuthManager(): BiometricAuthManager {
    val vm: BiometricHelperViewModel = hiltViewModel()
    return vm.biometricAuthManager
}
