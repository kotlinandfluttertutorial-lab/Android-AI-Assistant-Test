/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/Haptics.kt
 * Purpose    : Haptic feedback helpers (Phase 11.9).
 *
 *              Provides a single [HapticHelper] composable helper that wraps
 *              [LocalHapticFeedback] so feature composables only need one
 *              import.  Respects [LocalReducedMotionEnabled] — when reduced
 *              motion is active, all haptic feedback is suppressed.
 *
 * Architecture Layer : Core-UI — motion system.
 * Requirements       : 27.1 (haptic feedback)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Returns a [HapticHelper] scoped to the current composition.
 *
 * Usage:
 * ```kotlin
 * val haptic = rememberHapticHelper()
 * Modifier.combinedClickable(onLongClick = {
 *     haptic.longPress()
 *     showMenu()
 * })
 * ```
 */
@Composable
fun rememberHapticHelper(): HapticHelper {
    val haptic = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotionEnabled.current
    return remember(haptic, reducedMotion) { HapticHelper(haptic, reducedMotion) }
}

/**
 * Thin wrapper around [HapticFeedback] that suppresses feedback when
 * [reducedMotion] is `true`.
 */
class HapticHelper(
    private val haptic: HapticFeedback,
    private val reducedMotion: Boolean
) {
    /** Haptic for long-press gestures (context menus, swipe reveal). */
    fun longPress() {
        if (!reducedMotion) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Haptic confirming a successful action (send message, copy confirmed).
     * Uses [HapticFeedbackType.TextHandleMove] as the closest available
     * "confirm" type in the standard Compose API.
     */
    fun confirm() {
        if (!reducedMotion) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Haptic for text selection handle movement. */
    fun textHandleMove() {
        if (!reducedMotion) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}
