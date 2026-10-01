/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : SplashScreen.kt
 * Purpose    : Splash screen — Phase B UI update.
 *
 *              Aligned to Stitch design reference (SplashScreen.tsx):
 *              - 4-stop diagonal dark-purple gradient background
 *              - Glass-morphism rounded-square logo container (88 dp, r28)
 *              - Custom AI logo mark (SVG path drawn on Canvas)
 *              - App name 32 sp bold white, tagline 14 sp white/60%
 *              - 3-dot staggered bounce loading animation
 *              - "Powered by Gemini" footer label
 *              - 2 800 ms delay (matches Stitch setTimeout)
 *              - Reduced-motion: skip dot animation, instant display
 *
 * Architecture Layer : Feature (feature-auth) — Compose UI layer.
 *                      Callbacks only; no navigation logic inside.
 *
 * Preserved from prior version:
 *              - onInitComplete callback signature
 *              - isAuthenticated parameter
 *              - LaunchedEffect routing call
 *
 * Requirements       : 1.6, 28.3
 * ============================================================
 */
package com.aiassistant.feature.auth

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import kotlinx.coroutines.delay

// ── Constants ─────────────────────────────────────────────────────────────────

/** Matches Stitch: setTimeout(() => navigate('onboarding'), 2800) */
private const val SPLASH_DELAY_MS = 2_800L

private val LOGO_CONTAINER_SIZE = 88.dp
private val LOGO_CORNER_RADIUS  = 28.dp
private val DOT_SIZE            = 8.dp

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Full-screen splash composable shown during cold-start initialisation.
 *
 * After [SPLASH_DELAY_MS] calls [onInitComplete] with [isAuthenticated] so the
 * nav graph can route directly to Home or to the Login/Onboarding flow.
 *
 * @param isAuthenticated Whether a valid JWT was found in secure storage.
 * @param onInitComplete  Callback invoked after the splash delay.
 */
@Composable
fun SplashScreen(
    isAuthenticated: Boolean = false,
    onInitComplete: (isAuthenticated: Boolean) -> Unit,
) {
    // 4-stop diagonal gradient — exactly matches Stitch
    val backgroundBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to StitchColors.splashGradientStop0, // #1A0533
            0.40f to StitchColors.splashGradientStop1, // #381E72
            0.75f to StitchColors.splashGradientStop2, // #6750A4
            1.00f to StitchColors.splashGradientStop3, // #9C89C4
        ),
        start = Offset(0f, 0f),
        end   = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = backgroundBrush)
            .semantics { contentDescription = "AI Assistant loading" },
    ) {
        // ── Ambient orb blobs (non-semantic decorations) ──────────────────────
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.TopEnd)
                .blur(40.dp)
                .background(StitchColors.splashOrbLight, RoundedCornerShape(percent = 50)),
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.BottomStart)
                .blur(60.dp)
                .background(StitchColors.splashOrbDark, RoundedCornerShape(percent = 50)),
        )

        // ── Central content ───────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
        ) {
            // Glass-morphism logo container
            GlassLogoContainer(
                size = LOGO_CONTAINER_SIZE,
                cornerRadius = LOGO_CORNER_RADIUS,
            )

            Spacer(Modifier.height(20.dp))

            // App name
            Text(
                text = "AI Assistant",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            // Tagline
            Text(
                text = "Your intelligent companion",
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = Color.White.copy(alpha = 0.60f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(48.dp))

            // 3-dot staggered loading animation
            SplashLoadingDots()
        }

        // ── Footer label ──────────────────────────────────────────────────────
        Text(
            text = "Powered by Gemini",
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            ),
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }

    // ── Side-effect: navigate after delay ─────────────────────────────────────
    LaunchedEffect(Unit) {
        delay(SPLASH_DELAY_MS)
        onInitComplete(isAuthenticated)
    }
}

// ── Glass logo container ──────────────────────────────────────────────────────

/**
 * Rounded-square container with a glass-morphism appearance:
 * white/12% fill, white/20% border, subtle shadow via elevation.
 * Holds the [AiLogoMark] Canvas drawing.
 */
@Composable
private fun GlassLogoContainer(size: Dp, cornerRadius: Dp) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = shape,
            ),
    ) {
        // AI logo mark drawn on Canvas — matches Stitch SVG path
        Box(
            modifier = Modifier
                .size(44.dp)
                .drawBehind { drawAiLogoMark() },
        )
    }
}

/**
 * Draws the AI logo mark directly into the composable's draw scope.
 *
 * The mark is a stylised "robot face" with:
 * - A white teardrop/bulb body shape
 * - Two purple filled eye circles
 * - A purple arc smile
 *
 * Coordinates are normalised to a 44 × 44 dp canvas (matching Stitch viewBox 0 0 44 44).
 */
private fun DrawScope.drawAiLogoMark() {
    val w = size.width
    val h = size.height

    // ── Body (teardrop/bulb) ──────────────────────────────────────────────────
    // Stitch path: "M22 8c-1.5 0-2.7 1.5-2.7 3.3 ... c0 5.4 4.9 9.7 11 9.7s11-4.3 11-9.7..."
    // Approximate with a rounded-rect + semicircle top using Path
    val bodyPath = Path().apply {
        // Top circle (the "antenna bulb")
        val cx = w * 0.5f
        val stemTopY = h * 0.265f // ~11.65/44
        val stemBotY = h * 0.318f // ~14/44
        val bulbR    = w * 0.061f // ~2.7/44

        // Move to left of stem base
        moveTo(cx - bulbR, stemBotY)

        // Left side down to head bottom
        val headLeft  = w * 0.25f  // ~11/44
        val headRight = w * 0.75f  // ~33/44
        val headBot   = h * 0.772f // ~34/44
        val headTop   = h * 0.318f // ~14/44

        // Draw the outline as a stadium/rounded rect
        moveTo(headLeft, headTop)
        lineTo(headLeft,  headBot - w * 0.136f) // bottom-left corner
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(headLeft, headBot - w * 0.272f, headLeft + w * 0.272f, headBot),
            startAngleDegrees = 180f, sweepAngleDegrees = 90f, forceMoveTo = false
        )
        lineTo(headRight - w * 0.136f, headBot)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(headRight - w * 0.272f, headBot - w * 0.272f, headRight, headBot),
            startAngleDegrees = 270f, sweepAngleDegrees = 90f, forceMoveTo = false
        )
        lineTo(headRight, headTop)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(cx - bulbR * 2.5f, stemTopY - bulbR * 2f, cx + bulbR * 2.5f, stemTopY + bulbR * 4f),
            startAngleDegrees = 0f, sweepAngleDegrees = 180f, forceMoveTo = false
        )
        close()
    }
    drawPath(bodyPath, color = Color.White.copy(alpha = 0.90f))

    // ── Eyes (filled purple circles) ─────────────────────────────────────────
    val eyeY  = h * 0.523f  // ~23/44
    val eyeR  = w * 0.045f  // ~2/44
    val eyeColor = Color(0xFF6750A4)
    // Left eye
    drawCircle(color = eyeColor, radius = eyeR, center = Offset(w * 0.386f, eyeY))  // ~17/44
    // Right eye
    drawCircle(color = eyeColor, radius = eyeR, center = Offset(w * 0.614f, eyeY))  // ~27/44

    // ── Smile arc ─────────────────────────────────────────────────────────────
    val smilePath = Path().apply {
        // "M17 28c1.3 1.5 3 2.3 5 2.3s3.7-.8 5-2.3"
        val sx = w * 0.386f  // 17/44
        val sy = h * 0.636f  // 28/44
        moveTo(sx, sy)
        cubicTo(
            sx + w * 0.068f, sy + h * 0.114f,
            sx + w * 0.182f, sy + h * 0.163f,
            sx + w * 0.227f, sy + h * 0.163f,
        )
        cubicTo(
            sx + w * 0.295f, sy + h * 0.163f,
            sx + w * 0.386f, sy + h * 0.114f,
            sx + w * 0.454f, sy,
        )
    }
    drawPath(
        path  = smilePath,
        color = eyeColor,
        style = Stroke(width = w * 0.034f, cap = StrokeCap.Round),
    )
}

// ── Loading dots ──────────────────────────────────────────────────────────────

/**
 * Three pulsing dots with staggered alpha keyframe animation.
 * Respects [LocalReducedMotionEnabled]: all three shown at full opacity when true.
 */
@Composable
private fun SplashLoadingDots() {
    val reducedMotion = LocalReducedMotionEnabled.current

    // Composable calls must be unconditional — always create the transition,
    // then ignore the animated value when reduced motion is active.
    val transition = rememberInfiniteTransition(label = "splashDots")

    val dot1AlphaAnimated by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1_400
                0.2f at 0     using LinearEasing
                1.0f at 280   using LinearEasing
                0.2f at 700   using LinearEasing
                0.2f at 1_400 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dot1Alpha",
    )
    val dot2AlphaAnimated by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1_400
                0.2f at 200   using LinearEasing
                1.0f at 480   using LinearEasing
                0.2f at 900   using LinearEasing
                0.2f at 1_400 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dot2Alpha",
    )
    val dot3AlphaAnimated by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1_400
                0.2f at 400   using LinearEasing
                1.0f at 680   using LinearEasing
                0.2f at 1_100 using LinearEasing
                0.2f at 1_400 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "dot3Alpha",
    )

    // When reduced motion is active, show all dots at full opacity (no animation).
    val dot1Alpha = if (reducedMotion) 0.8f else dot1AlphaAnimated
    val dot2Alpha = if (reducedMotion) 0.8f else dot2AlphaAnimated
    val dot3Alpha = if (reducedMotion) 0.8f else dot3AlphaAnimated

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Loading" },
    ) {
        listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(DOT_SIZE)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.80f)),
            )
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = false, name = "SplashScreen")
@Composable
private fun SplashPreview() {
    AppTheme(dynamicColor = false) {
        SplashScreen(isAuthenticated = false, onInitComplete = {})
    }
}
