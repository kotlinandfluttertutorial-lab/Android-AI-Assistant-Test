/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Animation.kt
 * Purpose    : Centralized animation specification tokens for the AI Assistant
 *              design system.  Provides named duration constants, easing curves,
 *              and reusable AnimationSpec factories so that all motion across
 *              the app shares the same rhythm and feel.
 *
 * Architecture Layer : Core-UI — design system foundation.
 *                      Imported by all feature composables that need animation.
 *                      Never referenced from domain or data layers.
 *
 * Dependencies       : Compose animation (core, spring, tween, keyframes).
 *
 * Design Decision    : Rather than scattering tween(300) calls across composables,
 *                      all timing and easing decisions live here.  Feature code
 *                      calls AppAnimation.enterSpec() etc., making a global
 *                      timing change a one-line edit.
 *                      The AppTransition.kt file (navigation transitions) is kept
 *                      separate since it is imported by the NavHost which may not
 *                      have the full composition tree — but it reads durations from
 *                      this file so the values stay in sync.
 *
 * Reduced Motion     : Every spec factory accepts a [reducedMotion] boolean.
 *                      When true, duration collapses to 0 ms (instant).
 *                      Callers read LocalReducedMotionEnabled and pass it in.
 *
 * Requirements       : 24.3 (motion principles), 23.1 (accessibility reduced motion)
 * ============================================================
 */
package com.aiassistant.core.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

// ── Duration constants ────────────────────────────────────────────────────────
// Named durations avoid magic numbers and communicate intent at the call site.

/** 100 ms — micro-interactions: icon taps, ripples, immediate feedback. */
const val DURATION_MICRO  = 100

/** 150 ms — quick state transitions: button press, chip select. */
const val DURATION_QUICK  = 150

/** 200 ms — standard state transitions: tab switch, fade-through. */
const val DURATION_SHORT  = 200

/** 300 ms — standard navigation: slide push/pop, screen-level fade. */
const val DURATION_MEDIUM = 300

/** 400 ms — theme color crossfade, large layout changes. */
const val DURATION_LONG   = 400

/** 600 ms — skeleton shimmer cycle, onboarding transitions. */
const val DURATION_EXTRA  = 600

// ── Easing aliases ────────────────────────────────────────────────────────────
// Aliases to M3-recommended easings so composables don't need direct imports.

/** Standard ease: fast out / slow in — used for entering elements. */
val EasingEnter: Easing = LinearOutSlowInEasing

/** Standard ease: fast out / linear in — used for exiting elements. */
val EasingExit: Easing = FastOutLinearInEasing

/** Emphasized ease: fast out / slow in — used for shared element and emphasis. */
val EasingEmphasized: Easing = FastOutSlowInEasing

// ── Spec factories ────────────────────────────────────────────────────────────

/**
 * Tween spec for entering elements (e.g., [AnimatedVisibility] enter).
 * Duration: [DURATION_MEDIUM] (300 ms). Easing: [EasingEnter].
 *
 * @param reducedMotion Collapses duration to 0 ms when `true`.
 */
fun <T> enterSpec(reducedMotion: Boolean = false): TweenSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_MEDIUM, easing = EasingEnter)

/**
 * Tween spec for exiting elements.
 * Duration: [DURATION_SHORT] (200 ms). Easing: [EasingExit].
 *
 * @param reducedMotion Collapses duration to 0 ms when `true`.
 */
fun <T> exitSpec(reducedMotion: Boolean = false): TweenSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_SHORT, easing = EasingExit)

/**
 * Tween spec for quick UI state changes (button enable/disable, color transitions).
 * Duration: [DURATION_QUICK] (150 ms). Easing: [EasingEmphasized].
 *
 * @param reducedMotion Collapses duration to 0 ms when `true`.
 */
fun <T> stateSpec(reducedMotion: Boolean = false): TweenSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_QUICK, easing = EasingEmphasized)

/**
 * Tween spec for theme color crossfade.
 * Duration: [DURATION_LONG] (400 ms). Easing: [EasingEmphasized].
 *
 * @param reducedMotion Collapses duration to 0 ms when `true`.
 */
fun <T> themeSpec(reducedMotion: Boolean = false): TweenSpec<T> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_LONG, easing = EasingEmphasized)

/**
 * Spring spec for bouncy interactions (send button scale, swipe-to-dismiss snap).
 * Uses [Spring.DampingRatioMediumBouncy] for a subtle but satisfying feel.
 *
 * @param reducedMotion Returns a critically damped spring (no overshoot) when `true`.
 */
fun <T> bounceSpec(reducedMotion: Boolean = false): SpringSpec<T> = spring(
    dampingRatio = if (reducedMotion) Spring.DampingRatioNoBouncy else Spring.DampingRatioMediumBouncy,
    stiffness    = Spring.StiffnessMedium
)

/**
 * Spring spec for snapping layout changes (content size, drawer width).
 * Critically damped — no overshoot, fast settle.
 */
fun <T> snapSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness    = Spring.StiffnessHigh
)

// ── IntOffset specs (for slide animations) ────────────────────────────────────

/** Enter slide tween spec (IntOffset). */
fun enterOffsetSpec(reducedMotion: Boolean = false): FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_MEDIUM, easing = EasingEnter)

/** Exit slide tween spec (IntOffset). */
fun exitOffsetSpec(reducedMotion: Boolean = false): FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_SHORT, easing = EasingExit)

// ── IntSize specs (for animateContentSize) ────────────────────────────────────

/** Content size change tween spec. */
fun contentSizeSpec(reducedMotion: Boolean = false): FiniteAnimationSpec<IntSize> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_MEDIUM, easing = EasingEmphasized)

// ── Dp specs (for size/offset Dp animations) ─────────────────────────────────

/** Dp value transition spec (e.g., corner radius morphing). */
fun dpSpec(reducedMotion: Boolean = false): FiniteAnimationSpec<Dp> =
    tween(durationMillis = if (reducedMotion) 0 else DURATION_QUICK, easing = EasingEmphasized)

// ── Shimmer ───────────────────────────────────────────────────────────────────

/**
 * Duration of one shimmer sweep cycle (ms).
 * Used by shimmer loading skeleton composables.
 */
const val SHIMMER_DURATION_MS = 1200

/**
 * Duration of the streaming cursor blink period (ms).
 * The cursor is visible for half this duration, invisible for the other half.
 */
const val CURSOR_BLINK_MS = 800

/**
 * Duration the copy-confirmed checkmark icon is displayed before reverting (ms).
 */
const val COPY_CONFIRM_DURATION_MS = 1500

/**
 * Delay before the "Back online" connectivity banner auto-dismisses (ms).
 */
const val ONLINE_BANNER_AUTO_DISMISS_MS = 3000
