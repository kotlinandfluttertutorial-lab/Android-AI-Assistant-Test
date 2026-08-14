/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : FailoverInfoBanner.kt
 * Purpose    : Non-blocking informational banner displayed when the network
 *              layer has failed over to a secondary backend endpoint.
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Stateless Composable (state provided by ViewModel / caller)
 *
 * Key Concepts:
 *   - Non-blocking: does not prevent user interaction
 *   - Dismisses automatically when primary endpoint recovers (caller sets isVisible=false)
 *   - Uses secondary color tokens to distinguish from error banners (errorContainer)
 *     and offline banners (tertiaryContainer)
 *   - Icon accompanies color to meet accessibility req 23.4 (no color-only indicators)
 *
 * Dependencies:
 *   - Compose Material 3, core-ui AppTheme tokens, spacing system
 * ============================================================
 */
/**
 * FailoverInfoBanner.kt
 *
 * Purpose: A non-blocking informational banner displayed at the top of screens when
 *          the AI Assistant's network layer has automatically failed over to a secondary
 *          backend endpoint.
 *
 * The banner:
 * - Shows the name of the currently active backend endpoint.
 * - Shows a short reason why failover occurred (e.g. "HTTP 503", "Connection error").
 * - Auto-dismisses when the primary endpoint recovers — the caller simply sets
 *   [isVisible] to `false` (typically driven by [FailoverBannerState.isVisible] from
 *   [FailoverBannerStateProvider]).
 * - Is non-blocking: it overlays content using an animated slide-in without consuming
 *   touch events in the area below it.
 *
 * Architecture: core-ui — shared design system component. Zero business logic.
 * Dependencies: Compose Material 3, core-ui AppTheme, spacing tokens.
 *
 * Requirements: 35.6
 */

package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

/**
 * Non-blocking informational banner shown when a failover to a secondary backend
 * endpoint has occurred (Requirement 35.6).
 *
 * The banner animates in/out vertically. When [isVisible] becomes `false` (primary
 * endpoint recovered) the banner slides back up automatically.
 *
 * @param isVisible          Whether the banner should be shown. Animate to `false` to
 *                           trigger the auto-dismiss slide-up animation.
 * @param activeBackendName  Name of the currently active backend endpoint to display.
 * @param failoverReason     Short description of why failover occurred.
 * @param contentDescription TalkBack announcement. Defaults to a human-readable
 *                           description including [activeBackendName] and [failoverReason].
 * @param modifier           Optional [Modifier] applied to the [AnimatedVisibility] wrapper.
 */
@Composable
fun FailoverInfoBanner(
    isVisible: Boolean,
    activeBackendName: String,
    failoverReason: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val a11yLabel = contentDescription
        ?: "Failover active. Using backend: $activeBackendName. Reason: $failoverReason."

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = a11yLabel },
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.xs
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Swap icon — non-color status cue (Requirement 23.4).
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = null, // described by the row's semantic label
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Using backup endpoint: $activeBackendName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    if (failoverReason.isNotBlank()) {
                        Text(
                            text = "Reason: $failoverReason",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "FailoverInfoBanner — Visible")
@Composable
private fun FailoverInfoBannerVisiblePreview() {
    AppTheme(dynamicColor = false) {
        FailoverInfoBanner(
            isVisible = true,
            activeBackendName = "us-secondary",
            failoverReason = "HTTP 503"
        )
    }
}

@Preview(showBackground = true, name = "FailoverInfoBanner — Hidden")
@Composable
private fun FailoverInfoBannerHiddenPreview() {
    AppTheme(dynamicColor = false) {
        FailoverInfoBanner(
            isVisible = false,
            activeBackendName = "",
            failoverReason = ""
        )
    }
}

@Preview(
    showBackground = true,
    name = "FailoverInfoBanner — Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun FailoverInfoBannerDarkPreview() {
    AppTheme(dynamicColor = false) {
        FailoverInfoBanner(
            isVisible = true,
            activeBackendName = "eu-failover",
            failoverReason = "Connection error"
        )
    }
}
