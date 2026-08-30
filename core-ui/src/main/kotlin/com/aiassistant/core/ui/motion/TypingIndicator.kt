/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/TypingIndicator.kt
 * Purpose    : Three-dot stagger animation shown in ChatDetailScreen while
 *              waiting for the first streaming token from the AI.  Hidden
 *              immediately when the first token arrives.
 *
 * Architecture Layer : Core-UI — shared composable, motion system.
 *                      Consumed by feature-chat ChatDetailScreen and
 *                      feature-on-device-rag OnDeviceRagChatScreen.
 *                      Never imported from domain or data layers.
 *
 * Dependencies       : Compose animation, core-ui ReducedMotion.
 *
 * Design Decision    : Three dots animate with a 120 ms stagger so the
 *                      indicator reads as a continuous "thinking" motion
 *                      rather than three independent pulses.  Each dot
 *                      scales between 0.4 and 1.0 on a repeating
 *                      InfiniteTransition.  When reducedMotion is true all
 *                      dots are shown at full scale without animation —
 *                      the indicator is still visible so the user knows the
 *                      AI is processing, but no motion occurs.
 *
 * Requirements       : 2.2 (typing indicator), 24.3 (motion principles)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Duration of one complete dot pulse cycle. */
private const val DOT_CYCLE_MS = 600

/** Delay between each dot's animation start (stagger). */
private const val DOT_STAGGER_MS = 120

/** Minimum scale applied to each dot at the bottom of the pulse. */
private const val DOT_MIN_SCALE = 0.4f

/**
 * Three-dot typing indicator composable.
 *
 * Shows an animated sequence of three dots that pulse in a staggered wave
 * pattern while the AI is generating a response.  Hidden by the caller
 * (via [androidx.compose.animation.AnimatedVisibility]) when the first
 * streaming token arrives.
 *
 * Respects [LocalReducedMotionEnabled] — when reduced motion is active the
 * dots are rendered statically at full size.
 *
 * @param modifier      Modifier applied to the [Row] container.
 * @param dotColor      Color of each dot (defaults to [MaterialTheme.colorScheme.onSurfaceVariant]).
 * @param dotSize       Diameter of each dot (default 8 dp).
 * @param dotSpacing    Spacing between dots (default 4 dp).
 */
@Composable
fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dotSize: Dp = 8.dp,
    dotSpacing: Dp = 4.dp,
) {
    val reducedMotion = LocalReducedMotionEnabled.current

    Row(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .semantics { contentDescription = "AI is thinking" },
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            TypingDot(
                delayMs = if (reducedMotion) 0 else index * DOT_STAGGER_MS,
                reducedMotion = reducedMotion,
                color = dotColor,
                size = dotSize,
            )
        }
    }
}

@Composable
private fun TypingDot(
    delayMs: Int,
    reducedMotion: Boolean,
    color: Color,
    size: Dp,
) {
    val scale: Float = if (reducedMotion) {
        1f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "typingDot")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = DOT_MIN_SCALE,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = DOT_CYCLE_MS,
                    delayMillis = delayMs,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotScale_$delayMs",
        )
        animatedScale
    }

    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .background(color = color, shape = CircleShape),
    )
}
