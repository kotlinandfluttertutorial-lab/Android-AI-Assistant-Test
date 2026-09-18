/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : SplashScreen.kt
 * Purpose    : Production-quality Splash screen (Phase 3.5 upgrade).
 *
 *              Changes from Phase 3.5:
 *              - Uses AppIcons.Ai.Assistant instead of Icons.Filled.Psychology
 *              - Gradient background accent behind the icon
 *              - Subtitle line with on-device / cloud indicators
 *              - Respects LocalReducedMotionEnabled for the scale animation
 *              - Consistent spacing via MaterialTheme.spacing
 *
 * Architecture Layer : Feature (feature-auth) — Compose UI layer.
 * Requirements       : 1.6, 28.3
 * ============================================================
 */
package com.aiassistant.feature.auth

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.motion.LocalReducedMotionEnabled
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay

private val LOGO_SIZE = 80.dp
private const val SPLASH_DELAY_MS = 500L

/**
 * Full-screen splash composable shown during cold-start initialisation.
 *
 * Displays the AI Assistant brand mark centred on the background with a
 * subtle breathing scale animation (unless reduced motion is active).
 *
 * After [SPLASH_DELAY_MS] calls [onInitComplete] with the auth result so the
 * nav graph can route to Home or Login.
 *
 * @param isAuthenticated Whether a valid JWT was found in secure storage.
 * @param onInitComplete  Callback invoked after the splash delay.
 */
@Composable
fun SplashScreen(
    isAuthenticated: Boolean = false,
    onInitComplete: (isAuthenticated: Boolean) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val reducedMotion = LocalReducedMotionEnabled.current

    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd   = if (isDark) AppColors.gradientEndDark   else AppColors.gradientEndLight

    // Subtle breathe scale — disabled when reduced motion is on
    val breatheScale: Float = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "splashBreathe")
        val scale by transition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "splashScale"
        )
        scale
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "AI Assistant loading" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand icon with gradient circle background
            Box(
                modifier = Modifier
                    .size(LOGO_SIZE)
                    .scale(breatheScale)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.linearGradient(listOf(gradientStart, gradientEnd))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Ai.Assistant,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.lg))

            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            Text(
                text = "Your intelligent companion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))

            // AI mode indicators — subtle row showing available modes
            Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.lg)
            ) {
                AiModeTag(
                    icon = AppIcons.Ai.Gemma,
                    label = "Gemma",
                    tint = if (isDark) AppColors.gemmaIndicatorDark else AppColors.gemmaIndicatorLight
                )
                AiModeTag(
                    icon = AppIcons.Ai.Cloud,
                    label = "Cloud AI",
                    tint = if (isDark) AppColors.cloudIndicatorDark else AppColors.cloudIndicatorLight
                )
                AiModeTag(
                    icon = AppIcons.Ai.Rag,
                    label = "RAG",
                    tint = if (isDark) AppColors.ragIndicatorDark else AppColors.ragIndicatorLight
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(SPLASH_DELAY_MS)
        onInitComplete(isAuthenticated)
    }
}

@Composable
private fun AiModeTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "$label mode available" }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
