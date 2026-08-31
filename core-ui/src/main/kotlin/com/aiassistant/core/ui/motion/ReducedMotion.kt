/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/ReducedMotion.kt
 * Purpose    : Composition local that signals whether the user has enabled
 *              "Remove animations" in Android Accessibility settings.  Every
 *              animated composable in the app must read this local and skip or
 *              shorten its animation when it is true.
 *
 * Architecture Layer : Core-UI — motion system foundation.
 *                      Read by MeshGradientBackground, AppTransition,
 *                      TypingIndicator, and the pressScale modifier.
 *                      Never referenced from domain or data layers.
 *
 * Dependencies       : Compose runtime, Android AccessibilityManager.
 *
 * Design Decision    : A composition local is used instead of passing a Boolean
 *                      parameter through every composable because the reduced-
 *                      motion preference is a cross-cutting concern.  Providing
 *                      it once at AppTheme level means all descendant composables
 *                      opt-in automatically without changing their signatures.
 *                      The value is derived from
 *                      AccessibilityManager.isAnimationEnabled (API 26+) which
 *                      reflects both the "Remove animations" toggle and the
 *                      global "Transition animation scale = 0" developer option.
 *
 * Requirements       : 24.3 (motion principles — respect LocalReducedMotionEnabled)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

/**
 * Composition local providing whether the user has requested reduced motion.
 *
 * Provided by [com.aiassistant.core.ui.AppTheme].  Defaults to `false`
 * (animations enabled) so Compose Preview and tests render normally without
 * a real [AccessibilityManager].
 *
 * Usage:
 * ```kotlin
 * val reducedMotion = LocalReducedMotionEnabled.current
 * val duration = if (reducedMotion) 0 else 300
 * ```
 */
val LocalReducedMotionEnabled = compositionLocalOf { false }

/**
 * Reads the current reduced-motion preference from [AccessibilityManager] and
 * returns it as a [Boolean].
 *
 * Call this inside [AppTheme] to derive the value for [LocalReducedMotionEnabled].
 * The [AccessibilityManager.isAnimationEnabled] flag is `true` when animations
 * are allowed, so we invert it to get "reduced motion requested".
 */
@Composable
@ReadOnlyComposable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
        as? AccessibilityManager

    // Prefer AccessibilityManager.isAnimationEnabled when available at runtime.
    // Use reflection to avoid compile-time reference to newer API surfaces.
    am?.let {
        try {
            val method = AccessibilityManager::class.java.getMethod("isAnimationEnabled")
            val enabled = method.invoke(it) as? Boolean
            if (enabled != null) return enabled == false
        } catch (e: NoSuchMethodException) {
            // Not available on this API level — fall back below.
        } catch (_: Exception) {
            // Reflection failed for some reason — fall back below.
        }
    }

    // Fallback: treat animator duration scale == 0 as reduced-motion requested.
    val scale = try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    } catch (_: Exception) {
        1f
    }
    return scale == 0f
}
