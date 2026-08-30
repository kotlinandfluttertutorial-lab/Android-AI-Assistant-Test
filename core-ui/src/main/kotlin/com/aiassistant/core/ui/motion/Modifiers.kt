/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/Modifiers.kt
 * Purpose    : Reusable Modifier extensions for the app motion system.
 *              Currently provides `pressScale` — a tactile scale-down
 *              animation applied to all tappable cards and buttons to give
 *              physical feedback on press.
 *
 * Architecture Layer : Core-UI — motion system / shared composable utilities.
 *                      Applied to FeatureCard, TicketCard, ChatBubble, and
 *                      any other tappable surface in feature modules.
 *                      Never referenced from domain or data layers.
 *
 * Dependencies       : Compose animation, Compose foundation (interactionSource),
 *                      core-ui ReducedMotion.
 *
 * Design Decision    : The scale factor (0.97) is deliberately subtle — it gives
 *                      tactile feedback without making the UI feel "wobbly".
 *                      The animation uses spring() rather than tween() so the
 *                      release bounces naturally back to 1.0 without a jarring
 *                      cut.  When reduced motion is active the modifier is a
 *                      no-op (scale stays at 1.0 at all times).
 *
 * Requirements       : 24.3 (motion principles — pressScale on all tappable cards)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale

/** Scale factor applied while the component is pressed. */
private const val PRESS_SCALE_FACTOR = 0.97f

/**
 * Applies a subtle scale-down animation on press to give tappable surfaces
 * a tactile "click" feeling.
 *
 * The composable reads [LocalReducedMotionEnabled] and becomes a no-op when
 * reduced motion is active so the animation is never shown to users who have
 * disabled animations in accessibility settings.
 *
 * Usage:
 * ```kotlin
 * Card(
 *     onClick = { ... },
 *     modifier = Modifier.pressScale(),
 * )
 * ```
 *
 * @param interactionSource  Optional [MutableInteractionSource]; a new one is
 *                           created automatically when not provided.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val reducedMotion = LocalReducedMotionEnabled.current
    if (reducedMotion) return@composed this

    val source = interactionSource ?: remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) PRESS_SCALE_FACTOR else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "pressScale",
    )

    this.scale(scale)
}
