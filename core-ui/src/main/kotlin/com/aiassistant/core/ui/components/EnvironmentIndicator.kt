/**
 * EnvironmentIndicator.kt — core-ui module
 *
 * Purpose: Reusable Compose component that displays a small, prominent environment
 *          badge so testers and developers can immediately distinguish Local / Stage
 *          builds from a Production build.
 *
 * Supported badges:
 *   "local"      → blue  [ LOCAL ] badge — Docker Compose stack
 *   "stage"      → amber [ STAGE ] badge — GCP Stage environment
 *   "production" → nothing is rendered   — zero layout cost
 *
 * Design decisions:
 * - Accepts [environmentName] string from [EnvironmentConfig.environmentName] so the
 *   caller passes a single value rather than computing booleans in the UI layer.
 * - Local uses the existing Cloud blue tokens (cloudContainer*) — visually distinct
 *   from Stage amber and clearly communicates "connected to a backend".
 * - Stage uses the existing Warning amber tokens from [Color.kt].
 * - AnimatedVisibility fades the badge in/out; Production emits zero layout nodes.
 * - contentDescription announces the environment context to screen readers.
 *
 * Usage:
 * ```kotlin
 * // In a ViewModel:
 * val environmentName: String = environmentConfig.environmentName
 *
 * // In a Composable:
 * EnvironmentIndicator(environmentName = viewModel.environmentName)
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
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.Warning20
import com.aiassistant.core.ui.Warning40
import com.aiassistant.core.ui.Warning80
import com.aiassistant.core.ui.Warning90
import com.aiassistant.core.ui.WarningDark
import com.aiassistant.core.ui.spacing

/**
 * Displays an environment badge when the build is not Production.
 *
 * | [environmentName] | Badge        | Colour       |
 * |-------------------|--------------|--------------|
 * | `"local"`         | `[ LOCAL ]`  | Blue         |
 * | `"stage"`         | `[ STAGE ]`  | Amber        |
 * | `"production"`    | *(nothing)*  | —            |
 *
 * Emits zero layout nodes in production — safe to include unconditionally in
 * any screen that may be rendered across all three flavors.
 *
 * @param environmentName  Value from [com.aiassistant.core.network.EnvironmentConfig.environmentName].
 *                         One of `"local"`, `"stage"`, or `"production"`.
 * @param modifier         Optional [Modifier] for the outermost visible container.
 *                         Ignored in production (nothing is composed).
 */
@Composable
fun EnvironmentIndicator(
    environmentName: String,
    modifier: Modifier = Modifier
) {
    val isVisible = environmentName == "local" || environmentName == "stage"

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val isDark = isSystemInDarkTheme()

        val (containerColor, contentColor, iconTint, label, description) = when (environmentName) {
            "local" -> EnvironmentBadgeStyle(
                containerColor = if (isDark) AppColors.cloudContainerDark  else AppColors.cloudContainerLight,
                contentColor   = if (isDark) AppColors.cloudOnContainerDark else AppColors.cloudOnContainerLight,
                iconTint       = if (isDark) AppColors.cloudIndicatorDark   else AppColors.cloudIndicatorLight,
                label          = "LOCAL",
                description    = "Local build indicator — connected to Docker Compose backend"
            )
            else -> EnvironmentBadgeStyle(  // "stage"
                containerColor = if (isDark) WarningDark else Warning90,
                contentColor   = if (isDark) Warning80   else Warning20,
                iconTint       = if (isDark) Warning80   else Warning40,
                label          = "STAGE",
                description    = "Stage build indicator — this is not a production build"
            )
        }

        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = modifier.semantics {
                contentDescription = description
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
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor
                )
            }
        }
    }
}

/** Internal value holder for badge colours, label, and accessibility text. */
private data class EnvironmentBadgeStyle(
    val containerColor: androidx.compose.ui.graphics.Color,
    val contentColor: androidx.compose.ui.graphics.Color,
    val iconTint: androidx.compose.ui.graphics.Color,
    val label: String,
    val description: String
)
