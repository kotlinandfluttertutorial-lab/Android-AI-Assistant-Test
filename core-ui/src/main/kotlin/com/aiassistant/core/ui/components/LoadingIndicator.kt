/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : LoadingIndicator.kt
 * Purpose    : LoadingIndicator — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : LoadingIndicator.kt
 * Purpose    : LoadingIndicator — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * LoadingIndicator.kt
 *
 * Purpose: Reusable animated loading indicator composable for use while AI responses or
 *          data are being fetched.
 * Architecture: core-ui â€” shared design system; consumed by feature modules.
 * Dependencies: Compose Material 3, AppTheme tokens.
 *
 * Design decisions:
 * - Three visual variants are supported: [LoadingIndicatorStyle.CIRCULAR] for inline/overlay
 *   use, [LoadingIndicatorStyle.DOTS] for chat-typing-indicator use, and
 *   [LoadingIndicatorStyle.LINEAR] for full-width progress bars.
 * - A [contentDescription] parameter allows callers to provide a TalkBack label. When not
 *   supplied, the composable defaults to "Loadingâ€¦" so screen-reader users always receive
 *   feedback.
 * - Status is never conveyed by color alone â€” an animated icon or label accompanies each
 *   style to satisfy the no-color-only requirement (23.4).
 * - Colors are sourced from [MaterialTheme.colorScheme]; no hardcoded hex values.
 * - Requirements: 2.10, 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme

/** Visual style variants for [LoadingIndicator]. */
enum class LoadingIndicatorStyle {
    /** A standard circular spinner â€” use for overlays and inline loading states. */
    CIRCULAR,

    /**
     * Three animated pulsing dots â€” use as a typing/thinking indicator while waiting for
     * the first AI token to arrive.
     */
    DOTS,

    /** A full-width horizontal progress bar â€” use for page-level loading. */
    LINEAR
}

/**
 * Animated loading indicator.
 *
 * @param style              The visual style â€” one of [LoadingIndicatorStyle].
 * @param contentDescription TalkBack label describing the loading context.
 *                           Defaults to "Loadingâ€¦" when not supplied.
 * @param modifier           Optional [Modifier] applied to the root element.
 * @param size               Diameter of the circular variant or dot size for DOTS style.
 *                           Ignored for [LoadingIndicatorStyle.LINEAR].
 */
@Composable
fun LoadingIndicator(
    style: LoadingIndicatorStyle = LoadingIndicatorStyle.CIRCULAR,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val a11yLabel = contentDescription ?: "Loadingâ€¦"

    when (style) {
        LoadingIndicatorStyle.CIRCULAR -> {
            CircularProgressIndicator(
                modifier = modifier
                    .size(size)
                    .semantics { this.contentDescription = a11yLabel },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        LoadingIndicatorStyle.DOTS -> {
            TypingDotsIndicator(
                contentDescription = a11yLabel,
                modifier = modifier,
                dotSize = size.coerceAtMost(12.dp)
            )
        }

        LoadingIndicatorStyle.LINEAR -> {
            LinearProgressIndicator(
                modifier = modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .semantics { this.contentDescription = a11yLabel },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// â”€â”€â”€ Typing dots â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Three pulsing dots that mimic a chat "typing" indicator. */
@Composable
private fun TypingDotsIndicator(contentDescription: String, modifier: Modifier = Modifier, dotSize: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "typing_dots")

    // Each dot fades in/out with a staggered delay to create the wave effect.
    val dot1Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.2f at 0 using LinearEasing
                1f at 200 using LinearEasing
                0.2f at 600 using LinearEasing
                0.2f at 1200 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1"
    )
    val dot2Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.2f at 200 using LinearEasing
                1f at 400 using LinearEasing
                0.2f at 800 using LinearEasing
                0.2f at 1200 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2"
    )
    val dot3Alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.2f at 400 using LinearEasing
                1f at 600 using LinearEasing
                0.2f at 1000 using LinearEasing
                0.2f at 1200 using LinearEasing
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(dotSize / 2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "LoadingIndicator â€“ Circular")
@Composable
private fun LoadingCircularPreview() {
    AppTheme(dynamicColor = false) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(style = LoadingIndicatorStyle.CIRCULAR)
        }
    }
}

@Preview(showBackground = true, name = "LoadingIndicator â€“ Dots (Typing)")
@Composable
private fun LoadingDotsPreview() {
    AppTheme(dynamicColor = false) {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(
                style = LoadingIndicatorStyle.DOTS,
                contentDescription = "Assistant is typingâ€¦"
            )
        }
    }
}

@Preview(showBackground = true, name = "LoadingIndicator â€“ Linear")
@Composable
private fun LoadingLinearPreview() {
    AppTheme(dynamicColor = false) {
        LoadingIndicator(
            style = LoadingIndicatorStyle.LINEAR,
            contentDescription = "Loading conversationsâ€¦",
            modifier = Modifier.padding(16.dp)
        )
    }
}
