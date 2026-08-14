/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : BiometricHelperViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the BiometricHelper feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : MVVM ViewModel
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
 * File       : BiometricHelperViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the BiometricHelper feature
 *
 * Architecture Layer : Feature (feature-auth)
 * Pattern Used       : MVVM ViewModel
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
 * BiometricHelperViewModel.kt
 *
 * Purpose: Thin ViewModel used exclusively to bridge the Hilt DI graph into composables
 *          that need access to [BiometricAuthManager] without coupling composable functions
 *          directly to the DI framework.
 * Architecture: feature-auth â€” presentation helper; no UI logic.
 * Dependencies: core-security (BiometricAuthManager)
 *
 * Design decisions:
 * - A dedicated ViewModel is used instead of a Hilt EntryPoint because `hiltViewModel()`
 *   is the idiomatic Compose way to access Hilt-provided instances without creating
 *   an EntryPoint interface in the app module.
 * - Exposes [biometricAuthManager] as a public val so composables can call
 *   `hiltViewModel<BiometricHelperViewModel>().biometricAuthManager` and get the
 *   singleton instance already in the DI graph.
 *
 * Requirements: 1.7
 */
package com.aiassistant.feature.auth

import androidx.lifecycle.ViewModel
import com.aiassistant.core.security.BiometricAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Hilt ViewModel used to surface [BiometricAuthManager] to composables via [hiltViewModel].
 */
@HiltViewModel
class BiometricHelperViewModel @Inject constructor(val biometricAuthManager: BiometricAuthManager) : ViewModel()
