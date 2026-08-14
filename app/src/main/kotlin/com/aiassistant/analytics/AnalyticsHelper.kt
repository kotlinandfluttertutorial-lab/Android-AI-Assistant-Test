/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : AnalyticsHelper.kt
 * Purpose    : Thin wrapper around FirebaseAnalytics that provides a Compose-friendly
 *          `trackScreenView` composable and an imperative `logScreenView` function.
 *          Keeping all Firebase Analytics calls centralised here makes it easy to
 *          swap or extend the analytics implementation without touching each screen.
 *
 * Usage in Composables:
 *   trackScreenView(screenName = "Login", screenClass = "LoginScreen")
 *
 * Requirements: 18.6
 * ============================================================
 */
package com.aiassistant.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent

/**
 * Stateless composable that logs a `screen_view` event to Firebase Analytics when it
 * enters the composition. The event is logged exactly once per composition entry â€” it
 * does not re-fire on recompositions.
 *
 * @param screenName  Human-readable name shown in the Firebase console (e.g. "Login").
 * @param screenClass Simple class name of the primary Composable (e.g. "LoginScreen").
 */
@Composable
fun trackScreenView(screenName: String, screenClass: String) {
    val context = LocalContext.current
    DisposableEffect(screenName, screenClass) {
        val analytics = FirebaseAnalytics.getInstance(context)
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
            param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            param(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        }
        onDispose { /* nothing to tear down */ }
    }
}
