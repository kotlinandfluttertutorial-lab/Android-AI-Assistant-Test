/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : OfflineBanner.kt
 * Purpose    : OfflineBanner — core-ui module component
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
 * File       : OfflineBanner.kt
 * Purpose    : OfflineBanner — core-ui module component
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
 * OfflineBanner.kt
 *
 * Purpose: A persistent banner displayed at the top of screens whenever the device has
 *          no network connectivity, fulfilling the offline-first UX requirement.
 * Architecture: core-ui â€” shared design system; consumed by all feature modules that
 *               perform network operations (feature-chat, feature-rag, feature-history,
 *               etc.).
 * Dependencies: Compose Material 3, AppTheme tokens.
 *
 * Design decisions:
 * - Uses [MaterialTheme.colorScheme.tertiaryContainer] / [onTertiaryContainer] to
 *   visually differentiate the offline banner from the error banner ([errorContainer]).
 *   Both containers are specified by the M3 palette and meet the 3:1 contrast ratio for
 *   large text and the 4.5:1 ratio for normal text within the static brand scheme.
 * - A Wi-Fi-off icon accompanies the "You are offline" text label so the connectivity
 *   state is never conveyed by color alone â€” satisfying requirement 23.4.
 * - [contentDescription] allows callers to override the TalkBack announcement. When
 *   omitted it defaults to "You are offline. Some features are unavailable."
 * - The composable is intentionally always-visible (no dismiss button) to match the
 *   spec requirement of a *persistent* offline banner (requirement 10.4).
 * - All layout values use [MaterialTheme.spacing] tokens; no magic number literals.
 * - Requirements: 10.4, 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
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
 * A persistent banner that indicates the device currently has no network connectivity.
 *
 * Place this composable at the top of any screen that requires network access. It
 * is always visible when shown â€” there is no dismiss control, matching the offline-first
 * spec requirement for a *persistent* indicator.
 *
 * @param message            The human-readable message to display. Defaults to
 *                           "You're offline. Some features are unavailable."
 * @param contentDescription TalkBack label. Defaults to
 *                           "You are offline. Some features are unavailable."
 * @param modifier           Optional [Modifier] applied to the root [Surface].
 */
@Composable
fun OfflineBanner(
    message: String = "You're offline. Some features are unavailable.",
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val a11yLabel = contentDescription ?: "You are offline. Some features are unavailable."

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = a11yLabel },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.spacing.sm,
                vertical = MaterialTheme.spacing.xs
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Wi-Fi off icon â€” non-color status cue (requirement 23.4)
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null, // described by the row's semantic label
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "OfflineBanner â€“ Light")
@Composable
private fun OfflineBannerLightPreview() {
    AppTheme(dynamicColor = false) {
        OfflineBanner()
    }
}

@Preview(showBackground = true, name = "OfflineBanner â€“ Custom message")
@Composable
private fun OfflineBannerCustomMessagePreview() {
    AppTheme(dynamicColor = false) {
        OfflineBanner(message = "No internet connection. Showing cached data.")
    }
}

@Preview(
    showBackground = true,
    name = "OfflineBanner â€“ Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun OfflineBannerDarkPreview() {
    AppTheme(dynamicColor = false) {
        OfflineBanner()
    }
}
