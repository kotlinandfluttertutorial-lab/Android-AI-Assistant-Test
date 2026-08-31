/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/AppTransition.kt
 * Purpose    : Navigation transition specs for the app's NavHost.  Provides
 *              enter/exit/popEnter/popExit animations for the two transition
 *              patterns specified in the steering file:
 *                - Slide + fade push/pop  (300 ms) for standard navigation
 *                - Fade-through tab switch (200 ms) for bottom-nav tabs
 *              All animations respect LocalReducedMotionEnabled — when reduced
 *              motion is active all durations collapse to 0 ms.
 *
 * Architecture Layer : Core-UI — motion system.
 *                      Used by app/MainActivity.kt NavHost and by any feature
 *                      NavGraphBuilder that needs custom transitions.
 *                      Never imported from domain or data layers.
 *
 * Dependencies       : Compose animation, Compose navigation-compose,
 *                      core-ui ReducedMotion.
 *
 * Design Decision    : Transition specs are collected in one file so changing
 *                      the app-wide timing requires editing a single location.
 *                      Functions accept an explicit `reducedMotion: Boolean`
 *                      parameter instead of reading the composition local
 *                      directly so they can be called from
 *                      NavGraphBuilder.composable {} blocks where the
 *                      CompositionLocal may not yet be in scope.
 *
 * Requirements       : 24.3 (motion principles)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

// ── Timing constants ──────────────────────────────────────────────────────────

/** Duration for standard slide + fade push/pop navigation. */
const val NAV_TRANSITION_MS = 300

/** Duration for fade-through bottom-nav tab switches. */
const val TAB_TRANSITION_MS = 200

// ── Standard push / pop transitions (used by most NavGraph composables) ───────

/**
 * Enter transition when navigating forward — slide in from right + fade in.
 *
 * @param reducedMotion When `true`, returns [EnterTransition.None] (0 ms).
 */
fun enterSlideIn(reducedMotion: Boolean): EnterTransition = if (reducedMotion) {
    EnterTransition.None
} else {
    slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(NAV_TRANSITION_MS)
    ) + fadeIn(animationSpec = tween(NAV_TRANSITION_MS))
}

/**
 * Exit transition when navigating forward — slide out to left + fade out.
 *
 * @param reducedMotion When `true`, returns [ExitTransition.None].
 */
fun exitSlideOut(reducedMotion: Boolean): ExitTransition = if (reducedMotion) {
    ExitTransition.None
} else {
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / 3 },
        animationSpec = tween(NAV_TRANSITION_MS)
    ) + fadeOut(animationSpec = tween(NAV_TRANSITION_MS))
}

/**
 * Pop-enter transition when navigating back — slide in from left + fade in.
 *
 * @param reducedMotion When `true`, returns [EnterTransition.None].
 */
fun popEnterSlideIn(reducedMotion: Boolean): EnterTransition = if (reducedMotion) {
    EnterTransition.None
} else {
    slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 3 },
        animationSpec = tween(NAV_TRANSITION_MS)
    ) + fadeIn(animationSpec = tween(NAV_TRANSITION_MS))
}

/**
 * Pop-exit transition when navigating back — slide out to right + fade out.
 *
 * @param reducedMotion When `true`, returns [ExitTransition.None].
 */
fun popExitSlideOut(reducedMotion: Boolean): ExitTransition = if (reducedMotion) {
    ExitTransition.None
} else {
    slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(NAV_TRANSITION_MS)
    ) + fadeOut(animationSpec = tween(NAV_TRANSITION_MS))
}

// ── Tab fade-through transitions ──────────────────────────────────────────────

/**
 * Enter transition for bottom-nav tab switches — fade in.
 *
 * @param reducedMotion When `true`, returns [EnterTransition.None].
 */
fun enterFadeThrough(reducedMotion: Boolean): EnterTransition = if (reducedMotion) {
    EnterTransition.None
} else {
    fadeIn(animationSpec = tween(TAB_TRANSITION_MS))
}

/**
 * Exit transition for bottom-nav tab switches — fade out.
 *
 * @param reducedMotion When `true`, returns [ExitTransition.None].
 */
fun exitFadeThrough(reducedMotion: Boolean): ExitTransition = if (reducedMotion) {
    ExitTransition.None
} else {
    fadeOut(animationSpec = tween(TAB_TRANSITION_MS))
}

// ── SharedTransitionLayout instant spec ──────────────────────────────────────

/**
 * Duration for [SharedTransitionLayout] hero transitions (Home → Chat,
 * Profile → Memory).  Always 0 ms as specified in steering §50.9:
 * "SharedTransitionLayout: instant switch (0ms tween)."
 */
const val SHARED_ELEMENT_TRANSITION_MS = 0
