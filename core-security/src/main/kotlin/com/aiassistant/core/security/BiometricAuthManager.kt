/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-security
 * File       : BiometricAuthManager.kt
 * Purpose    : BiometricAuthManager — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
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
 * Module     : core-security
 * File       : BiometricAuthManager.kt
 * Purpose    : BiometricAuthManager — core-security module component
 *
 * Architecture Layer : Core-Security
 * Pattern Used       : Kotlin Class
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
 * BiometricAuthManager.kt â€” core-security module
 *
 * Manages device biometric authentication using AndroidX Biometric library.
 * No biometric data is captured, stored, or transmitted by this component â€”
 * it only orchestrates the system biometric prompt and reports the outcome.
 *
 * Requirements:
 *   1.7 â€” WHEN a User enables biometric authentication, THE AI_Assistant SHALL
 *         use the device biometric prompt to unlock the local session WITHOUT
 *         transmitting biometric data to the Backend.
 */
package com.aiassistant.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for device biometric authentication.
 *
 * Implementations must guarantee that no biometric template, scan, or
 * derived data is transmitted to any server or persisted beyond the scope
 * of the authentication prompt.
 */
interface BiometricAuthManager {

    /**
     * Returns true if the device supports and has enrolled strong biometrics
     * (Class 3 / BIOMETRIC_STRONG) or device credentials as a fallback.
     *
     * @param context any Context (Application or Activity)
     */
    fun isBiometricAvailable(context: Context): Boolean

    /**
     * Launches the system biometric prompt on [activity].
     *
     * The outcome is reported exclusively through the callbacks:
     * - [onSuccess] is invoked when authentication succeeds.
     * - [onError] is invoked with an error code and human-readable message
     *   when authentication cannot be completed (cancelled, lockout, etc.).
     *
     * Biometric data never leaves the device; this method only requests the
     * OS to perform on-device verification.
     *
     * @param activity the FragmentActivity hosting the prompt
     * @param onSuccess callback invoked on successful authentication
     * @param onError   callback invoked on failure; receives (errorCode, message)
     */
    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (Int, String) -> Unit)
}

/**
 * Production implementation of [BiometricAuthManager] using [BiometricPrompt].
 *
 * Authentication is handled entirely by the Android OS biometric subsystem.
 * This class only receives a Boolean outcome â€” no raw biometric data.
 */
@Singleton
class BiometricAuthManagerImpl @Inject constructor() : BiometricAuthManager {

    override fun isBiometricAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val result = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    override fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onError: (Int, String) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // result.cryptoObject is intentionally not captured or forwarded
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                // A single failed attempt â€” the prompt stays open; no callback here
                // because the OS will call onAuthenticationError on final lockout.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_prompt_title))
            .setSubtitle(activity.getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }
}
