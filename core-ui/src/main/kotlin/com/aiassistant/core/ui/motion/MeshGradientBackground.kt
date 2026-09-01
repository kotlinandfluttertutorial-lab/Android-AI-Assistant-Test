/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : motion/MeshGradientBackground.kt
 * Purpose    : Animated full-screen gradient background used on the Login
 *              screen and optionally on the Home Dashboard hero card.
 *              Produces a softly shifting blue → purple gradient that
 *              communicates the app's AI identity without distracting from
 *              content.
 *
 * Architecture Layer : Core-UI — shared composable, motion system.
 *                      Used by feature-auth LoginScreen and app HomeDashboard.
 *                      Never imported from domain or data layers.
 *
 * Dependencies       : Compose animation, Compose foundation, AppColors.
 *
 * Design Decision    : A true mesh gradient (multi-point interpolation) is not
 *                      available in Compose 1.x without a custom shader.  This
 *                      implementation approximates the effect using two
 *                      `RadialGradient` brushes whose center points animate
 *                      along slow Lissajous paths.  The motion is imperceptible
 *                      at normal glance speed (~8 s period) but adds depth.
 *
 *                      Reduced-motion fallback: when LocalReducedMotionEnabled
 *                      is true the gradients are drawn at their initial positions
 *                      with no animation — the visual result is a static two-tone
 *                      gradient that still conveys brand identity.
 *
 *                      Dark-mode aware: gradient colors are selected from
 *                      AppColors.gradientStart/End light or dark variants based
 *                      on the current [isSystemInDarkTheme] value.
 *
 * Requirements       : 24.1 (dark-first), 24.3 (motion principles)
 * ============================================================
 */
package com.aiassistant.core.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aiassistant.core.ui.AppColors
import kotlin.math.cos
import kotlin.math.sin

/** Period of the slow gradient shift animation (milliseconds). */
private const val GRADIENT_PERIOD_MS = 8_000

/**
 * Animated full-screen gradient background.
 *
 * Draws two overlapping radial gradients whose centers shift slowly over time
 * to produce a soft, living "mesh gradient" effect.  On reduced motion the
 * centers are fixed and the canvas is drawn once.
 *
 * @param modifier    Applied to the underlying [Canvas]. Defaults to [fillMaxSize].
 * @param isDark      Override for dark/light variant selection (defaults to system).
 */
@Composable
fun MeshGradientBackground(modifier: Modifier = Modifier.fillMaxSize(), isDark: Boolean = isSystemInDarkTheme()) {
    val reducedMotion = LocalReducedMotionEnabled.current

    val startColor = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val endColor = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight

    // ── Animate center offsets (Lissajous path) ───────────────────────────────
    val phase1: Float
    val phase2: Float

    if (reducedMotion) {
        phase1 = 0f
        phase2 = 0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "meshGradient")
        val p1 by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = GRADIENT_PERIOD_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "gradientPhase1"
        )
        val p2 by infiniteTransition.animateFloat(
            initialValue = (Math.PI / 2).toFloat(),
            targetValue = ((2 * Math.PI) + Math.PI / 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = GRADIENT_PERIOD_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "gradientPhase2"
        )
        phase1 = p1
        phase2 = p2
    }

    Canvas(
        modifier = modifier.semantics { contentDescription = "Animated gradient background" }
    ) {
        val w = size.width
        val h = size.height

        // Center 1: traces a slow ellipse in the upper-left quadrant
        val cx1 = w * (0.3f + 0.2f * sin(phase1))
        val cy1 = h * (0.2f + 0.15f * cos(phase1 * 0.7f))

        // Center 2: traces a complementary ellipse in the lower-right quadrant
        val cx2 = w * (0.7f + 0.2f * cos(phase2))
        val cy2 = h * (0.75f + 0.15f * sin(phase2 * 0.5f))

        // Draw base gradient (fills the whole canvas)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    startColor.copy(alpha = 0.9f),
                    endColor.copy(alpha = 0.8f)
                )
            )
        )

        // Overlay radial 1 (primary brand blue / warm glow)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(startColor.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(cx1, cy1),
                radius = w * 0.7f,
                tileMode = TileMode.Clamp
            ),
            center = Offset(cx1, cy1),
            radius = w * 0.7f
        )

        // Overlay radial 2 (brand purple / tertiary hue)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(endColor.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(cx2, cy2),
                radius = w * 0.6f,
                tileMode = TileMode.Clamp
            ),
            center = Offset(cx2, cy2),
            radius = w * 0.6f
        )
    }
}
