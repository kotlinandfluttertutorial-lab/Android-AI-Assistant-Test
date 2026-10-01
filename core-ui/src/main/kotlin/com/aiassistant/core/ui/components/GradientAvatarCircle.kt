/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/GradientAvatarCircle.kt
 * Purpose    : Circular avatar composable that renders either a gradient-filled
 *              circle with an initial letter, or a loaded image via Coil.
 *              Used on ProfileScreen header, HomeScreen top-bar user avatar, and
 *              any other surface that needs a user identity indicator.
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-profile ProfileScreen,
 *                      app HomeDashboard, and feature-settings SettingsScreen.
 *
 * Design reference   : ProfileScreen.tsx — 72 × 72 avatar with gradient + initial
 *                      HomeScreen.tsx   — 40 × 40 avatar with gradient + initial
 *
 * Design decisions   :
 * - Two display modes:
 *     [GradientAvatarMode.INITIAL] — gradient background + first letter of [name].
 *     [GradientAvatarMode.IMAGE]   — Coil [AsyncImage] with shimmer placeholder.
 * - Gradient uses [Brush.linearGradient] so no drawable resource is needed.
 * - Online indicator dot is optional ([showOnlineDot]); when visible it is
 *   positioned absolute bottom-end using a [Box] overlay.
 * - The online dot carries a mandatory contentDescription ("Online") so
 *   TalkBack users understand the user's status.
 * - Badge overlays (e.g. "Free Plan", "Pro") are NOT part of this component —
 *   they are positioned by the caller to keep this composable single-purpose.
 * - [size] is the only required layout dimension — all internal proportions
 *   scale from it (initial font size = size * 0.38; online dot = size * 0.22).
 *
 * Requirements       : 23.1 (accessibility), 24.1 (token usage)
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors

// ── Mode enum ─────────────────────────────────────────────────────────────────

/** Controls how [GradientAvatarCircle] renders its content. */
enum class GradientAvatarMode {
    /** Show a gradient background with the first letter of [GradientAvatarCircle]'s [name]. */
    INITIAL,
    /** Load a remote or local image via Coil; falls back to [INITIAL] on error. */
    IMAGE,
}

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Circular avatar with either a gradient+initial or a loaded image.
 *
 * @param name             Display name — used for the initial letter and the
 *                         default accessibility label.
 * @param size             Diameter of the circle (default 40 dp).
 * @param mode             [GradientAvatarMode.INITIAL] or [GradientAvatarMode.IMAGE].
 * @param imageUrl         Remote image URL — only used in [GradientAvatarMode.IMAGE].
 * @param gradientStart    Top colour of the gradient background.
 * @param gradientEnd      Bottom colour of the gradient background.
 * @param initialTextColor Colour of the initial letter (defaults to [Color.White]).
 * @param borderColor      Optional border colour around the circle.
 *                         Pass `Color.Unspecified` to hide.
 * @param borderWidth      Border stroke width (default 2 dp; ignored when
 *                         [borderColor] is [Color.Unspecified]).
 * @param showOnlineDot    When `true`, renders a green indicator dot at the
 *                         bottom-end of the avatar.
 * @param contentDescription Accessibility label. Defaults to "[name]'s avatar".
 * @param modifier         Optional layout modifier.
 */
@Composable
fun GradientAvatarCircle(
    name: String,
    size: Dp = 40.dp,
    mode: GradientAvatarMode = GradientAvatarMode.INITIAL,
    imageUrl: String? = null,
    gradientStart: Color = StitchColors.splashGradientStop2, // #6750A4
    gradientEnd: Color = StitchColors.splashGradientStop3,   // #9C89C4
    initialTextColor: Color = Color.White,
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = 2.dp,
    showOnlineDot: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = contentDescription ?: "${name}'s avatar"
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    // Scale initial font size from circle size (≈38%)
    val initialFontSize: TextUnit = (size.value * 0.38f).sp

    // Online dot metrics
    val dotSize = (size.value * 0.22f).dp
    val dotOffset = (size.value * 0.05f).dp

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = a11yLabel },
    ) {
        // ── Avatar circle ─────────────────────────────────────────────────────
        val circleModifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (borderColor != Color.Unspecified) {
                    Modifier.border(borderWidth, borderColor, CircleShape)
                } else Modifier
            )

        when (mode) {
            GradientAvatarMode.INITIAL -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = circleModifier
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(gradientStart, gradientEnd),
                            ),
                        ),
                ) {
                    Text(
                        text = initial,
                        style = TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = initialFontSize,
                            color = initialTextColor,
                        ),
                    )
                }
            }

            GradientAvatarMode.IMAGE -> {
                SubcomposeAsyncImage(
                    model = imageUrl,
                    contentDescription = null, // described at parent level
                    contentScale = ContentScale.Crop,
                    loading = {
                        // Shimmer placeholder while image loads
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(gradientStart, gradientEnd),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = initial,
                                style = TextStyle(
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = initialFontSize,
                                    color = initialTextColor,
                                ),
                            )
                        }
                    },
                    error = {
                        // On load failure fall back to gradient initial
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(gradientStart, gradientEnd),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = initial,
                                style = TextStyle(
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = initialFontSize,
                                    color = initialTextColor,
                                ),
                            )
                        }
                    },
                    modifier = circleModifier,
                )
            }
        }

        // ── Online indicator dot ──────────────────────────────────────────────
        if (showOnlineDot) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = (-dotOffset), y = (-dotOffset))
                    .clip(CircleShape)
                    .background(Color(0xFF386A20)) // green — matches Stitch
                    .border(
                        width = (dotSize.value * 0.15f).dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    )
                    .semantics { this.contentDescription = "Online" },
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "GradientAvatarCircle — 40 dp initial")
@Composable
private fun AvatarSmallPreview() {
    AppTheme(dynamicColor = false) {
        GradientAvatarCircle(
            name = "Firoj Khan",
            size = 40.dp,
            modifier = androidx.compose.ui.Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "GradientAvatarCircle — 72 dp with online dot")
@Composable
private fun AvatarLargePreview() {
    AppTheme(dynamicColor = false) {
        GradientAvatarCircle(
            name = "Firoj Khan",
            size = 72.dp,
            showOnlineDot = true,
            borderColor = Color.White.copy(alpha = 0.3f),
            borderWidth = 3.dp,
            modifier = androidx.compose.ui.Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "GradientAvatarCircle — 56 dp, no dot")
@Composable
private fun AvatarMediumPreview() {
    AppTheme(dynamicColor = false) {
        GradientAvatarCircle(
            name = "Alex",
            size = 56.dp,
            gradientStart = StitchColors.onboardingSlide1Start,
            gradientEnd = StitchColors.onboardingSlide1End,
            modifier = androidx.compose.ui.Modifier.padding(16.dp),
        )
    }
}
