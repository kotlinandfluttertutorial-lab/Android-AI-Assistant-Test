/**
 * EnvironmentIndicator.kt — core-ui module
 *
 * Purpose: Reusable Compose component that displays a small, prominent STAGE badge
 *          so testers and developers can immediately distinguish a Stage build from
 *          a Production build when looking at the running app.
 *
 * Design decisions:
 * - Uses the existing Warning amber tokens from [Color.kt] — visually distinct from
 *   all AI-mode chips (teal/blue/violet) and from error red, so it cannot be confused
 *   with functional UI feedback.
 * - The component is a pure Compose function with no ViewModel/DI dependency. The
 *   caller decides whether to show it; in practice only stage-flavor code paths pass
 *   `isStage = true`.
 * - Accessibility: the Surface has a `contentDescription` of "Stage build indicator"
 *   so screen readers announce the context correctly.
 * - Animation: the badge uses `AnimatedVisibility` with a short fade so it does not
 *   flash abruptly on first composition.
 * - Production: when `isStage = false` the composable emits zero layout nodes so it
 *   has no performance cost in production builds.
 *
 * Usage in HomeDashboard:
 * ```kotlin
 * EnvironmentIndicator(isStage = BuildConfig.IS_PRODUCTION.not())
 * ```
 *
 * Prefer injecting the value from [EnvironmentConfig] via a ViewModel rather than
 * accessing BuildConfig directly from UI code:
 * ```kotlin
 * EnvironmentIndicator(isStage = !uiState.isProduction)
 * ```
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.Warning20
import com.aiassistant.core.ui.Warning40
import com.aiassistant.core.ui.Warning80
import com.aiassistant.core.ui.Warning90
import com.aiassistant.core.ui.WarningDark
import com.aiassistant.core.ui.spacing

/**
 * Displays an amber "STAGE" badge when [isStage] is `true`.
 *
 * Emits nothing when [isStage] is `false`, so it is safe to unconditionally include
 * this composable in any screen that may be rendered across both flavors.
 *
 * @param isStage  Whether the running build is the Stage variant.
 *                 Derive from [com.aiassistant.core.network.EnvironmentConfig.isProduction]:
 *                 `isStage = !environmentConfig.isProduction`.
 * @param modifier Optional [Modifier] applied to the outermost visible container.
 *                 Ignored in production (nothing is composed).
 */
@Composable
fun EnvironmentIndicator(
    isStage: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isStage,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val isDark = isSystemInDarkTheme()

        val containerColor = if (isDark) WarningDark    else Warning90
        val contentColor   = if (isDark) Warning80      else Warning20
        val iconTint       = if (isDark) Warning80      else Warning40

        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = modifier.semantics {
                contentDescription = "Stage build indicator — this is not a production build"
            }
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = 4.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Status.Warning,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "STAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            }
        }
    }
}
